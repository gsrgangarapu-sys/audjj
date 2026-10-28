package com.autodj

import android.content.Context
import android.speech.SpeechRecognizer

object Voice {
    fun errorText(e: Int): String = when (e) {
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Microphone busy. End any call or recording (for example Google Meet), then tap again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed. Allow it in Settings > Apps > Auto DJ."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error. Check your connection and try again."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I heard no speech. Tap and speak closer to the phone."
        SpeechRecognizer.ERROR_CLIENT -> "Cancelled."
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording problem. Close other apps using the mic and retry."
        SpeechRecognizer.ERROR_SERVER -> "The speech service had an error. Try again shortly."
        else -> "Voice input failed (code $e). Try again."
    }

    fun handle(c: Context, text: String): String {
        val t = text.lowercase()
        return when {
            t.isBlank() -> "I did not catch that."
            "snooze" in t -> { c.serviceAction(PlaybackService.ACTION_SNOOZE); "Snoozing." }
            "stop" in t || "pause" in t -> { c.serviceAction(PlaybackService.ACTION_STOP); "Stopped." }
            "play" in t || "start" in t -> { c.startPlayback("manual"); "Starting today's playlist." }
            "timetable" in t || "schedule" in t || "today" in t -> {
                val r = Store.routine(); val p = Store.plans()[Store.todayIndex()]
                "Today's theme is ${p.theme}. Wake-up ${if (r.enabled) "at %d:%02d for ${r.durationMin} minutes".format(r.hour, r.minute) else "is off"}, with ${p.tracks.size} songs."
            }
            "time" in t -> "It is " + fmtClock(System.currentTimeMillis())
            "next" in t || "alarm" in t -> {
                val r = Store.routine()
                if (r.enabled) "Your next alarm is " + fmtTime(Scheduler.nextTrigger(r)) else "The wake-up routine is off."
            }
            else -> "Try: what time is it, next alarm, timetable, play, stop, or snooze."
        }
    }
}
