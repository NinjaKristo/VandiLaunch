package app.vandilaunch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class LaunchJsonTest {
    // Trimmed from a real Launch Library 2.3.0 response.
    private val list = """
        {"count":3,"next":null,"previous":null,"results":[
          {"id":"late","name":"Falcon 9 Block 5 | NROL-195","slug":"falcon-9-block-5-nrol-195",
           "status":{"id":2,"name":"To Be Determined","abbrev":"TBD"},
           "net":"2026-11-30T00:00:00Z","net_precision":{"id":7,"name":"Month","abbrev":"M"},
           "launch_service_provider":{"id":121,"name":"SpaceX","abbrev":"SpX"},
           "rocket":{"id":1,"configuration":{"id":164,"name":"Falcon 9","full_name":"Falcon 9 Block 5"}},
           "pad":{"id":16,"name":"Space Launch Complex 4E","location":{"id":11,"name":"Vandenberg SFB, CA, USA"}}},
          {"id":"7661","name":"Falcon 9 Block 5 | USSF-317","slug":"falcon-9-block-5-ussf-317",
           "status":{"id":1,"name":"Go for Launch","abbrev":"Go"},
           "net":"2026-11-18T03:14:00Z","net_precision":{"id":1,"name":"Minute","abbrev":"MIN"},
           "launch_service_provider":{"id":121,"name":"SpaceX","abbrev":"SpX"},
           "rocket":{"id":1,"configuration":{"id":164,"name":"Falcon 9","full_name":"Falcon 9 Block 5"}},
           "pad":{"id":16,"name":"Space Launch Complex 4E","location":{"id":11,"name":"Vandenberg SFB, CA, USA"}}},
          {"id":"cape","name":"Atlas V | Elsewhere","slug":"elsewhere",
           "status":{"id":1,"name":"Go for Launch"},
           "net":"2026-11-19T03:14:00Z","net_precision":{"id":1},
           "launch_service_provider":{"name":"ULA"},
           "rocket":{"configuration":{"name":"Atlas V"}},
           "pad":{"name":"SLC-41","location":{"id":12,"name":"Cape Canaveral SFS, FL, USA"}}},
          {"id":"sparse","name":"Mystery","slug":null,"status":null,
           "net":"2026-12-31T00:00:00Z","net_precision":null,
           "launch_service_provider":null,"rocket":null,"pad":null},
          {"id":"broken","name":"No time","net":null}
        ]}
    """.trimIndent()

    @Test
    fun parsesVandenbergLaunchesSoonestFirst() {
        val parsed = LaunchJson.parseList(list)
        assertEquals(listOf("7661", "late", "sparse"), parsed.map { it.id })

        val l = parsed[0]
        assertEquals("Falcon 9 Block 5 | USSF-317", l.name)
        assertEquals("SpaceX", l.provider)
        assertEquals("Falcon 9", l.rocket)
        assertEquals(Instant.parse("2026-11-18T03:14:00Z").toEpochMilli(), l.netMillis)
        assertEquals(1, l.precision)
        assertEquals(1, l.statusId)
        assertEquals("Go for Launch", l.status)
        assertEquals("Space Launch Complex 4E", l.pad)
        assertTrue(l.timeKnown)
        assertNull(l.liveUrl)
    }

    @Test
    fun missingFieldsBecomeBlankAndVague() {
        val sparse = LaunchJson.parseList(list).last()
        assertEquals("", sparse.provider)
        assertEquals("", sparse.slug)
        assertEquals("", sparse.status)
        assertTrue(!sparse.timeKnown && !sparse.dateKnown)
    }

    @Test(expected = Exception::class)
    fun rejectsABodyThatIsNotALaunchList() {
        LaunchJson.parseList("""{"detail":"Request was throttled."}""")
    }

    @Test
    fun picksTheOfficialWebcast() {
        val detail = """
            {"id":"7661","vid_urls":[
               {"priority":10,"url":"https://example.com/fan","type":{"id":2,"name":"Unofficial Webcast"}},
               {"priority":10,"url":"javascript:alert(1)","type":{"id":1,"name":"Official Webcast"}},
               {"priority":10,"url":"https://x.com/i/broadcasts/abc","type":{"id":1,"name":"Official Webcast"}}],
             "info_urls":[{"url":"https://www.spacex.com/launches/ussf317","type":{"id":1,"name":"Official Page"}}]}
        """.trimIndent()
        assertEquals(
            "https://x.com/i/broadcasts/abc" to "https://www.spacex.com/launches/ussf317",
            LaunchJson.parseLinks(detail),
        )
        assertEquals(null to null, LaunchJson.parseLinks("""{"id":"x","vid_urls":[],"info_urls":null}"""))
    }

    @Test
    fun cacheRoundTrips() {
        val parsed = LaunchJson.parseList(list)
        val withLinks = parsed.mapIndexed { i, l -> if (i == 0) l.copy(liveUrl = "https://x.com/a", infoUrl = "https://b.c") else l }
        assertEquals(withLinks, LaunchJson.fromCache(LaunchJson.toCache(withLinks)))
        assertTrue(LaunchJson.fromCache(null).isEmpty())
        assertTrue(LaunchJson.fromCache("not json").isEmpty())
    }
}
