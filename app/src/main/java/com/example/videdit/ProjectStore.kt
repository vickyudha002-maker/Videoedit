package com.example.videdit

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class ProjectInfo(val id: String, val name: String, val ratioLabel: String, val updated: Long, val clips: Int)

object ProjectStore {
    private fun dir(c: Context) = File(c.filesDir, "projects").also { it.mkdirs() }
    fun mediaDir(c: Context) = File(c.filesDir, "media").also { it.mkdirs() }

    /** Copies a picked file into the app's own storage so projects can reopen later. */
    fun importMedia(c: Context, u: Uri, ext: String): File {
        val f = File(mediaDir(c), "${UUID.randomUUID()}.$ext")
        c.contentResolver.openInputStream(u)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f
    }

    fun save(c: Context, p: Project) {
        val o = JSONObject()
        o.put("id", p.id); o.put("name", p.name); o.put("ratioLabel", p.ratioLabel); o.put("ratio", p.ratio.toDouble())
        o.put("title", p.title); o.put("updated", System.currentTimeMillis())
        val ts = JSONArray()
        p.tracks.forEach { t ->
            val to = JSONObject()
            to.put("video", t.video); to.put("locked", t.locked); to.put("muted", t.muted)
            val a = JSONArray()
            t.clips.forEach { a.put(it.toJson()) }
            to.put("clips", a)
            ts.put(to)
        }
        o.put("tracks", ts)
        File(dir(c), "${p.id}.json").writeText(o.toString())
    }

    fun load(c: Context, id: String): Project? = try {
        val o = JSONObject(File(dir(c), "$id.json").readText())
        val p = Project(o.getString("id"), o.getString("name"), o.getString("ratioLabel"), o.getDouble("ratio").toFloat())
        p.title = o.optString("title", "")
        val cache = HashMap<String, Media>()
        p.tracks.clear()
        val ts = o.getJSONArray("tracks")
        for (i in 0 until ts.length()) {
            val to = ts.getJSONObject(i)
            val t = Track(to.getBoolean("video"))
            t.locked = to.optBoolean("locked"); t.muted = to.optBoolean("muted")
            val a = to.getJSONArray("clips")
            for (j in 0 until a.length()) {
                val co = a.getJSONObject(j)
                if (File(co.getString("path")).exists()) t.clips.add(Clip.fromJson(co, cache))
            }
            p.tracks.add(t)
        }
        p
    } catch (e: Exception) {
        null
    }

    fun list(c: Context): List<ProjectInfo> = (dir(c).listFiles() ?: emptyArray()).mapNotNull { f ->
        try {
            val o = JSONObject(f.readText())
            var n = 0
            val ts = o.getJSONArray("tracks")
            for (i in 0 until ts.length()) n += ts.getJSONObject(i).getJSONArray("clips").length()
            ProjectInfo(o.getString("id"), o.getString("name"), o.getString("ratioLabel"), o.optLong("updated"), n)
        } catch (e: Exception) {
            null
        }
    }.sortedByDescending { it.updated }

    fun delete(c: Context, id: String) {
        File(dir(c), "$id.json").delete()
        val used = HashSet<String>()
        dir(c).listFiles()?.forEach { f ->
            try {
                val ts = JSONObject(f.readText()).getJSONArray("tracks")
                for (i in 0 until ts.length()) {
                    val a = ts.getJSONObject(i).getJSONArray("clips")
                    for (j in 0 until a.length()) used.add(a.getJSONObject(j).getString("path"))
                }
            } catch (e: Exception) {
            }
        }
        mediaDir(c).listFiles()?.forEach { if (it.path !in used) it.delete() }
    }
}
