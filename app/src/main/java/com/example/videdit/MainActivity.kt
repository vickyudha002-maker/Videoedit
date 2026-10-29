package com.example.videdit

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Root() } }
    }

    @Composable
    fun Root() {
        var screen by remember { mutableStateOf(0) } // 0 splash, 1 home, 2 ratio, 3 editor
        var current by remember { mutableStateOf<Project?>(null) }
        var projects by remember { mutableStateOf(ProjectStore.list(this)) }
        BackHandler(enabled = screen == 2) { screen = 1 }
        when (screen) {
            0 -> Splash { screen = 1 }
            1 -> Home(projects, onNew = { screen = 2 }, onOpen = { id ->
                ProjectStore.load(this, id)?.let { p ->
                    p.tracks.flatMap { t -> t.clips.map { it.m } }.distinct().forEach { prepare(this, it) }
                    current = p; screen = 3
                }
            }, onDelete = { id -> ProjectStore.delete(this, id); projects = ProjectStore.list(this) })
            2 -> RatioPage { label, r ->
                val p = Project(UUID.randomUUID().toString(), "Project ${projects.size + 1}", label, r)
                ProjectStore.save(this, p)
                current = p; screen = 3
            }
            else -> current?.let { p ->
                Editor(this, p) {
                    ProjectStore.save(this, p)
                    projects = ProjectStore.list(this)
                    screen = 1
                }
            }
        }
    }
}

@Composable
private fun Splash(onDone: () -> Unit) {
    LaunchedEffect(Unit) { delay(3000); onDone() }
    Box(Modifier.fillMaxSize().background(BG), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.ic_launcher), null, Modifier.size(96.dp).clip(RoundedCornerShape(22.dp)))
            Spacer(Modifier.height(12.dp))
            Text("Video Editor", color = Color.White, fontSize = 26.sp)
            Text("Create. Edit. Share.", color = Color(0xFF9AA0A6), fontSize = 13.sp)
        }
    }
}

@Composable
private fun Home(projects: List<ProjectInfo>, onNew: () -> Unit, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    var del by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxSize().background(BG).systemBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(0.8f).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.ic_launcher), null, Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
            Spacer(Modifier.height(10.dp))
            Text("Video Editor", color = Color.White, fontSize = 20.sp)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onNew, colors = ButtonDefaults.buttonColors(containerColor = RED, contentColor = Color.White)) { Text("＋  New project") }
        }
        Column(Modifier.weight(1.4f).fillMaxHeight()) {
            Text("Recent projects", color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            if (projects.isEmpty()) Text("No projects yet. Tap New project to start.", color = Color(0xFF9AA0A6), fontSize = 13.sp)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(projects) { p ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PANEL).clickable { onOpen(p.id) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, color = Color.White, fontSize = 15.sp)
                            Text("${p.ratioLabel} • ${p.clips} clips • " +
                                SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(p.updated)),
                                color = Color(0xFF9AA0A6), fontSize = 11.sp)
                        }
                        TextButton(onClick = { del = p.id }) { Text("✕", color = Color.White) }
                    }
                }
            }
        }
    }
    if (del != null) AlertDialog(
        onDismissRequest = { del = null },
        title = { Text("Delete project?") },
        text = { Text("This also removes its copied media.") },
        confirmButton = { TextButton(onClick = { onDelete(del!!); del = null }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { del = null }) { Text("Cancel") } }
    )
}

@Composable
private fun RatioPage(onPick: (String, Float) -> Unit) {
    Column(Modifier.fillMaxSize().background(BG).systemBarsPadding().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Choose video ratio", color = Color.White, fontSize = 20.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RATIOS.forEach { (l, r, n) ->
                Column(Modifier.clip(RoundedCornerShape(8.dp)).clickable { onPick(l, r) }.background(PANEL).padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.height(70.dp).aspectRatio(r).background(Color.White, RoundedCornerShape(3.dp)))
                    Text(l, color = Color.White, fontSize = 16.sp)
                    Text(n, color = Color.White, fontSize = 11.sp)
                }
            }
        }
    }
}
