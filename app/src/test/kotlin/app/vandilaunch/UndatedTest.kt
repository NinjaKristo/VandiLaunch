package app.vandilaunch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class UndatedTest {
    private fun launch(net: String, precision: Int, id: String = net) = Launch(
        id = id, name = "Falcon 9 | X", provider = "SpaceX", rocket = "Falcon 9",
        netMillis = Instant.parse(net).toEpochMilli(), precision = precision, statusId = 2,
        status = "TBD", pad = "p", slug = "s",
    )

    @Test
    fun countsUndatedLaunchesPerMonthInOrder() {
        val list = listOf(
            launch("2026-12-31T00:00:00Z", 7, "d1"),
            launch("2026-11-30T00:00:00Z", 7, "n1"),
            launch("2026-11-30T00:00:00Z", 7, "n2"),
            launch("2026-12-31T00:00:00Z", 11, "d2"),
            launch("2026-11-30T00:00:00Z", 7, "n3"),
            launch("2026-12-31T00:00:00Z", 11, "d3"),
            launch("2026-12-31T00:00:00Z", 11, "d4"),
        )
        assertEquals(listOf("3x NOVEMBER", "4x DECEMBER"), Times.undatedByMonth(list))
    }

    @Test
    fun datedLaunchesAreNotCounted() {
        val list = listOf(
            launch("2026-11-21T12:30:00Z", 1),
            launch("2026-11-25T00:00:00Z", 3),
            launch("2026-11-30T00:00:00Z", 7),
        )
        assertEquals(listOf("1x NOVEMBER"), Times.undatedByMonth(list))
        assertTrue(Times.undatedByMonth(list.take(2)).isEmpty())
    }

    @Test
    fun sameMonthInDifferentYearsStaysSeparate() {
        val list = listOf(launch("2027-01-31T00:00:00Z", 7), launch("2026-01-31T00:00:00Z", 7))
        assertEquals(listOf("1x JANUARY", "1x JANUARY"), Times.undatedByMonth(list))
    }
}
