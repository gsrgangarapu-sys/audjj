package com.autodj

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.util.Calendar

object Scheduler {
    const val ACTION = "com.autodj.ALARM"

    fun nextTrigger(r: Routine, now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now; set(Calendar.HOUR_OF_DAY, r.hour); set(Calendar.MINUTE, r.minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun pi(c: Context, code: Int, kind: String, endAt: Long) = PendingIntent.getBroadcast(
        c, code, Intent(c, AlarmReceiver::class.java).setAction(ACTION).putExtra("kind", kind).putExtra("end_at", endAt),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** setAlarmClock is exempt from Doze and does not need the exact-alarm permission. */
    fun setAt(c: Context, at: Long, code: Int, kind: String, endAt: Long = 0L) {
        val am = c.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(c, 7, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi(c, code, kind, endAt))
    }

    fun scheduleDaily(c: Context) {
        Store.init(c)
        val r = Store.routine()
        if (!r.enabled) {
            c.getSystemService(AlarmManager::class.java).cancel(pi(c, 1, "daily", 0)); Store.setNextAt(0); Bus.bump(); return
        }
        val at = nextTrigger(r)
        setAt(c, at, 1, "daily"); Store.setNextAt(at); Bus.bump()
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Store.init(c)
        val kind = i.getStringExtra("kind") ?: "daily"
        Store.log("Alarm fired ($kind)")
        if (kind == "daily") { Scheduler.scheduleDaily(c); if (!Store.routine().enabled) return }
        c.startPlayback(kind, i.getLongExtra("end_at", 0L))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Store.init(c); Store.log("Rescheduled after ${i.action?.substringAfterLast('.')}"); Scheduler.scheduleDaily(c)
    }
}
