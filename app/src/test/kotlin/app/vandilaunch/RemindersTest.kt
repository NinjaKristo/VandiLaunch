package app.vandilaunch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemindersTest {
    private val now = 1_800_000_000_000L
    private val all = Reminders.OFFSETS.toSet()
    private val h24 = 24 * Times.HOUR
    private val h1 = Times.HOUR
    private val m10 = 10 * Times.MIN

    private fun launch(net: Long, precision: Int = 1, statusId: Int = 1, id: String = "a") = Launch(
        id = id, name = "Falcon 9 | Test", provider = "SpaceX", rocket = "Falcon 9",
        netMillis = net, precision = precision, statusId = statusId, status = "Go",
        pad = "SLC-4E", slug = "test",
    )

    @Test
    fun nothingDueBeforeTheFirstLeadTime() {
        val l = launch(now + 30 * Times.HOUR)
        assertTrue(Reminders.due(listOf(l), all, emptyMap(), now).isEmpty())
        assertEquals(l.netMillis - h24, Reminders.nextFire(listOf(l), all, now))
    }

    @Test
    fun eachLeadTimeFiresOnce() {
        val l = launch(now + h24)
        assertEquals(listOf(Reminders.Due(l, h24)), Reminders.due(listOf(l), all, emptyMap(), now))
        val fired = mapOf(Reminders.key("a", h24) to l.netMillis)
        assertTrue(Reminders.due(listOf(l), all, fired, now + Times.HOUR).isEmpty())
        assertEquals(l.netMillis - h1, Reminders.nextFire(listOf(l), all, now))

        val atOneHour = l.netMillis - h1
        assertEquals(listOf(Reminders.Due(l, h1)), Reminders.due(listOf(l), all, fired, atOneHour))
    }

    @Test
    fun aLaunchFirstSeenLateGetsOnlyTheNearestReminder() {
        val l = launch(now + 2 * Times.HOUR)
        assertEquals(listOf(Reminders.Due(l, h24)), Reminders.due(listOf(l), all, emptyMap(), now))
        val soon = launch(now + 5 * Times.MIN)
        assertEquals(listOf(Reminders.Due(soon, m10)), Reminders.due(listOf(soon), all, emptyMap(), now))
    }

    @Test
    fun disabledLeadTimesAreIgnored() {
        val l = launch(now + 30 * Times.MIN)
        assertEquals(listOf(Reminders.Due(l, h24)), Reminders.due(listOf(l), setOf(h24), emptyMap(), now))
        assertNull(Reminders.nextFire(listOf(l), setOf(h24), now))
        assertTrue(Reminders.due(listOf(l), emptySet(), emptyMap(), now).isEmpty())
    }

    @Test
    fun aBigSlipRepeatsTheReminderButASmallOneDoesNot() {
        val firedFor = now + 50 * Times.MIN
        val fired = mapOf(Reminders.key("a", h1) to firedFor)
        val nudged = launch(firedFor + 5 * Times.MIN)
        assertTrue(Reminders.due(listOf(nudged), setOf(h1), fired, now + 10 * Times.MIN).isEmpty())

        val slipped = launch(firedFor + h24)
        assertTrue(Reminders.due(listOf(slipped), setOf(h1), fired, now).isEmpty())
        assertEquals(
            listOf(Reminders.Due(slipped, h1)),
            Reminders.due(listOf(slipped), setOf(h1), fired, slipped.netMillis - h1),
        )
    }

    @Test
    fun vagueFinishedAndPastLaunchesNeverRemind() {
        val list = listOf(
            launch(now + Times.HOUR, precision = 7, id = "month"),
            launch(now + Times.HOUR, precision = 3, id = "day"),
            launch(now + Times.HOUR, statusId = 3, id = "done"),
            launch(now - Times.MIN, id = "past"),
        )
        assertTrue(Reminders.due(list, all, emptyMap(), now).isEmpty())
        assertNull(Reminders.nextFire(list, all, now))
    }

    @Test
    fun reportsTimeChangesOnlyForCloseLaunches() {
        val close = launch(now + 3 * Times.HOUR)
        val far = launch(now + 5 * Times.DAY, id = "far")
        val old = listOf(close, far)

        assertTrue(Reminders.changes(old, old, now).isEmpty())
        // Under five minutes is noise.
        assertTrue(Reminders.changes(old, listOf(close.copy(netMillis = close.netMillis + 2 * Times.MIN)), now).isEmpty())
        // Far-off launches move around all the time.
        assertTrue(Reminders.changes(old, listOf(far.copy(netMillis = far.netMillis + Times.DAY)), now).isEmpty())

        val slipped = close.copy(netMillis = close.netMillis + Times.DAY)
        assertEquals(listOf(Reminders.Change(close, slipped)), Reminders.changes(old, listOf(slipped, far), now))

        val scrubbed = close.copy(precision = 7)
        assertEquals(listOf(Reminders.Change(close, scrubbed)), Reminders.changes(old, listOf(scrubbed), now))

        // It launched: not a change of plan.
        assertTrue(Reminders.changes(old, listOf(close.copy(netMillis = now, statusId = 3)), now).isEmpty())
        // New launches have nothing to compare against.
        assertTrue(Reminders.changes(emptyList(), old, now).isEmpty())
    }
}
