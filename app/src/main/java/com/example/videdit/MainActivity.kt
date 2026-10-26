package com.example.videdit

import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import java.io.File

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
                text = log.readText()
                setTextIsSelectable(true)
                setPadding(24, 120, 24, 24)
            }
            setContentView(android.widget.ScrollView(this).apply { addView(t) })
            log.delete()
            return
        }
        setContent { MaterialTheme { Editor() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Editor() {
        val clips = remember { mutableStateListOf<Clip>() }
        var height by remember { mutableStateOf(1080) }
        var status by remember { mutableStateOf("Add clips to begin") }
        val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            uris.forEach { u ->
                val r = MediaMetadataRetriever()
                r.setDataSource(this, u)
                val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                r.release()
                if (d > 0) clips.add(Clip(u, d))
            }
        }
        Column(Modifier.systemBarsPadding().padding(16.dp)) {
            Button(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) { Text("+ Add video clips") }
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(clips) { i, c ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Clip ${i + 1}: ${((c.end - c.start) / 1000).toInt()}s kept of ${c.durMs / 1000}s")
                            RangeSlider(value = c.start..c.end, onValueChange = { c.start = it.start; c.end = it.endInclusive }, valueRange = 0f..c.durMs.toFloat())
                            TextButton(onClick = { clips.remove(c) }) { Text("Remove") }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(720 to "720p", 1080 to "1080p", 2160 to "4K").forEach { (h, l) ->
                    FilterChip(selected = height == h, onClick = { height = h }, label = { Text(l) })
                }
            }
            Button(enabled = clips.isNotEmpty(), onClick = { status = "Exporting..."; export(clips.toList(), height) { status = it } }) { Text("Export") }
            Text(status)
        }
    }

    private fun export(clips: List<Clip>, h: Int, say: (String) -> Unit) {
        val items = clips.map { c ->
            val mi = MediaItem.Builder().setUri(c.uri).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(c.start.toLong()).setEndPositionMs(c.end.toLong()).build()
            ).build()
            EditedMediaItem.Builder(mi).setEffects(Effects(listOf(), listOf(Presentation.createForHeight(h)))).build()
        }
        val out = File(cacheDir, "out.mp4").also { it.delete() }
        Transformer.Builder(this).addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                save(out); say("Done. Saved in Movies/VideoEditor")
            }
            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                say("Export failed: ${exportException.message}")
            }
        }).build().start(Composition.Builder(EditedMediaItemSequence(items)).build(), out.path)
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
