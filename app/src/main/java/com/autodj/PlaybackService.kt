package com.autodj

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlaybackService : MediaSessionService() {
    companion object {
        const val ACTION_START = "com.autodj.START"
        const val ACTION_STOP = "com.autodj.STOP"
        const val ACTION_SNOOZE = "com.autodj.SNOOZE"
        const val NID = 1001
        const val CH = "autodj_playback"
    }

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val h = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var endAt = 0L; private var startAt = 0L; private var lastStart = 0L
    private var kind = "daily"; private var active = false; private var reportedPlaying = false; private var errors = 0

    override fun onCreate() {
        super.onCreate(); Store.init(this)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_ALARM).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setWakeMode(C.WAKE_MODE_LOCAL).build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!active) return
                if (isPlaying) {
                    Live.state.value = Live.state.value.copy(phase = Phase.Playing, msg = "")
                    if (!reportedPlaying) {
                        reportedPlaying = true; Store.log("Playback started ($kind)")
                        if (kind == "test_locked") {
                            val pm = getSystemService(PowerManager::class.java)
                            Store.setTest("t_locked", if (!pm.isInteractive) "PASSED (screen was off) ${fmtTime(System.currentTimeMillis())}"
                            else "INCONCLUSIVE: screen was on. Lock the phone and run again.")
                        }
                    }
                } else if (reportedPlaying) {
                    Live.state.value = Live.state.value.copy(phase = Phase.Paused)
                }
            }
            override fun onPlayerError(e: PlaybackException) {
                Store.log("Player error: ${e.errorCodeName}")
                if (++errors >= player.mediaItemCount) stopAll("Playback failed: ${e.errorCodeName}", true)
                else { player.seekToNextMediaItem(); player.prepare(); player.play() }
            }
        })
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(i, flags, startId)
        when (i?.action) {
            ACTION_START -> { startFg(); startRoutine(i) }
            ACTION_STOP -> stopAll("Stopped by user")
            ACTION_SNOOZE -> snooze()
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 9, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 8, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Auto DJ").setContentText("Wake-up routine running")
            .setContentIntent(open).addAction(0, "Stop", stop).setOngoing(true).build()
        ServiceCompat.startForeground(this, NID, n,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
    }

    private fun startRoutine(i: Intent) {
        val now = System.currentTimeMillis()
        if (active && now - lastStart < 60_000) { Store.log("Duplicate trigger ignored"); return }
        h.removeCallbacksAndMessages(null); tts?.stop()           // overlapping trigger: restart cleanly
        lastStart = now; kind = i.getStringExtra("kind") ?: "daily"; errors = 0; reportedPlaying = false; active = true
        val r = Store.routine(); val given = i.getLongExtra("end_at", 0L)
        startAt = now; endAt = if (given > now) given else now + r.durationMin * 60_000L
        val plan = Store.plans()[Store.todayIndex()]
        var items = plan.tracks.filter { Audio.readable(this, Uri.parse(it.uri)) }.map { it.name to it.uri }
        val missing = plan.tracks.size - items.size
        if (missing > 0) Store.log("$missing track(s) unavailable and skipped")
        if (items.isEmpty()) {
            Store.log("Playlist empty or unavailable: using fallback alarm sound")
            items = listOf("Fallback alarm sound" to RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM).toString())
        }
        if (r.shuffle) items = items.shuffled()
        Live.state.value = PState(Phase.Preparing, "Today: ${plan.theme}", startAt, endAt, "")
        player.setMediaItems(items.map {
            MediaItem.Builder().setUri(it.second).setMediaMetadata(MediaMetadata.Builder().setTitle(it.first).build()).build()
        })
        player.repeatMode = Player.REPEAT_MODE_ALL            // repeat-to-fill
        player.volume = if (r.fade) 0.05f else 1f
        player.prepare()
        val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())
        if (r.greeting) greetThen("Good morning. Today is $day. Today's theme is ${plan.theme}.") { beginPlay(r.fade) } else beginPlay(r.fade)
    }

    private fun greetThen(text: String, go: () -> Unit) {
        var done = false
        val once = { if (!done) { done = true; h.post { go() } } }
        tts = TextToSpeech(this) { s ->
            if (s != TextToSpeech.SUCCESS) { Store.log("Text-to-speech unavailable, skipping greeting"); once() }
            else {
                tts?.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(u: String?) {}
                    override fun onDone(u: String?) { once() }
                    override fun onError(u: String?) { once() }
                })
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "greet")
            }
        }
        h.postDelayed({ once() }, 15_000)
    }

    private fun beginPlay(fade: Boolean) {
        if (!active) return
        player.play()
        if (fade) {
            var v = 0.05f
            val step = object : Runnable { override fun run() { v = (v + 0.05f).coerceAtMost(1f); player.volume = v; if (v < 1f) h.postDelayed(this, 3000) } }
            h.postDelayed(step, 3000)
        }
        h.postDelayed({ if (active) stopAll("Finished at scheduled stop time") }, (endAt - System.currentTimeMillis()).coerceAtLeast(1000))
        h.postDelayed({ if (active && !reportedPlaying) stopAll("Playback did not start", true) }, 20_000)
    }

    /** Snooze pauses music and restarts after N minutes. It does NOT shift the stop time (minimum 5 min of play). */
    private fun snooze() {
        if (!active) return
        val r = Store.routine(); val at = System.currentTimeMillis() + r.snoozeMin * 60_000L
        val end = if (endAt - at < 5 * 60_000L) at + 5 * 60_000L else endAt
        stopAll("Snoozed ${r.snoozeMin} min"); Scheduler.setAt(this, at, 2, "snooze", end)
    }

    private fun stopAll(reason: String, error: Boolean = false) {
        h.removeCallbacksAndMessages(null); tts?.stop(); player.stop(); player.clearMediaItems(); active = false
        Live.state.value = if (error) PState(Phase.Error, msg = reason) else PState()
        Store.log(reason)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null); tts?.shutdown(); session?.release(); player.release(); super.onDestroy()
    }
}
