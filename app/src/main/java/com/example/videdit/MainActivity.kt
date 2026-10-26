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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
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
                text = log.readText(); setTextIsSelectable(true); setPadding(24, 120, 24, 24)
            }
            setContentView(android.widget.ScrollView(this).apply { addView(t) })
            log.delete()
            return
        }
        setContent { MaterialTheme { Editor() } }
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
        var height by remember { mutableStateOf(1080) }
        var status by remember { mutableStateOf("Add clips to begin") }
        var title by remember { mutableStateOf("") }
        var image by remember { mutableStateOf<Bitmap?>(null) }
        var music by remember { mutableStateOf<Pair<Uri, Long>?>(null) }
        var preview by remember { mutableStateOf<Uri?>(null) }
        val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            uris.forEach { u ->
                val d = dur(u)
                if (d > 0) { clips.add(Clip(u, d)); if (preview == null) preview = u }
            }
        }
        val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
            if (u != null) music = u to dur(u)
        }
        val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
            if (u != null) image = contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) }
        }
        Column(Modifier.systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState())) {
            AndroidView(
                factory = { VideoView(it) },
                update = { v -> preview?.let { if (v.tag != it) { v.tag = it; v.setVideoURI(it); v.start() } } },
                modifier = Modifier.fillMaxWidth().height(200.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) { Text("+ Video") }
                Button(onClick = { pickAudio.launch("audio/*") }) { Text("+ Audio") }
                Button(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("+ Image") }
            }
            Text("Timeline (video clips merge in this order)")
            Row(Modifier.fillMaxWidth().height(36.dp)) {
                clips.forEachIndexed { i, c ->
                    Box(Modifier.weight(max(c.end - c.start, 1f)).fillMaxHeight()
                        .background(if (i % 2 == 0) Color(0xFF6C3CE9) else Color(0xFF3C8CE9))) { Text("V${i + 1}", color = Color.White) }
                }
            }
            Spacer(Modifier.height(4.dp))
            val total = clips.sumOf { (it.end - it.start).toLong() }.coerceAtLeast(1)
            music?.let { m ->
                Box(Modifier.fillMaxWidth((m.second.toFloat() / total).coerceIn(0.05f, 1f)).height(28.dp)
                    .background(Color(0xFF2E9E6B))) { Text("Music", color = Color.White) }
                TextButton(onClick = { music = null }) { Text("Remove music") }
            }
            if (image != null) TextButton(onClick = { image = null }) { Text("Remove image overlay") }
            clips.forEachIndexed { i, c ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Clip ${i + 1}: ${((c.end - c.start) / 1000).toInt()}s kept of ${c.durMs / 1000}s")
                        RangeSlider(value = c.start..c.end, onValueChange = { c.start = it.start; c.end = it.endInclusive }, valueRange = 0f..c.durMs.toFloat())
                        Row {
                            TextButton(onClick = { preview = c.uri }) { Text("Preview") }
                            if (i > 0) TextButton(onClick = { clips.add(i - 1, clips.removeAt(i)) }) { Text("Move up") }
                            TextButton(onClick = { clips.remove(c) }) { Text("Remove") }
                        }
                    }
                }
            }
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title text (optional)") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(720 to "720p", 1080 to "1080p", 2160 to "4K").forEach { (h, l) ->
                    FilterChip(selected = height == h, onClick = { height = h }, label = { Text(l) })
                }
            }
            Button(enabled = clips.isNotEmpty(), onClick = {
                status = "Exporting..."
                export(clips.toList(), height, title.trim(), image, music) { status = it }
            }) { Text("Export") }
            Text(status)
        }
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
