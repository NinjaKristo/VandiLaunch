package app.vandilaunch

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/** Alarms, boot, app update and clock changes all land here. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.applicationContext
        if (intent.action == Sync.ACTION_TICK) {
            LaunchWidget.updateAll(c)
            return
        }
        val pending = goAsync()
        thread {
            try {
                // Re-check the schedule before reminding: launch times slip. If the check
                // fails the reminder still goes out from the saved schedule.
                if (intent.action == Sync.ACTION_WAKE) Sync.refresh(c, quick = true)
                Sync.apply(c)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Periodic schedule refresh, run by the system when there is a network. */
class RefreshJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            Sync.refresh(applicationContext)
            Sync.apply(applicationContext)
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
