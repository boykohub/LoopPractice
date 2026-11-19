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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.pow
import kotlin.math.roundToInt

data class Loop(val id: Char, val start: Long, val end: Long)

/**
 * Lives for the whole app process (owned by [LoopApp]), so loops and the speed trainer
 * keep working while the screen is off or the app is in the background.
 */
class PracticeEngine(private val app: Application) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val prefs = app.getSharedPreferences("looppractice", Context.MODE_PRIVATE)

    val player: ExoPlayer = ExoPlayer.Builder(app)
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

    var speed by mutableFloatStateOf(1f); private set
    var semitones by mutableIntStateOf(0); private set

    val loops = mutableStateListOf<Loop>()
    var activeId by mutableStateOf<Char?>(null); private set
    var selectedId by mutableStateOf<Char?>(null); private set
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
                    val l = activeLoop()
                    if (l != null) {
                        onLoopWrap(l)
                    }
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                error = "Can't play this file (${e.errorCodeName})"
            }
        })

        scope.launch {
            while (true) {
                tick()
                delay(10)
            }
        }

        restoreGlobal()

        // Re-open the last file, if we still have permission to read it.
        prefs.getString("lastUri", null)?.let { s ->
            val u = Uri.parse(s)
            val ok = app.contentResolver.persistedUriPermissions.any { it.uri == u && it.isReadPermission }
            if (ok) load(u, takePermission = false)
        }
    }

    private var saveCounter = 0

    private fun tick() {
        positionMs = player.currentPosition
        if (++saveCounter >= 200) { // about every 2 seconds
            saveCounter = 0
            saveState()
        }
        val l = activeLoop() ?: return
        if (l.end > l.start && player.isPlaying && positionMs >= l.end) {
            onLoopWrap(l)
        }
    }

    private fun onLoopWrap(l: Loop) {
        player.seekTo(l.start)
        positionMs = l.start
        if (!player.playWhenReady) player.play()
        reps += 1
        if (trainerOn && trainerEvery > 0 && reps % trainerEvery == 0) {
            val max = trainerMaxPct / 100f
            if (speed < max) setSpeed((speed + trainerStepPct / 100f).coerceAtMost(max))
        }
    }

    fun activeLoop(): Loop? = activeId?.let { id -> loops.firstOrNull { it.id == id } }
    fun selectedLoop(): Loop? = selectedId?.let { id -> loops.firstOrNull { it.id == id } }

    // ---------- file ----------

    fun load(u: Uri, takePermission: Boolean = true) {
        val ctx = app
        if (takePermission) {
            try {
                ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // Some sources (e.g. "Open with") don't allow keeping access. Playback still works now.
            }
        }
        saveState() // remember where we were in the previous file
        error = null
        uri = u
        fileName = queryName(u)
        durationMs = 0L
        activeId = null
        selectedId = null
        reps = 0
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(u)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(fileName).setArtist("Loop Practice").build())
                .build()
        )
        player.prepare()
        loadLoops()
        restoreState()
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
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(activeLoop()?.start ?: 0L)
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(ms: Long) {
        val t = ms.coerceIn(0L, maxOf(durationMs, 0L))
        player.seekTo(t)
        positionMs = t
    }

    fun skip(deltaMs: Long) = seekTo(positionMs + deltaMs)

    fun toStart() = seekTo(activeLoop()?.start ?: 0L)

    // ---------- speed & pitch ----------

    fun setSpeed(v: Float) {
        speed = ((v.coerceIn(0.25f, 2f) * 100f).roundToInt()) / 100f
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
        // Media3 time-stretches with pitch kept, so speed and pitch are independent.
        player.playbackParameters = PlaybackParameters(speed, pitch)
    }

    // ---------- loops ----------

    fun addLoop() {
        if (durationMs <= 0) return
        val id = ('A'..'Z').firstOrNull { c -> loops.none { it.id == c } } ?: return
        val start = positionMs.coerceIn(0, durationMs)
        var end = (start + 8_000).coerceAtMost(durationMs)
        if (end - start < 200) end = durationMs
        loops.add(Loop(id, start, end))
        loops.sortBy { it.id }
        selectedId = id
        saveLoops()
    }

    /** Tap a loop: jump straight to its start and loop it. */
    fun activate(id: Char) {
        val l = loops.firstOrNull { it.id == id } ?: return
        activeId = id
        selectedId = id
        reps = 0
        player.seekTo(l.start)
        positionMs = l.start
        player.play()
    }

    fun select(id: Char) {
        selectedId = id
    }

    fun loopOff() {
        activeId = null
        reps = 0
    }

    fun deleteSelected() {
        val id = selectedId ?: return
        loops.removeAll { it.id == id }
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

    private fun update(id: Char, f: (Loop) -> Loop) {
        val i = loops.indexOfFirst { it.id == id }
        if (i < 0) return
        loops[i] = f(loops[i])
        saveLoops()
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
            .toString()
        if (g != lastSavedGlobal) {
            prefs.edit().putString("global", g).apply()
            lastSavedGlobal = g
        }
        val u = uri ?: return
        if (durationMs <= 0) return // not loaded yet; don't overwrite with empty state
        val st = JSONObject()
            .put("speed", speed.toDouble())
            .put("semi", semitones)
            .put("active", activeId?.toString() ?: "")
            .put("sel", selectedId?.toString() ?: "")
            .put("pos", positionMs)
            .toString()
        if (st != lastSavedState) {
            prefs.edit().putString("state:$u", st).apply()
            lastSavedState = st
        }
    }

    private fun restoreState() {
        val u = uri ?: return
        val s = prefs.getString("state:$u", null)
        speed = 1f
        semitones = 0
        if (s != null) {
            try {
                val o = JSONObject(s)
                speed = o.optDouble("speed", 1.0).toFloat().coerceIn(0.25f, 2f)
                semitones = o.optInt("semi", 0).coerceIn(-12, 12)
                activeId = o.optString("active", "").firstOrNull()?.takeIf { c -> loops.any { it.id == c } }
                o.optString("sel", "").firstOrNull()?.takeIf { c -> loops.any { it.id == c } }?.let { selectedId = it }
                val pos = o.optLong("pos", 0L).coerceAtLeast(0L)
                player.seekTo(pos)
                positionMs = pos
            } catch (_: Exception) {
            }
        }
        lastSavedState = ""
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
        } catch (_: Exception) {
        }
    }

    private fun loopsKey() = "loops:" + (uri?.toString() ?: "")

    private fun saveLoops() {
        if (uri == null) return
        val arr = JSONArray()
        loops.forEach { l ->
            arr.put(JSONObject().put("id", l.id.toString()).put("s", l.start).put("e", l.end))
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
                loops.add(Loop(o.getString("id")[0], o.getLong("s"), o.getLong("e")))
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
