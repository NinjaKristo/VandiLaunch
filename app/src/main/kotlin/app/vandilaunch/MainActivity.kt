package app.vandilaunch

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.time.ZoneId
import java.util.concurrent.Executors

/** The only screen: next launch with a live countdown and video, then the ones after it. */
class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var next: Launch? = null
    private var refreshing = false
    private var lastFailed = false

    // Video state. [web] exists only while the video is open.
    private var web: WebView? = null
    private var videoFor: String? = null
    private var videoUrl: String? = null
    private var videoIsPlayerOnly = false

    // Full screen is either the whole web view (OWN) or the page's own player (PAGE).
    private enum class Full { NONE, OWN, PAGE }

    private var full = Full.NONE
    private var pageView: View? = null
    private var pageCallback: WebChromeClient.CustomViewCallback? = null
    private var backCallback: Any? = null

    private val tick = object : Runnable {
        override fun run() {
            showCountdown()
            ui.postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Notifier.ensureChannel(this)

        findViewById<View>(R.id.refresh).setOnClickListener { refresh() }
        findViewById<View>(R.id.watch).setOnClickListener {
            if (web != null) closeVideo() else next?.let { openVideo(it) }
        }
        findViewById<View>(R.id.details).setOnClickListener { next?.let { open(it.detailsUrl) } }
        findViewById<View>(R.id.open_original).setOnClickListener { videoUrl?.let { open(it) } }
        findViewById<View>(R.id.fullscreen).setOnClickListener { enterFull(null, null) }
        findViewById<View>(R.id.full_exit).setOnClickListener { exitFull() }

        val enabled = Store.enabledOffsets(this)
        val boxes = intArrayOf(R.id.remind_24h, R.id.remind_1h, R.id.remind_10m)
        Reminders.OFFSETS.forEachIndexed { i, offset ->
            val box = findViewById<CheckBox>(boxes[i])
            box.isChecked = offset in enabled
            box.setOnCheckedChangeListener { _, on ->
                Store.setOffset(this, offset, on)
                io.execute { Sync.apply(applicationContext) }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
        render()
        ui.post(tick)
        if (System.currentTimeMillis() - Store.fetchedAt(this) > 10 * Times.MIN) refresh()
    }

    override fun onPause() {
        super.onPause()
        web?.onPause()
        ui.removeCallbacks(tick)
    }

    override fun onDestroy() {
        closeVideo()
        io.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        sizeVideo()
    }

    // Android 13+ leaves full screen through the back callback registered in enterFull;
    // this covers the older versions that have no such callback.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Only reached before Android 13.")
    override fun onBackPressed() {
        if (full != Full.NONE) exitFull() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun refresh() {
        if (refreshing) return
        refreshing = true
        render()
        val app = applicationContext
        io.execute {
            val ok = Sync.refresh(app)
            Sync.apply(app)
            runOnUiThread {
                refreshing = false
                lastFailed = !ok
                render()
            }
        }
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val list = Times.upcoming(Store.launches(this), now)
        val fetchedAt = Store.fetchedAt(this)
        val launch = list.firstOrNull()
        next = launch

        // The video belongs to one launch; once that is no longer next, it goes.
        if (web != null && launch?.id != videoFor) closeVideo()

        val message = findViewById<TextView>(R.id.message)
        message.visibility = if (launch == null) View.VISIBLE else View.GONE
        findViewById<View>(R.id.next).visibility = if (launch == null) View.GONE else View.VISIBLE
        if (launch == null) {
            message.setText(
                when {
                    fetchedAt != 0L -> R.string.no_launches
                    lastFailed && !refreshing -> R.string.load_failed
                    else -> R.string.loading
                }
            )
        } else {
            Ui.bindIdent(findViewById(R.id.logo), findViewById(R.id.ident), launch)
            findViewById<TextView>(R.id.date).text = Times.monthDay(launch, zone)
            val day = findViewById<TextView>(R.id.day)
            day.text = Times.dayName(launch, zone)
            day.visibility = if (launch.dateKnown) View.VISIBLE else View.GONE
            findViewById<TextView>(R.id.time).text = Ui.time(this, launch, zone)
            showWatchLabel()
            showCountdown()
        }

        val later = findViewById<LinearLayout>(R.id.later)
        later.removeAllViews()
        val rest = list.drop(1)
        findViewById<View>(R.id.later_label).visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
        for (l in rest) if (l.dateKnown) later.addView(row(l, zone))
        // Launches with no date yet collapse into one row of per-month counts.
        val undated = Times.undatedByMonth(rest)
        if (undated.isNotEmpty()) {
            val tbd = layoutInflater.inflate(R.layout.row_tbd, later, false)
            tbd.findViewById<TextView>(R.id.tbd_months).text = undated.joinToString("\n")
            later.addView(tbd)
        }

        findViewById<TextView>(R.id.footer).text = buildString {
            append(getString(R.string.credit))
            append('\n')
            append(
                when {
                    refreshing -> getString(R.string.updating)
                    fetchedAt == 0L -> getString(if (lastFailed) R.string.tap_retry else R.string.never_updated)
                    else -> "Updated " + Times.clock(fetchedAt, zone) +
                        if (lastFailed) " · " + getString(R.string.offline) else ""
                }
            )
        }
    }

    private fun showWatchLabel() {
        findViewById<TextView>(R.id.watch).setText(
            when {
                web != null -> R.string.hide_video
                else -> R.string.stream
            }
        )
    }

    private fun showCountdown() {
        val l = next ?: return
        val rem = l.netMillis - System.currentTimeMillis()
        val text = when {
            !l.timeKnown -> "TBD"
            rem <= 0 -> "LIFTOFF"
            else -> Times.longCountdown(rem)
        }
        findViewById<TextView>(R.id.countdown).text = text
        if (full != Full.NONE) findViewById<TextView>(R.id.full_timer).text = text
        // The current launch aged out: move on to the one after it.
        if (rem < -Times.LIVE_WINDOW) render()
    }

    private fun row(l: Launch, zone: ZoneId): View {
        val row = layoutInflater.inflate(R.layout.row_launch, findViewById<ViewGroup>(R.id.later), false)
        row.findViewById<TextView>(R.id.row_date).text = Times.monthDay(l, zone)
        val day = row.findViewById<TextView>(R.id.row_day)
        day.text = Times.dayName(l, zone)
        day.visibility = if (l.dateKnown) View.VISIBLE else View.GONE
        row.findViewById<TextView>(R.id.row_time).text = Ui.time(this, l, zone)
        Ui.bindIdent(row.findViewById(R.id.row_logo), row.findViewById(R.id.row_ident), l)
        row.setOnClickListener { open(l.detailsUrl) }
        return row
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Video -------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun openVideo(l: Launch) {
        val url = l.watchUrl
        val youtube = Video.youtubeId(url)
        val w = try {
            WebView(this)
        } catch (e: Exception) {
            // No WebView on this device: fall back to whatever app handles the link.
            open(url)
            return
        }
        w.setBackgroundColor(Color.BLACK)
        w.keepScreenOn = true
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
        }
        w.webViewClient = object : WebViewClient() {
            // Stay on web pages; ignore links that try to hand off to another app.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                return scheme != "http" && scheme != "https"
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) = enterFull(view, callback)
            override fun onHideCustomView() = exitFull()
        }

        web = w
        videoFor = l.id
        videoUrl = url
        videoIsPlayerOnly = youtube != null
        findViewById<FrameLayout>(R.id.video_holder).addView(w, MATCH, MATCH)
        findViewById<View>(R.id.video_box).visibility = View.VISIBLE
        sizeVideo()
        showWatchLabel()

        if (youtube != null) {
            // YouTube only plays embedded video for a page with a real web origin.
            w.loadDataWithBaseURL("https://$packageName", Video.embedHtml(youtube), "text/html", "utf-8", null)
        } else {
            w.loadUrl(url)
        }
    }

    private fun closeVideo() {
        val w = web ?: return
        exitFull()
        web = null
        videoFor = null
        videoUrl = null
        (w.parent as? ViewGroup)?.removeView(w)
        w.destroy()
        findViewById<View>(R.id.video_box).visibility = View.GONE
        showWatchLabel()
    }

    /** A bare player gets a 16:9 box; a full web page needs more room to be usable. */
    private fun sizeVideo() {
        val holder = findViewById<FrameLayout>(R.id.video_holder)
        holder.post {
            if (holder.width == 0) return@post
            val height = if (videoIsPlayerOnly) holder.width * 9 / 16 else holder.width * 5 / 4
            if (holder.layoutParams.height != height) {
                holder.layoutParams.height = height
                holder.requestLayout()
            }
        }
    }

    /** [pageView] is set when the page's own player asked for full screen. */
    private fun enterFull(pageView: View?, callback: WebChromeClient.CustomViewCallback?) {
        val w = web
        if (w == null || full != Full.NONE) {
            callback?.onCustomViewHidden()
            return
        }
        val holder = findViewById<FrameLayout>(R.id.full_holder)
        if (pageView != null) {
            holder.addView(pageView, MATCH, MATCH)
            this.pageView = pageView
            pageCallback = callback
            full = Full.PAGE
        } else {
            findViewById<FrameLayout>(R.id.video_holder).removeView(w)
            holder.addView(w, MATCH, MATCH)
            full = Full.OWN
        }
        findViewById<View>(R.id.full).visibility = View.VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setBarsHidden(true)
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = OnBackInvokedCallback { exitFull() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        }
        showCountdown()
    }

    private fun exitFull() {
        val was = full
        if (was == Full.NONE) return
        // Cleared first: telling the page it left full screen calls back into here.
        full = Full.NONE
        val holder = findViewById<FrameLayout>(R.id.full_holder)
        if (was == Full.PAGE) {
            pageView?.let { holder.removeView(it) }
            val callback = pageCallback
            pageView = null
            pageCallback = null
            callback?.onCustomViewHidden()
        } else {
            web?.let {
                holder.removeView(it)
                findViewById<FrameLayout>(R.id.video_holder).addView(it, MATCH, MATCH)
            }
        }
        findViewById<View>(R.id.full).visibility = View.GONE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        setBarsHidden(false)
        if (Build.VERSION.SDK_INT >= 33) {
            (backCallback as? OnBackInvokedCallback)?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
            backCallback = null
        }
        sizeVideo()
    }

    private fun setBarsHidden(hidden: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val controller = window.insetsController ?: return
            if (hidden) {
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsets.Type.systemBars())
            } else {
                controller.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (hidden) {
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            } else 0
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
