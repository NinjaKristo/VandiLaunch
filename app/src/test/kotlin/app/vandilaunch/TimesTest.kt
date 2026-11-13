package app.vandilaunch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TimesTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, zone).toInstant().toEpochMilli()

    private fun launch(net: Long, precision: Int = 1, statusId: Int = 1) = Launch(
        id = "a", name = "Falcon 9 Block 5 | USSF-317", provider = "SpaceX", rocket = "Falcon 9",
        netMillis = net, precision = precision, statusId = statusId, status = "Go for Launch",
        pad = "Space Launch Complex 4E", slug = "falcon-9-block-5-ussf-317",
    )

    // Wed 2 Dec 2026, 9:04pm Pacific.
    private val net = at(2026, 12, 2, 21, 4)
    private val l = launch(net)

    @Test
    fun formatsDateAndTimeInPieces() {
        assertEquals("12•2", Times.monthDay(l, zone))
        assertEquals("Wednesday", Times.dayName(l, zone))
        assertEquals("9:04", Times.hourMinute(l, zone))
        assertEquals("pm", Times.amPm(l, zone))
        assertEquals("am", Times.amPm(launch(at(2026, 12, 2, 4, 30)), zone))
    }

    @Test
    fun formatsOneLineForNotifications() {
        assertEquals("WED-12•2", Times.dateLine(l, zone))
        assertEquals("9:04pm", Times.timeLine(l, zone))
        assertEquals("WED-12•2 9:04pm", Times.whenLine(l, zone))
        assertEquals("12:30am", Times.clock(at(2026, 12, 2, 0, 30), zone))
        assertEquals("12:00pm", Times.clock(at(2026, 12, 2, 12, 0), zone))
    }

    @Test
    fun countsDaysUntilLaunchDay() {
        val c = Times.count(l, at(2026, 11, 29, 23, 0), zone) as Times.Label
        assertEquals("3 days", c.text)
        assertFalse(c.close)
        assertEquals(at(2026, 11, 30, 0, 0), c.nextChange)
        // Late the night before is still a day away, even though under 24h remain.
        assertEquals("1 day", (Times.count(l, at(2026, 12, 1, 23, 59), zone) as Times.Label).text)
    }

    @Test
    fun countsHoursOnLaunchDay() {
        val c = Times.count(l, at(2026, 12, 2, 0, 0), zone) as Times.Label
        assertEquals("21 hours", c.text)
        assertTrue(c.close)
        assertEquals(net - 21 * Times.HOUR, c.nextChange)
        val six = Times.count(l, net - 6 * Times.HOUR, zone) as Times.Label
        assertEquals("6 hours", six.text)
        assertEquals(net - 5 * Times.HOUR, six.nextChange)
        assertEquals("5 hours", (Times.count(l, net - 5 * Times.HOUR - 1, zone) as Times.Label).text)
    }

    @Test
    fun ticksForTheLastFiveHours() {
        assertEquals(Times.Ticking(net, net), Times.count(l, net - 5 * Times.HOUR, zone))
        assertEquals(Times.Ticking(net, net), Times.count(l, net - 1, zone))
        assertTrue(Times.count(l, net - 1, zone).close)
    }

    @Test
    fun ticksAcrossMidnightInsteadOfShowingADay() {
        val early = launch(at(2026, 12, 3, 0, 30))
        assertEquals(
            Times.Ticking(early.netMillis, early.netMillis),
            Times.count(early, at(2026, 12, 2, 23, 0), zone),
        )
    }

    @Test
    fun dayCountHandsOverToTheClockBeforeMidnightWhenNeeded() {
        val early = launch(at(2026, 12, 3, 2, 0))
        val c = Times.count(early, at(2026, 12, 2, 20, 0), zone) as Times.Label
        assertEquals("1 day", c.text)
        assertEquals(early.netMillis - Times.TICK_FROM, c.nextChange)
    }

    @Test
    fun showsNowAfterLiftoffThenMovesOn() {
        val c = Times.count(l, net + Times.MIN, zone) as Times.Label
        assertEquals("NOW", c.text)
        assertTrue(c.close)
        assertEquals(net + Times.LIVE_WINDOW, c.nextChange)

        val later = launch(net + Times.DAY).copy(id = "b")
        assertEquals(l, Times.next(listOf(later, l), net + Times.MIN))
        assertEquals(later, Times.next(listOf(later, l), net + Times.LIVE_WINDOW))
        assertNull(Times.next(listOf(l), net + Times.LIVE_WINDOW))
    }

    @Test
    fun skipsFinishedLaunches() {
        val later = launch(net + Times.DAY).copy(id = "b")
        assertEquals(later, Times.next(listOf(launch(net, statusId = 3), later), net - Times.HOUR))
    }

    @Test
    fun vagueLaunchesShowNoFakeTime() {
        // Launch Library stores "November" as the last day of the month, midnight UTC,
        // which is still the 29th in California.
        val month = launch(ZonedDateTime.parse("2026-11-30T00:00:00Z").toInstant().toEpochMilli(), precision = 7)
        assertEquals("NOV", Times.monthDay(month, zone))
        assertEquals("", Times.dayName(month, zone))
        assertEquals("TBD", Times.hourMinute(month, zone))
        assertEquals("", Times.amPm(month, zone))
        assertEquals("NOV TBD", Times.whenLine(month, zone))
        val c = Times.count(month, at(2026, 11, 13, 9, 0), zone) as Times.Label
        assertEquals("TBD", c.text)
        assertFalse(c.close)

        val day = launch(ZonedDateTime.parse("2026-11-30T00:00:00Z").toInstant().toEpochMilli(), precision = 3)
        assertEquals("11•30", Times.monthDay(day, zone))
        assertEquals("Monday", Times.dayName(day, zone))
        assertEquals("MON-11•30 TBD", Times.whenLine(day, zone))
        assertEquals("17 days", (Times.count(day, at(2026, 11, 13, 9, 0), zone) as Times.Label).text)
        assertEquals("TODAY", (Times.count(day, at(2026, 11, 30, 9, 0), zone) as Times.Label).text)
    }

    @Test
    fun longCountdownAndRoughWording() {
        assertEquals("3 days 04:05:06", Times.longCountdown(3 * Times.DAY + 4 * Times.HOUR + 5 * Times.MIN + 6_000))
        assertEquals("1 day 00:00:00", Times.longCountdown(Times.DAY))
        assertEquals("4:05:06", Times.longCountdown(4 * Times.HOUR + 5 * Times.MIN + 6_999))
        assertEquals("24h", Times.roughly(24 * Times.HOUR - 2_000))
        assertEquals("1h", Times.roughly(Times.HOUR))
        assertEquals("10 min", Times.roughly(10 * Times.MIN - 3_000))
        assertEquals("20h", Times.roughly(20 * Times.HOUR))
    }

    @Test
    fun derivedNames() {
        assertEquals("USSF-317", l.mission)
        assertEquals("https://spacelaunchnow.me/launch/falcon-9-block-5-ussf-317", l.detailsUrl)
        assertEquals("https://x.com/live", l.copy(liveUrl = "https://x.com/live").watchUrl)
    }
}
