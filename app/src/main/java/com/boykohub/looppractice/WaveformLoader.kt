package com.boykohub.looppractice

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max

/** Decodes the file once and builds a peak envelope for drawing the waveform. */
object WaveformLoader {
    const val BUCKET_MS = 5L // one peak value per 5 ms

    suspend fun load(ctx: Context, uri: Uri, onUpdate: (FloatArray, Boolean) -> Unit) {
        withContext(Dispatchers.Default) {
            val ex = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                ex.setDataSource(ctx, uri, null)
                var track = -1
                for (i in 0 until ex.trackCount) {
                    val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) { track = i; break }
                }
                if (track < 0) return@withContext
                ex.selectTrack(track)
                val fmt = ex.getTrackFormat(track)
                val mime = fmt.getString(MediaFormat.KEY_MIME)!!
                var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var isFloat = false
                val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L

                var peaks = FloatArray(max(16, (durUs / 1000 / BUCKET_MS + 16).toInt()))
                var bucket = 0
                var framesInBucket = 0L
                var cur = 0f
                var framesPerBucket = max(1L, sr * BUCKET_MS / 1000)

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(fmt, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inDone = false
                var lastPublish = System.currentTimeMillis()

                while (true) {
                    coroutineContext.ensureActive()
                    if (!inDone) {
                        val ii = codec.dequeueInputBuffer(5000)
                        if (ii >= 0) {
                            val ib = codec.getInputBuffer(ii)!!
                            val n = ex.readSampleData(ib, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(info, 5000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val of = codec.outputFormat
                        sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        framesPerBucket = max(1L, sr * BUCKET_MS / 1000)
                    } else if (oi >= 0) {
                        val ob = codec.getOutputBuffer(oi)!!.order(ByteOrder.nativeOrder())
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        if (isFloat) {
                            val fb = ob.asFloatBuffer()
                            val frames = fb.remaining() / ch
                            for (f in 0 until frames) {
                                for (c in 0 until ch) cur = max(cur, abs(fb.get()))
                                if (++framesInBucket >= framesPerBucket) {
                                    if (bucket >= peaks.size) peaks = peaks.copyOf(peaks.size * 2)
                                    peaks[bucket++] = cur; cur = 0f; framesInBucket = 0
                                }
                            }
                        } else {
                            val sb = ob.asShortBuffer()
                            val frames = sb.remaining() / ch
                            for (f in 0 until frames) {
                                for (c in 0 until ch) cur = max(cur, abs(sb.get().toFloat()) / 32768f)
                                if (++framesInBucket >= framesPerBucket) {
                                    if (bucket >= peaks.size) peaks = peaks.copyOf(peaks.size * 2)
                                    peaks[bucket++] = cur; cur = 0f; framesInBucket = 0
                                }
                            }
                        }
                        codec.releaseOutputBuffer(oi, false)
                        val now = System.currentTimeMillis()
                        if (now - lastPublish > 400) {
                            lastPublish = now
                            val snap = peaks.copyOf(bucket)
                            withContext(Dispatchers.Main) { onUpdate(snap, false) }
                        }
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
                // Normalise so quiet recordings still show a clear shape.
                val result = peaks.copyOf(bucket)
                var mx = 0f
                for (v in result) mx = max(mx, v)
                if (mx > 0f) for (i in result.indices) result[i] = result[i] / mx
                withContext(Dispatchers.Main) { onUpdate(result, true) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                withContext(Dispatchers.Main) { onUpdate(FloatArray(0), true) }
            } finally {
                try { codec?.stop() } catch (_: Exception) {}
                try { codec?.release() } catch (_: Exception) {}
                ex.release()
            }
        }
    }
}
