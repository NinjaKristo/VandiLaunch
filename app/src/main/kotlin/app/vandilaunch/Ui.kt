package app.vandilaunch

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.widget.ImageView
import android.widget.TextView
import android.view.View
import java.time.ZoneId

/** Small pieces of styled text shared by the screen and the widget. */
object Ui {
    /** "7:14" with a smaller, grey "pm" after it; plain "TBD" when the time is unknown. */
    fun time(c: Context, l: Launch, zone: ZoneId): CharSequence {
        val s = SpannableStringBuilder(Times.hourMinute(l, zone))
        val suffix = Times.amPm(l, zone)
        if (suffix.isNotEmpty()) {
            val start = s.length
            s.append(suffix)
            s.setSpan(RelativeSizeSpan(0.5f), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(ForegroundColorSpan(c.getColor(R.color.muted)), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return s
    }

    /** "Falcon 9" followed by a smaller mission name; led by the provider's name if it has no logo. */
    fun ident(l: Launch, withProvider: Boolean): CharSequence {
        val s = SpannableStringBuilder()
        if (withProvider && l.provider.isNotBlank()) s.append(l.provider).append(" · ")
        s.append(l.rocket.ifBlank { l.mission })
        if (l.rocket.isNotBlank() && l.mission.isNotBlank()) {
            val start = s.length
            s.append("  ").append(l.mission)
            s.setSpan(RelativeSizeSpan(0.8f), start, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return s
    }

    /** Fills the faded "who is launching what" line: logo if there is one, the name if not. */
    fun bindIdent(logo: ImageView, text: TextView, l: Launch) {
        val url = l.logoUrl
        logo.tag = url
        logo.contentDescription = l.provider
        val ready = url?.let { Logos.cached(it) }
        if (ready != null) logo.setImageBitmap(ready)
        logo.visibility = if (ready != null) View.VISIBLE else View.GONE
        text.text = ident(l, withProvider = ready == null)
        if (url != null && ready == null) {
            Logos.load(logo.context, url) { bitmap ->
                // The view may have been reused for another launch while this was loading.
                if (logo.tag == url) {
                    logo.setImageBitmap(bitmap)
                    logo.visibility = View.VISIBLE
                    text.text = ident(l, withProvider = false)
                }
            }
        }
    }
}
