package app.vandilaunch

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Ties the pieces together: fetch, then reminders, alarms and the widget. */
object Sync {
    const val ACTION_WAKE = "app.vandilaunch.WAKE"
    const val ACTION_TICK = "app.vandilaunch.TICK"

    private const val JOB_ID = 1
    private const val JOB_PERIOD = 2 * Times.HOUR

    /** Close to launch, times slip often: re-check this frequently inside the window. */
    private const val CLOSE_WINDOW = 6 * Times.HOUR
    private const val CLOSE_RECHECK = 20 * Times.MIN

    private val lock = Any()

    /** Downloads the schedule. Blocking; call off the main thread. Returns false if it failed. */
    fun refresh(c: Context, quick: Boolean = false): Boolean = synchronized(lock) {
        val old = Store.launches(c)
        val fresh = try {
            Api.fetch(old, quick)
        } catch (e: Exception) {
            Log.w("VandiLaunch", "Schedule refresh failed: $e")
            return@synchronized false
        }
        val now = System.currentTimeMillis()
        Store.save(c, fresh, now)

        val changes = Reminders.changes(old, fresh, now)
        if (changes.isNotEmpty()) {
            val fired = Store.fired(c)
            for (ch in changes) {
                Notifier.changed(c, ch.new)
                // The change notice covers any lead time already reached at the new time,
                // so those reminders are not sent again right behind it.
                for (off in Reminders.OFFSETS) {
                    if (ch.new.netMillis - off <= now) fired[Reminders.key(ch.new.id, off)] = ch.new.netMillis
                }
            }
            Store.saveFired(c, fired)
        }
        true
    }

    /** Sends due reminders, sets the next alarm, redraws the widget. Cheap; no network. */
    fun apply(c: Context) = synchronized(lock) {
        val now = System.currentTimeMillis()
        val list = Store.launches(c)
        val enabled = Store.enabledOffsets(c)

        val fired = Store.fired(c)
        for (d in Reminders.due(list, enabled, fired, now)) {
            Notifier.remind(c, d.launch, now)
            fired[Reminders.key(d.launch.id, d.offset)] = d.launch.netMillis
        }
        val ids = list.map { it.id }.toSet()
        fired.keys.retainAll { it.substringBefore('|') in ids }
        Store.saveFired(c, fired)

        val next = Times.next(list, now)
        val recheck = if (next != null && next.timeKnown && next.netMillis - now < CLOSE_WINDOW) {
            now + CLOSE_RECHECK
        } else null
        val wake = listOfNotNull(Reminders.nextFire(list, enabled, now), recheck).minOrNull()
        setAlarm(c, ACTION_WAKE, wake, wakeup = true)

        ensureJob(c)
        LaunchWidget.updateAll(c)
    }

    /** One alarm per action; null cancels it. */
    fun setAlarm(c: Context, action: String, at: Long?, wakeup: Boolean) {
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            c, 0, Intent(c, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (at == null) {
            am.cancel(pi)
            return
        }
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        when {
            wakeup && exact -> am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            wakeup -> am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            // Widget redraws need not wake the phone; they land when it next wakes.
            exact -> am.setExact(AlarmManager.RTC, at, pi)
            else -> am.set(AlarmManager.RTC, at, pi)
        }
    }

    private fun ensureJob(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(c, RefreshJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(JOB_PERIOD)
                .setPersisted(true)
                .build()
        )
    }
}
