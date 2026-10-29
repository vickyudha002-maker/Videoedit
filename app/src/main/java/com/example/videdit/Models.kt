package com.example.videdit

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

val PANEL = Color(0xFF2A2D33)
val BG = Color(0xFF14161A)
val ORANGE = Color(0xFFFF9F1C)
val BLUE = Color(0xFF3D8BFF)
val RED = Color(0xFFE5483F)
val RATIOS = listOf(Triple("9:16", 9f / 16f, "Shorts / Reels"), Triple("16:9", 16f / 9f, "YouTube"),
    Triple("1:1", 1f, "Square"), Triple("4:5", 0.8f, "Instagram"))

/** kind: 0 = video, 1 = audio, 2 = image */
class Media(val path: String, val durMs: Long, val kind: Int) {
    val uri: Uri get() = Uri.fromFile(File(path))
    val thumbs = mutableStateListOf<ImageBitmap>()
    var wave by mutableStateOf<List<Float>>(emptyList())
}

class Clip(val m: Media, s: Float, e: Float) {
    var start by mutableStateOf(s)
    var end by mutableStateOf(e)
    var speed by mutableStateOf(1f)
    var volume by mutableStateOf(1f)
    var filter by mutableStateOf(0)
    var bright by mutableStateOf(0f)
    var contrast by mutableStateOf(0f)
    var sat by mutableStateOf(0f)
    var vib by mutableStateOf(0f)
    var temp by mutableStateOf(0f)
    var high by mutableStateOf(0f)
    var zoomA by mutableStateOf(1f)
    var zoomB by mutableStateOf(1f)
    var panAx by mutableStateOf(0f)
    var panAy by mutableStateOf(0f)
    var panBx by mutableStateOf(0f)
    var panBy by mutableStateOf(0f)
    var lx by mutableStateOf(0f)
    var ly by mutableStateOf(0f)
    var ls by mutableStateOf(0.4f)
    val eq = mutableStateListOf(0f, 0f, 0f, 0f, 0f)

    /** Length on the timeline in ms (after speed change). */
    val lenMs: Float get() = (end - start) / speed

    fun copy(): Clip = fromJson(toJson(), hashMapOf(m.path to m))

    fun toJson(): JSONObject = JSONObject().apply {
        put("path", m.path); put("dur", m.durMs); put("kind", m.kind)
        put("start", start.toDouble()); put("end", end.toDouble())
        put("speed", speed.toDouble()); put("volume", volume.toDouble()); put("filter", filter)
        put("bright", bright.toDouble()); put("contrast", contrast.toDouble()); put("sat", sat.toDouble())
        put("vib", vib.toDouble()); put("temp", temp.toDouble()); put("high", high.toDouble())
        put("zoomA", zoomA.toDouble()); put("zoomB", zoomB.toDouble())
        put("panAx", panAx.toDouble()); put("panAy", panAy.toDouble())
        put("panBx", panBx.toDouble()); put("panBy", panBy.toDouble())
        put("lx", lx.toDouble()); put("ly", ly.toDouble()); put("ls", ls.toDouble())
        put("eq", JSONArray(eq.map { it.toDouble() }))
    }

    companion object {
        private fun f(o: JSONObject, k: String, d: Float) = o.optDouble(k, d.toDouble()).toFloat()

        fun fromJson(o: JSONObject, cache: MutableMap<String, Media>): Clip {
            val path = o.getString("path")
            val m = cache.getOrPut(path) { Media(path, o.getLong("dur"), o.getInt("kind")) }
            val c = Clip(m, f(o, "start", 0f), f(o, "end", m.durMs.toFloat()))
            c.speed = f(o, "speed", 1f); c.volume = f(o, "volume", 1f); c.filter = o.optInt("filter", 0)
            c.bright = f(o, "bright", 0f); c.contrast = f(o, "contrast", 0f); c.sat = f(o, "sat", 0f)
            c.vib = f(o, "vib", 0f); c.temp = f(o, "temp", 0f); c.high = f(o, "high", 0f)
            c.zoomA = f(o, "zoomA", 1f); c.zoomB = f(o, "zoomB", 1f)
            c.panAx = f(o, "panAx", 0f); c.panAy = f(o, "panAy", 0f)
            c.panBx = f(o, "panBx", 0f); c.panBy = f(o, "panBy", 0f)
            c.lx = f(o, "lx", 0f); c.ly = f(o, "ly", 0f); c.ls = f(o, "ls", 0.4f)
            val e = o.optJSONArray("eq")
            if (e != null) for (i in 0 until minOf(5, e.length())) c.eq[i] = e.optDouble(i, 0.0).toFloat()
            return c
        }
    }
}

class Track(val video: Boolean) {
    val clips = mutableStateListOf<Clip>()
    var locked by mutableStateOf(false)
    var muted by mutableStateOf(false)
}

class Project(val id: String, var name: String, var ratioLabel: String, var ratio: Float) {
    val tracks = mutableStateListOf(Track(true), Track(false))
    var title by mutableStateOf("")
}
