package com.autodj

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); enableEdgeToEdge()
        Store.init(this); Scheduler.scheduleDaily(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun App() {
    var tab by remember { mutableIntStateOf(0) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Bus.bump() }
    LaunchedEffect(Unit) { if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) }
    val items = listOf("Home" to Icons.Default.Home, "Routine" to Icons.Default.DateRange,
        "Music" to Icons.Default.PlayArrow, "Settings" to Icons.Default.Settings)
    Scaffold(contentWindowInsets = WindowInsets.statusBars, bottomBar = {
        NavigationBar { items.forEachIndexed { i, (n, ic) ->
            NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(ic, n) }, label = { Text(n, maxLines = 1) })
        } }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) { 0 -> Home(); 1 -> RoutineTab(); 2 -> MusicTab(); else -> SettingsTab() }
        }
    }
}

@Composable fun Page(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp), content = content)

@Composable fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) = Row(
    Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.bodyLarge); Text(sub, style = MaterialTheme.typography.bodySmall) }
    Spacer(Modifier.width(8.dp)); Switch(checked, onChange)
}

// ---------------- HOME ----------------
@Composable fun Home() {
    val ctx = LocalContext.current
    val st by Live.state.collectAsState()
    val tick by Bus.tick.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    var voice by remember { mutableStateOf(false) }
    val r = remember(tick) { Store.routine() }
    val plan = remember(tick) { Store.plans()[Store.todayIndex()] }
    val running = st.phase == Phase.Playing || st.phase == Phase.Paused || st.phase == Phase.Preparing
    Page {
        Text("Auto DJ", style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when (st.phase) { Phase.Idle -> "Not playing"; Phase.Preparing -> "Preparing…"; Phase.Playing -> "Playing"
                Phase.Paused -> "Paused (audio focus lost)"; Phase.Error -> "Problem" }, style = MaterialTheme.typography.titleLarge)
            if (st.title.isNotEmpty()) Text(st.title)
            if (st.msg.isNotEmpty()) Text(st.msg, color = MaterialTheme.colorScheme.error)
            if (running) {
                val total = (st.endAt - st.startAt).coerceAtLeast(1)
                Text("Remaining ${fmtDur((st.endAt - now).coerceAtLeast(0))} · stops at ${fmtClock(st.endAt)}")
                LinearProgressIndicator(progress = { ((now - st.startAt).toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
        } }
        Button(onClick = { ctx.serviceAction(PlaybackService.ACTION_STOP) }, Modifier.fillMaxWidth().heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Stop all") }
        if (running) OutlinedButton(onClick = { ctx.serviceAction(PlaybackService.ACTION_SNOOZE) }, Modifier.fillMaxWidth()) { Text("Snooze ${r.snoozeMin} min") }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Next alarm", style = MaterialTheme.typography.labelLarge)
            Text(if (r.enabled) fmtTime(Scheduler.nextTrigger(r)) else "Routine is off", style = MaterialTheme.typography.titleMedium)
            Text("Today: ${plan.theme} · ${plan.tracks.size} local song(s)")
            if (plan.tracks.isEmpty()) Text("No songs for today: a fallback alarm sound will play.", color = MaterialTheme.colorScheme.error)
        } }
        OutlinedButton(onClick = { ctx.startPlayback("test_now", System.currentTimeMillis() + 120_000) }, Modifier.fillMaxWidth()) { Text("Test now (2 minutes)") }
        Button(onClick = { voice = true }, Modifier.fillMaxWidth()) { Text("Ask Auto DJ (voice)") }
    }
    if (voice) VoiceDialog { voice = false }
}

@Composable fun VoiceDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var heard by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("Tap Speak, then say: “What is next?”") }
    var listening by remember { mutableStateOf(false) }
    val tts = remember { TextToSpeech(ctx) {} }
    val rec = remember { if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else null }
    DisposableEffect(Unit) { onDispose { rec?.destroy(); tts.shutdown() } }
    fun say(t: String) { reply = t; tts.speak(t, TextToSpeech.QUEUE_FLUSH, null, "r") }
    fun start() {
        if (rec == null) { say("Speech recognition is unavailable on this device. Install or enable Google speech services."); return }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(e: Int) { listening = false; say(Voice.errorText(e)) }
            override fun onResults(b: Bundle?) {
                listening = false
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                heard = t; say(Voice.handle(ctx, t))
            }
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        listening = true
        rec.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else say("Microphone permission is needed. Allow it in Settings > Apps > Auto DJ.")
    }
    AlertDialog(onDismissRequest = onClose, title = { Text("Ask Auto DJ") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (heard.isNotEmpty()) Text("You said: $heard", style = MaterialTheme.typography.bodySmall)
            Text(reply)
            Text("Commands: time, next alarm, timetable, play, stop, snooze.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        Button(onClick = {
            if (listening) { rec?.cancel(); listening = false; say("Cancelled.") }
            else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
            else perm.launch(Manifest.permission.RECORD_AUDIO)
        }) { Text(if (listening) "Listening… tap to cancel" else "Speak") }
    }, dismissButton = { TextButton(onClick = onClose) { Text("Close") } })
}

// ---------------- ROUTINE ----------------
@OptIn(ExperimentalLayoutApi::class)
@Composable fun RoutineTab() {
    val ctx = LocalContext.current
    var r by remember { mutableStateOf(Store.routine()) }
    Page {
        Text("Wake-up routine", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth().clickable {
            TimePickerDialog(ctx, { _, h, m -> r = r.copy(hour = h, minute = m) }, r.hour, r.minute, false).show()
        }) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("%d:%02d %s".format(if (r.hour % 12 == 0) 12 else r.hour % 12, r.minute, if (r.hour < 12) "AM" else "PM"), style = MaterialTheme.typography.headlineMedium)
                Text("Daily · tap to change time")
            }
            Switch(r.enabled, { r = r.copy(enabled = it) })
        } }
        Text("Duration", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 45, 60, 90).forEach { m -> FilterChip(r.durationMin == m, { r = r.copy(durationMin = m) }, { Text("$m min") }) }
        }
        SwitchRow("Voice greeting", "Speaks the day and theme first", r.greeting) { r = r.copy(greeting = it) }
        SwitchRow("Fade in", "Raises volume over about 1 minute", r.fade) { r = r.copy(fade = it) }
        SwitchRow("Shuffle", "Playlist repeats to fill the time", r.shuffle) { r = r.copy(shuffle = it) }
        Text("Snooze", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 15).forEach { m -> FilterChip(r.snoozeMin == m, { r = r.copy(snoozeMin = m) }, { Text("$m min") }) }
        }
        Text("Snooze does not move the stop time; the routine still ends at the original time (at least 5 minutes of music after snooze).", style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            Store.saveRoutine(r); Scheduler.scheduleDaily(ctx); Store.log("Routine saved: %d:%02d, %d min".format(r.hour, r.minute, r.durationMin))
            Toast.makeText(ctx, "Saved. Next: " + if (r.enabled) fmtTime(Scheduler.nextTrigger(r)) else "off", Toast.LENGTH_LONG).show()
        }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Save routine") }
    }
}

// ---------------- MUSIC ----------------
@OptIn(ExperimentalLayoutApi::class)
@Composable fun MusicTab() {
    val ctx = LocalContext.current
    val tick by Bus.tick.collectAsState()
    var day by remember { mutableIntStateOf(Store.todayIndex()) }
    val plans = remember(tick) { Store.plans() }
    var msg by remember { mutableStateOf("") }
    var replaceIdx by remember { mutableIntStateOf(-1) }
    val plan = plans[day]
    val ok = remember(plans, day) { plan.tracks.map { Audio.readable(ctx, Uri.parse(it.uri)) } }
    fun savePlan(p: DayPlan, bump: Boolean = true) {
        val l = Store.plans().toMutableList(); l[day] = p; Store.savePlans(l); if (bump) Bus.bump()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val kept = try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); true } catch (e: Exception) { false }
            val d = Audio.duration(ctx, uri)
            if (!kept) msg = "Cannot keep access to this file after restart. Pick it through the Files app (Downloads/Audio)."
            else if (d == null || d <= 0) msg = "This file is not playable audio."
            else {
                val t = Track(uri.toString(), Audio.name(ctx, uri), d)
                val l = plan.tracks.toMutableList()
                if (replaceIdx in l.indices) l[replaceIdx] = t else l.add(t)
                savePlan(plan.copy(tracks = l)); msg = "Added ${t.name}"
            }
            replaceIdx = -1
        }
    }
    Page {
        Text("Music sources", style = MaterialTheme.typography.headlineSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Store.days.forEachIndexed { i, d -> FilterChip(day == i, { day = i; msg = "" }, { Text(d) }) }
        }
        var theme by remember(day) { mutableStateOf(plan.theme) }
        OutlinedTextField(theme, { theme = it; savePlan(Store.plans()[day].copy(theme = it), false) }, label = { Text("${Store.days[day]} theme (editable)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Local songs (alarm source)", style = MaterialTheme.typography.titleMedium)
        if (plan.tracks.isEmpty()) Text("No songs yet. A fallback alarm sound plays if the list is empty.", style = MaterialTheme.typography.bodySmall)
        plan.tracks.forEachIndexed { i, t ->
            var menu by remember { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${fmtDur(t.durationMs)} · " + if (ok[i]) "Readable" else "Unavailable: replace or remove",
                        style = MaterialTheme.typography.bodySmall, color = if (ok[i]) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = {
                    msg = "Opening preview…"
                    Previewer.play(ctx, Uri.parse(t.uri), { msg = "Previewing (10 s)"; Store.setTest("t_audio", "PASSED ${fmtTime(System.currentTimeMillis())}") },
                        { msg = "Preview failed: $it"; Store.setTest("t_audio", "FAILED: $it") })
                }, enabled = ok[i]) { Text("Preview") }
                Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Replace") }, onClick = { menu = false; replaceIdx = i; picker.launch(arrayOf("audio/*")) })
                        DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; savePlan(plan.copy(tracks = plan.tracks.filterIndexed { k, _ -> k != i })) })
                    } }
            } }
        }
        Button(onClick = { replaceIdx = -1; picker.launch(arrayOf("audio/*")) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Add song") }
        if (msg.isNotEmpty()) Text(msg, style = MaterialTheme.typography.bodyMedium)

        HorizontalDivider()
        Text("YouTube (opens separately)", style = MaterialTheme.typography.titleMedium)
        var yt by remember(day) { mutableStateOf(plan.youtube) }
        val kind = youtubeKind(yt)
        OutlinedTextField(yt, { yt = it; savePlan(Store.plans()[day].copy(youtube = it), false) }, label = { Text("Video or playlist link") },
            isError = yt.isNotBlank() && kind == null, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(when { yt.isBlank() -> "Optional"; kind != null -> "Valid YouTube $kind link"; else -> "Not a valid YouTube video or playlist link" }) })
        OutlinedButton(onClick = {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(yt.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { msg = "No app can open this link." }
        }, enabled = kind != null, modifier = Modifier.fillMaxWidth()) { Text("Open in YouTube") }
        Text("YouTube is never used as the alarm sound and cannot be started or stopped automatically.", style = MaterialTheme.typography.bodySmall)
    }
}

// ---------------- SETTINGS ----------------
@Composable fun StatusRow(title: String, sub: String, status: String, good: Boolean, action: String? = null, onAction: () -> Unit = {}) = Row(
    Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(sub, style = MaterialTheme.typography.bodySmall)
        Text(status, style = MaterialTheme.typography.labelMedium, color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    }
    if (action != null) { Spacer(Modifier.width(8.dp)); OutlinedButton(onClick = onAction) { Text(action) } }
}

@Composable fun SettingsTab() {
    val ctx = LocalContext.current
    val tick by Bus.tick.collectAsState()
    val events = remember(tick) { Store.events() }
    val notifOk = remember(tick) { ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled() }
    val batt = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val next = remember(tick) { Store.nextAt() }
    fun good(s: String) = s.startsWith("PASSED")
    Page {
        Text("Setup & checks", style = MaterialTheme.typography.headlineSmall)
        StatusRow("Notifications", "Needed for the playback notification and Stop button", if (notifOk) "Allowed" else "Blocked",
            notifOk, "Open") { ctx.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)) }
        StatusRow("Alarm scheduled", "Uses the system alarm clock (works in Doze)", if (next > 0) "Next: ${fmtTime(next)}" else "No alarm scheduled", next > 0)
        StatusRow("Battery optimisation", "Unrestricted battery helps on some phones", if (batt) "Unrestricted" else "Optimised (usually fine)", batt, "Open") {
            ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        val a = Store.test("t_audio"); val t = Store.test("t_tts"); val l = Store.test("t_locked")
        StatusRow("Song preview", "Passes only when a preview actually starts (Music tab)", a, good(a))
        StatusRow("Voice greeting", "Passes only when speech actually starts", t, good(t), "Test") { ttsTest(ctx) }
        StatusRow("Locked-screen alarm", "Schedules a 1-minute test; lock the phone straight away", l, good(l), "Test") {
            val at = System.currentTimeMillis() + 60_000
            Store.setTest("t_locked", "Waiting for alarm at ${fmtClock(at)}… lock the phone")
            Scheduler.setAt(ctx, at, 3, "test_locked", at + 60_000); Store.log("Locked-screen test scheduled")
        }
        HorizontalDivider()
        Text("Help", style = MaterialTheme.typography.titleMedium)
        Text("• Voice says microphone busy: end Google Meet or any recording, then retry.\n" +
            "• Song will not play: re-add it with Add song; files must stay in place. Unavailable files are skipped.\n" +
            "• Alarms are kept after reboot and time-zone changes.\n" +
            "• If you force-stop the app, Android cancels alarms until you open it again. A powered-off phone cannot ring.\n" +
            "• Alarms use the alarm volume. Do not mute it.\n" +
            "• 'Hey Google' is not connected; use the Ask Auto DJ button.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Event history", style = MaterialTheme.typography.titleMedium)
        if (events.isEmpty()) Text("No events yet.", style = MaterialTheme.typography.bodySmall)
        events.take(40).forEach { Text("${fmtTime(it.time)}  ${it.text}", style = MaterialTheme.typography.bodySmall) }
    }
}

fun ttsTest(ctx: Context) {
    var t: TextToSpeech? = null
    t = TextToSpeech(ctx.applicationContext) { s ->
        if (s == TextToSpeech.SUCCESS) {
            t?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(u: String?) { Store.setTest("t_tts", "PASSED ${fmtTime(System.currentTimeMillis())}") }
                override fun onDone(u: String?) { t?.shutdown() }
                override fun onError(u: String?) { Store.setTest("t_tts", "FAILED: speech error"); t?.shutdown() }
            })
            t?.speak("Good morning from Auto D J.", TextToSpeech.QUEUE_FLUSH, null, "t")
        } else Store.setTest("t_tts", "FAILED: no text-to-speech engine")
    }
}
