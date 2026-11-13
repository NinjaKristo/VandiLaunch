package app.vandilaunch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.ZoneId
import java.util.concurrent.Executors

/** The only screen: next launch with a live countdown, then the ones after it. */
class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var next: Launch? = null
    private var refreshing = false
    private var lastFailed = false

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
        findViewById<View>(R.id.watch).setOnClickListener { next?.let { open(it.watchUrl) } }
        findViewById<View>(R.id.details).setOnClickListener { next?.let { open(it.detailsUrl) } }

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
        render()
        ui.post(tick)
        if (System.currentTimeMillis() - Store.fetchedAt(this) > 10 * Times.MIN) refresh()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
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

        val hasLaunch = launch != null
        for (id in intArrayOf(R.id.who, R.id.when_text, R.id.countdown, R.id.status, R.id.buttons)) {
            findViewById<View>(id).visibility = if (hasLaunch) View.VISIBLE else View.GONE
        }
        val mission = findViewById<TextView>(R.id.mission)
        if (launch == null) {
            mission.setText(
                when {
                    fetchedAt != 0L -> R.string.no_launches
                    lastFailed && !refreshing -> R.string.load_failed
                    else -> R.string.loading
                }
            )
        } else {
            mission.text = launch.mission
            findViewById<TextView>(R.id.who).text = launch.who
            findViewById<TextView>(R.id.when_text).text = Times.whenLine(launch, zone)
            findViewById<TextView>(R.id.status).text =
                listOf(launch.status, launch.shortPad).filter { it.isNotBlank() }.joinToString(" · ")
            findViewById<TextView>(R.id.watch).setText(
                if (launch.liveUrl != null) R.string.watch_live else R.string.find_stream
            )
            showCountdown()
        }

        val later = findViewById<LinearLayout>(R.id.later)
        later.removeAllViews()
        val rest = list.drop(1)
        findViewById<View>(R.id.later_label).visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
        for (l in rest) later.addView(row(l, zone))

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

    private fun showCountdown() {
        val l = next ?: return
        val rem = l.netMillis - System.currentTimeMillis()
        findViewById<TextView>(R.id.countdown).text = when {
            !l.timeKnown -> "TBD"
            rem <= 0 -> "LIFTOFF"
            else -> Times.longCountdown(rem)
        }
        // The current launch aged out: move on to the one after it.
        if (rem < -Times.LIVE_WINDOW) render()
    }

    private fun row(l: Launch, zone: ZoneId): View {
        val row = layoutInflater.inflate(R.layout.row_launch, findViewById<ViewGroup>(R.id.later), false)
        row.findViewById<TextView>(R.id.row_when).text = Times.whenLine(l, zone)
        row.findViewById<TextView>(R.id.row_name).text = l.mission
        row.findViewById<TextView>(R.id.row_who).text = l.who
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
}
