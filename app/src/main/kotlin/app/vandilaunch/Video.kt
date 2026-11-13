package app.vandilaunch

import java.net.URI

/** Working out how to show a webcast link inside the app. Pure, so it is unit tested. */
object Video {
    private val ID = Regex("[A-Za-z0-9_-]{6,20}")

    /** The video id if [url] is a YouTube video link, else null (searches, channels, other sites). */
    fun youtubeId(url: String): String? {
        val u = try {
            URI(url)
        } catch (e: Exception) {
            return null
        }
        val host = u.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val path = u.path ?: ""
        val id = when (host) {
            "youtu.be" -> path.trim('/').substringBefore('/')
            "youtube.com", "youtube-nocookie.com" -> when {
                path == "/watch" -> u.query?.split('&')?.firstOrNull { it.startsWith("v=") }?.substring(2)
                path.startsWith("/live/") || path.startsWith("/embed/") || path.startsWith("/shorts/") ->
                    path.split('/').getOrNull(2)
                else -> null
            }
            else -> null
        }
        return id?.takeIf { ID.matches(it) }
    }

    /** A page that is nothing but the YouTube player, filling the view. */
    fun embedHtml(id: String): String =
        """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000}iframe{border:0;width:100%;height:100%}</style></head>
<body><iframe src="https://www.youtube.com/embed/$id?autoplay=1&playsinline=1"
 allow="autoplay; encrypted-media; picture-in-picture; fullscreen" allowfullscreen
 referrerpolicy="strict-origin-when-cross-origin"></iframe></body></html>"""
}
