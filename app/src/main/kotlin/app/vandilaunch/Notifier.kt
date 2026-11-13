package app.vandilaunch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import java.time.ZoneId

object Notifier {
    private const val CHANNEL = "launches"

    fun ensureChannel(c: Context) {
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, c.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun remind(c: Context, l: Launch, now: Long) {
        post(
            c, l,
            title = "Vandenberg launch in ${Times.roughly(l.netMillis - now)}",
            text = "${l.mission} · ${l.provider} · ${Times.whenLine(l, ZoneId.systemDefault())}",
        )
    }

    fun changed(c: Context, l: Launch) {
        post(
            c, l,
            title = "Launch time changed",
            text = if (l.timeKnown) "${l.mission} now ${Times.whenLine(l, ZoneId.systemDefault())}"
            else "${l.mission} postponed, new time TBD",
        )
    }

    private fun post(c: Context, l: Launch, title: String, text: String) {
        ensureChannel(c)
        val now = System.currentTimeMillis()
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_rocket)
            .setColor(c.getColor(R.color.accent))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_EVENT)
            .setAutoCancel(true)
        if (l.timeKnown && l.netMillis > now) {
            // A live countdown in the notification itself; it clears an hour after T-0.
            b.setWhen(l.netMillis).setShowWhen(true)
                .setUsesChronometer(true).setChronometerCountDown(true)
                .setTimeoutAfter(l.netMillis - now + Times.HOUR)
        }
        l.liveUrl?.let { url ->
            val watch = PendingIntent.getActivity(
                c, l.id.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(c, R.drawable.ic_stat_rocket), c.getString(R.string.watch_live), watch
                ).build()
            )
        }
        // One notification per launch: a newer notice replaces the older one.
        c.getSystemService(NotificationManager::class.java).notify(l.id.hashCode(), b.build())
    }
}
