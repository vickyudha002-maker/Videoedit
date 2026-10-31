package com.example.videdit

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.*
import androidx.media3.transformer.Composition
import com.google.common.collect.ImmutableList
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Colour filters, adjustments and pan/zoom for one clip. Used by the preview and by export. */
@UnstableApi
fun clipEffects(c: Clip, export: Boolean = false): List<Effect> {
    val fx = mutableListOf<Effect>()
    var bright = c.bright + c.high * 0.15f
    var contrast = c.contrast
    var sat = c.sat * 100f + c.vib * 50f
    var red = 1f + c.temp * 0.2f
    var blue = 1f - c.temp * 0.2f
    when (c.filter) {
        1 -> fx.add(RgbFilter.createGrayscaleFilter())
        2 -> { sat += 35f; contrast += 0.2f }
        3 -> { red *= 1.12f; blue *= 0.88f }
        4 -> { red *= 0.88f; blue *= 1.12f }
        5 -> { contrast -= 0.3f; bright += 0.1f }
        6 -> fx.add(RgbFilter.createInvertedFilter())
    }
    if (bright != 0f) fx.add(Brightness(bright.coerceIn(-1f, 1f)))
    if (contrast != 0f) fx.add(Contrast(contrast.coerceIn(-1f, 1f)))
    if (sat != 0f) fx.add(HslAdjustment.Builder().adjustSaturation(sat.coerceIn(-100f, 100f)).build())
    if (red != 1f || blue != 1f) fx.add(RgbAdjustment.Builder().setRedScale(red.coerceIn(0f, 5f)).setBlueScale(blue.coerceIn(0f, 5f)).build())
    if (c.zoomA != 1f || c.zoomB != 1f) {
        val za = c.zoomA; val zb = c.zoomB
        val pax = c.panAx; val pay = c.panAy; val pbx = c.panBx; val pby = c.panBy
        val lenUs = (c.end - c.start) * 1000f / (if (export) c.speed else 1f)
        fx.add(MatrixTransformation { t ->
            val p = (t / lenUs).coerceIn(0f, 1f)
            val z = za + (zb - za) * p
            val px = pax + (pbx - pax) * p
            val py = pay + (pby - pay) * p
            Matrix().apply { postScale(z, z); postTranslate(-px * (z - 1f), py * (z - 1f)) }
        })
    }
    return fx
}

@UnstableApi
private fun audioFx(c: Clip): List<AudioProcessor> {
    val l = mutableListOf<AudioProcessor>()
    if (c.speed != 1f) l.add(SonicAudioProcessor().apply { setSpeed(c.speed); setPitch(1f) })
    if (c.volume != 1f || c.eq.any { abs(it) >= 0.1f }) l.add(EqProcessor(c.volume, c.eq.toFloatArray()))
    return l
}

private class Biquad(f0: Double, gainDb: Double, fs: Int) {
    private val b0: Double; private val b1: Double; private val b2: Double; private val a1: Double; private val a2: Double
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
    init {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * f0 / fs
        val alpha = sin(w0) / 2.0
        val a0 = 1 + alpha / a
        b0 = (1 + alpha * a) / a0; b1 = -2 * cos(w0) / a0; b2 = (1 - alpha * a) / a0
        a1 = -2 * cos(w0) / a0; a2 = (1 - alpha / a) / a0
    }
    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
}

/** Volume + 5-band equalizer on 16-bit PCM audio. */
@UnstableApi
private class EqProcessor(private val gain: Float, private val bands: FloatArray) : BaseAudioProcessor() {
    private val freqs = doubleArrayOf(60.0, 230.0, 910.0, 3600.0, 14000.0)
    private var filters: List<List<Biquad>> = emptyList()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        return inputAudioFormat
    }

    override fun onFlush() {
        val fmt = inputAudioFormat
        filters = (0 until fmt.channelCount).map {
            (0 until 5).filter { i -> abs(bands[i]) >= 0.1f && freqs[i] < fmt.sampleRate * 0.45 }
                .map { i -> Biquad(freqs[i], bands[i].toDouble(), fmt.sampleRate) }
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val out = replaceOutputBuffer(inputBuffer.remaining())
        val ch = inputAudioFormat.channelCount
        while (inputBuffer.hasRemaining()) {
            for (c in 0 until ch) {
                var x = inputBuffer.short.toDouble()
                for (f in filters[c]) x = f.process(x)
                x *= gain
                out.putShort(x.coerceIn(-32768.0, 32767.0).toInt().toShort())
            }
        }
        out.flip()
    }
}

private fun clipItem(c: Clip, uri: Uri = c.m.uri) = MediaItem.Builder().setUri(uri).setClippingConfiguration(
    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.start.toLong()).setEndPositionMs(c.end.toLong()).build()
).build()

private fun silenceUri(act: ComponentActivity, ms: Long): Uri {
    val f = File(act.cacheDir, "silence_$ms.wav")
    if (!f.exists()) {
        val rate = 44100
        val bytes = (ms * rate / 1000 * 2).toInt()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
        h.putShort(1.toShort()).putShort(1.toShort()).putInt(rate).putInt(rate * 2).putShort(2.toShort()).putShort(16.toShort())
        h.put("data".toByteArray()).putInt(bytes)
        f.outputStream().use { o ->
            o.write(h.array())
            val z = ByteArray(8192)
            var left = bytes
            while (left > 0) { val n = min(left, z.size); o.write(z, 0, n); left -= n }
        }
    }
    return Uri.fromFile(f)
}

private fun clearUri(act: ComponentActivity): Uri {
    val f = File(act.cacheDir, "clear.png")
    if (!f.exists()) {
        val b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    return Uri.fromFile(f)
}

private fun clipAtMs(t: Track, ms: Float): Clip? {
    var s = 0f
    for (c in t.clips) { if (ms >= s && ms < s + c.lenMs) return c; s += c.lenMs }
    return null
}

@UnstableApi
private fun videoFx(c: Clip, ow: Int, oh: Int, layout: Int): List<Effect> {
    val l = mutableListOf<Effect>()
    if (c.speed != 1f) l.add(SpeedChangeEffect(c.speed))
    l.addAll(clipEffects(c, true))
    l.add(Presentation.createForWidthAndHeight(ow, oh, layout))
    return l
}

@UnstableApi
fun exportProject(act: ComponentActivity, p: Project, q: Int, crop: Boolean, name: String, say: (String) -> Unit) {
    val (ow, oh) = outSize(q, p.ratio)
    val m = min(ow, oh)
    val tracks = p.tracks.toList()
    val mainIdx = tracks.indexOfFirst { it.video }
    val main = tracks.getOrNull(mainIdx)
    if (main == null || main.clips.none { it.m.kind == 0 }) { say("Add at least one video clip"); return }
    val fitMode = if (crop) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
    val fill = Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
    val seqs = mutableListOf<EditedMediaItemSequence>()

    // 1) main video track
    val mainItems = main.clips.filter { it.m.kind == 0 }.mapIndexed { idx, c ->
        val fx = videoFx(c, ow, oh, fitMode).toMutableList()
        if (p.title.isNotEmpty() && idx == 0) {
            val s = SpannableString(p.title)
            s.setSpan(AbsoluteSizeSpan(m / 12), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(ForegroundColorSpan(android.graphics.Color.WHITE), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(BackgroundColorSpan(0x99000000.toInt()), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val ov = listOf<TextureOverlay>(TextOverlay.createStaticTextOverlay(s, OverlaySettings.Builder()
                .setBackgroundFrameAnchor(0f, 0.75f).setOverlayFrameAnchor(0f, 0f).build()))
            fx.add(OverlayEffect(ImmutableList.copyOf(ov)))
        }
        EditedMediaItem.Builder(clipItem(c)).setEffects(Effects(audioFx(c), fx)).setRemoveAudio(main.muted)
            .apply { if (c.speed > 1f) setFrameRate(30) }.build()
    }
    seqs.add(EditedMediaItemSequence(mainItems))

    // 2) layer tracks (images and overlay videos)
    val layerTracks = ArrayList<Track>()
    tracks.forEachIndexed { i, t ->
        if (t.video && i != mainIdx && t.clips.any { it.m.kind != 3 }) {
            val items = t.clips.map { c ->
                when (c.m.kind) {
                    3 -> EditedMediaItem.Builder(MediaItem.fromUri(clearUri(act))).setDurationUs((c.lenMs * 1000).toLong()).setFrameRate(30).build()
                    2 -> EditedMediaItem.Builder(MediaItem.fromUri(c.m.uri)).setDurationUs(((c.end - c.start) * 1000).toLong()).setFrameRate(30)
                        .setEffects(Effects(listOf(), clipEffects(c, true) + Presentation.createForWidthAndHeight(ow, oh, fill))).build()
                    else -> EditedMediaItem.Builder(clipItem(c)).setEffects(Effects(audioFx(c), videoFx(c, ow, oh, fill)))
                        .setRemoveAudio(t.muted).apply { if (c.speed > 1f) setFrameRate(30) }.build()
                }
            }
            seqs.add(EditedMediaItemSequence(items))
            layerTracks.add(t)
        }
    }

    // 3) audio tracks
    tracks.forEach { t ->
        if (!t.video && !t.muted && t.clips.any { it.m.kind != 3 }) {
            val items = t.clips.map { c ->
                if (c.m.kind == 3) EditedMediaItem.Builder(MediaItem.fromUri(silenceUri(act, c.lenMs.toLong()))).setRemoveVideo(true).build()
                else EditedMediaItem.Builder(clipItem(c)).setEffects(Effects(audioFx(c), listOf())).setRemoveVideo(true).build()
            }
            seqs.add(EditedMediaItemSequence(items))
        }
    }

    val settings = object : VideoCompositorSettings {
        override fun getOutputSize(inputSizes: MutableList<Size>): Size = inputSizes[0]
        override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
            if (inputId <= 0 || inputId > layerTracks.size) return OverlaySettings.Builder().build()
            val c = clipAtMs(layerTracks[inputId - 1], presentationTimeUs / 1000f)
            if (c == null || c.m.kind == 3) return OverlaySettings.Builder().build()
            return OverlaySettings.Builder().setScale(c.ls, c.ls).setBackgroundFrameAnchor(c.lx, c.ly).setOverlayFrameAnchor(0f, 0f).build()
        }
    }
    val cb = Composition.Builder(seqs[0], *seqs.drop(1).toTypedArray()).experimentalSetForceAudioTrack(true)
    if (layerTracks.isNotEmpty()) cb.setVideoCompositorSettings(settings)
    val comp = cb.build()

    val safe = name.trim().ifEmpty { "video" }.replace(Regex("[^A-Za-z0-9 _-]"), "_")
    val out = File(act.cacheDir, "out.mp4").also { it.delete() }
    val handler = Handler(Looper.getMainLooper())
    val ph = ProgressHolder()
    var running = true
    lateinit var tf: Transformer
    tf = Transformer.Builder(act).addListener(object : Transformer.Listener {
        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
            running = false
            say("Saving...")
            Thread {
                val msg = try { saveToMovies(act, out, safe); "Done. Saved in Movies/VideoEditor as $safe.mp4" } catch (e: Exception) { "Save failed: ${e.message}" }
                act.runOnUiThread { say(msg) }
            }.start()
        }
        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
            running = false
            say("Export failed: ${exportException.errorCodeName} ${exportException.message}")
        }
    }).build()
    tf.start(comp, out.path)
    handler.post(object : Runnable {
        override fun run() {
            if (!running) return
            if (tf.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) say("Exporting... ${ph.progress}%")
            handler.postDelayed(this, 700)
        }
    })
}

private fun saveToMovies(act: ComponentActivity, f: File, name: String) {
    val v = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VideoEditor")
    }
    val u = act.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)!!
    act.contentResolver.openOutputStream(u)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
}
