package app.vandilaunch

import kotlin.math.abs

/** Decides which reminders are due. Pure: no Android classes, so it is unit tested. */
object Reminders {
    /** Lead times offered, longest first. */
    val OFFSETS = longArrayOf(24 * Times.HOUR, Times.HOUR, 10 * Times.MIN)

    /** A launch that slips at least this much gets its already-sent reminders again. */
    const val MOVED = 30 * Times.MIN

    /** A slip of at least this much, for a launch this close, is announced on its own. */
    const val CHANGE_MIN = 5 * Times.MIN
    const val CHANGE_HORIZON = 48 * Times.HOUR

    data class Due(val launch: Launch, val offset: Long)
    data class Change(val old: Launch, val new: Launch)

    fun key(id: String, offset: Long) = "$id|$offset"

    private fun remindable(l: Launch, now: Long) = l.timeKnown && !l.finished && l.netMillis > now

    /**
     * Reminders to send right now. [fired] maps [key] to the launch time the reminder was sent
     * for. At most one per launch: the shortest lead time that has been reached, so a launch
     * first seen two hours out gets "in 2h", not a stale 24h notice as well.
     */
    fun due(list: List<Launch>, enabled: Set<Long>, fired: Map<String, Long>, now: Long): List<Due> =
        list.filter { remindable(it, now) }.mapNotNull { l ->
            val offset = enabled.filter { l.netMillis - it <= now }.minOrNull() ?: return@mapNotNull null
            val sentFor = fired[key(l.id, offset)]
            if (sentFor == null || abs(l.netMillis - sentFor) >= MOVED) Due(l, offset) else null
        }

    /** When the next reminder comes due, or null if none is pending. */
    fun nextFire(list: List<Launch>, enabled: Set<Long>, now: Long): Long? =
        list.filter { remindable(it, now) }
            .flatMap { l -> enabled.map { l.netMillis - it } }
            .filter { it > now }
            .minOrNull()

    /** Launches that were close and had a real time, and have now moved or lost it. */
    fun changes(old: List<Launch>, new: List<Launch>, now: Long): List<Change> {
        val before = old.associateBy { it.id }
        return new.mapNotNull { n ->
            val o = before[n.id] ?: return@mapNotNull null
            val wasClose = o.timeKnown && o.netMillis > now - Times.LIVE_WINDOW &&
                o.netMillis - now < CHANGE_HORIZON
            val moved = !n.timeKnown || abs(n.netMillis - o.netMillis) >= CHANGE_MIN
            if (wasClose && moved && !n.finished) Change(o, n) else null
        }
    }
}
