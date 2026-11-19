package com.boykohub.looppractice

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** JNI bridge to the native stretch engines (see src/main/cpp/stretch_jni.cpp). */
object NativeStretch {
    init {
        System.loadLibrary("musicstretch")
    }

    @JvmStatic external fun nCreate(channels: Int, sampleRate: Int, mode: Int): Long
    @JvmStatic external fun nDestroy(h: Long)
    @JvmStatic external fun nSetParams(h: Long, speed: Float, pitch: Float)
    @JvmStatic external fun nQueue(h: Long, buf: ByteBuffer, frames: Int)
    @JvmStatic external fun nAvailable(h: Long): Int
    @JvmStatic external fun nRead(h: Long, buf: ByteBuffer, maxFrames: Int): Int
    @JvmStatic external fun nEndOfStream(h: Long)
    @JvmStatic external fun nReset(h: Long)
}

/**
 * Time-stretch + pitch-shift audio processor for Media3, replacing the built-in Sonic.
 * Melody mode uses Signalsmith Stretch; Drums mode uses Rubber Band with crisp transients.
 * Only works on 16-bit PCM, which is what the audio sink feeds it.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class StretchProcessor : AudioProcessor {

    companion object {
        const val MODE_MELODY = 0
        const val MODE_DRUMS = 1
    }

    @Volatile private var pendingMode = MODE_MELODY
    @Volatile private var pendingSpeed = 1f
    @Volatile private var pendingPitch = 1f

    private var pendingFormat = AudioFormat.NOT_SET
    private var format = AudioFormat.NOT_SET
    private var mode = -1
    private var handle = 0L

    private var inBuf: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var outBuf: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var inputEnded = false

    fun requestMode(m: Int) {
        pendingMode = m
    }

    fun setSpeedPitch(speed: Float, pitch: Float) {
        pendingSpeed = speed
        pendingPitch = pitch
    }

    /** Speed currently applied to the audio (1 when bypassed). */
    fun effectiveSpeed(): Float = if (isActive()) pendingSpeed else 1f

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        pendingFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        pendingFormat.sampleRate != Format.NO_VALUE &&
            (abs(pendingSpeed - 1f) >= 0.0005f || abs(pendingPitch - 1f) >= 0.0005f)

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (handle == 0L) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        if (inBuf.capacity() < bytes) {
            inBuf = ByteBuffer.allocateDirect(bytes * 2).order(ByteOrder.nativeOrder())
        }
        inBuf.clear()
        inBuf.put(inputBuffer)
        val frames = bytes / (2 * format.channelCount)
        NativeStretch.nQueue(handle, inBuf, frames)
    }

    override fun queueEndOfStream() {
        if (handle != 0L) NativeStretch.nEndOfStream(handle)
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        if (handle == 0L) return AudioProcessor.EMPTY_BUFFER
        val avail = NativeStretch.nAvailable(handle)
        if (avail <= 0) return AudioProcessor.EMPTY_BUFFER
        val bytes = avail * 2 * format.channelCount
        if (outBuf.capacity() < bytes) {
            outBuf = ByteBuffer.allocateDirect(bytes * 2).order(ByteOrder.nativeOrder())
        }
        outBuf.clear()
        val got = NativeStretch.nRead(handle, outBuf, avail)
        outBuf.position(0)
        outBuf.limit(got * 2 * format.channelCount)
        return outBuf
    }

    override fun isEnded(): Boolean =
        inputEnded && (handle == 0L || NativeStretch.nAvailable(handle) == 0)

    override fun flush() {
        if (isActive()) {
            val f = pendingFormat
            val m = pendingMode
            if (handle == 0L || m != mode || f.sampleRate != format.sampleRate || f.channelCount != format.channelCount) {
                if (handle != 0L) NativeStretch.nDestroy(handle)
                handle = NativeStretch.nCreate(f.channelCount, f.sampleRate, m)
                mode = m
            }
            format = f
            NativeStretch.nSetParams(handle, pendingSpeed, pendingPitch)
            NativeStretch.nReset(handle)
        }
        inputEnded = false
    }

    override fun reset() {
        if (handle != 0L) NativeStretch.nDestroy(handle)
        handle = 0L
        mode = -1
        pendingFormat = AudioFormat.NOT_SET
        format = AudioFormat.NOT_SET
        inputEnded = false
    }
}

/** Replaces Media3's default chain (silence skipping + Sonic) with our stretcher. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Suppress("DEPRECATION")
class StretchChain(private val p: StretchProcessor) : DefaultAudioSink.AudioProcessorChain {
    override fun getAudioProcessors(): Array<AudioProcessor> = arrayOf(p)

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        val speed = playbackParameters.speed.coerceIn(0.1f, 4f)
        val pitch = playbackParameters.pitch.coerceIn(0.25f, 4f)
        p.setSpeedPitch(speed, pitch)
        return PlaybackParameters(speed, pitch)
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean = false

    // The stretcher outputs exactly input/speed frames, so media time = playout time × speed.
    override fun getMediaDuration(playoutDuration: Long): Long =
        (playoutDuration * p.effectiveSpeed().toDouble()).toLong()

    override fun getSkippedOutputFrameCount(): Long = 0L
}
