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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.*
import androidx.media3.transformer.Composition
import com.google.common.collect.ImmutableList
import java.io.File
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

class Clip(val uri: Uri, val durMs: Long) {
    var start by mutableStateOf(0f)
    var end by mutableStateOf(durMs.toFloat())
    val thumbs = mutableStateListOf<ImageBitmap>()
}

private val PANEL = Color(0xFF2A2D33)
private val BG = Color(0xFF14161A)

@Composable
private fun Tool(label: String, color: Color = PANEL, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).background(color, RoundedCornerShape(6.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) { Text(label, color = Color.White, fontSize = 12.sp) }
}

@Composable
private fun Item(sym: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(sym, fontSize = 18.sp, color = Color.White)
        Text(label, fontSize = 10.sp, color = Color.White)
    }
}

private fun outSize(q: Int, r: Float): Pair<Int, Int> =
    if (r >= 1f) (q * r).toInt() / 2 * 2 to q else q to (q / r).toInt() / 2 * 2

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

    private fun loadThumbs(c: Clip) {
        Thread {
            try {
                val r = MediaMetadataRetriever()
                r.setDataSource(this, c.uri)
                val n = minOf(40, ((c.durMs + 6499) / 6500).toInt())
                for (i in 0 until n) {
                    val f = r.getFrameAtTime(i * 6500L * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    val bm = if (f != null) Bitmap.createScaledBitmap(f, 156, 88, true)
                    else Bitmap.createBitmap(156, 88, Bitmap.Config.ARGB_8888)
                    runOnUiThread { c.thumbs.add(bm.asImageBitmap()) }
                }
                r.release()
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun loadWave(u: Uri, ms: Long, set: (List<Float>) -> Unit) {
        Thread {
            try {
                val n = ((ms / 1000f) * 4f).toInt().coerceIn(20, 1500)
                val p = peaks(u, ms * 1000, n)
                runOnUiThread { set(p) }
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
        val clips = remember { mutableStateListOf<Clip>() }
        var screen by remember { mutableStateOf(0) }
        var ratio by remember { mutableStateOf(9f / 16f) }
        var ratioLabel by remember { mutableStateOf("9:16") }
        var quality by remember { mutableStateOf(1080) }
        var crop by remember { mutableStateOf(false) }
        var fileName by remember { mutableStateOf("my_video") }
        var sel by remember { mutableStateOf(-1) }
        var status by remember { mutableStateOf("") }
        var title by remember { mutableStateOf("") }
        var showTitle by remember { mutableStateOf(false) }
        var image by remember { mutableStateOf<Bitmap?>(null) }
        var music by remember { mutableStateOf<Pair<Uri, Long>?>(null) }
        var wave by remember { mutableStateOf<List<Float>>(emptyList()) }
        var preview by remember { mutableStateOf<Uri?>(null) }
        var vv by remember { mutableStateOf<VideoView?>(null) }
        val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            uris.forEach { u ->
                val d = dur(u)
                if (d > 0) {
                    val c = Clip(u, d); clips.add(c); loadThumbs(c)
                    if (sel < 0) { sel = 0; preview = u }
                }
            }
        }
        val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
            if (u != null) { val d = dur(u); music = u to d; wave = emptyList(); loadWave(u, d) { wave = it } }
        }
        val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
            if (u != null) image = contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) }
        }
        val totalMs = clips.sumOf { (it.end - it.start).toLong() }

        if (screen == 0) {
            Column(Modifier.fillMaxSize().background(BG).systemBarsPadding().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Choose your frame", color = Color.White, fontSize = 20.sp)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf(Triple("9:16", 9f / 16f, "Shorts / Reels"), Triple("16:9", 16f / 9f, "YouTube"),
                        Triple("1:1", 1f, "Square"), Triple("4:5", 0.8f, "Instagram")).forEach { (l, r, n) ->
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
                Text("Quality", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(720 to "720p", 1080 to "1080p", 2160 to "4K").forEach { (q, l) ->
                        FilterChip(selected = quality == q, onClick = { quality = q }, label = { Text(l) })
                    }
                }
                Text("Frame $ratioLabel  •  ${outSize(quality, ratio).let { "${it.first} × ${it.second}" }}", color = Color.White)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = crop, onCheckedChange = { crop = it })
                    Text("Fill frame (crop edges)", color = Color.White, modifier = Modifier.padding(start = 8.dp))
                }
                Button(onClick = {
                    status = "Exporting..."
                    export(clips.toList(), quality, ratio, crop, title.trim(), image, music, fileName) { status = it }
                }) { Text("Export video") }
                Text(status, color = Color.White)
            }
        } else {
            Row(Modifier.fillMaxSize().background(BG).systemBarsPadding().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.width(46.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Tool("Export", Color(0xFFE5483F)) { if (clips.isNotEmpty()) { status = ""; screen = 2 } }
                    Tool(ratioLabel) { screen = 0 }
                    Tool("Del") {
                        if (sel in clips.indices) { clips.removeAt(sel); sel = if (clips.isEmpty()) -1 else minOf(sel, clips.lastIndex) }
                    }
                    Tool("◀") { if (sel > 0) { clips.add(sel - 1, clips.removeAt(sel)); sel-- } }
                    Tool("▶") { if (sel in 0 until clips.lastIndex) { clips.add(sel + 1, clips.removeAt(sel)); sel++ } }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxHeight().aspectRatio(ratio).background(Color.Black)) {
                                AndroidView(
                                    factory = { ctx -> VideoView(ctx).also { vv = it } },
                                    update = { v -> preview?.let { if (v.tag != it) { v.tag = it; v.setVideoURI(it); v.start() } } },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        Box(Modifier.width(150.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(140.dp).background(PANEL, CircleShape)) {
                                Item("🎞️", "Media", Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) {
                                    pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                                }
                                Item("🖼️", "Layer", Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) {
                                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }
                                Item("🎵", "Audio", Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) { pickAudio.launch("audio/*") }
                                Item("T", "Text", Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) { showTitle = true }
                                Box(Modifier.align(Alignment.Center).size(44.dp).background(Color(0xFFE5483F), CircleShape)
                                    .clickable { vv?.let { if (it.isPlaying) it.pause() else it.start() } },
                                    contentAlignment = Alignment.Center) { Text("▶", color = Color.White, fontSize = 18.sp) }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (sel in clips.indices) {
                            val c = clips[sel]
                            Text("V${sel + 1}", color = Color.White, fontSize = 12.sp)
                            RangeSlider(value = c.start..c.end, onValueChange = { c.start = it.start; c.end = it.endInclusive },
                                valueRange = 0f..c.durMs.toFloat(), modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                            Text("${totalMs / 1000}s total", color = Color.White, fontSize = 12.sp)
                        } else Text("Tap Media to add video clips", color = Color.White, fontSize = 12.sp)
                    }
                    Column(Modifier.fillMaxWidth().height(130.dp).background(PANEL, RoundedCornerShape(6.dp))
                        .horizontalScroll(rememberScrollState()).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.height(44.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            clips.forEachIndexed { i, c ->
                                Box(Modifier.width(max((c.end - c.start) / 1000f * 12f, 28f).dp).fillMaxHeight()
                                    .clip(RoundedCornerShape(4.dp)).background(Color(0xFF3A3F47))
                                    .border(2.dp, if (i == sel) Color.White else Color.Transparent, RoundedCornerShape(4.dp))
                                    .clickable { sel = i; preview = c.uri }) {
                                    Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).offset(x = (-c.start / 1000f * 12f).dp)) {
                                        c.thumbs.forEach { Image(it, null, Modifier.width(78.dp).fillMaxHeight(), contentScale = ContentScale.Crop) }
                                    }
                                    Text("V${i + 1}", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(3.dp))
                                }
                            }
                        }
                        Row(Modifier.height(28.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (image != null) Box(Modifier.width(70.dp).fillMaxHeight().background(Color(0xFFE5883C), RoundedCornerShape(4.dp))
                                .clickable { image = null }) { Text("Image ✕", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(3.dp)) }
                            if (title.isNotEmpty()) Box(Modifier.width(90.dp).fillMaxHeight().background(Color(0xFFE5B72E), RoundedCornerShape(4.dp))
                                .clickable { title = "" }) { Text("T $title", color = Color.Black, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(3.dp)) }
                        }
                        Row(Modifier.height(36.dp)) {
                            music?.let { m ->
                                Box(Modifier.width(max(minOf(m.second, totalMs) / 1000f * 12f, 40f).dp).fillMaxHeight()
                                    .background(Color(0xFF1F6F4D), RoundedCornerShape(4.dp)).clickable { music = null; wave = emptyList() }) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        if (wave.isNotEmpty()) {
                                            val shown = (wave.size * minOf(1f, totalMs.toFloat() / m.second)).toInt().coerceIn(1, wave.size)
                                            val bw = size.width / shown
                                            for (i in 0 until shown) {
                                                val bh = max(wave[i], 0.04f) * size.height
                                                drawRect(Color.White, Offset(i * bw, (size.height - bh) / 2), Size(bw * 0.7f, bh))
                                            }
                                        }
                                    }
                                    Text("Music ✕", color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(3.dp))
                                }
                            }
                        }
                    }
                }
            }
            if (showTitle) AlertDialog(
                onDismissRequest = { showTitle = false },
                confirmButton = { TextButton(onClick = { showTitle = false }) { Text("OK") } },
                title = { Text("Title text") },
                text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) }
            )
        }
    }

    private fun export(clips: List<Clip>, q: Int, ratio: Float, crop: Boolean, title: String, img: Bitmap?,
                       music: Pair<Uri, Long>?, name: String, say: (String) -> Unit) {
        val (ow, oh) = outSize(q, ratio)
        val m = minOf(ow, oh)
        val items = clips.mapIndexed { idx, c ->
            val mi = MediaItem.Builder().setUri(c.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.start.toLong()).setEndPositionMs(c.end.toLong()).build()
            ).build()
            val layout = if (crop) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
            val fx = mutableListOf<Effect>(Presentation.createForWidthAndHeight(ow, oh, layout))
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
            EditedMediaItem.Builder(mi).setEffects(Effects(listOf(), fx)).build()
        }
        val videoSeq = EditedMediaItemSequence(items)
        val comp = if (music == null) Composition.Builder(videoSeq).build() else {
            val total = clips.sumOf { (it.end - it.start).toLong() }
            val am = MediaItem.Builder().setUri(music.first).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(0).setEndPositionMs(minOf(music.second, total)).build()
            ).build()
            val audioSeq = EditedMediaItemSequence(listOf(EditedMediaItem.Builder(am).setRemoveVideo(true).build()))
            Composition.Builder(videoSeq, audioSeq).build()
        }
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
