package com.example.videdit

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.*
import androidx.media3.transformer.Composition
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.delay
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

class Media(val uri: Uri, val durMs: Long) {
    val thumbs = mutableStateListOf<ImageBitmap>()
    var wave by mutableStateOf<List<Float>>(emptyList())
}

class Clip(val m: Media, s: Float, e: Float) {
    var start by mutableStateOf(s)
    var end by mutableStateOf(e)
}

class Track(val video: Boolean) {
    val clips = mutableStateListOf<Clip>()
    var locked by mutableStateOf(false)
    var muted by mutableStateOf(false)
}

class TSnap(val video: Boolean, val locked: Boolean, val muted: Boolean, val clips: List<Triple<Media, Float, Float>>)

private const val PPD = 12f
private const val PH = 96
private val PANEL = Color(0xFF2A2D33)
private val BG = Color(0xFF14161A)
private val ORANGE = Color(0xFFFF9F1C)
private val BLUE = Color(0xFF3D8BFF)
private val RED = Color(0xFFE5483F)
private val RATIOS = listOf(Triple("9:16", 9f / 16f, "Shorts / Reels"), Triple("16:9", 16f / 9f, "YouTube"),
    Triple("1:1", 1f, "Square"), Triple("4:5", 0.8f, "Instagram"))

private fun findAt(t: Track, ms: Float): Pair<Int, Float>? {
    var s = 0f
    t.clips.forEachIndexed { i, c ->
        val l = c.end - c.start
        if (ms >= s && ms < s + l) return i to (ms - s)
        s += l
    }
    return null
}

private fun outSize(q: Int, r: Float): Pair<Int, Int> =
    if (r >= 1f) (q * r).toInt() / 2 * 2 to q else q to (q / r).toInt() / 2 * 2

@Composable
private fun SideBtn(sym: String, label: String, color: Color = PANEL, alpha: Float = 1f, onClick: () -> Unit) {
    Column(Modifier.size(44.dp).alpha(alpha).clip(RoundedCornerShape(10.dp)).background(color).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(sym, fontSize = 16.sp, color = Color.White)
        Text(label, fontSize = 8.sp, color = Color.White)
    }
}

@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val log = File(filesDir, "crash.txt")
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            log.writeText(android.util.Log.getStackTraceString(e))
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        if (log.exists()) {
            val t = android.widget.TextView(this).apply {
                text = log.readText(); setTextIsSelectable(true); setPadding(24, 60, 24, 24)
            }
            setContentView(android.widget.ScrollView(this).apply { addView(t) })
            log.delete()
            return
        }
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }

    private fun dur(u: Uri): Long {
        val r = MediaMetadataRetriever()
        r.setDataSource(this, u)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        r.release()
        return d
    }

    private fun loadThumbs(m: Media) {
        Thread {
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(this, m.uri)
                val n = minOf(40, ((m.durMs + 6499) / 6500).toInt())
                for (i in 0 until n) {
                    val f = r.getFrameAtTime(i * 6500L * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    val bm = if (f != null) Bitmap.createScaledBitmap(f, 156, 88, true)
                    else Bitmap.createBitmap(156, 88, Bitmap.Config.ARGB_8888)
                    runOnUiThread { m.thumbs.add(bm.asImageBitmap()) }
                }
                r.release()
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun loadWave(m: Media) {
        Thread {
            try {
                val n = ((m.durMs / 1000f) * 4f).toInt().coerceIn(20, 1500)
                val p = peaks(m.uri, m.durMs * 1000, n)
                runOnUiThread { m.wave = p }
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun peaks(u: Uri, durUs: Long, n: Int): List<Float> {
        val ex = MediaExtractor()
        ex.setDataSource(this, u, null)
        val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio") }
        ex.selectTrack(ti)
        val fmt = ex.getTrackFormat(ti)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        val pk = FloatArray(n)
        val info = MediaCodec.BufferInfo()
        var eos = false
        var done = false
        while (!done) {
            if (!eos) {
                val i = codec.dequeueInputBuffer(10000)
                if (i >= 0) {
                    val s = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                    if (s < 0) {
                        codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eos = true
                    } else {
                        codec.queueInputBuffer(i, 0, s, ex.sampleTime, 0); ex.advance()
                    }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 10000)
            if (o >= 0) {
                val sb = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                val bk = (info.presentationTimeUs * n / durUs).toInt().coerceIn(0, n - 1)
                var mx = 0
                while (sb.hasRemaining()) {
                    mx = max(mx, abs(sb.get().toInt()))
                    if (sb.remaining() > 8) sb.position(sb.position() + 8)
                }
                pk[bk] = max(pk[bk], mx / 32768f)
                codec.releaseOutputBuffer(o, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        val top = pk.max().coerceAtLeast(0.01f)
        return pk.map { it / top }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun App() {
        var screen by remember { mutableStateOf(0) }
        var ratio by remember { mutableStateOf(9f / 16f) }
        var ratioLabel by remember { mutableStateOf("9:16") }
        var quality by remember { mutableStateOf(1080) }
        var crop by remember { mutableStateOf(false) }
        var effect by remember { mutableStateOf(0) }
        var fileName by remember { mutableStateOf("my_video") }
        var selT by remember { mutableStateOf(-1) }
        var selC by remember { mutableStateOf(-1) }
        var status by remember { mutableStateOf("") }
        var title by remember { mutableStateOf("") }
        var image by remember { mutableStateOf<Bitmap?>(null) }
        var showText by remember { mutableStateOf(false) }
        var showFx by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }
        var playing by remember { mutableStateOf(false) }
        val tracks = remember { mutableStateListOf(Track(true), Track(false)) }
        val undo = remember { mutableStateListOf<List<TSnap>>() }
        val redo = remember { mutableStateListOf<List<TSnap>>() }
        val scroll = rememberScrollState()
        val dens = LocalDensity.current.density

        fun snap() = tracks.map { t -> TSnap(t.video, t.locked, t.muted, t.clips.map { Triple(it.m, it.start, it.end) }) }
        fun restore(s: List<TSnap>) {
            tracks.clear()
            s.forEach { x ->
                val t = Track(x.video); t.locked = x.locked; t.muted = x.muted
                x.clips.forEach { (m, a, b) -> t.clips.add(Clip(m, a, b)) }
                tracks.add(t)
            }
            selT = -1; selC = -1
        }
        fun commit() { undo.add(snap()); redo.clear() }
        fun doUndo() { if (undo.isNotEmpty()) { redo.add(snap()); restore(undo.removeAt(undo.lastIndex)) } }
        fun doRedo() { if (redo.isNotEmpty()) { undo.add(snap()); restore(redo.removeAt(redo.lastIndex)) } }
        fun split() {
            val ti = if (selT in tracks.indices) selT else tracks.indexOfFirst { it.video }
            val t = tracks.getOrNull(ti) ?: return
            if (t.locked) return
            val ms = scroll.value / dens / PPD * 1000f
            val (ci, off) = findAt(t, ms) ?: return
            val c = t.clips[ci]
            val cut = c.start + off
            if (off < 200f || cut > c.end - 200f) return
            commit()
            t.clips[ci] = Clip(c.m, c.start, cut)
            t.clips.add(ci + 1, Clip(c.m, cut, c.end))
            selT = ti; selC = ci + 1
        }
        fun delSel() {
            val t = tracks.getOrNull(selT) ?: return
            if (t.locked || selC !in t.clips.indices) return
            commit(); t.clips.removeAt(selC); selC = -1
        }

        val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            val t = tracks.getOrNull(selT)?.takeIf { it.video && !it.locked } ?: tracks.firstOrNull { it.video && !it.locked }
            if (t != null && uris.isNotEmpty()) {
                commit()
                uris.forEach { u ->
                    val d = dur(u)
                    if (d > 0) { val m = Media(u, d); loadThumbs(m); t.clips.add(Clip(m, 0f, d.toFloat())) }
                }
            }
        }
        val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
            val t = tracks.getOrNull(selT)?.takeIf { !it.video && !it.locked } ?: tracks.firstOrNull { !it.video && !it.locked }
            if (t != null && u != null) {
                val d = dur(u)
                if (d > 0) { commit(); val m = Media(u, d); loadWave(m); t.clips.add(Clip(m, 0f, d.toFloat())) }
            }
        }
        val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
            if (u != null) image = contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) }
        }

        val tick by remember { derivedStateOf { (scroll.value / dens / PPD * 10f).toInt() } }
        LaunchedEffect(playing) {
            while (playing) {
                delay(33)
                val longest = tracks.maxOfOrNull { t -> t.clips.sumOf { (it.end - it.start).toLong() } } ?: 0L
                if (scroll.value / dens / PPD * 1000f >= longest) playing = false else scroll.scrollBy(PPD * dens * 0.033f)
            }
        }

        val Player: @Composable (Modifier) -> Unit = { mod ->
            AndroidView(factory = { ctx -> VideoView(ctx) }, modifier = mod, update = { v ->
                val ms = tick * 100f
                val hit = tracks.filter { it.video }.firstNotNullOfOrNull { t -> findAt(t, ms)?.let { (i, o) -> t.clips[i] to o } }
                if (hit != null) {
                    val (c, o) = hit
                    if (v.tag != c.m.uri) { v.tag = c.m.uri; v.setVideoURI(c.m.uri); v.seekTo((c.start + o).toInt()) }
                    else if (!playing) v.seekTo((c.start + o).toInt())
                    if (playing && !v.isPlaying) v.start() else if (!playing && v.isPlaying) v.pause()
                }
            })
        }

        if (screen == 0) {
            Column(Modifier.fillMaxSize().background(BG).systemBarsPadding().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Choose your frame", color = Color.White, fontSize = 20.sp)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    RATIOS.forEach { (l, r, n) ->
                        Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { ratio = r; ratioLabel = l; screen = 1 }
                            .background(PANEL).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.height(70.dp).aspectRatio(r).background(Color.White, RoundedCornerShape(3.dp)))
                            Text(l, color = Color.White, fontSize = 16.sp)
                            Text(n, color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }
        } else if (screen == 2) {
            Column(Modifier.fillMaxSize().background(BG).systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { screen = 1 }) { Text("← Back") }
                    Text("Export", color = Color.White, fontSize = 20.sp)
                }
                OutlinedTextField(value = fileName, onValueChange = { fileName = it }, label = { Text("File name") }, singleLine = true)
                Text("Frame", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RATIOS.forEach { (l, r, _) -> FilterChip(selected = ratioLabel == l, onClick = { ratio = r; ratioLabel = l }, label = { Text(l) }) }
                }
                Text("Quality", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(720 to "720p", 1080 to "1080p", 2160 to "4K").forEach { (q, l) ->
                        FilterChip(selected = quality == q, onClick = { quality = q }, label = { Text(l) })
                    }
                }
                Text("Output: ${outSize(quality, ratio).let { "${it.first} × ${it.second}" }}", color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = crop, onCheckedChange = { crop = it })
                    Text("Fill frame (crop edges)", color = Color.White, modifier = Modifier.padding(start = 8.dp))
                }
                Button(onClick = {
                    status = "Exporting..."
                    export(snap(), quality, ratio, crop, effect, title.trim(), image, fileName) { status = it }
                }) { Text("Export video") }
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
                            SideBtn("⬆", "Export", RED) { if (tracks.any { it.video && it.clips.isNotEmpty() }) { status = ""; playing = false; screen = 2 } }
                            SideBtn("↶", "Undo", alpha = if (undo.isEmpty()) 0.4f else 1f) { doUndo() }
                            SideBtn("↷", "Redo", alpha = if (redo.isEmpty()) 0.4f else 1f) { doRedo() }
                        }
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxHeight().aspectRatio(ratio).background(Color.Black)) { Player(Modifier.fillMaxSize()) }
                        }
                    }
                    Column(Modifier.fillMaxWidth().weight(1f).background(PANEL, RoundedCornerShape(8.dp))) {
                        Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            val ms = tick * 100
                            Text("%d:%02d.%d".format(ms / 60000, ms / 1000 % 60, ms / 100 % 10), color = Color.White, fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { split() }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("✂ Split", fontSize = 12.sp) }
                            TextButton(onClick = { delSel() }, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("🗑 Delete", fontSize = 12.sp) }
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                            Row {
                                Column(Modifier.width(84.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    tracks.forEachIndexed { ti, t ->
                                        Row(Modifier.fillMaxWidth().height(if (t.video) 48.dp else 40.dp)
                                            .background(if (ti == selT) Color(0x33FFFFFF) else Color.Transparent)
                                            .clickable { selT = ti; selC = -1 }, verticalAlignment = Alignment.CenterVertically) {
                                            Text(if (t.locked) "🔒" else "🔓", Modifier.clickable { t.locked = !t.locked }.padding(4.dp), fontSize = 14.sp)
                                            Text(if (t.muted) "🔇" else "🔊", Modifier.clickable { t.muted = !t.muted }.padding(4.dp), fontSize = 14.sp)
                                            Text((if (t.video) "V" else "A") + tracks.take(ti + 1).count { it.video == t.video }, color = Color.White, fontSize = 11.sp)
                                        }
                                    }
                                    Box {
                                        Box(Modifier.padding(start = 6.dp, top = 2.dp).size(28.dp).background(RED, CircleShape).clickable { menu = true },
                                            contentAlignment = Alignment.Center) { Text("+", color = Color.White, fontSize = 18.sp) }
                                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                            DropdownMenuItem(text = { Text("Add video track") }, onClick = {
                                                menu = false; commit()
                                                val at = tracks.indexOfFirst { !it.video }.let { if (it < 0) tracks.size else it }
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
                                        tracks.forEachIndexed { ti, t ->
                                            Row(Modifier.height(if (t.video) 48.dp else 40.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Spacer(Modifier.width(PH.dp))
                                                t.clips.forEachIndexed { ci, c ->
                                                    val selected = ti == selT && ci == selC
                                                    Box(Modifier.width(max((c.end - c.start) / 1000f * PPD, 20f).dp).fillMaxHeight()
                                                        .alpha(if (t.locked) 0.6f else 1f)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(if (t.video) Color(0xFF3A3F47) else Color(0xFF1F6F4D))
                                                        .clickable { selT = ti; selC = ci }
                                                        .then(if (selected) Modifier.border(2.dp, ORANGE, RoundedCornerShape(4.dp)) else Modifier)) {
                                                        if (t.video) {
                                                            Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset(x = (-c.start / 1000f * PPD).dp)) {
                                                                c.m.thumbs.forEach { Image(it, null, Modifier.width(78.dp).fillMaxHeight(), contentScale = ContentScale.Crop) }
                                                            }
                                                        } else {
                                                            Canvas(Modifier.fillMaxSize()) {
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
                                                        if (selected && !t.locked) {
                                                            Box(Modifier.align(Alignment.CenterStart).width(14.dp).fillMaxHeight().background(ORANGE)
                                                                .pointerInput(c) {
                                                                    detectHorizontalDragGestures(onDragStart = { commit() }) { _, dx ->
                                                                        c.start = (c.start + dx / dens / PPD * 1000f).coerceIn(0f, c.end - 300f)
                                                                    }
                                                                })
                                                            Box(Modifier.align(Alignment.CenterEnd).width(14.dp).fillMaxHeight().background(ORANGE)
                                                                .pointerInput(c) {
                                                                    detectHorizontalDragGestures(onDragStart = { commit() }) { _, dx ->
                                                                        c.end = (c.end + dx / dens / PPD * 1000f).coerceIn(c.start + 300f, c.m.durMs.toFloat())
                                                                    }
                                                                })
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
                Column(Modifier.width(44.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    SideBtn("🎞️", "Media") { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
                    SideBtn("🎵", "Audio") { pickAudio.launch("audio/*") }
                    SideBtn("T", "Text") { showText = true }
                    SideBtn("✨", "Effects") { showFx = true }
                    SideBtn("⛶", "Full") { playing = false; screen = 3 }
                    SideBtn(if (playing) "⏸" else "▶", if (playing) "Pause" else "Play", RED) { playing = !playing }
                }
            }
            if (showText) AlertDialog(
                onDismissRequest = { showText = false },
                confirmButton = { TextButton(onClick = { showText = false }) { Text("OK") } },
                title = { Text("Title & image") },
                text = {
                    Column {
                        OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text("Title text") })
                        TextButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                            Text(if (image == null) "+ Add image overlay" else "Change image overlay")
                        }
                        if (image != null) TextButton(onClick = { image = null }) { Text("Remove image") }
                    }
                }
            )
            if (showFx) AlertDialog(
                onDismissRequest = { showFx = false },
                confirmButton = { TextButton(onClick = { showFx = false }) { Text("OK") } },
                title = { Text("Effect (applied at export)") },
                text = {
                    Column {
                        listOf("None", "Black & white", "Invert", "Brighter", "More contrast").forEachIndexed { i, l ->
                            FilterChip(selected = effect == i, onClick = { effect = i }, label = { Text(l) })
                        }
                    }
                }
            )
        }
    }

    private fun export(ts: List<TSnap>, q: Int, ratio: Float, crop: Boolean, effect: Int, title: String,
                       img: Bitmap?, name: String, say: (String) -> Unit) {
        val (ow, oh) = outSize(q, ratio)
        val m = minOf(ow, oh)
        val vids = ts.filter { it.video && it.clips.isNotEmpty() }
        if (vids.isEmpty()) { say("Add at least one video clip"); return }
        val layout = if (crop) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
        val seqs = mutableListOf<EditedMediaItemSequence>()
        vids.forEachIndexed { vi, t ->
            val items = t.clips.mapIndexed { idx, (md, a, b) ->
                val mi = MediaItem.Builder().setUri(md.uri).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(a.toLong()).setEndPositionMs(b.toLong()).build()
                ).build()
                val fx = mutableListOf<Effect>()
                when (effect) {
                    1 -> fx.add(RgbFilter.createGrayscaleFilter())
                    2 -> fx.add(RgbFilter.createInvertedFilter())
                    3 -> fx.add(Brightness(0.25f))
                    4 -> fx.add(Contrast(0.4f))
                }
                fx.add(Presentation.createForWidthAndHeight(ow, oh, layout))
                if (vi == 0) {
                    val ov = mutableListOf<TextureOverlay>()
                    if (title.isNotEmpty() && idx == 0) {
                        val s = SpannableString(title)
                        s.setSpan(AbsoluteSizeSpan(m / 12), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        s.setSpan(ForegroundColorSpan(android.graphics.Color.WHITE), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        s.setSpan(BackgroundColorSpan(0x99000000.toInt()), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        ov.add(TextOverlay.createStaticTextOverlay(s, OverlaySettings.Builder()
                            .setBackgroundFrameAnchor(0f, 0.75f).setOverlayFrameAnchor(0f, 0f).build()))
                    }
                    if (img != null) {
                        val th = m / 6
                        val bm = Bitmap.createScaledBitmap(img, max(1, img.width * th / img.height), th, true)
                        ov.add(BitmapOverlay.createStaticBitmapOverlay(bm, OverlaySettings.Builder()
                            .setBackgroundFrameAnchor(0.75f, -0.75f).setOverlayFrameAnchor(0f, 0f).build()))
                    }
                    if (ov.isNotEmpty()) fx.add(OverlayEffect(ImmutableList.copyOf(ov)))
                }
                EditedMediaItem.Builder(mi).setEffects(Effects(listOf(), fx)).setRemoveAudio(t.muted).build()
            }
            seqs.add(EditedMediaItemSequence(items))
        }
        ts.filter { !it.video && !it.muted && it.clips.isNotEmpty() }.forEach { t ->
            seqs.add(EditedMediaItemSequence(t.clips.map { (md, a, b) ->
                val mi = MediaItem.Builder().setUri(md.uri).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(a.toLong()).setEndPositionMs(b.toLong()).build()
                ).build()
                EditedMediaItem.Builder(mi).setRemoveVideo(true).build()
            }))
        }
        val comp = Composition.Builder(seqs[0], *seqs.drop(1).toTypedArray()).build()
        val safe = name.trim().ifEmpty { "video" }.replace(Regex("[^A-Za-z0-9 _-]"), "_")
        val out = File(cacheDir, "out.mp4").also { it.delete() }
        Transformer.Builder(this).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                save(out, safe); say("Done. Saved in Movies/VideoEditor as $safe.mp4")
            }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                say("Export failed: ${exportException.message}")
            }
        }).build().start(comp, out.path)
    }

    private fun save(f: File, name: String) {
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoEditor")
        }
        val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)!!
        contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
    }
}
