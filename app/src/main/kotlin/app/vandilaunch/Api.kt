package app.vandilaunch

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Launch Library 2 (thespacedevs.com): free, keyless, limited to 15 requests an hour. */
object Api {
    private const val ROOT = "https://ll.thespacedevs.com/2.3.0/"
    private const val BASE = ROOT + "launches/"
    private const val LIST =
        BASE + "upcoming/?location__ids=${LaunchJson.VANDENBERG_LOCATION_ID}&limit=10&ordering=net"

    /** Webcast links are only published close to launch, so only look this far ahead. */
    private const val LINK_HORIZON = 48 * Times.HOUR

    /** Logo lookups cost a request each; a refresh never spends more than this many. */
    private const val MAX_LOGO_LOOKUPS = 2

    /**
     * Fetches upcoming Vandenberg launches. [quick] is for broadcast receivers, which get
     * about ten seconds in total: short timeouts, and the extra lookups only if there is time.
     */
    fun fetch(previous: List<Launch>, quick: Boolean): List<Launch> {
        val started = System.currentTimeMillis()
        val body = if (quick) get(LIST, 3000, 3500) else get(LIST, 10_000, 15_000)
        val known = previous.associateBy { it.id }
        val logos = HashMap<Int, String>()
        for (p in previous) if (p.logoUrl != null) logos[p.providerId] = p.logoUrl
        var list = LaunchJson.parseList(body).map { l ->
            val old = known[l.id]
            l.copy(liveUrl = old?.liveUrl, infoUrl = old?.infoUrl, logoUrl = logos[l.providerId])
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

        if (!quick) {
            val missing = Times.upcoming(list, now)
                .filter { it.logoUrl == null && it.providerId != 0 }
                .map { it.providerId }.distinct().take(MAX_LOGO_LOOKUPS)
            for (agency in missing) {
                try {
                    val logo = LaunchJson.parseAgencyLogo(get("${ROOT}agencies/$agency/", 10_000, 15_000))
                        ?: continue
                    list = list.map { if (it.providerId == agency) it.copy(logoUrl = logo) else it }
                } catch (e: Exception) {
                    // No logo this time; the provider's name is shown instead.
                }
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
            c.setRequestProperty("User-Agent", "android-launch-countdown/1.1")
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
