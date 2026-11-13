package app.vandilaunch

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Parsing of Launch Library 2 responses, and the app's own compact cache format. */
object LaunchJson {
    const val VANDENBERG_LOCATION_ID = 11

    /** Parses a `launches/upcoming/` response. Throws if the body is not a launch list. */
    fun parseList(body: String): List<Launch> {
        val results = JSONObject(body).getJSONArray("results")
        val out = ArrayList<Launch>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val pad = o.optJSONObject("pad")
            // The query already filters by location; this guards against a changed API default.
            val locationId = pad?.optJSONObject("location")?.optInt("id", VANDENBERG_LOCATION_ID)
            if (locationId != null && locationId != VANDENBERG_LOCATION_ID) continue
            val net = try {
                Instant.parse(str(o, "net")).toEpochMilli()
            } catch (e: Exception) {
                continue
            }
            val status = o.optJSONObject("status")
            val provider = o.optJSONObject("launch_service_provider")
            out += Launch(
                id = str(o, "id"),
                name = str(o, "name"),
                provider = str(provider, "name"),
                rocket = str(o.optJSONObject("rocket")?.optJSONObject("configuration"), "name"),
                netMillis = net,
                precision = o.optJSONObject("net_precision")?.optInt("id", 99) ?: 99,
                statusId = status?.optInt("id", 0) ?: 0,
                status = str(status, "name"),
                pad = str(pad, "name"),
                slug = str(o, "slug"),
                providerId = provider?.optInt("id", 0) ?: 0,
            )
        }
        return out.sortedBy { it.netMillis }
    }

    /** Pulls (live webcast url, info page url) out of a single detailed launch response. */
    fun parseLinks(body: String): Pair<String?, String?> {
        val o = JSONObject(body)
        return firstUrl(o.optJSONArray("vid_urls"), preferType = "Official") to
            firstUrl(o.optJSONArray("info_urls"), preferType = "Official")
    }

    /** Pulls the logo image url out of an `agencies/{id}/` response. */
    fun parseAgencyLogo(body: String): String? =
        str(JSONObject(body).optJSONObject("logo"), "image_url").takeIf { it.startsWith("https://") }

    private fun firstUrl(arr: JSONArray?, preferType: String): String? {
        if (arr == null) return null
        var fallback: String? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = str(o, "url")
            if (!url.startsWith("https://") && !url.startsWith("http://")) continue
            if (str(o.optJSONObject("type"), "name").startsWith(preferType, ignoreCase = true)) return url
            if (fallback == null) fallback = url
        }
        return fallback
    }

    fun toCache(list: List<Launch>): String {
        val arr = JSONArray()
        for (l in list) {
            arr.put(
                JSONObject()
                    .put("id", l.id).put("name", l.name).put("provider", l.provider)
                    .put("rocket", l.rocket).put("net", l.netMillis).put("precision", l.precision)
                    .put("statusId", l.statusId).put("status", l.status).put("pad", l.pad)
                    .put("slug", l.slug).put("live", l.liveUrl ?: JSONObject.NULL)
                    .put("info", l.infoUrl ?: JSONObject.NULL)
                    .put("providerId", l.providerId).put("logo", l.logoUrl ?: JSONObject.NULL)
            )
        }
        return arr.toString()
    }

    fun fromCache(s: String?): List<Launch> {
        if (s.isNullOrEmpty()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Launch(
                    id = str(o, "id"), name = str(o, "name"), provider = str(o, "provider"),
                    rocket = str(o, "rocket"), netMillis = o.getLong("net"),
                    precision = o.getInt("precision"), statusId = o.getInt("statusId"),
                    status = str(o, "status"), pad = str(o, "pad"), slug = str(o, "slug"),
                    liveUrl = str(o, "live").ifEmpty { null },
                    infoUrl = str(o, "info").ifEmpty { null },
                    // Absent in caches written by version 1.0.
                    providerId = o.optInt("providerId", 0),
                    logoUrl = str(o, "logo").ifEmpty { null },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** optString that treats JSON null and a missing object as "". */
    private fun str(o: JSONObject?, key: String): String =
        if (o == null || o.isNull(key)) "" else o.optString(key, "")
}
