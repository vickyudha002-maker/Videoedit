package com.example.videdit

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

val FILTERS = listOf("None", "B&W", "Vivid", "Warm", "Cool", "Fade", "Invert")
val SPEEDS = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f)
val EQ_LABELS = listOf("60Hz", "230Hz", "910Hz", "3.6k", "14k")
private const val PH = 96

class TSnap(val video: Boolean, val locked: Boolean, val muted: Boolean, val clips: List<Clip>)
class Tool(val id: String, val sym: String, val label: String)

fun outSize(q: Int, r: Float): Pair<Int, Int> =
    if (r >= 1f) (q * r).toInt() / 2 * 2 to q else q to (q / r).toInt() / 2 * 2

/** An empty space on a track (kind 3). */
fun gapClip(ms: Float) = Clip(Media("gap", 3_600_000L, 3), 0f, ms)

private fun trackLen(t: Track): Float { var s = 0f; t.clips.forEach { s += it.lenMs }; return s }
private fun clipStart(t: Track, idx: Int): Float { var s = 0f; for (k in 0 until idx) s += t.clips[k].lenMs; return s }
private fun findAt(t: Track, ms: Float): Pair<Int, Float>? {
    var s = 0f
    t.clips.forEachIndexed { i, c ->
        if (ms >= s && ms < s + c.lenMs) return i to (ms - s)
        s += c.lenMs
    }
    return null
}
private fun fmtT(ms: Int) = "%d:%02d.%d".format(ms / 60000, ms / 1000 % 60, ms / 100 % 10)
private fun effSig(c: Clip) = listOf(c.filter, c.bright, c.contrast, c.sat, c.vib, c.temp, c.high,
    c.zoomA, c.zoomB, c.panAx, c.panAy, c.panBx, c.panBy).joinToString(",")

private fun toolsFor(c: Clip, isMain: Boolean, isVideoTrack: Boolean): List<Tool> = when {
    !isVideoTrack -> listOf(Tool("volume", "🔊", "Vol/EQ"), Tool("trim", "✂️", "Trim"), Tool("split", "➗", "Split"),
        Tool("speed", "⏱", "Speed"), Tool("move_l", "◀", "Left"), Tool("move_r", "▶", "Right"), Tool("dup", "⧉", "Copy"), Tool("delete", "🗑", "Delete"))
    c.m.kind == 2 -> listOf(Tool("layerpos", "⤡", "Size"), Tool("trim", "✂️", "Trim"), Tool("filter", "🎨", "Filter"),
        Tool("adjust", "🎚", "Adjust"), Tool("move_l", "◀", "Left"), Tool("move_r", "▶", "Right"), Tool("dup", "⧉", "Copy"), Tool("delete", "🗑", "Delete"))
    isMain -> listOf(Tool("extract", "🎵", "Extract"), Tool("trim", "✂️", "Trim"), Tool("split", "➗", "Split"), Tool("reverse", "⏪", "Reverse"),
        Tool("speed", "⏱", "Speed"), Tool("zoom", "🔍", "Pan/Zoom"), Tool("filter", "🎨", "Filter"), Tool("volume", "🔊", "Vol/EQ"),
        Tool("adjust", "🎚", "Adjust"), Tool("move_l", "◀", "Left"), Tool("move_r", "▶", "Right"), Tool("dup", "⧉", "Copy"), Tool("delete", "🗑", "Delete"))
    else -> listOf(Tool("layerpos", "⤡", "Size"), Tool("trim", "✂️", "Trim"), Tool("split", "➗", "Split"), Tool("speed", "⏱", "Speed"),
        Tool("filter", "🎨", "Filter"), Tool("volume", "🔊", "Vol/EQ"), Tool("adjust", "🎚", "Adjust"),
        Tool("move_l", "◀", "Left"), Tool("move_r", "▶", "Right"), Tool("dup", "⧉", "Copy"), Tool("delete", "🗑", "Delete"))
}

@Composable
private fun SideBtn(sym: String, label: String, color: Color = PANEL, alpha: Float = 1f, onClick: () -> Unit) {
    Column(Modifier.size(44.dp).alpha(alpha).clip(RoundedCornerShape(10.dp)).background(color).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(sym, fontSize = 16.sp, color = Color.White)
        Text(label, fontSize = 8.sp, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun Slid(label: String, v: Float, r: ClosedFloatingPointRange<Float>, show: String, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 10.sp, modifier = Modifier.width(56.dp))
        Slider(value = v, onValueChange = onChange, valueRange = r, modifier = Modifier.weight(1f))
        Text(show, color = Color.White, fontSize = 10.sp, modifier = Modifier.width(38.dp))
    }
}

private fun resetTool(c: Clip, tool: String) {
    when (tool) {
        "speed" -> c.speed = 1f
        "zoom" -> { c.zoomA = 1f; c.zoomB = 1f; c.panAx = 0f; c.panAy = 0f; c.panBx = 0f; c.panBy = 0f }
        "filter" -> c.filter = 0
        "adjust" -> { c.bright = 0f; c.contrast = 0f; c.sat = 0f; c.vib = 0f; c.temp = 0f; c.high = 0f }
        "volume" -> { c.volume = 1f; for (i in 0 until 5) c.eq[i] = 0f }
        "layerpos" -> { c.lx = 0f; c.ly = 0f; c.ls = 0.4f }
    }
}

@Composable
private fun ToolPanel(c: Clip, tool: String, onDone: () -> Unit) {
    val title = when (tool) {
        "speed" -> "Speed"; "zoom" -> "Pan & zoom"; "filter" -> "Filters"; "adjust" -> "Adjustments"
        "volume" -> "Volume & equalizer"; else -> "Position & size"
    }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { resetTool(c, tool) }) { Text("Reset") }
            Button(onClick = onDone, contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(32.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RED, contentColor = Color.White)) { Text("✓ Done") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (tool) {
                "speed" -> {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SPEEDS.forEach { s -> FilterChip(selected = c.speed == s, onClick = { c.speed = s }, label = { Text("${s}x") }) }
                    }
                    Slid("Speed", c.speed, 0.25f..4f, "%.2fx".format(c.speed)) { c.speed = (it * 20).roundToInt() / 20f }
                }
                "filter" -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FILTERS.forEachIndexed { i, n -> FilterChip(selected = c.filter == i, onClick = { c.filter = i }, label = { Text(n) }) }
                }
                else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        when (tool) {
                            "zoom" -> {
                                Slid("Zoom start", c.zoomA, 1f..3f, "%.1fx".format(c.zoomA)) { c.zoomA = it }
                                Slid("Pan X start", c.panAx, -1f..1f, "%.1f".format(c.panAx)) { c.panAx = it }
                                Slid("Pan Y start", c.panAy, -1f..1f, "%.1f".format(c.panAy)) { c.panAy = it }
                            }
                            "adjust" -> {
                                Slid("Brightness", c.bright, -1f..1f, "${(c.bright * 100).toInt()}") { c.bright = it }
                                Slid("Contrast", c.contrast, -1f..1f, "${(c.contrast * 100).toInt()}") { c.contrast = it }
                                Slid("Saturation", c.sat, -1f..1f, "${(c.sat * 100).toInt()}") { c.sat = it }
                            }
                            "volume" -> {
                                Slid("Volume", c.volume, 0f..2f, "${(c.volume * 100).toInt()}%") { c.volume = it }
                                for (i in 0 until 2) Slid(EQ_LABELS[i], c.eq[i], -12f..12f, "${c.eq[i].toInt()}dB") { c.eq[i] = it }
                            }
                            else -> Slid("Size", c.ls, 0.1f..1f, "${(c.ls * 100).toInt()}%") { c.ls = it }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        when (tool) {
                            "zoom" -> {
                                Slid("Zoom end", c.zoomB, 1f..3f, "%.1fx".format(c.zoomB)) { c.zoomB = it }
                                Slid("Pan X end", c.panBx, -1f..1f, "%.1f".format(c.panBx)) { c.panBx = it }
                                Slid("Pan Y end", c.panBy, -1f..1f, "%.1f".format(c.panBy)) { c.panBy = it }
                            }
                            "adjust" -> {
                                Slid("Vibrance", c.vib, -1f..1f, "${(c.vib * 100).toInt()}") { c.vib = it }
                                Slid("Temperature", c.temp, -1f..1f, "${(c.temp * 100).toInt()}") { c.temp = it }
                                Slid("Highlights", c.high, -1f..1f, "${(c.high * 100).toInt()}") { c.high = it }
                            }
                            "volume" -> for (i in 2 until 5) Slid(EQ_LABELS[i], c.eq[i], -12f..12f, "${c.eq[i].toInt()}dB") { c.eq[i] = it }
                            else -> {
                                Slid("Left/Right", c.lx, -1f..1f, "%.1f".format(c.lx)) { c.lx = it }
                                Slid("Up/Down", c.ly, -1f..1f, "%.1f".format(c.ly)) { c.ly = it }
                                Text("Tip: you can also drag the layer in the preview.", color = Color(0xFF9AA0A6), fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LayerBox(c: Clip, wPx: Float, hPx: Float, dens: Float, selected: Boolean) {
    val bm = c.m.thumbs.firstOrNull()
    val wDp = (wPx * c.ls / dens).dp
    val ar = wPx / hPx
    val hDp = wDp / ar
    Box(Modifier.offset(x = ((wPx * (c.lx + 1f) / 2f) / dens).dp - wDp / 2, y = ((hPx * (1f - c.ly) / 2f) / dens).dp - hDp / 2)
        .size(wDp, hDp).border(if (selected) 2.dp else 1.dp, if (selected) ORANGE else Color.White)
        .pointerInput(c) {
            detectDragGestures { _, d ->
                c.lx = (c.lx + d.x / wPx * 2f).coerceIn(-1f, 1f)
                c.ly = (c.ly - d.y / hPx * 2f).coerceIn(-1f, 1f)
            }
        }) {
        if (bm != null) Image(bm, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun Editor(act: ComponentActivity, project: Project, onBack: () -> Unit) {
    val tracks = project.tracks
    var screen by remember { mutableStateOf(1) } // 1 editor, 2 export page, 3 full preview
    var selT by remember { mutableStateOf(-1) }
    var selC by remember { mutableStateOf(-1) }
    var tool by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var showText by remember { mutableStateOf(false) }
    var showTrim by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var ppd by remember { mutableStateOf(12f) }
    var quality by remember { mutableStateOf(1080) }
    var crop by remember { mutableStateOf(false) }
    var fileName by remember { mutableStateOf(project.name.replace(' ', '_')) }
    val undo = remember { mutableStateListOf<List<TSnap>>() }
    val redo = remember { mutableStateListOf<List<TSnap>>() }
    val scroll = rememberScrollState()
    val dens = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val exo = remember { ExoPlayer.Builder(act).build() }
    DisposableEffect(Unit) { onDispose { exo.release() } }
    val last = remember { arrayOfNulls<Any>(2) }

    fun mainIdx() = tracks.indexOfFirst { it.video }
    fun playMs() = scroll.value / dens / ppd * 1000f
    fun totalMs() = tracks.maxOfOrNull { trackLen(it) } ?: 0f
    fun selClip(): Clip? = tracks.getOrNull(selT)?.clips?.getOrNull(selC)
    fun snap() = tracks.map { t -> TSnap(t.video, t.locked, t.muted, t.clips.map { it.copy() }) }
    fun restore(s: List<TSnap>) {
        tracks.clear()
        s.forEach { x ->
            val t = Track(x.video); t.locked = x.locked; t.muted = x.muted
            x.clips.forEach { t.clips.add(it.copy()) }
            tracks.add(t)
        }
        selT = -1; selC = -1; tool = null
    }
    fun commit() { undo.add(snap()); redo.clear() }
    fun doUndo() { if (undo.isNotEmpty()) { redo.add(snap()); restore(undo.removeAt(undo.lastIndex)) } }
    fun doRedo() { if (redo.isNotEmpty()) { undo.add(snap()); restore(redo.removeAt(redo.lastIndex)) } }

    fun split() {
        val ti = if (selT in tracks.indices) selT else mainIdx()
        val t = tracks.getOrNull(ti) ?: return
        if (t.locked) return
        val (ci, off) = findAt(t, playMs()) ?: return
        val c = t.clips[ci]
        if (c.m.kind == 3) return
        val cut = c.start + off * c.speed
        if (off < 200f || cut > c.end - 200f) { status = "Move the playhead inside the clip"; return }
        commit()
        val a = c.copy(); a.end = cut
        val b = c.copy(); b.start = cut
        t.clips[ci] = a
        t.clips.add(ci + 1, b)
        selT = ti; selC = ci + 1
    }
    fun trimSide(left: Boolean) {
        val t = tracks.getOrNull(selT) ?: return
        val c = t.clips.getOrNull(selC) ?: return
        val (ci, off) = findAt(t, playMs()) ?: run { status = "Move the playhead onto the clip"; return }
        if (ci != selC) { status = "Move the playhead onto the selected clip"; return }
        val src = c.start + off * c.speed
        commit()
        if (left) c.start = src.coerceAtMost(c.end - 300f) else c.end = src.coerceAtLeast(c.start + 300f)
    }
    fun nudge(t: Track, c: Clip, dxMs: Float) {
        val idx = t.clips.indexOf(c)
        if (idx < 0) return
        val prev = t.clips.getOrNull(idx - 1)
        if (prev != null && prev.m.kind == 3) {
            val nl = prev.end + dxMs
            if (nl <= 50f) t.clips.removeAt(idx - 1) else prev.end = nl
        } else if (dxMs > 50f) t.clips.add(idx, gapClip(dxMs))
        selC = t.clips.indexOf(c)
    }
    fun runTool(id: String) {
        val t = tracks.getOrNull(selT) ?: return
        val c = t.clips.getOrNull(selC) ?: return
        if (t.locked) { status = "This track is locked"; return }
        when (id) {
            "trim" -> showTrim = true
            "split" -> split()
            "extract" -> {
                commit()
                val start = clipStart(t, selC)
                val am = Media(c.m.path, c.m.durMs, 1); prepare(act, am)
                val ac = Clip(am, c.start, c.end); ac.speed = c.speed; ac.volume = c.volume
                val nt = Track(false)
                if (start > 50f) nt.clips.add(gapClip(start))
                nt.clips.add(ac); tracks.add(nt)
                c.volume = 0f
                status = "Audio moved to a new audio track"
            }
            "reverse" -> {
                if (c.end - c.start > 30000f) { status = "Reverse works on clips up to 30 s. Trim or split first."; return }
                busy = "Reversing the clip..."
                reverseVideo(act, c.m, c.start.toLong(), c.end.toLong()) { nm ->
                    busy = null
                    if (nm == null) status = "Reverse failed" else {
                        val i = t.clips.indexOf(c)
                        if (i >= 0) {
                            commit()
                            val o = c.toJson()
                            o.put("path", nm.path); o.put("dur", nm.durMs); o.put("start", 0.0); o.put("end", nm.durMs.toDouble())
                            t.clips[i] = Clip.fromJson(o, hashMapOf("0|${nm.path}" to nm))
                            prepare(act, nm)
                        }
                    }
                }
            }
            "move_l" -> if (selC > 0) { commit(); t.clips.add(selC - 1, t.clips.removeAt(selC)); selC-- }
            "move_r" -> if (selC < t.clips.lastIndex) { commit(); t.clips.add(selC + 1, t.clips.removeAt(selC)); selC++ }
            "dup" -> { commit(); t.clips.add(selC + 1, c.copy()) }
            "delete" -> { commit(); t.clips.removeAt(selC); selC = -1 }
            else -> { commit(); tool = id }
        }
    }
    fun zoom(f: Float) {
        val tt = scroll.value / dens / ppd
        ppd = (ppd * f).coerceIn(4f, 80f)
        scope.launch { scroll.scrollTo((tt * ppd * dens).toInt()) }
    }
    fun importFiles(uris: List<Uri>, force: Int?, done: (List<Pair<File, Int>>) -> Unit) {
        status = "Importing..."
        Thread {
            val out = uris.mapNotNull { u ->
                try {
                    val mime = act.contentResolver.getType(u) ?: ""
                    val ext = mime.substringAfter('/', "bin").ifEmpty { "bin" }
                    val kind = force ?: if (mime.startsWith("image")) 2 else if (mime.startsWith("audio")) 1 else 0
                    ProjectStore.importMedia(act, u, ext) to kind
                } catch (e: Exception) {
                    null
                }
            }
            act.runOnUiThread { status = ""; done(out) }
        }.start()
    }
    fun layerTrack(): Track {
        val mi = mainIdx()
        val sel = tracks.getOrNull(selT)
        if (sel != null && sel.video && selT != mi && !sel.locked) return sel
        val ex = tracks.filterIndexed { i, t -> t.video && i != mi && !t.locked }.firstOrNull()
        if (ex != null) return ex
        val nt = Track(true)
        val fa = tracks.indexOfFirst { !it.video }
        tracks.add(if (fa < 0) tracks.size else fa, nt)
        return nt
    }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) importFiles(uris, 0) { files ->
            val t = tracks.firstOrNull { it.video }
            if (t != null && !t.locked) {
                commit()
                files.forEach { (f, _) ->
                    val d = mediaDuration(f.path)
                    if (d > 0) { val m = Media(f.path, d, 0); prepare(act, m); t.clips.add(Clip(m, 0f, d.toFloat())) }
                }
            }
        }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) importFiles(listOf(u), 1) { files ->
            val t = tracks.getOrNull(selT)?.takeIf { !it.video && !it.locked }
                ?: tracks.firstOrNull { !it.video && !it.locked } ?: Track(false).also { tracks.add(it) }
            commit()
            files.forEach { (f, _) ->
                val d = mediaDuration(f.path)
                if (d > 0) { val m = Media(f.path, d, 1); prepare(act, m); t.clips.add(Clip(m, 0f, d.toFloat())) }
            }
        }
    }
    val pickLayer = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) importFiles(uris, null) { files ->
            commit()
            val t = layerTrack()
            files.forEach { (f, k) ->
                if (k == 2) { val m = Media(f.path, 3_600_000L, 2); prepare(act, m); t.clips.add(Clip(m, 0f, 5000f)) }
                else if (k == 0) {
                    val d = mediaDuration(f.path)
                    if (d > 0) { val m = Media(f.path, d, 0); prepare(act, m); t.clips.add(Clip(m, 0f, d.toFloat())) }
                }
            }
        }
    }

    val tick by remember { derivedStateOf { (scroll.value / dens / ppd * 10f).toInt() } }
    LaunchedEffect(Unit) { while (true) { delay(5000); ProjectStore.save(act, project) } }
    LaunchedEffect(status) { if (status.isNotEmpty() && screen != 2) { delay(2500); status = "" } }
    LaunchedEffect(playing) {
        while (playing) {
            delay(33)
            if (scroll.value / dens / ppd * 1000f >= totalMs()) playing = false else scroll.scrollBy(ppd * dens * 0.033f)
        }
    }
    LaunchedEffect(scroll.isScrollInProgress) {
        if (!scroll.isScrollInProgress && !playing) {
            val cur = scroll.value / dens
            var best = -1f
            tracks.forEach { t -> var s = 0f; t.clips.forEach { c -> s += c.lenMs; val x = s / 1000f * ppd; if (best < 0f || abs(x - cur) < abs(best - cur)) best = x } }
            if (best >= 0f && abs(best - cur) < 8f) scroll.animateScrollTo((best * dens).toInt())
        }
    }
    BackHandler {
        when {
            screen != 1 -> screen = 1
            tool != null -> tool = null
            selC >= 0 -> selC = -1
            else -> { playing = false; ProjectStore.save(act, project); onBack() }
        }
    }

    val Player: @Composable (Modifier) -> Unit = { mod ->
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { useController = false; player = exo } }, modifier = mod, update = { _ ->
            val mt = tracks.firstOrNull { it.video }
            val hit = mt?.let { t -> findAt(t, tick * 100f)?.let { (i, o) -> t.clips[i] to o } }
            if (hit == null || hit.first.m.kind != 0) {
                if (last[0] != null) { exo.pause(); exo.clearMediaItems(); last[0] = null }
            } else {
                val (c, o) = hit
                val key = "${c.m.path}|${c.start}|${c.end}"
                val changed = last[0] != key
                if (changed) {
                    last[0] = key
                    exo.setMediaItem(MediaItem.Builder().setUri(c.m.uri).setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.start.toLong()).setEndPositionMs(c.end.toLong()).build()).build())
                    exo.prepare()
                }
                val sig = effSig(c)
                if (last[1] != sig) { last[1] = sig; exo.setVideoEffects(clipEffects(c)) }
                exo.setPlaybackSpeed(c.speed)
                exo.volume = c.volume.coerceIn(0f, 1f)
                if (changed || !playing) exo.seekTo((o * c.speed).toLong())
                exo.playWhenReady = playing
            }
        })
    }

    if (screen == 2) {
        Column(Modifier.fillMaxSize().background(BG).systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { screen = 1 }) { Text("← Back") }
                Text("Export", color = Color.White, fontSize = 20.sp)
            }
            OutlinedTextField(value = fileName, onValueChange = { fileName = it }, label = { Text("File name") }, singleLine = true)
            Text("Frame", color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RATIOS.forEach { (l, r, _) ->
                    FilterChip(selected = project.ratioLabel == l, onClick = { project.ratio = r; project.ratioLabel = l }, label = { Text(l) })
                }
            }
            Text("Quality", color = Color.White)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(720 to "720p", 1080 to "1080p", 2160 to "4K").forEach { (q, l) ->
                    FilterChip(selected = quality == q, onClick = { quality = q }, label = { Text(l) })
                }
            }
            Text("Output: ${outSize(quality, project.ratio).let { "${it.first} × ${it.second}" }}", color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = crop, onCheckedChange = { crop = it })
                Text("Fill frame (crop edges)", color = Color.White, modifier = Modifier.padding(start = 8.dp))
            }
            Button(onClick = {
                status = "Exporting..."
                exportProject(act, project, quality, crop, fileName) { status = it }
            }, colors = ButtonDefaults.buttonColors(containerColor = RED, contentColor = Color.White)) { Text("Export video") }
            Text(status, color = Color.White)
        }
    } else if (screen == 3) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Player(Modifier.fillMaxSize())
            TextButton(onClick = { screen = 1 }, modifier = Modifier.align(Alignment.TopStart).systemBarsPadding()) { Text("✕ Close") }
            TextButton(onClick = { playing = !playing }, modifier = Modifier.align(Alignment.BottomCenter).systemBarsPadding()) {
                Text(if (playing) "⏸ Pause" else "▶ Play")
            }
        }
    } else {
        Row(Modifier.fillMaxSize().background(BG).systemBarsPadding().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.weight(1.1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Column(Modifier.width(44.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SideBtn("⬆", "Export", RED) {
                            if (tracks.any { it.video && it.clips.isNotEmpty() }) { playing = false; status = ""; screen = 2 }
                        }
                        SideBtn("↶", "Undo", alpha = if (undo.isEmpty()) 0.4f else 1f) { doUndo() }
                        SideBtn("↷", "Redo", alpha = if (redo.isEmpty()) 0.4f else 1f) { doRedo() }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        BoxWithConstraints(Modifier.fillMaxHeight().aspectRatio(project.ratio).background(Color.Black)) {
                            val wPx = constraints.maxWidth.toFloat()
                            val hPx = constraints.maxHeight.toFloat()
                            Player(Modifier.fillMaxSize())
                            val ms = tick * 100f
                            tracks.forEachIndexed { ti, lt ->
                                if (lt.video && ti != mainIdx()) {
                                    val h = findAt(lt, ms)
                                    if (h != null && lt.clips[h.first].m.kind != 3) LayerBox(lt.clips[h.first], wPx, hPx, dens, lt.clips[h.first] === selClip())
                                }
                            }
                            if (project.title.isNotEmpty()) Text(project.title, color = Color.White, fontSize = 14.sp,
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp).background(Color(0x99000000)).padding(4.dp))
                            if (status.isNotEmpty()) Text(status, color = Color.White, fontSize = 11.sp,
                                modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(4.dp))
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().weight(1f).background(PANEL, RoundedCornerShape(8.dp))) {
                    val sc = selClip()
                    if (tool != null && sc != null) ToolPanel(sc, tool!!) { tool = null } else {
                        Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(fmtT(tick * 100), color = Color.White, fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { zoom(0.75f) }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("－", fontSize = 14.sp) }
                            TextButton(onClick = { zoom(1.33f) }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("＋", fontSize = 14.sp) }
                            TextButton(onClick = { split() }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("✂ Split", fontSize = 12.sp) }
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            Row {
                                Column(Modifier.width(84.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Spacer(Modifier.height(14.dp))
                                    tracks.forEachIndexed { ti, t ->
                                        val label = if (t.video) (if (ti == mainIdx()) "V1" else "L${tracks.take(ti + 1).count { it.video } - 1}")
                                        else "A${tracks.take(ti + 1).count { !it.video }}"
                                        Row(Modifier.fillMaxWidth().height(48.dp)
                                            .background(if (ti == selT) Color(0x33FFFFFF) else Color.Transparent)
                                            .clickable { selT = ti }, verticalAlignment = Alignment.CenterVertically) {
                                            Text(if (t.locked) "🔒" else "🔓", Modifier.clickable { t.locked = !t.locked }.padding(4.dp), fontSize = 14.sp)
                                            Text(if (t.muted) "🔇" else "🔊", Modifier.clickable { t.muted = !t.muted }.padding(4.dp), fontSize = 14.sp)
                                            Text(label, color = Color.White, fontSize = 11.sp)
                                        }
                                    }
                                    Box {
                                        Box(Modifier.padding(start = 6.dp, top = 2.dp).size(28.dp).background(RED, CircleShape).clickable { menu = true },
                                            contentAlignment = Alignment.Center) { Text("+", color = Color.White, fontSize = 18.sp) }
                                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                            DropdownMenuItem(text = { Text("Add layer track") }, onClick = {
                                                menu = false; commit()
                                                val fa = tracks.indexOfFirst { !it.video }
                                                val at = if (fa < 0) tracks.size else fa
                                                tracks.add(at, Track(true)); selT = at; selC = -1
                                            })
                                            DropdownMenuItem(text = { Text("Add audio track") }, onClick = {
                                                menu = false; commit(); tracks.add(Track(false)); selT = tracks.lastIndex; selC = -1
                                            })
                                        }
                                    }
                                }
                                Box(Modifier.weight(1f)) {
                                    Column(Modifier.horizontalScroll(scroll), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(Modifier.height(14.dp)) {
                                            Spacer(Modifier.width(PH.dp))
                                            val step = if (ppd >= 12f) 5 else 10
                                            repeat((totalMs() / 1000f / step).toInt() + 8) { i ->
                                                Box(Modifier.width((step * ppd).dp)) {
                                                    Text("%d:%02d".format(i * step / 60, i * step % 60), color = Color(0xFF9AA0A6), fontSize = 9.sp)
                                                }
                                            }
                                        }
                                        tracks.forEachIndexed { ti, t ->
                                            Row(Modifier.height(48.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Spacer(Modifier.width(PH.dp))
                                                t.clips.forEachIndexed { ci, c ->
                                                    key(c) {
                                                        val gap = c.m.kind == 3
                                                        val selected = ti == selT && ci == selC
                                                        val canMove = selected && !t.locked && ti != mainIdx()
                                                        Box(Modifier.width(max(c.lenMs / 1000f * ppd, 20f).dp).fillMaxHeight()
                                                            .alpha(if (t.locked) 0.6f else 1f)
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .background(when (c.m.kind) { 1 -> Color(0xFF1F6F4D); 3 -> Color(0x22FFFFFF); else -> Color(0xFF3A3F47) })
                                                            .then(if (gap) Modifier else Modifier.clickable { selT = ti; selC = ci })
                                                            .then(if (selected) Modifier.border(2.dp, ORANGE, RoundedCornerShape(4.dp)) else Modifier)
                                                            .then(if (canMove) Modifier.pointerInput(c) {
                                                                detectHorizontalDragGestures(onDragStart = { commit() }) { _, dx -> nudge(t, c, dx / dens / ppd * 1000f) }
                                                            } else Modifier)) {
                                                            when (c.m.kind) {
                                                                0 -> Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset(x = (-c.start / 1000f * ppd / c.speed).dp)) {
                                                                    c.m.thumbs.forEach { Image(it, null, Modifier.width((6.5f * ppd / c.speed).dp).fillMaxHeight(), contentScale = ContentScale.Crop) }
                                                                }
                                                                2 -> c.m.thumbs.firstOrNull()?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                                                1 -> Canvas(Modifier.fillMaxSize()) {
                                                                    val w = c.m.wave
                                                                    if (w.isNotEmpty()) {
                                                                        val a = (c.start / c.m.durMs * w.size).toInt().coerceIn(0, w.size - 1)
                                                                        val e = (c.end / c.m.durMs * w.size).toInt().coerceIn(a + 1, w.size)
                                                                        val bw = size.width / (e - a)
                                                                        for (i in a until e) {
                                                                            val bh = max(w[i], 0.04f) * size.height
                                                                            drawRect(Color.White, Offset((i - a) * bw, (size.height - bh) / 2), Size(bw * 0.7f, bh))
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                            if (c.speed != 1f && !gap) Text("${c.speed}x", color = Color.White, fontSize = 9.sp,
                                                                modifier = Modifier.align(Alignment.BottomEnd).background(Color(0x99000000)).padding(2.dp))
                                                            if (selected && !t.locked && !gap) {
                                                                if (c.m.kind != 2) Box(Modifier.align(Alignment.CenterStart).width(12.dp).fillMaxHeight()
                                                                    .background(ORANGE, RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
                                                                    .pointerInput(c) {
                                                                        detectHorizontalDragGestures(onDragStart = { commit() }) { _, dx ->
                                                                            c.start = (c.start + dx / dens / ppd * 1000f * c.speed).coerceIn(0f, c.end - 300f)
                                                                        }
                                                                    })
                                                                Box(Modifier.align(Alignment.CenterEnd).width(12.dp).fillMaxHeight()
                                                                    .background(ORANGE, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                                                                    .pointerInput(c) {
                                                                        detectHorizontalDragGestures(onDragStart = { commit() }) { _, dx ->
                                                                            val upper = if (c.m.kind == 2) 3_600_000f else c.m.durMs.toFloat()
                                                                            c.end = (c.end + dx / dens / ppd * 1000f * c.speed).coerceIn(c.start + 300f, upper)
                                                                        }
                                                                    })
                                                            }
                                                        }
                                                    }
                                                }
                                                Spacer(Modifier.width(400.dp))
                                            }
                                        }
                                    }
                                    Box(Modifier.matchParentSize()) {
                                        Box(Modifier.offset(x = PH.dp).width(2.dp).fillMaxHeight().background(BLUE))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.width(44.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val sc = selClip()
                if (sc != null) {
                    SideBtn("‹", "Back") { selC = -1; tool = null }
                    val st = tracks[selT]
                    toolsFor(sc, selT == mainIdx(), st.video).forEach { tl ->
                        SideBtn(tl.sym, tl.label, if (tool == tl.id) ORANGE else PANEL) { runTool(tl.id) }
                    }
                } else {
                    SideBtn("🎞️", "Media") { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
                    SideBtn("🎵", "Audio") { pickAudio.launch("audio/*") }
                    SideBtn("T", "Text") { showText = true }
                    SideBtn("🗂", "Layer") { pickLayer.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
                    SideBtn("⛶", "Full") { playing = false; screen = 3 }
                    SideBtn(if (playing) "⏸" else "▶", if (playing) "Pause" else "Play", RED) { playing = !playing }
                }
            }
        }
        if (showText) AlertDialog(
            onDismissRequest = { showText = false },
            confirmButton = { TextButton(onClick = { showText = false }) { Text("OK") } },
            title = { Text("Title text") },
            text = { OutlinedTextField(value = project.title, onValueChange = { project.title = it }, singleLine = true) }
        )
        if (showTrim) AlertDialog(
            onDismissRequest = { showTrim = false },
            confirmButton = { TextButton(onClick = { trimSide(false); showTrim = false }) { Text("Trim right of playhead") } },
            dismissButton = { TextButton(onClick = { trimSide(true); showTrim = false }) { Text("Trim left of playhead") } },
            title = { Text("Trim") },
            text = { Text("Move the blue playhead onto the clip, then choose which side to remove.") }
        )
        if (busy != null) AlertDialog(
            onDismissRequest = {}, confirmButton = {},
            title = { Text(busy!!) }, text = { LinearProgressIndicator() }
        )
    }
}
