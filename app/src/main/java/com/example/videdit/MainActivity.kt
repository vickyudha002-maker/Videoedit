package com.example.videdit

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlin.math.max

class Clip(val uri: Uri, val durMs: Long) {
    var start by mutableStateOf(0f)
    var end by mutableStateOf(durMs.toFloat())
}

private val PANEL = Color(0xFF2A2D33)

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
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Editor() } }
    }

    private fun dur(u: Uri): Long {
        val r = MediaMetadataRetriever()
        r.setDataSource(this, u)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        r.release()
        return d
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Editor() {
        val clips = remember { mutableStateListOf<Clip>() }
        var sel by remember { mutableStateOf(-1) }
        var height by remember { mutableStateOf(1080) }
        var status by remember { mutableStateOf("") }
        var title by remember { mutableStateOf("") }
        var showTitle by remember { mutableStateOf(false) }
        var image by remember { mutableStateOf<Bitmap?>(null) }
        var music by remember { mutableStateOf<Pair<Uri, Long>?>(null) }
        var preview by remember { mutableStateOf<Uri?>(null) }
        var vv by remember { mutableStateOf<VideoView?>(null) }
        val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            uris.forEach { u ->
                val d = dur(u)
                if (d > 0) { clips.add(Clip(u, d)); if (sel < 0) { sel = 0; preview = u } }
            }
        }
        val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
            if (u != null) music = u to dur(u)
        }
        val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
            if (u != null) image = contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) }
        }
        val totalMs = clips.sumOf { (it.end - it.start).toLong() }
        Row(Modifier.fillMaxSize().background(Color(0xFF14161A)).systemBarsPadding().padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.width(46.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Tool("Export", Color(0xFFE5483F)) {
                    if (clips.isNotEmpty()) { status = "Exporting..."; export(clips.toList(), height, title.trim(), image, music) { status = it } }
                }
                Tool(if (height == 2160) "4K" else "${height}p") { height = when (height) { 720 -> 1080; 1080 -> 2160; else -> 720 } }
                Tool("Del") {
                    if (sel in clips.indices) { clips.removeAt(sel); sel = if (clips.isEmpty()) -1 else minOf(sel, clips.lastIndex) }
                }
                Tool("◀") { if (sel > 0) { clips.add(sel - 1, clips.removeAt(sel)); sel-- } }
                Tool("▶") { if (sel in 0 until clips.lastIndex) { clips.add(sel + 1, clips.removeAt(sel)); sel++ } }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black), contentAlignment = Alignment.Center) {
                        AndroidView(
                            factory = { ctx -> VideoView(ctx).also { vv = it } },
                            update = { v -> preview?.let { if (v.tag != it) { v.tag = it; v.setVideoURI(it); v.start() } } },
                            modifier = Modifier.fillMaxSize()
                        )
                        if (status.isNotEmpty()) Text(status, color = Color.White, fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.BottomStart).background(Color(0x88000000)).padding(4.dp))
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
                Column(Modifier.fillMaxWidth().height(116.dp).background(PANEL, RoundedCornerShape(6.dp))
                    .horizontalScroll(rememberScrollState()).padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.height(44.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        clips.forEachIndexed { i, c ->
                            Box(Modifier.width(max((c.end - c.start) / 1000f * 12f, 28f).dp).fillMaxHeight()
                                .background(if (i % 2 == 0) Color(0xFF6C3CE9) else Color(0xFF3C8CE9), RoundedCornerShape(4.dp))
                                .border(2.dp, if (i == sel) Color.White else Color.Transparent, RoundedCornerShape(4.dp))
                                .clickable { sel = i; preview = c.uri }) { Text("V${i + 1}", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(3.dp)) }
                        }
                    }
                    Row(Modifier.height(28.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (image != null) Box(Modifier.width(70.dp).fillMaxHeight().background(Color(0xFFE5883C), RoundedCornerShape(4.dp))
                            .clickable { image = null }) { Text("Image ✕", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(3.dp)) }
                        if (title.isNotEmpty()) Box(Modifier.width(90.dp).fillMaxHeight().background(Color(0xFFE5B72E), RoundedCornerShape(4.dp))
                            .clickable { title = "" }) { Text("T $title", color = Color.Black, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(3.dp)) }
                    }
                    Row(Modifier.height(28.dp)) {
                        music?.let { m ->
                            Box(Modifier.width(max(minOf(m.second, totalMs) / 1000f * 12f, 40f).dp).fillMaxHeight()
                                .background(Color(0xFF2E9E6B), RoundedCornerShape(4.dp)).clickable { music = null }) {
                                Text("Music ✕", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(3.dp))
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

    private fun export(clips: List<Clip>, h: Int, title: String, img: Bitmap?, music: Pair<Uri, Long>?, say: (String) -> Unit) {
        val items = clips.mapIndexed { idx, c ->
            val mi = MediaItem.Builder().setUri(c.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.start.toLong()).setEndPositionMs(c.end.toLong()).build()
            ).build()
            val fx = mutableListOf<Effect>(Presentation.createForHeight(h))
            val ov = mutableListOf<TextureOverlay>()
            if (title.isNotEmpty() && idx == 0) {
                val s = SpannableString(title)
                s.setSpan(AbsoluteSizeSpan(h / 12), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                s.setSpan(ForegroundColorSpan(android.graphics.Color.WHITE), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                s.setSpan(BackgroundColorSpan(0x99000000.toInt()), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                ov.add(TextOverlay.createStaticTextOverlay(s, OverlaySettings.Builder()
                    .setBackgroundFrameAnchor(0f, 0.75f).setOverlayFrameAnchor(0f, 0f).build()))
            }
            if (img != null) {
                val th = h / 6
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
        val out = File(cacheDir, "out.mp4").also { it.delete() }
        Transformer.Builder(this).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                save(out); say("Done. Saved in Movies/VideoEditor")
            }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                say("Export failed: ${exportException.message}")
            }
        }).build().start(comp, out.path)
    }

    private fun save(f: File) {
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "edit_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoEditor")
        }
        val u = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)!!
        contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
    }
}
