package app.vandilaunch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTest {
    @Test
    fun findsTheIdInEveryYoutubeVideoLinkShape() {
        val id = "dQw4w9WgXcQ"
        assertEquals(id, Video.youtubeId("https://www.youtube.com/watch?v=$id"))
        assertEquals(id, Video.youtubeId("https://m.youtube.com/watch?feature=share&v=$id&t=10"))
        assertEquals(id, Video.youtubeId("https://youtu.be/$id?si=abc"))
        assertEquals(id, Video.youtubeId("https://www.youtube.com/live/$id"))
        assertEquals(id, Video.youtubeId("https://www.youtube.com/embed/$id"))
    }

    @Test
    fun otherLinksAreNotYoutubeVideos() {
        assertNull(Video.youtubeId("https://www.youtube.com/results?search_query=launch+live"))
        assertNull(Video.youtubeId("https://www.youtube.com/@spacex"))
        assertNull(Video.youtubeId("https://x.com/i/broadcasts/2lgkMvaRVPMdYR"))
        assertNull(Video.youtubeId("https://evil.example/watch?v=dQw4w9WgXcQ"))
        assertNull(Video.youtubeId("https://www.youtube.com/watch?v=\"><script>"))
        assertNull(Video.youtubeId("not a url"))
    }

    @Test
    fun embedPagePlaysThatVideo() {
        assertTrue(Video.embedHtml("dQw4w9WgXcQ").contains("https://www.youtube.com/embed/dQw4w9WgXcQ?"))
    }

    @Test
    fun readsProviderIdAndAgencyLogo() {
        val list = """{"results":[{"id":"a","name":"Falcon 9 | X","net":"2026-11-18T03:14:00Z",
            "launch_service_provider":{"id":121,"name":"SpaceX"}}]}"""
        assertEquals(121, LaunchJson.parseList(list).single().providerId)

        assertEquals(
            "https://cdn.example/spacex.png",
            LaunchJson.parseAgencyLogo("""{"id":121,"logo":{"id":1,"image_url":"https://cdn.example/spacex.png"}}"""),
        )
        assertNull(LaunchJson.parseAgencyLogo("""{"id":121,"logo":null}"""))
        assertNull(LaunchJson.parseAgencyLogo("""{"id":121,"logo":{"image_url":"file:///etc/passwd"}}"""))
    }

    @Test
    fun cacheKeepsLogoAndReadsOldCachesWithoutOne() {
        val l = Launch(
            id = "a", name = "n", provider = "SpaceX", rocket = "Falcon 9", netMillis = 1, precision = 1,
            statusId = 1, status = "Go", pad = "p", slug = "s", providerId = 121, logoUrl = "https://cdn.example/l.png",
        )
        assertEquals(listOf(l), LaunchJson.fromCache(LaunchJson.toCache(listOf(l))))

        val old = """[{"id":"a","name":"n","provider":"SpaceX","rocket":"Falcon 9","net":1,"precision":1,
            "statusId":1,"status":"Go","pad":"p","slug":"s","live":null,"info":null}]"""
        assertEquals(listOf(l.copy(providerId = 0, logoUrl = null)), LaunchJson.fromCache(old))
    }
}
