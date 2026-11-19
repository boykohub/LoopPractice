package com.boykohub.looppractice

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A loop. [repeats] = how many times it plays before moving on to [next] (0 = forever).
 * With no [next], it just keeps looping.
 */
data class Song(val uri: String, val name: String, val lastOpened: Long)

data class Loop(
    val id: Char,
    val start: Long,
    val end: Long,
    val repeats: Int = 0,
    val next: Char? = null,
    /** This loop's own playback speed (each loop remembers its own). */
    val speed: Float? = null,
)

/**
 * Lives for the whole app process (owned by [LoopApp]), so loops and the speed trainer
 * keep working while the screen is off or the app is in the background.
 */
class PracticeEngine(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val prefs = app.getSharedPreferences("looppractice", Context.MODE_PRIVATE)

    /** Our own time-stretch (Signalsmith for melody, Rubber Band for drums) instead of Media3's Sonic. */
    val stretch = StretchProcessor()

    private val renderers = RenderersFactory { handler, videoListener, audioListener, _, _ ->
        val sink = DefaultAudioSink.Builder(app)
            .setAudioProcessorChain(StretchChain(stretch))
            .build()
        arrayOf<Renderer>(
            MediaCodecAudioRenderer(app, MediaCodecSelector.DEFAULT, handler, audioListener, sink),
            MediaCodecVideoRenderer(app, MediaCodecSelector.DEFAULT, 5000L, handler, videoListener, 50),
        )
    }

    val player: ExoPlayer = ExoPlayer.Builder(app, renderers)
        .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
        .setWakeMode(C.WAKE_MODE_LOCAL) // keep the CPU awake with the screen off
        .build()
        .apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
        }

    var uri by mutableStateOf<Uri?>(null); private set
    var fileName by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null); private set
    var durationMs by mutableLongStateOf(0L); private set
    var positionMs by mutableLongStateOf(0L); private set
    var isPlaying by mutableStateOf(false); private set

    /** Speed being played right now (the active loop's speed, or the song speed). */
    var speed by mutableFloatStateOf(1f); private set
    /** Speed used when no loop is active. */
    private var songSpeed = 1f

    // ---------- video ----------
    var hasVideo by mutableStateOf(false); private set
    var videoAspect by mutableFloatStateOf(16f / 9f); private set
    var videoOn by mutableStateOf(true); private set

    /** True while the app is on screen; the position check runs less often when it isn't. */
    @Volatile var uiVisible = true
    var semitones by mutableIntStateOf(0); private set

    /** 0 = Melody (Signalsmith Stretch), 1 = Drums (Rubber Band, crisp transients). */
    var stretchMode by mutableIntStateOf(StretchProcessor.MODE_MELODY); private set

    /** Waveform peaks: one value (0..1) per [WaveformLoader.BUCKET_MS] ms. Fills in while decoding. */
    var peaks by mutableStateOf(FloatArray(0)); private set
    var peaksDone by mutableStateOf(false); private set
    private var waveJob: Job? = null
    private var scrubbingUntil = 0L
    private var lastScrubSeek = 0L

    val loops = mutableStateListOf<Loop>()
    var activeId by mutableStateOf<Char?>(null); private set
    var selectedId by mutableStateOf<Char?>(null); private set
    /** A loop just made with "+": its end is set by tapping its letter. */
    var awaitingEndId by mutableStateOf<Char?>(null); private set

    /** Join mode: tap letters to play several loops as one span (gaps included). Loops stay unchanged. */
    var joinMode by mutableStateOf(false); private set
    val joined = mutableStateListOf<Char>()

    /** Song library: every imported file, with its own saved loops and settings. */
    val songs = mutableStateListOf<Song>()
    var importing by mutableStateOf(false); private set
    /** Details of a crash from the last run, shown once with a Copy button. */
    var crashReport by mutableStateOf<String?>(null); private set
    var reps by mutableIntStateOf(0); private set

    // Speed trainer: raise the speed a little every N loop repeats.
    var trainerOn by mutableStateOf(false)
    var trainerStepPct by mutableIntStateOf(5)
    var trainerEvery by mutableIntStateOf(2)
    var trainerMaxPct by mutableIntStateOf(100)

    // Fine-edit step for loop points, in ms.
    var nudgeStep by mutableLongStateOf(100L)

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) saveState()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    val d = player.duration
                    if (d != C.TIME_UNSET && d > 0) durationMs = d
                }
                if (state == Player.STATE_ENDED) {
                    if (joinedRange() != null) onJoinedWrap()
                    else activeLoop()?.let { onLoopWrap(it) }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                hasVideo = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) {
                    videoAspect = size.width * size.pixelWidthHeightRatio / size.height
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                error = "Can't play this file (${e.errorCodeName})"
            }
        })

        scope.launch {
            while (true) {
                tick()
                delay(if (uiVisible) 10 else 25)
            }
        }

        restoreGlobal()
        loadCrashReport()

        loadLibrary()

        // Re-open the last song, if we can still read it.
        prefs.getString("lastUri", null)?.let { s ->
            val u = Uri.parse(s)
            if (canRead(u)) openUri(u, null)
        }
    }

    private var saveCounter = 0

    private fun tick() {
        if (android.os.SystemClock.uptimeMillis() > scrubbingUntil) positionMs = player.currentPosition
        if (++saveCounter >= 200) { // about every 2 seconds
            saveCounter = 0
            saveState()
        }
        val jr = joinedRange()
        if (jr != null) {
            if (player.isPlaying && positionMs >= jr.last) onJoinedWrap()
            return
        }
        val l = activeLoop() ?: return
        if (l.end > l.start && player.isPlaying && positionMs >= l.end) {
            onLoopWrap(l)
        }
    }

    /** Span played in join mode: from the earliest start to the latest end of the joined loops. */
    fun joinedRange(): LongRange? {
        if (joined.size < 2) return null
        val ls = loops.filter { it.id in joined }
        if (ls.size < 2) return null
        return ls.minOf { it.start }..ls.maxOf { it.end }
    }

    /** Range that is looping right now (joined span or the active loop), if any. */
    fun loopRange(): LongRange? = joinedRange() ?: activeLoop()?.let { it.start..it.end }

    private fun onJoinedWrap() {
        val r = joinedRange() ?: return
        reps += 1
        player.seekTo(r.first)
        positionMs = r.first
        if (!player.playWhenReady) player.play()
        if (trainerOn && trainerEvery > 0 && reps % trainerEvery == 0) {
            val max = trainerMaxPct / 100f
            if (speed < max) changeSpeed((speed + trainerStepPct / 100f).coerceAtMost(max))
        }
    }

    fun toggleJoinMode() {
        if (joinMode) {
            // Leave join mode: go back to looping the first joined loop on its own.
            val first = joined.firstOrNull()
            joinMode = false
            joined.clear()
            if (first != null) activate(first) else reps = 0
        } else {
            finishAwaiting()
            joinMode = true
            joined.clear()
            activeId?.let { joined.add(it) }
        }
    }

    private fun toggleJoined(id: Char) {
        if (id in joined) joined.remove(id) else joined.add(id)
        selectedId = id
        reps = 0
        when {
            joined.size >= 2 -> {
                // Play the joined span at the song speed; keep playing if we're already inside it.
                activeId = null
                speed = songSpeed
                applyParams()
                val r = joinedRange()!!
                if (positionMs < r.first || positionMs > r.last) {
                    player.seekTo(r.first); positionMs = r.first
                }
                player.play()
            }
            joined.size == 1 -> {
                val only = joined.first()
                val l = loops.firstOrNull { it.id == only } ?: return
                activeId = only
                speed = l.speed ?: speed
                applyParams()
                if (positionMs < l.start || positionMs > l.end) {
                    player.seekTo(l.start); positionMs = l.start
                }
                player.play()
            }
            else -> {
                activeId = null
                speed = songSpeed
                applyParams()
            }
        }
        saveState()
    }

    private fun onLoopWrap(l: Loop) {
        reps += 1
        // Chained loops: after the set number of repeats, move on to the next loop.
        val nxt = l.next?.let { n -> loops.firstOrNull { it.id == n } }
        val target = if (nxt != null && l.repeats > 0 && reps >= l.repeats) {
            activeId = nxt.id
            selectedId = nxt.id
            reps = 0
            nxt.speed?.let { speed = it; applyParams() }
            nxt
        } else l
        // If the next loop starts right where this one ends, just keep playing (no seek, no gap).
        val contiguous = target !== l && kotlin.math.abs(target.start - l.end) < 40
        if (!contiguous) {
            player.seekTo(target.start)
            positionMs = target.start
        }
        if (!player.playWhenReady) player.play()
        if (trainerOn && trainerEvery > 0 && reps % trainerEvery == 0) {
            val max = trainerMaxPct / 100f
            if (speed < max) changeSpeed((speed + trainerStepPct / 100f).coerceAtMost(max))
        }
    }

    fun activeLoop(): Loop? = activeId?.let { id -> loops.firstOrNull { it.id == id } }
    fun selectedLoop(): Loop? = selectedId?.let { id -> loops.firstOrNull { it.id == id } }

    // ---------- file ----------

    /**
     * Import a file (Open button, Share, Open with). If Android lets us keep access, we play it
     * in place; otherwise (e.g. shared from WhatsApp) we copy it into the app so the song can
     * always be reopened from the Songs list.
     */
    fun load(u: Uri) {
        val name = queryName(u)
        val kept = try {
            app.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: Exception) {
            false
        }
        if (kept || u.scheme == "file") {
            openUri(u, name)
            return
        }
        importing = true
        scope.launch {
            val copy = withContext(Dispatchers.IO) {
                try {
                    val dir = java.io.File(app.filesDir, "songs").apply { mkdirs() }
                    val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
                    val f = java.io.File(dir, "${System.currentTimeMillis()}_$safe")
                    app.contentResolver.openInputStream(u)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                    f
                } catch (_: Exception) {
                    null
                }
            }
            importing = false
            if (copy != null) openUri(Uri.fromFile(copy), name)
            else openUri(u, name) // play it now at least
        }
    }

    /** Open a song from the Songs list. */
    fun openSong(song: Song) {
        val u = Uri.parse(song.uri)
        if (!canRead(u)) {
            error = "Can't open \"${song.name}\" any more — the file was moved or access was lost. Import it again with Open."
            return
        }
        openUri(u, null)
    }

    private fun canRead(u: Uri): Boolean = when (u.scheme) {
        "file" -> u.path?.let { java.io.File(it).exists() } == true
        else -> app.contentResolver.persistedUriPermissions.any { it.uri == u && it.isReadPermission }
    }

    private fun openUri(u: Uri, displayName: String?) {
        saveState() // remember where we were in the previous song
        error = null
        uri = u
        val existing = songs.firstOrNull { it.uri == u.toString() }
        fileName = existing?.name ?: displayName ?: queryName(u)
        upsertSong(u.toString(), fileName)
        durationMs = 0L
        activeId = null
        selectedId = null
        awaitingEndId = null
        joinMode = false
        joined.clear()
        reps = 0
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(u)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(fileName).setArtist("Music Practice").build())
                .build()
        )
        player.prepare()
        loadLoops()
        restoreState()
        loadWaveform(u)
        prefs.edit().putString("lastUri", u.toString()).apply()
    }

    private fun queryName(u: Uri): String {
        return try {
            app.contentResolver
                .query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        } ?: (u.lastPathSegment ?: "Audio")
    }

    // ---------- transport ----------

    fun togglePlay() {
        if (uri == null) return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(loopRange()?.first ?: 0L)
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(ms: Long) {
        val t = ms.coerceIn(0L, maxOf(durationMs, 0L))
        player.seekTo(t)
        positionMs = t
    }

    /** Jump back/forward. While a loop is active, stays inside the loop. */
    fun skip(deltaMs: Long) {
        val r = loopRange()
        var t = positionMs + deltaMs
        if (r != null && positionMs >= r.first && positionMs <= r.last) {
            t = t.coerceIn(r.first, maxOf(r.first, r.last - 50))
        }
        seekTo(t)
    }

    /** Dragging the waveform: move the position smoothly, seeking at most ~20 times a second. */
    fun scrubTo(ms: Long) {
        val t = ms.coerceIn(0L, maxOf(durationMs, 0L))
        positionMs = t
        val now = android.os.SystemClock.uptimeMillis()
        scrubbingUntil = now + 250
        if (now - lastScrubSeek > 50) {
            player.seekTo(t)
            lastScrubSeek = now
        }
    }

    fun endScrub() {
        player.seekTo(positionMs)
        scrubbingUntil = android.os.SystemClock.uptimeMillis() + 150
    }

    fun toStart() = seekTo(loopRange()?.first ?: 0L)

    // ---------- song library ----------

    private fun loadLibrary() {
        songs.clear()
        try {
            val arr = JSONArray(prefs.getString("library", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                songs.add(Song(o.getString("u"), o.getString("n"), o.optLong("t", 0L)))
            }
        } catch (_: Exception) {
        }
        songs.sortByDescending { it.lastOpened }
    }

    private fun saveLibrary() {
        val arr = JSONArray()
        songs.forEach { arr.put(JSONObject().put("u", it.uri).put("n", it.name).put("t", it.lastOpened)) }
        prefs.edit().putString("library", arr.toString()).apply()
    }

    private fun upsertSong(u: String, name: String) {
        songs.removeAll { it.uri == u }
        songs.add(0, Song(u, name, System.currentTimeMillis()))
        saveLibrary()
    }

    fun renameSong(song: Song, newName: String) {
        val n = newName.trim().ifEmpty { return }
        val i = songs.indexOfFirst { it.uri == song.uri }
        if (i < 0) return
        songs[i] = songs[i].copy(name = n)
        if (uri?.toString() == song.uri) fileName = n
        saveLibrary()
    }

    /** Remove a song: its loops and settings, and the app's own copy of the file if it made one. */
    fun removeSong(song: Song) {
        songs.removeAll { it.uri == song.uri }
        saveLibrary()
        prefs.edit().remove("loops:${song.uri}").remove("state:${song.uri}").apply()
        val u = Uri.parse(song.uri)
        if (u.scheme == "file") u.path?.let { java.io.File(it).delete() }
        if (uri?.toString() == song.uri) {
            player.stop()
            player.clearMediaItems()
            uri = null
            fileName = ""
            loops.clear()
            peaks = FloatArray(0)
            prefs.edit().remove("lastUri").apply()
        }
    }

    fun loopCount(song: Song): Int = try {
        JSONArray(prefs.getString("loops:${song.uri}", "[]")).length()
    } catch (_: Exception) {
        0
    }

    // ---------- speed & pitch ----------

    fun changeSpeed(v: Float) {
        speed = ((v.coerceIn(0.25f, 2f) * 100f).roundToInt()) / 100f
        val id = activeId
        if (id != null) update(id) { it.copy(speed = speed) } else songSpeed = speed
        applyParams()
    }

    fun changeSemitones(delta: Int) {
        semitones = (semitones + delta).coerceIn(-12, 12)
        applyParams()
    }

    fun resetPitch() {
        semitones = 0
        applyParams()
    }

    private fun applyParams() {
        val pitch = 2f.pow(semitones / 12f)
        // Our StretchProcessor time-stretches with pitch kept, so speed and pitch are independent.
        player.playbackParameters = PlaybackParameters(speed, pitch)
    }

    fun changeStretchMode(m: Int) {
        if (m == stretchMode) return
        stretchMode = m
        stretch.requestMode(m)
        // A seek to where we are makes the audio pipeline restart with the new engine.
        if (uri != null) player.seekTo(player.currentPosition)
    }

    // ---------- waveform ----------

    private fun loadWaveform(u: Uri) {
        waveJob?.cancel()
        peaks = FloatArray(0)
        peaksDone = false
        waveJob = scope.launch {
            WaveformLoader.load(app, u) { arr, done ->
                peaks = arr
                peaksDone = done
            }
        }
    }

    // ---------- loops ----------

    fun addLoop() {
        if (durationMs <= 0) return
        val id = ('A'..'Z').firstOrNull { c -> loops.none { it.id == c } } ?: return
        finishAwaiting()
        val start = positionMs.coerceIn(0, durationMs)
        // End is set by tapping the new letter; until then it runs to the end of the song.
        loops.add(Loop(id, start, durationMs, speed = speed))
        loops.sortBy { it.id }
        // Let playback run on past any active loop so you can reach the end point.
        joinMode = false
        joined.clear()
        activeId = null
        reps = 0
        selectedId = id
        awaitingEndId = id
        saveLoops()
    }

    /** Tap on a letter: sets the end of a new loop, otherwise jumps to the loop and loops it. */
    fun tapLoop(id: Char) {
        if (joinMode) { toggleJoined(id); return }
        if (awaitingEndId == id) {
            val l = loops.firstOrNull { it.id == id }
            awaitingEndId = null
            if (l != null) {
                val e = positionMs.coerceIn(0L, durationMs)
                if (e > l.start + 50) update(id) { it.copy(end = e) }
                else update(id) { it.copy(end = (it.start + 8_000).coerceAtMost(durationMs)) }
            }
        }
        activate(id)
    }

    /** If a new loop is still waiting for its end, give it a sensible default end. */
    private fun finishAwaiting() {
        val id = awaitingEndId ?: return
        awaitingEndId = null
        update(id) { l -> if (l.end >= durationMs) l.copy(end = (l.start + 8_000).coerceAtMost(durationMs)) else l }
    }

    /** End shown on screen: a loop waiting for its end grows with the playhead. */
    fun displayEnd(l: Loop): Long =
        if (awaitingEndId == l.id) maxOf(l.start, positionMs) else l.end

    /** Tap a loop: jump straight to its start and loop it. */
    fun activate(id: Char) {
        joinMode = false
        joined.clear()
        if (awaitingEndId != null && awaitingEndId != id) finishAwaiting()
        val l = loops.firstOrNull { it.id == id } ?: return
        activeId = id
        selectedId = id
        reps = 0
        speed = l.speed ?: speed
        if (l.speed == null) update(id) { it.copy(speed = speed) }
        applyParams()
        player.seekTo(l.start)
        positionMs = l.start
        player.play()
    }

    fun select(id: Char) {
        selectedId = id
    }

    fun loopOff() {
        joinMode = false
        joined.clear()
        activeId = null
        reps = 0
        speed = songSpeed
        applyParams()
    }

    fun changeVideoOn(on: Boolean) {
        videoOn = on
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !on)
            .build()
        saveState()
    }

    fun deleteSelected() {
        val id = selectedId ?: return
        if (awaitingEndId == id) awaitingEndId = null
        joined.remove(id)
        loops.removeAll { it.id == id }
        for (i in loops.indices) if (loops[i].next == id) loops[i] = loops[i].copy(next = null)
        if (activeId == id) activeId = null
        selectedId = loops.firstOrNull()?.id
        saveLoops()
    }

    fun setStart(id: Char, ms: Long) = update(id) { l ->
        l.copy(start = ms.coerceIn(0L, l.end - 20))
    }

    fun setEnd(id: Char, ms: Long) = update(id) { l ->
        l.copy(end = ms.coerceIn(l.start + 20, maxOf(durationMs, l.start + 20)))
    }

    fun startNow(id: Char) = update(id) { l ->
        val s = positionMs.coerceIn(0L, durationMs)
        if (s < l.end - 20) l.copy(start = s) else l.copy(start = s, end = (s + 8_000).coerceAtMost(durationMs))
    }

    fun endNow(id: Char) = update(id) { l ->
        val e = positionMs.coerceIn(0L, durationMs)
        if (e > l.start + 20) l.copy(end = e) else l
    }

    /** How many times the loop plays before moving to its next loop (0 = forever). */
    fun setRepeats(id: Char, n: Int) = update(id) { it.copy(repeats = n.coerceIn(0, 99)) }

    /** Which loop comes after this one (null = none, just keep looping). */
    fun setNext(id: Char, next: Char?) = update(id) { l ->
        l.copy(next = next, repeats = if (next != null && l.repeats == 0) 1 else l.repeats)
    }

    private fun update(id: Char, f: (Loop) -> Loop) {
        val i = loops.indexOfFirst { it.id == id }
        if (i < 0) return
        loops[i] = f(loops[i])
        saveLoops()
    }

    // ---------- crash reports ----------

    private fun loadCrashReport() {
        var report: String? = null
        try {
            val f = java.io.File(app.filesDir, "crash.txt")
            if (f.exists()) {
                report = f.readText()
                f.delete()
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val am = app.getSystemService(android.app.ActivityManager::class.java)
                val last = am.getHistoricalProcessExitReasons(app.packageName, 0, 1).firstOrNull()
                val seen = prefs.getLong("lastExitTs", 0L)
                if (last != null && last.timestamp > seen) {
                    prefs.edit().putLong("lastExitTs", last.timestamp).apply()
                    val r = last.reason
                    if (report == null && (r == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE ||
                            r == android.app.ApplicationExitInfo.REASON_CRASH ||
                            r == android.app.ApplicationExitInfo.REASON_ANR)
                    ) {
                        val kind = when (r) {
                            android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                            android.app.ApplicationExitInfo.REASON_ANR -> "App froze (ANR)"
                            else -> "Crash"
                        }
                        report = "$kind at ${java.util.Date(last.timestamp)}\n${last.description ?: ""}"
                    }
                }
            }
        } catch (_: Exception) {
        }
        crashReport = report?.take(6000)
    }

    fun dismissCrash() {
        crashReport = null
    }

    // ---------- remembering everything between sessions ----------

    private var lastSavedState = ""
    private var lastSavedGlobal = ""

    /** Saves speed, pitch, active/selected loop and position for this file, plus global settings. */
    fun saveState() {
        val g = JSONObject()
            .put("trOn", trainerOn).put("trStep", trainerStepPct)
            .put("trEvery", trainerEvery).put("trMax", trainerMaxPct)
            .put("nudge", nudgeStep)
            .put("video", videoOn)
            .toString()
        if (g != lastSavedGlobal) {
            prefs.edit().putString("global", g).apply()
            lastSavedGlobal = g
        }
        val u = uri ?: return
        if (durationMs <= 0) return // not loaded yet; don't overwrite with empty state
        val st = JSONObject()
            .put("speed", songSpeed.toDouble())
            .put("semi", semitones)
            .put("active", activeId?.toString() ?: "")
            .put("sel", selectedId?.toString() ?: "")
            .put("pos", positionMs)
            .put("mode", stretchMode)
            .put("joined", joined.joinToString(""))
            .put("joinMode", joinMode)
            .toString()
        if (st != lastSavedState) {
            prefs.edit().putString("state:$u", st).apply()
            lastSavedState = st
        }
    }

    private fun restoreState() {
        val u = uri ?: return
        val s = prefs.getString("state:$u", null)
        songSpeed = 1f
        semitones = 0
        var mode = StretchProcessor.MODE_MELODY
        if (s != null) {
            try {
                val o = JSONObject(s)
                songSpeed = o.optDouble("speed", 1.0).toFloat().coerceIn(0.25f, 2f)
                semitones = o.optInt("semi", 0).coerceIn(-12, 12)
                activeId = o.optString("active", "").firstOrNull()?.takeIf { c -> loops.any { it.id == c } }
                o.optString("sel", "").firstOrNull()?.takeIf { c -> loops.any { it.id == c } }?.let { selectedId = it }
                mode = o.optInt("mode", StretchProcessor.MODE_MELODY)
                joined.clear()
                o.optString("joined", "").forEach { c -> if (loops.any { it.id == c }) joined.add(c) }
                joinMode = o.optBoolean("joinMode", false) && joined.isNotEmpty()
                if (joined.size >= 2) activeId = null
                val pos = o.optLong("pos", 0L).coerceAtLeast(0L)
                player.seekTo(pos)
                positionMs = pos
            } catch (_: Exception) {
            }
        }
        lastSavedState = ""
        speed = activeLoop()?.speed ?: songSpeed
        stretchMode = mode
        stretch.requestMode(mode)
        applyParams()
    }

    private fun restoreGlobal() {
        val s = prefs.getString("global", null) ?: return
        try {
            val o = JSONObject(s)
            trainerOn = o.optBoolean("trOn", false)
            trainerStepPct = o.optInt("trStep", 5)
            trainerEvery = o.optInt("trEvery", 2)
            trainerMaxPct = o.optInt("trMax", 100)
            nudgeStep = o.optLong("nudge", 100L)
            changeVideoOn(o.optBoolean("video", true))
        } catch (_: Exception) {
        }
    }

    private fun loopsKey() = "loops:" + (uri?.toString() ?: "")

    private fun saveLoops() {
        if (uri == null) return
        val arr = JSONArray()
        loops.forEach { l ->
            arr.put(
                JSONObject().put("id", l.id.toString()).put("s", l.start).put("e", l.end)
                    .put("r", l.repeats).put("n", l.next?.toString() ?: "")
                    .apply { l.speed?.let { put("sp", it.toDouble()) } }
            )
        }
        prefs.edit().putString(loopsKey(), arr.toString()).apply()
    }

    private fun loadLoops() {
        loops.clear()
        val s = prefs.getString(loopsKey(), null) ?: return
        try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                loops.add(
                    Loop(
                        o.getString("id")[0], o.getLong("s"), o.getLong("e"),
                        o.optInt("r", 0), o.optString("n", "").firstOrNull(),
                        if (o.has("sp")) o.getDouble("sp").toFloat().coerceIn(0.25f, 2f) else null
                    )
                )
            }
            selectedId = loops.firstOrNull()?.id
        } catch (_: Exception) {
        }
    }

    /** Headphone / lock-screen "next": go to the next loop (or the first one). */
    fun nextLoop() {
        if (loops.isEmpty()) { skip(10_000); return }
        val i = loops.indexOfFirst { it.id == activeId }
        activate(loops[(i + 1).mod(loops.size)].id)
    }

    /** Headphone / lock-screen "previous": restart the loop, or go back one loop if near its start. */
    fun prevLoop() {
        val cur = activeLoop()
        if (cur == null) {
            if (loops.isEmpty()) toStart() else activate(loops.last().id)
            return
        }
        if (positionMs - cur.start > 1500) { seekTo(cur.start); return }
        val i = loops.indexOfFirst { it.id == activeId }
        activate(loops[(i - 1).mod(loops.size)].id)
    }
}
