package app.vandilaunch

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Launch Library 2 (thespacedevs.com): free, keyless, limited to 15 requests an hour. */
object Api {
    private const val BASE = "https://ll.thespacedevs.com/2.3.0/launches/"
    private const val LIST =
        BASE + "upcoming/?location__ids=${LaunchJson.VANDENBERG_LOCATION_ID}&limit=10&ordering=net"

    /** Webcast links are only published close to launch, so only look this far ahead. */
    private const val LINK_HORIZON = 48 * Times.HOUR

    /**
     * Fetches upcoming Vandenberg launches. [quick] is for broadcast receivers, which get
     * about ten seconds in total: short timeouts, and the link lookup only if there is time.
     */
    fun fetch(previous: List<Launch>, quick: Boolean): List<Launch> {
        val started = System.currentTimeMillis()
        val body = if (quick) get(LIST, 3000, 3500) else get(LIST, 10_000, 15_000)
        val known = previous.associateBy { it.id }
        var list = LaunchJson.parseList(body).map { l ->
            val old = known[l.id]
            if (old == null) l else l.copy(liveUrl = old.liveUrl, infoUrl = old.infoUrl)
        }

        val now = System.currentTimeMillis()
        val next = Times.next(list, now)
        val wanted = next != null && next.timeKnown && next.netMillis - now < LINK_HORIZON
        if (wanted && (!quick || now - started < 2500)) {
            try {
                val url = BASE + next!!.id + "/"
                val (live, info) = LaunchJson.parseLinks(
                    if (quick) get(url, 1500, 2000) else get(url, 10_000, 15_000)
                )
                list = list.map {
                    if (it.id == next.id) it.copy(liveUrl = live ?: it.liveUrl, infoUrl = info ?: it.infoUrl)
                    else it
                }
            } catch (e: Exception) {
                // Links are a nice-to-have; the schedule itself is already in hand.
            }
        }
        return list
    }

    private fun get(url: String, connectMs: Int, readMs: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "android-launch-countdown/1.0")
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
