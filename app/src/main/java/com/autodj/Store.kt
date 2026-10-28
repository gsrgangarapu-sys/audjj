package com.autodj

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class Track(val uri: String, val name: String, val durationMs: Long)
data class DayPlan(val theme: String, val tracks: List<Track>, val youtube: String)
data class Routine(
    val hour: Int = 6, val minute: Int = 0, val durationMin: Int = 60, val enabled: Boolean = true,
    val fade: Boolean = true, val greeting: Boolean = true, val shuffle: Boolean = true, val snoozeMin: Int = 10
)
data class Event(val time: Long, val text: String)

enum class Phase { Idle, Preparing, Playing, Paused, Error }
data class PState(val phase: Phase = Phase.Idle, val title: String = "", val startAt: Long = 0, val endAt: Long = 0, val msg: String = "")

object Live { val state = MutableStateFlow(PState()) }
object Bus { val tick = MutableStateFlow(0); fun bump() = tick.update { it + 1 } }

object Store {
    val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val defaultThemes = listOf("Shiva", "Hanuman", "Ganesha", "Sai Baba", "Lakshmi", "Venkateswara", "Surya")
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("autodj", Context.MODE_PRIVATE) }

    /** Monday = 0 ... Sunday = 6 */
    fun dayIndex(dow: Int) = (dow + 5) % 7
    fun todayIndex() = dayIndex(Calendar.getInstance().get(Calendar.DAY_OF_WEEK))

    fun routine(): Routine {
        val j = try { JSONObject(sp.getString("routine", "{}")!!) } catch (e: Exception) { JSONObject() }
        return Routine(j.optInt("h", 6), j.optInt("m", 0), j.optInt("d", 60), j.optBoolean("on", true),
            j.optBoolean("fade", true), j.optBoolean("greet", true), j.optBoolean("shuf", true), j.optInt("snooze", 10))
    }
    fun saveRoutine(r: Routine) {
        val j = JSONObject().put("h", r.hour).put("m", r.minute).put("d", r.durationMin).put("on", r.enabled)
            .put("fade", r.fade).put("greet", r.greeting).put("shuf", r.shuffle).put("snooze", r.snoozeMin)
        sp.edit().putString("routine", j.toString()).apply()
    }

    fun plans(): List<DayPlan> {
        val arr = try { JSONArray(sp.getString("plans", "[]")) } catch (e: Exception) { JSONArray() }
        return List(7) { i ->
            val o = arr.optJSONObject(i)
            if (o == null) DayPlan(defaultThemes[i], emptyList(), "") else {
                val ta = o.optJSONArray("tracks") ?: JSONArray()
                DayPlan(o.optString("theme", defaultThemes[i]),
                    (0 until ta.length()).map { k -> val t = ta.getJSONObject(k); Track(t.getString("u"), t.getString("n"), t.optLong("d")) },
                    o.optString("yt", ""))
            }
        }
    }
    fun savePlans(p: List<DayPlan>) {
        val arr = JSONArray()
        p.forEach { d ->
            val ta = JSONArray(); d.tracks.forEach { ta.put(JSONObject().put("u", it.uri).put("n", it.name).put("d", it.durationMs)) }
            arr.put(JSONObject().put("theme", d.theme).put("tracks", ta).put("yt", d.youtube))
        }
        sp.edit().putString("plans", arr.toString()).apply()
    }

    fun log(text: String) {
        val arr = try { JSONArray(sp.getString("events", "[]")) } catch (e: Exception) { JSONArray() }
        val out = JSONArray().put(JSONObject().put("t", System.currentTimeMillis()).put("x", text))
        for (i in 0 until minOf(arr.length(), 99)) out.put(arr.get(i))
        sp.edit().putString("events", out.toString()).apply(); Bus.bump()
    }
    fun events(): List<Event> {
        val arr = try { JSONArray(sp.getString("events", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map { val o = arr.getJSONObject(it); Event(o.getLong("t"), o.getString("x")) }
    }
    fun test(key: String): String = sp.getString(key, "Not run") ?: "Not run"
    fun setTest(key: String, v: String) { sp.edit().putString(key, v).apply(); Bus.bump() }
    fun nextAt(): Long = sp.getLong("next_at", 0)
    fun setNextAt(v: Long) { sp.edit().putLong("next_at", v).apply() }
}
