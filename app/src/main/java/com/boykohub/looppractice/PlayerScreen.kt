package com.boykohub.looppractice

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val loopColors = listOf(
    Color(0xFFFFC15E), Color(0xFF7FD1FF), Color(0xFFB8F28B), Color(0xFFFF8FA3),
    Color(0xFFC9A7FF), Color(0xFF6EE7D8), Color(0xFFFFA36E), Color(0xFFE3E37A),
)

fun loopColor(id: Char): Color = loopColors[(id - 'A').mod(loopColors.size)]

fun fmt(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    val m = t / 60_000
    val s = (t / 1000) % 60
    val cs = (t / 10) % 100
    return "%d:%02d.%02d".format(m, s, cs)
}

fun fmtMs(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    return "%d:%02d.%03d".format(t / 60_000, (t / 1000) % 60, t % 1000)
}

/** Draw a small label only where it fits (drawText throws if it starts outside the canvas). */
fun androidx.compose.ui.graphics.drawscope.DrawScope.safeLabel(
    measurer: androidx.compose.ui.text.TextMeasurer, text: String, x: Float, y: Float, style: TextStyle
) {
    if (x < 0f || y < 0f || x > size.width - 30f || y > size.height - 30f) return
    drawText(measurer, text, topLeft = Offset(x, y), style = style)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(vm: PracticeEngine, onOpen: () -> Unit) {
    // Keep the screen awake while you practise.
    val view = LocalView.current
    DisposableEffect(vm.isPlaying) {
        view.keepScreenOn = vm.isPlaying
        onDispose { }
    }

    var showSongs by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (showSongs) {
        androidx.activity.compose.BackHandler { showSongs = false }
        SongsScreen(vm, onClose = { showSongs = false }, onOpen = { showSongs = false; onOpen() })
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp)
    ) {
        // ---------- header ----------
        Row(Modifier.padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Music Practice", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    if (vm.uri == null) "No file loaded" else vm.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(onClick = { showSongs = true }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("Songs") }
            Spacer(Modifier.width(6.dp))
            FilledTonalButton(onClick = onOpen) { Text("Open") }
        }

        if (vm.importing) Text("Importing…", color = MaterialTheme.colorScheme.primary)
        vm.crashReport?.let { CrashCard(it) { vm.dismissCrash() } }
        vm.error?.let { Text(it, color = Color(0xFFFF8FA3)) }

        if (vm.uri == null) {
            Spacer(Modifier.height(40.dp))
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text("Open an audio file", fontSize = 18.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "MP3, WAV, FLAC, M4A/AAC, OGG, Opus and video files. " +
                    "You can also use Share → Music Practice from other apps.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            return@Column
        }

        // ---------- pinned: waveform, time and transport stay visible ----------
        ScrollingWaveform(vm)
        Row(Modifier.padding(top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(fmt(vm.positionMs), fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(fmt(vm.durationMs), fontFamily = FontFamily.Monospace, color = Color.Gray)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallKey("|◀", Modifier.weight(1f)) { vm.toStart() }
            SmallKey("−5", Modifier.weight(1f)) { vm.skip(-5000) }
            SmallKey("−1", Modifier.weight(1f)) { vm.skip(-1000) }
            Button(
                onClick = { vm.togglePlay() },
                modifier = Modifier.weight(1.5f).height(56.dp),
                contentPadding = ButtonDefaults.TextButtonContentPadding
            ) { Text(if (vm.isPlaying) "❚❚" else "▶", fontSize = 22.sp) }
            SmallKey("+1", Modifier.weight(1f)) { vm.skip(1000) }
            SmallKey("+5", Modifier.weight(1f)) { vm.skip(5000) }
        }
        Spacer(Modifier.height(8.dp))

        // ---------- everything else scrolls ----------
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Timeline(vm)

            if (vm.hasVideo) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show video", modifier = Modifier.weight(1f))
                    Switch(checked = vm.videoOn, onCheckedChange = { vm.changeVideoOn(it) })
                }
                if (vm.videoOn) VideoBox(vm)
            }

            // ---------- loops ----------
            Section(
                "Loops" + when {
                    vm.awaitingEndId != null -> "  · tap ${vm.awaitingEndId} to set its end"
                    vm.joinMode && vm.joined.size >= 2 -> "  · ${vm.joined.sorted().joinToString("+")} ×${vm.reps}"
                    vm.joinMode -> "  · tap letters to join"
                    vm.activeId != null -> "  · ${vm.activeId} ×${vm.reps}"
                    else -> ""
                },
                trailing = {
                    if (vm.loops.size >= 2) {
                        if (vm.joinMode) Button(onClick = { vm.toggleJoinMode() }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("Join") }
                        else TextButton(onClick = { vm.toggleJoinMode() }) { Text("Join") }
                    }
                    if (vm.activeId != null || vm.joined.isNotEmpty()) TextButton(onClick = { vm.loopOff() }) { Text("Loop off") }
                }
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    vm.loops.forEach { l ->
                        val c = loopColor(l.id)
                        val inJoin = l.id in vm.joined
                        val active = vm.activeId == l.id || (inJoin && vm.joined.size >= 2)
                        val selected = vm.selectedId == l.id
                        val awaiting = vm.awaitingEndId == l.id
                        Box(
                            Modifier
                                .size(width = 72.dp, height = 56.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .then(if (vm.joinMode) Modifier.border(2.dp, if (inJoin) Color.White else c.copy(alpha = 0.4f), RoundedCornerShape(12.dp)) else Modifier)
                                .background(if (active) c else c.copy(alpha = if (awaiting) 0.35f else 0.18f))
                                .combinedClickable(
                                    onClick = { vm.tapLoop(l.id) },
                                    onLongClick = { vm.select(l.id) }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (awaiting) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(l.id.toString(), fontSize = 20.sp, fontWeight = FontWeight.Black, color = c)
                                    Text("tap = end", fontSize = 10.sp, color = c)
                                }
                            } else {
                                Text(
                                    (if (active) "↻" else "") + l.id + (l.next?.let { "→$it" } ?: ""),
                                    fontSize = if (l.next != null) 17.sp else 22.sp,
                                    fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
                                    color = if (active) Color.Black else c
                                )
                            }
                        }
                    }
                    Box(
                        Modifier
                            .size(width = 64.dp, height = 56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .combinedClickable(onClick = { vm.addLoop() }),
                        contentAlignment = Alignment.Center
                    ) { Text("+", fontSize = 26.sp) }
                }
            }

            // ---------- loop editor ----------
            vm.selectedLoop()?.let { l ->
                val shownEnd = vm.displayEnd(l)
                Section(
                    "Loop ${l.id}  · ${fmt(shownEnd - l.start)}",
                    accent = loopColor(l.id),
                    trailing = { TextButton(onClick = { vm.deleteSelected() }) { Text("Delete", color = Color(0xFFFF8FA3)) } }
                ) {
                    PointEditor("Start", l.start, vm.nudgeStep, { vm.setStart(l.id, it) }, { vm.startNow(l.id) })
                    PointEditor("End", shownEnd, vm.nudgeStep, { vm.setEnd(l.id, it) }, { vm.endNow(l.id) })
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Step", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.width(36.dp))
                        listOf(1L to "1ms", 10L to "10ms", 100L to "0.1s", 1000L to "1s").forEach { (v, t) ->
                            if (vm.nudgeStep == v) Button(onClick = { vm.nudgeStep = v }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(t) }
                            else OutlinedButton(onClick = { vm.nudgeStep = v }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(t) }
                        }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Then", style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                            modifier = Modifier.align(Alignment.CenterVertically).width(36.dp)
                        )
                        if (l.next == null) Button(onClick = {}, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("Keep looping") }
                        else OutlinedButton(onClick = { vm.setNext(l.id, null) }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("Keep looping") }
                        vm.loops.filter { it.id != l.id }.forEach { o ->
                            if (l.next == o.id) Button(
                                onClick = {},
                                colors = ButtonDefaults.buttonColors(containerColor = loopColor(o.id), contentColor = Color.Black),
                                contentPadding = ButtonDefaults.TextButtonContentPadding
                            ) { Text("→ ${o.id}") }
                            else OutlinedButton(onClick = { vm.setNext(l.id, o.id) }, contentPadding = ButtonDefaults.TextButtonContentPadding) {
                                Text("→ ${o.id}", color = loopColor(o.id))
                            }
                        }
                    }
                    if (l.next != null) {
                        Stepper(
                            "Plays", "${l.repeats}× → ${l.next}",
                            { vm.setRepeats(l.id, (l.repeats - 1).coerceAtLeast(1)) },
                            { vm.setRepeats(l.id, l.repeats + 1) }
                        )
                    }
                }
            }

            // ---------- speed ----------
            Section("Speed  ${(vm.speed * 100).toInt()}%" + (vm.activeId?.let { "  · loop $it" } ?: "  · song")) {
                Slider(value = vm.speed, onValueChange = { vm.changeSpeed(it) }, valueRange = 0.25f..2f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.changeSpeed(vm.speed - 0.05f) }, modifier = Modifier.weight(1f)) { Text("−5%") }
                    OutlinedButton(onClick = { vm.changeSpeed(vm.speed - 0.01f) }, modifier = Modifier.weight(1f)) { Text("−1%") }
                    OutlinedButton(onClick = { vm.changeSpeed(vm.speed + 0.01f) }, modifier = Modifier.weight(1f)) { Text("+1%") }
                    OutlinedButton(onClick = { vm.changeSpeed(vm.speed + 0.05f) }, modifier = Modifier.weight(1f)) { Text("+5%") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(50, 60, 70, 75, 80, 90, 100, 125).forEach { p ->
                        TextButton(onClick = { vm.changeSpeed(p / 100f) }) {
                            Text("$p%", color = if ((vm.speed * 100).toInt() == p) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            // ---------- stretch algorithm ----------
            Section("Stretch sound") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(StretchProcessor.MODE_MELODY to "Melody", StretchProcessor.MODE_DRUMS to "Drums").forEach { (m, t) ->
                        if (vm.stretchMode == m) Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(t) }
                        else OutlinedButton(onClick = { vm.changeStretchMode(m) }, modifier = Modifier.weight(1f)) { Text(t) }
                    }
                }
            }

            // ---------- pitch ----------
            Section("Pitch  " + (if (vm.semitones > 0) "+" else "") + "${vm.semitones} semitones") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.changeSemitones(-1) }, modifier = Modifier.weight(1f)) { Text("− ½ step") }
                    OutlinedButton(onClick = { vm.resetPitch() }, modifier = Modifier.weight(1f)) { Text("Reset") }
                    OutlinedButton(onClick = { vm.changeSemitones(1) }, modifier = Modifier.weight(1f)) { Text("+ ½ step") }
                }
            }

            // ---------- speed trainer ----------
            Section(
                "Speed trainer",
                trailing = { Switch(checked = vm.trainerOn, onCheckedChange = { vm.trainerOn = it }) }
            ) {
                if (vm.trainerOn) {
                    Stepper("Add", "${vm.trainerStepPct}%", { vm.trainerStepPct = (vm.trainerStepPct - 1).coerceAtLeast(1) }, { vm.trainerStepPct = (vm.trainerStepPct + 1).coerceAtMost(25) })
                    Stepper("Every", "${vm.trainerEvery} reps", { vm.trainerEvery = (vm.trainerEvery - 1).coerceAtLeast(1) }, { vm.trainerEvery = (vm.trainerEvery + 1).coerceAtMost(20) })
                    Stepper("Up to", "${vm.trainerMaxPct}%", { vm.trainerMaxPct = (vm.trainerMaxPct - 5).coerceAtLeast(30) }, { vm.trainerMaxPct = (vm.trainerMaxPct + 5).coerceAtMost(200) })
                } else {
                    Text("Speeds up a loop a little every few repeats.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun Section(
    title: String,
    accent: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 24.dp)) {
            Text(
                title, style = MaterialTheme.typography.titleSmall, color = accent, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            trailing?.invoke()
        }
        content()
    }
}

/** − / + button that keeps stepping while you hold it. */
@Composable
fun RepeatButton(label: String, onStep: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val step by rememberUpdatedState(onStep)
    LaunchedEffect(pressed) {
        if (pressed) {
            step()
            delay(400)
            while (true) {
                step()
                delay(60)
            }
        }
    }
    OutlinedButton(onClick = {}, interactionSource = src, contentPadding = ButtonDefaults.TextButtonContentPadding) {
        Text(label, fontSize = 18.sp)
    }
}

@Composable
fun PointEditor(label: String, ms: Long, step: Long, onSet: (Long) -> Unit, onNow: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(36.dp))
        RepeatButton("−") { onSet(ms - step) }
        Text(
            fmtMs(ms), fontFamily = FontFamily.Monospace, fontSize = 16.sp,
            textAlign = TextAlign.Center, modifier = Modifier.weight(1f)
        )
        RepeatButton("+") { onSet(ms + step) }
        FilledTonalButton(onClick = onNow, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("Now") }
    }
}

@Composable
fun CrashCard(report: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF3A1E24))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("The app crashed last time", fontWeight = FontWeight.Bold, color = Color(0xFFFF8FA3))
        Text(
            report, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color.LightGray,
            maxLines = 6, overflow = TextOverflow.Ellipsis
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("Copy details") }
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    }
}

@Composable
fun Stepper(label: String, value: String, onDec: () -> Unit, onInc: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.width(56.dp))
        OutlinedButton(onClick = onDec, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("−") }
        Text(value, modifier = Modifier.width(80.dp), fontFamily = FontFamily.Monospace)
        OutlinedButton(onClick = onInc, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("+") }
    }
}

/** Track bar: loop regions in colour, playhead in white. Tap or drag to seek. */
@Composable
fun Timeline(vm: PracticeEngine) {
    val measurer = rememberTextMeasurer()
    val dur = vm.durationMs
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(60.dp)
            .pointerInput(dur) {
                detectTapGestures { o ->
                    if (dur > 0) vm.seekTo((o.x / size.width * dur).toLong())
                }
            }
            .pointerInput(dur) {
                detectHorizontalDragGestures { change, _ ->
                    if (dur > 0) vm.seekTo((change.position.x.coerceIn(0f, size.width.toFloat()) / size.width * dur).toLong())
                }
            }
    ) {
        val w = size.width
        val h = size.height
        drawRoundRect(Color(0xFF22252B), cornerRadius = CornerRadius(14f, 14f))
        if (dur <= 0) return@Canvas

        val pk = vm.peaks
        if (pk.isNotEmpty()) {
            val totalBuckets = (dur / WaveformLoader.BUCKET_MS).toInt().coerceAtLeast(1)
            val step = 3f
            var x = 0f
            while (x < w) {
                val a = (x / w * totalBuckets).toInt()
                val b = ((x + step) / w * totalBuckets).toInt().coerceAtLeast(a + 1)
                var m = 0f
                for (i in a until b.coerceAtMost(pk.size)) if (pk[i] > m) m = pk[i]
                val hh = (m * h * 0.42f).coerceAtLeast(0.5f)
                drawRect(Color(0x66FFFFFF), topLeft = Offset(x, h / 2f - hh), size = Size(step - 1f, hh * 2f))
                x += step
            }
        }
        val laneH = h * 0.42f
        vm.loops.forEachIndexed { i, l ->
            val x0 = l.start.toFloat() / dur * w
            val x1 = vm.displayEnd(l).toFloat() / dur * w
            val c = loopColor(l.id)
            val active = vm.activeId == l.id
            drawRect(
                c.copy(alpha = if (active) 0.45f else 0.18f),
                topLeft = Offset(x0, 0f),
                size = Size((x1 - x0).coerceAtLeast(2f), h)
            )
            drawRect(c, topLeft = Offset(x0, 0f), size = Size(3f, h))
            drawRect(c, topLeft = Offset(x1 - 3f, 0f), size = Size(3f, h))
            val y = 4f + (i % 2) * laneH
            safeLabel(measurer, l.id.toString(), x0 + 6f, y, TextStyle(color = c, fontSize = 12.sp, fontWeight = FontWeight.Bold))
        }
        vm.joinedRange()?.let { r ->
            val jx0 = r.first.toFloat() / dur * w
            val jx1 = r.last.toFloat() / dur * w
            drawRect(Color(0x55FFFFFF), topLeft = Offset(jx0, h - 6f), size = Size((jx1 - jx0).coerceAtLeast(2f), 6f))
        }
        val px = vm.positionMs.toFloat() / dur * w
        drawRect(Color.White, topLeft = Offset(px - 2f, 0f), size = Size(4f, h))
    }
}

@Composable
fun SmallKey(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        contentPadding = ButtonDefaults.TextButtonContentPadding
    ) { Text(label, fontSize = 15.sp) }
}

/**
 * Zoomed, moving waveform: the playhead sits in the middle and the audio scrolls past.
 * Drag to scrub, tap to jump, pinch to zoom.
 */
@Composable
fun ScrollingWaveform(vm: PracticeEngine) {
    var windowMs by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableFloatStateOf(8000f) }
    val measurer = rememberTextMeasurer()
    val dur = vm.durationMs
    val peaks = vm.peaks
    val waveColor = MaterialTheme.colorScheme.primary
    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1A1C21))
                .pointerInput(dur) {
                    detectTapGestures { o ->
                        if (dur > 0) {
                            val msPerPx = windowMs / size.width
                            vm.seekTo((vm.positionMs + (o.x - size.width / 2f) * msPerPx).toLong())
                        }
                    }
                }
                .pointerInput(dur) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var dragged = false
                        do {
                            val ev = awaitPointerEvent()
                            val zoom = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            if (ev.changes.size >= 2 && zoom != 1f) {
                                windowMs = (windowMs / zoom).coerceIn(1500f, 120_000f)
                                ev.changes.forEach { it.consume() }
                            } else if (ev.changes.size == 1 && kotlin.math.abs(pan.x) > 0.5f && dur > 0) {
                                val msPerPx = windowMs / size.width
                                vm.scrubTo((vm.positionMs - pan.x * msPerPx).toLong())
                                dragged = true
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                        if (dragged) vm.endScrub()
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val mid = h / 2f
            if (dur <= 0) return@Canvas
            val msPerPx = windowMs / w
            val pos = vm.positionMs.toFloat()
            val t0 = pos - w / 2f * msPerPx

            // loops
            vm.loops.forEach { l ->
                val x0 = (l.start - t0) / msPerPx
                val x1 = (vm.displayEnd(l) - t0) / msPerPx
                if (x1 >= 0f && x0 <= w) {
                    val c = loopColor(l.id)
                    val active = vm.activeId == l.id
                    val rx0 = x0.coerceAtLeast(-4f)
                    val rx1 = x1.coerceAtMost(w + 4f)
                    drawRect(c.copy(alpha = if (active) 0.22f else 0.10f), topLeft = Offset(rx0, 0f), size = Size((rx1 - rx0).coerceAtLeast(1f), h))
                    drawRect(c, topLeft = Offset(x0, 0f), size = Size(3f, h))
                    drawRect(c, topLeft = Offset(x1 - 3f, 0f), size = Size(3f, h))
                    // Keep the letter visible even when the loop started off-screen to the left.
                    safeLabel(measurer, l.id.toString(), x0.coerceAtLeast(0f) + 8f, 6f,
                        TextStyle(color = c, fontSize = 14.sp, fontWeight = FontWeight.Bold))
                }
            }

            // second ticks
            val tickEvery = when { windowMs <= 10_000 -> 1000L; windowMs <= 40_000 -> 5000L; else -> 10_000L }
            var tick = ((t0 / tickEvery).toLong().coerceAtLeast(0)) * tickEvery
            while (tick <= t0 + windowMs) {
                val x = (tick - t0) / msPerPx
                drawRect(Color(0x33FFFFFF), topLeft = Offset(x, h - 10f), size = Size(1.5f, 10f))
                tick += tickEvery
            }

            // waveform
            if (peaks.isNotEmpty()) {
                val step = 3f
                var x = 0f
                while (x < w) {
                    val a = ((t0 + x * msPerPx) / WaveformLoader.BUCKET_MS).toInt()
                    val b = ((t0 + (x + step) * msPerPx) / WaveformLoader.BUCKET_MS).toInt().coerceAtLeast(a + 1)
                    if (b > 0 && a < peaks.size) {
                        var m = 0f
                        for (i in a.coerceAtLeast(0) until b.coerceAtMost(peaks.size)) if (peaks[i] > m) m = peaks[i]
                        val hh = (m * (h * 0.44f)).coerceAtLeast(1f)
                        val past = x < w / 2f
                        drawRect(
                            if (past) waveColor else waveColor.copy(alpha = 0.55f),
                            topLeft = Offset(x, mid - hh), size = Size(step - 1f, hh * 2f)
                        )
                    }
                    x += step
                }
            } else {
                safeLabel(measurer, "Drawing waveform…", 16f, mid - 20f, TextStyle(color = Color.Gray, fontSize = 13.sp))
            }

            // joined span
            vm.joinedRange()?.let { r ->
                val jx0 = ((r.first - t0) / msPerPx).coerceAtLeast(-4f)
                val jx1 = ((r.last - t0) / msPerPx).coerceAtMost(w + 4f)
                if (jx1 > jx0) drawRect(Color(0x26FFFFFF), topLeft = Offset(jx0, h - 8f), size = Size(jx1 - jx0, 8f))
            }

            // playhead
            drawRect(Color.White, topLeft = Offset(w / 2f - 1.5f, 0f), size = Size(3f, h))
            // zoom indicator
            safeLabel(measurer, "${"%.0f".format(windowMs / 1000f)} s", w - 70f, h - 40f,
                TextStyle(color = Color(0x88FFFFFF), fontSize = 11.sp))
        }
    }
}

@Composable
fun VideoBox(vm: PracticeEngine) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            android.view.SurfaceView(ctx).also { vm.player.setVideoSurfaceView(it) }
        },
        onRelease = { vm.player.clearVideoSurfaceView(it) },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(vm.videoAspect.coerceIn(0.5f, 2.4f))
            .clip(RoundedCornerShape(14.dp))
    )
}

@OptIn(ExperimentalFoundationApi::class)
/** Saved songs: every imported file with its own loops and settings. */
@Composable
fun SongsScreen(vm: PracticeEngine, onClose: () -> Unit, onOpen: () -> Unit) {
    var renaming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Song?>(null) }
    var removing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Song?>(null) }
    val df = androidx.compose.runtime.remember { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(Modifier.padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("‹ Back") }
            Text("Songs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = onOpen) { Text("Import") }
        }
        if (vm.songs.isEmpty()) {
            Text(
                "No songs yet. Import a file with Import, or use Share → Music Practice from another app.",
                color = Color.Gray, modifier = Modifier.padding(top = 24.dp)
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            vm.songs.forEach { song ->
                val current = vm.uri?.toString() == song.uri
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (current) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        )
                        .combinedClickable(onClick = { vm.openSong(song); onClose() })
                        .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(song.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val n = vm.loopCount(song)
                        Text(
                            "$n loop${if (n == 1) "" else "s"} · ${df.format(java.util.Date(song.lastOpened))}",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray
                        )
                    }
                    TextButton(onClick = { renaming = song }) { Text("Rename") }
                    TextButton(onClick = { removing = song }) { Text("Remove", color = Color(0xFFFF8FA3)) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    renaming?.let { song ->
        var text by androidx.compose.runtime.remember(song) { androidx.compose.runtime.mutableStateOf(song.name) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename song") },
            text = { androidx.compose.material3.OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameSong(song, text); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }
    removing?.let { song ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove \"${song.name}\"?") },
            text = { Text("Its loops and settings are deleted. Your original file is not touched.") },
            confirmButton = { TextButton(onClick = { vm.removeSong(song); removing = null }) { Text("Remove", color = Color(0xFFFF8FA3)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } }
        )
    }
}
