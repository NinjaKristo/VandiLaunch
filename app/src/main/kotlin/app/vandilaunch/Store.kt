package app.vandilaunch

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Everything the app persists: the cached schedule, reminder switches, and what was sent. */
object Store {
    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("vandi", Context.MODE_PRIVATE)

    fun launches(c: Context): List<Launch> = LaunchJson.fromCache(prefs(c).getString("launches", null))

    fun fetchedAt(c: Context): Long = prefs(c).getLong("fetchedAt", 0)

    fun save(c: Context, list: List<Launch>, fetchedAt: Long) {
        prefs(c).edit()
            .putString("launches", LaunchJson.toCache(list))
            .putLong("fetchedAt", fetchedAt)
            .apply()
    }

    fun enabledOffsets(c: Context): Set<Long> {
        val p = prefs(c)
        return Reminders.OFFSETS.filter { p.getBoolean("remind_$it", true) }.toSet()
    }

    fun setOffset(c: Context, offset: Long, on: Boolean) {
        prefs(c).edit().putBoolean("remind_$offset", on).apply()
    }

    /** Reminder key -> the launch time that reminder was sent for. */
    fun fired(c: Context): MutableMap<String, Long> {
        val out = HashMap<String, Long>()
        try {
            val o = JSONObject(prefs(c).getString("fired", "{}")!!)
            for (k in o.keys()) out[k] = o.getLong(k)
        } catch (e: Exception) {
            // A corrupt record only risks one repeated reminder.
        }
        return out
    }

    fun saveFired(c: Context, fired: Map<String, Long>) {
        val o = JSONObject()
        for ((k, v) in fired) o.put(k, v)
        prefs(c).edit().putString("fired", o.toString()).apply()
    }
}
