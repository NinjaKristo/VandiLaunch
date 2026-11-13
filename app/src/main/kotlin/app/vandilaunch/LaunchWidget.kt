package app.vandilaunch

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import java.time.ZoneId
import java.util.Locale
import kotlin.concurrent.thread

/**
 * The 1x1 home screen widget. Days out it leads with the date; on launch day the
 * countdown moves to the top.
 */
class LaunchWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val c = context.applicationContext
        render(c, manager, ids)

        // A widget added before the app was ever opened, or one whose background refresh
        // has been starved, would otherwise sit on old data.
        if (System.currentTimeMillis() - Store.fetchedAt(c) > STALE) {
            val pending = goAsync()
            thread {
                try {
                    if (Sync.refresh(c, quick = true)) Sync.apply(c)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    companion object {
        private const val STALE = 3 * Times.HOUR

        fun updateAll(c: Context) {
            val manager = AppWidgetManager.getInstance(c)
            val ids = manager.getAppWidgetIds(ComponentName(c, LaunchWidget::class.java))
            if (ids.isNotEmpty()) render(c, manager, ids)
        }

        private fun render(c: Context, manager: AppWidgetManager, ids: IntArray) {
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val launch = Times.next(Store.launches(c), now)
            val count = launch?.let { Times.count(it, now, zone) }

            val views = if (launch != null && count != null && count.close) {
                close(c, launch, count, now, zone)
            } else {
                far(c, launch, count as Times.Label?, zone)
            }
            views.setOnClickPendingIntent(
                R.id.root,
                PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
            Sync.setAlarm(c, Sync.ACTION_TICK, count?.let { maxOf(it.nextChange, now + 1000) }, wakeup = false)
            manager.updateAppWidget(ids, views)
        }

        /** Date, day, time, then a small "4 days". */
        private fun far(c: Context, l: Launch?, count: Times.Label?, zone: ZoneId): RemoteViews {
            val views = RemoteViews(c.packageName, R.layout.widget)
            if (l == null) {
                views.setTextViewText(R.id.date, "–")
                views.setTextViewText(R.id.day, c.getString(R.string.widget_none_day))
                views.setTextViewText(R.id.time, c.getString(R.string.widget_none_time))
                views.setTextViewText(R.id.count, "")
            } else {
                views.setTextViewText(R.id.date, Times.monthDay(l, zone))
                views.setTextViewText(R.id.day, Times.dayName(l, zone).uppercase(Locale.US))
                views.setTextViewText(R.id.time, Ui.time(c, l, zone))
                // With only a month known the time line already says TBD.
                views.setTextViewText(R.id.count, if (l.dateKnown) count?.text.orEmpty() else "")
            }
            return views
        }

        /** Countdown on top, then the time, then a small date. */
        private fun close(c: Context, l: Launch, count: Times.Count, now: Long, zone: ZoneId): RemoteViews {
            val views = RemoteViews(c.packageName, R.layout.widget_close)
            when (count) {
                is Times.Label -> {
                    views.setViewVisibility(R.id.chrono, View.GONE)
                    views.setViewVisibility(R.id.count, View.VISIBLE)
                    views.setTextViewText(R.id.count, count.text)
                }
                is Times.Ticking -> {
                    views.setViewVisibility(R.id.count, View.GONE)
                    views.setViewVisibility(R.id.chrono, View.VISIBLE)
                    // Chronometer runs on the uptime clock, not the wall clock.
                    val base = SystemClock.elapsedRealtime() + (count.net - now)
                    views.setChronometer(R.id.chrono, base, null, true)
                    views.setChronometerCountDown(R.id.chrono, true)
                }
            }
            views.setTextViewText(R.id.time, Ui.time(c, l, zone))
            views.setTextViewText(R.id.date, Times.dateLine(l, zone))
            return views
        }
    }
}
