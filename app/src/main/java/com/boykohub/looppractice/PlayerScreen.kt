package com.boykohub.looppractice

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(vm: PlayerViewModel, onOpen: () -> Unit) {
    // Keep the screen awake while you practise.
    val view = LocalView.current
    DisposableEffect(vm.isPlaying) {
        view.keepScreenOn = vm.isPlaying
        onDispose { }
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ---------- header ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Loop Practice", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    if (vm.uri == null) "No file loaded" else vm.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            FilledTonalButton(onClick = onOpen) { Text("Open") }
        }

        vm.error?.let { Text(it, color = Color(0xFFFF8FA3)) }

        if (vm.uri == null) {
            Spacer(Modifier.height(40.dp))
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text("Open an audio file", fontSize = 18.sp)
            }
            Text(
                "MP3, WAV, FLAC, M4A/AAC, OGG, Opus, and the audio from video files. " +
                    "You can also pick \"Loop Practice\" when opening a file from Files or Drive.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            return@Column
        }

        // ---------- timeline ----------
        Timeline(vm)
        Row {
            Text(fmt(vm.positionMs), fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(fmt(vm.durationMs), fontFamily = FontFamily.Monospace, color = Color.Gray)
        }

        // ---------- transport ----------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { vm.toStart() }, modifier = Modifier.weight(1f)) { Text("|◀") }
            OutlinedButton(onClick = { vm.skip(-5000) }, modifier = Modifier.weight(1f)) { Text("−5s") }
            Button(
                onClick = { vm.togglePlay() },
                modifier = Modifier.weight(1.4f).height(56.dp)
            ) { Text(if (vm.isPlaying) "❚❚" else "▶", fontSize = 22.sp) }
            OutlinedButton(onClick = { vm.skip(5000) }, modifier = Modifier.weight(1f)) { Text("+5s") }
        }

        // ---------- loops ----------
        Section("Loops") {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                vm.loops.forEach { l ->
                    val c = loopColor(l.id)
                    val active = vm.activeId == l.id
                    val selected = vm.selectedId == l.id
                    Box(
                        Modifier
                            .size(width = 64.dp, height = 56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (active) c else c.copy(alpha = 0.18f))
                            .combinedClickable(
                                onClick = { vm.activate(l.id) },
                                onLongClick = { vm.select(l.id) }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            (if (active) "↻ " else "") + l.id,
                            fontSize = 22.sp,
                            fontWeight = if (selected) FontWeight.Black else FontWeight.Medium,
                            color = if (active) Color.Black else c
                        )
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (vm.activeId != null) "Looping ${vm.activeId} · ${vm.reps} reps" else "Loop off — tap a letter to loop it",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    modifier = Modifier.weight(1f)
                )
                if (vm.activeId != null) TextButton(onClick = { vm.loopOff() }) { Text("Loop off") }
            }
            Text("Long-press a letter to edit it without jumping.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }

        // ---------- loop editor ----------
        vm.selectedLoop()?.let { l ->
            Section("Edit loop ${l.id}", accent = loopColor(l.id)) {
                PointEditor(
                    label = "Start", ms = l.start, step = vm.nudgeStep,
                    onSet = { vm.setStart(l.id, it) }, onNow = { vm.startNow(l.id) }
                )
                PointEditor(
                    label = "End", ms = l.end, step = vm.nudgeStep,
                    onSet = { vm.setEnd(l.id, it) }, onNow = { vm.endNow(l.id) }
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Nudge", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    listOf(10L to "0.01s", 100L to "0.1s", 1000L to "1s").forEach { (v, t) ->
                        val on = vm.nudgeStep == v
                        if (on) Button(onClick = { vm.nudgeStep = v }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(t) }
                        else OutlinedButton(onClick = { vm.nudgeStep = v }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text(t) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.deleteSelected() }) { Text("Delete", color = Color(0xFFFF8FA3)) }
                }
                Text("Length ${fmt(l.end - l.start)}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }

        // ---------- speed ----------
        Section("Speed  ${(vm.speed * 100).toInt()}%") {
            Slider(value = vm.speed, onValueChange = { vm.setSpeed(it) }, valueRange = 0.25f..2f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.setSpeed(vm.speed - 0.05f) }, modifier = Modifier.weight(1f)) { Text("−5%") }
                OutlinedButton(onClick = { vm.setSpeed(vm.speed - 0.01f) }, modifier = Modifier.weight(1f)) { Text("−1%") }
                OutlinedButton(onClick = { vm.setSpeed(vm.speed + 0.01f) }, modifier = Modifier.weight(1f)) { Text("+1%") }
                OutlinedButton(onClick = { vm.setSpeed(vm.speed + 0.05f) }, modifier = Modifier.weight(1f)) { Text("+5%") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(50, 60, 70, 75, 80, 90, 100, 125).forEach { p ->
                    TextButton(onClick = { vm.setSpeed(p / 100f) }) {
                        Text("$p%", color = if ((vm.speed * 100).toInt() == p) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary)
                    }
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
        Section("Speed trainer") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Speeds up automatically while a loop repeats",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = vm.trainerOn, onCheckedChange = { vm.trainerOn = it })
            }
            if (vm.trainerOn) {
                Stepper("Add", "${vm.trainerStepPct}%", { vm.trainerStepPct = (vm.trainerStepPct - 1).coerceAtLeast(1) }, { vm.trainerStepPct = (vm.trainerStepPct + 1).coerceAtMost(25) })
                Stepper("Every", "${vm.trainerEvery} reps", { vm.trainerEvery = (vm.trainerEvery - 1).coerceAtLeast(1) }, { vm.trainerEvery = (vm.trainerEvery + 1).coerceAtMost(20) })
                Stepper("Up to", "${vm.trainerMaxPct}%", { vm.trainerMaxPct = (vm.trainerMaxPct - 5).coerceAtLeast(30) }, { vm.trainerMaxPct = (vm.trainerMaxPct + 5).coerceAtMost(200) })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun Section(title: String, accent: Color = MaterialTheme.colorScheme.primary, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = accent, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
fun PointEditor(label: String, ms: Long, step: Long, onSet: (Long) -> Unit, onNow: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, modifier = Modifier.width(44.dp))
        OutlinedButton(onClick = { onSet(ms - step) }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("−") }
        Text(fmt(ms), fontFamily = FontFamily.Monospace, fontSize = 17.sp, modifier = Modifier.width(92.dp))
        OutlinedButton(onClick = { onSet(ms + step) }, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("+") }
        Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = onNow, contentPadding = ButtonDefaults.TextButtonContentPadding) { Text("Now") }
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
fun Timeline(vm: PlayerViewModel) {
    val measurer = rememberTextMeasurer()
    val dur = vm.durationMs
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(84.dp)
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

        val laneH = 22f * density / 2.5f + 18f
        vm.loops.forEachIndexed { i, l ->
            val x0 = l.start.toFloat() / dur * w
            val x1 = l.end.toFloat() / dur * w
            val c = loopColor(l.id)
            val active = vm.activeId == l.id
            drawRect(
                c.copy(alpha = if (active) 0.45f else 0.18f),
                topLeft = Offset(x0, 0f),
                size = Size((x1 - x0).coerceAtLeast(2f), h)
            )
            drawRect(c, topLeft = Offset(x0, 0f), size = Size(3f, h))
            drawRect(c, topLeft = Offset(x1 - 3f, 0f), size = Size(3f, h))
            val y = 6f + (i % 2) * laneH
            drawText(
                measurer, l.id.toString(),
                topLeft = Offset(x0 + 8f, y),
                style = TextStyle(color = c, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            )
        }
        val px = vm.positionMs.toFloat() / dur * w
        drawRect(Color.White, topLeft = Offset(px - 2f, 0f), size = Size(4f, h))
    }
}
