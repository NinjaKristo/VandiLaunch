package app.vandilaunch

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** All date, time and countdown formatting. Pure: no Android classes, so it is unit tested. */
object Times {
    const val MIN = 60_000L
    const val HOUR = 60 * MIN
    const val DAY = 24 * HOUR

    /** How long after T-0 a launch still counts as the current one. */
    const val LIVE_WINDOW = 30 * MIN

    /** The widget switches from whole hours to a ticking clock this long before T-0. */
    const val TICK_FROM = 5 * HOUR

    private val DOW = DateTimeFormatter.ofPattern("EEE", Locale.US)
    private val DAY_NAME = DateTimeFormatter.ofPattern("EEEE", Locale.US)
    private val MONTH = DateTimeFormatter.ofPattern("MMM", Locale.US)
    private val CLOCK = DateTimeFormatter.ofPattern("h:mm", Locale.US)

    /** What the small countdown shows, and when it next needs redrawing. */
    sealed interface Count {
        val nextChange: Long

        /** Launch day or later: the countdown, not the date, is the headline. */
        val close: Boolean
    }

    /** Static text such as "3 days", "7 hours", "NOW" or "TBD". */
    data class Label(val text: String, override val nextChange: Long, override val close: Boolean = false) : Count

    /** A live H:MM:SS clock counting down to [net]. */
    data class Ticking(val net: Long, override val nextChange: Long) : Count {
        override val close: Boolean get() = true
    }

    /** Launches still worth showing, soonest first. */
    fun upcoming(list: List<Launch>, now: Long): List<Launch> =
        list.filter { !it.finished && it.netMillis > now - LIVE_WINDOW }.sortedBy { it.netMillis }

    fun next(list: List<Launch>, now: Long): Launch? = upcoming(list, now).firstOrNull()

    /**
     * Placeholder times ("sometime in November") are stored as a UTC instant, so reading them
     * in local time can land on the wrong day or month. Only real clock times get localised.
     */
    private fun at(l: Launch, zone: ZoneId): ZonedDateTime =
        Instant.ofEpochMilli(l.netMillis).atZone(if (l.timeKnown) zone else ZoneOffset.UTC)

    /**
     * Launches with no date yet, counted per month in time order: "3x NOVEMBER", "4x DECEMBER".
     */
    fun undatedByMonth(list: List<Launch>): List<String> =
        list.filter { !it.dateKnown }
            .sortedBy { it.netMillis }
            .groupBy { YearMonth.from(Instant.ofEpochMilli(it.netMillis).atZone(ZoneOffset.UTC)) }
            .map { (month, launches) ->
                "${launches.size}x ${month.month.getDisplayName(TextStyle.FULL, Locale.US).uppercase(Locale.US)}"
            }

    /** "Dec 2", or just "Dec" when only the month is known. */
    fun monthDay(l: Launch, zone: ZoneId): String {
        val z = at(l, zone)
        return if (l.dateKnown) "${MONTH.format(z)} ${z.dayOfMonth}" else MONTH.format(z)
    }

    /** "Friday", or "" when the day is not known. */
    fun dayName(l: Launch, zone: ZoneId): String = if (l.dateKnown) DAY_NAME.format(at(l, zone)) else ""

    /** "9:04", or "TBD". */
    fun hourMinute(l: Launch, zone: ZoneId): String = if (l.timeKnown) CLOCK.format(at(l, zone)) else "TBD"

    /** "pm", or "" when the time is not known. */
    fun amPm(l: Launch, zone: ZoneId): String = if (l.timeKnown) amPm(at(l, zone)) else ""

    private fun amPm(z: ZonedDateTime) = if (z.hour < 12) "am" else "pm"

    /** One-line form for notifications: "Fri Dec 2", or "Dec". */
    fun dateLine(l: Launch, zone: ZoneId): String =
        if (l.dateKnown) "${DOW.format(at(l, zone))} ${monthDay(l, zone)}" else monthDay(l, zone)

    /** "9:04pm", or "TBD". */
    fun timeLine(l: Launch, zone: ZoneId): String = hourMinute(l, zone) + amPm(l, zone)

    fun whenLine(l: Launch, zone: ZoneId): String = "${dateLine(l, zone)} ${timeLine(l, zone)}"

    fun clock(millis: Long, zone: ZoneId): String {
        val z = Instant.ofEpochMilli(millis).atZone(zone)
        return CLOCK.format(z) + amPm(z)
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"

    /**
     * The widget countdown: whole days until launch day, whole hours on launch day,
     * and a ticking clock for the last five hours.
     */
    fun count(l: Launch, now: Long, zone: ZoneId): Count {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val midnight = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        if (!l.dateKnown) return Label("TBD", midnight)

        val launchDay: LocalDate = at(l, zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, launchDay)
        if (!l.timeKnown) return Label(if (days <= 0) "TODAY" else plural(days, "day"), midnight)

        val rem = l.netMillis - now
        if (rem <= 0) return Label("NOW", l.netMillis + LIVE_WINDOW, close = true)
        // Checked before the day rule so a launch just after midnight still gets its clock.
        if (rem <= TICK_FROM) return Ticking(l.netMillis, l.netMillis)
        if (days <= 0) {
            val hours = rem / HOUR
            var change = l.netMillis - hours * HOUR
            if (change <= now) change = l.netMillis - (hours - 1) * HOUR
            return Label(plural(hours, "hour"), change, close = true)
        }
        return Label(plural(days, "day"), minOf(midnight, l.netMillis - TICK_FROM))
    }

    /** Full countdown for the app screen: "3 days 04:05:06" or "4:05:06". */
    fun longCountdown(rem: Long): String {
        val s = maxOf(rem, 0) / 1000
        val d = s / 86_400
        val h = (s % 86_400) / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (d > 0) String.format(Locale.US, "%s %02d:%02d:%02d", plural(d, "day"), h, m, sec)
        else String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    }

    /** Rough wording for notifications: "24h", "1h", "10 min". */
    fun roughly(rem: Long): String =
        if (rem >= 55 * MIN) "${(rem + 30 * MIN) / HOUR}h" else "${maxOf(1, (rem + MIN / 2) / MIN)} min"
}
