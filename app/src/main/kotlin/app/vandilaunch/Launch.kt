package app.vandilaunch

import java.net.URLEncoder

/** One launch, trimmed to what the app shows. Times are epoch millis (UTC). */
data class Launch(
    val id: String,
    val name: String,
    val provider: String,
    val rocket: String,
    val netMillis: Long,
    /** Launch Library net_precision id: 0 second, 1 minute, 2 hour, 3 day, 7+ month or vaguer. */
    val precision: Int,
    /** Launch Library status id: 1 go, 2 TBD, 3 success, 4 failure, 5 hold, 6 in flight, 7 partial failure, 8 TBC. */
    val statusId: Int,
    val status: String,
    val pad: String,
    val slug: String,
    val liveUrl: String? = null,
    val infoUrl: String? = null,
) {
    /** The clock time is real, not a placeholder. Only these get countdowns and reminders. */
    val timeKnown: Boolean get() = precision in 0..2

    /** The calendar day is real; vaguer launches only know the month or quarter. */
    val dateKnown: Boolean get() = precision in 0..6

    val finished: Boolean get() = statusId == 3 || statusId == 4 || statusId == 7

    /** "Falcon 9 Block 5 | USSF-317" -> "USSF-317". */
    val mission: String get() = name.substringAfter(" | ", name).trim()

    /** "SpaceX · Falcon 9". */
    val who: String get() = listOf(provider, rocket).filter { it.isNotBlank() }.joinToString(" · ")

    val shortPad: String get() = pad.replace("Space Launch Complex ", "SLC-")

    val detailsUrl: String get() = infoUrl ?: "https://spacelaunchnow.me/launch/$slug"

    /** The official webcast when one is listed, otherwise a search that usually finds it. */
    val watchUrl: String
        get() = liveUrl
            ?: ("https://www.youtube.com/results?search_query=" +
                URLEncoder.encode("$mission $provider launch live", "UTF-8"))
}
