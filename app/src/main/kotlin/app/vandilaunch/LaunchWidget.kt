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
import kotlin.concurrent.thread

/** The 1x1 home screen widget: date, time, and a countdown to the next launch. */
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
            val views = RemoteViews(c.packageName, R.layout.widget)

            views.setOnClickPendingIntent(
                R.id.root,
                PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )

            if (launch == null) {
                views.setTextViewText(R.id.date, c.getString(R.string.widget_none_top))
                views.setTextViewText(R.id.time, c.getString(R.string.widget_none_bottom))
                showLabel(c, views, "–", soon = false)
                Sync.setAlarm(c, Sync.ACTION_TICK, null, wakeup = false)
            } else {
                views.setTextViewText(R.id.date, Times.dateLine(launch, zone))
                views.setTextViewText(R.id.time, Times.timeLine(launch, zone))
                val count = Times.count(launch, now, zone)
                when (count) {
                    is Times.Label -> showLabel(c, views, count.text, soon = count.text == "NOW")
                    is Times.Ticking -> {
                        views.setViewVisibility(R.id.count, View.GONE)
                        views.setViewVisibility(R.id.chrono, View.VISIBLE)
                        // Chronometer runs on the uptime clock, not the wall clock.
                        val base = SystemClock.elapsedRealtime() + (count.net - now)
                        views.setChronometer(R.id.chrono, base, null, true)
                        views.setChronometerCountDown(R.id.chrono, true)
                    }
                }
                Sync.setAlarm(c, Sync.ACTION_TICK, maxOf(count.nextChange, now + 1000), wakeup = false)
            }
            manager.updateAppWidget(ids, views)
        }

        private fun showLabel(c: Context, views: RemoteViews, text: String, soon: Boolean) {
            views.setViewVisibility(R.id.chrono, View.GONE)
            views.setViewVisibility(R.id.count, View.VISIBLE)
            views.setTextViewText(R.id.count, text)
            views.setTextColor(R.id.count, c.getColor(if (soon) R.color.accent else R.color.text))
        }
    }
}
