package com.example.videdit

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.compose.ui.graphics.asImageBitmap
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

fun mediaDuration(path: String): Long {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(path)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
    } catch (e: Exception) {
        0L
    } finally {
        r.release()
    }
}

/** Fills in the frame thumbnails / waveform / image preview for a media item (runs in the background). */
fun prepare(act: Activity, m: Media) {
    when (m.kind) {
        0 -> loadThumbs(act, m)
        1 -> loadWave(act, m)
        else -> loadImage(act, m)
    }
}

private fun loadThumbs(act: Activity, m: Media) {
    Thread {
        try {
            val r = MediaMetadataRetriever()
            r.setDataSource(m.path)
            val n = minOf(40, ((m.durMs + 6499) / 6500).toInt())
            for (i in 0 until n) {
                val f = r.getFrameAtTime(i * 6500L * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                val bm = if (f != null) Bitmap.createScaledBitmap(f, 156, 88, true)
                else Bitmap.createBitmap(156, 88, Bitmap.Config.ARGB_8888)
                act.runOnUiThread { m.thumbs.add(bm.asImageBitmap()) }
            }
            r.release()
        } catch (e: Exception) {
        }
    }.start()
}

private fun loadImage(act: Activity, m: Media) {
    Thread {
        try {
            val o = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bm = BitmapFactory.decodeFile(m.path, o) ?: return@Thread
            val s = Bitmap.createScaledBitmap(bm, 156, 88, true)
            act.runOnUiThread { m.thumbs.add(s.asImageBitmap()) }
        } catch (e: Exception) {
        }
    }.start()
}

private fun loadWave(act: Activity, m: Media) {
    Thread {
        try {
            val n = ((m.durMs / 1000f) * 4f).toInt().coerceIn(20, 1500)
            val p = peaks(m.path, m.durMs * 1000, n)
            act.runOnUiThread { m.wave = p }
        } catch (e: Exception) {
        }
    }.start()
}

private fun peaks(path: String, durUs: Long, n: Int): List<Float> {
    val ex = MediaExtractor()
    ex.setDataSource(path)
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
