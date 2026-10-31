package com.example.videdit

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import androidx.activity.ComponentActivity
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** Makes a reversed (silent) copy of part of a video. Runs in the background; [done] is called on the main thread. */
fun reverseVideo(act: ComponentActivity, m: Media, startMs: Long, endMs: Long, done: (Media?) -> Unit) {
    Thread {
        var result: Media? = null
        try {
            result = doReverse(act, m, startMs, endMs)
        } catch (e: Exception) {
            android.util.Log.e("Reverse", "failed", e)
        }
        act.runOnUiThread { done(result) }
    }.start()
}

private fun doReverse(ctx: Context, m: Media, startMs: Long, endMs: Long): Media {
    val r = MediaMetadataRetriever()
    r.setDataSource(m.path)
    val vw = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
    val vh = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
    var w = if (rot % 180 == 0) vw else vh
    var h = if (rot % 180 == 0) vh else vw
    val sc = min(1f, 1280f / max(w, h))
    w = (w * sc).toInt() / 2 * 2
    h = (h * sc).toInt() / 2 * 2
    val fps = 30
    val frameUs = 1_000_000L / fps
    val n = ((endMs - startMs) * 1000 / frameUs).toInt().coerceAtLeast(2)
    val out = File(ProjectStore.mediaDir(ctx), "${UUID.randomUUID()}.mp4")

    val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, w * h * 6)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
    }
    val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    val surface = enc.createInputSurface()
    enc.start()
    val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    var track = -1
    var started = false
    var outIdx = 0
    val info = MediaCodec.BufferInfo()

    fun drain(end: Boolean) {
        if (end) enc.signalEndOfInputStream()
        var waits = 0
        while (true) {
            val i = enc.dequeueOutputBuffer(info, if (end) 10000 else 0)
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end || ++waits > 500) break
            } else if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = mux.addTrack(enc.outputFormat); mux.start(); started = true
            } else if (i >= 0) {
                val buf = enc.getOutputBuffer(i)!!
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                if (info.size > 0 && started) {
                    info.presentationTimeUs = outIdx * frameUs
                    outIdx++
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    mux.writeSampleData(track, buf, info)
                }
                enc.releaseOutputBuffer(i, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    try {
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val dst = Rect(0, 0, w, h)
        for (k in 0 until n) {
            val idx = n - 1 - k
            val bmp = r.getScaledFrameAtTime(startMs * 1000 + idx * frameUs, MediaMetadataRetriever.OPTION_CLOSEST, w, h) ?: continue
            val canvas = surface.lockCanvas(null)
            canvas.drawBitmap(bmp, null, dst, paint)
            surface.unlockCanvasAndPost(canvas)
            bmp.recycle()
            drain(false)
        }
        drain(true)
    } finally {
        try { enc.stop() } catch (e: Exception) { }
        enc.release()
        surface.release()
        try { if (started) mux.stop() } catch (e: Exception) { }
        mux.release()
        r.release()
    }
    return Media(out.path, outIdx * frameUs / 1000, 0)
}
