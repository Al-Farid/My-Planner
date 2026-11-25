package io.github.alfarid.planner

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

/** Setup screen: connect the widget to your planner and keep it live. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var code: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        code = findViewById(R.id.code)

        findViewById<Button>(R.id.save).setOnClickListener {
            val c = Config.cleanCode(code.text.toString())
            if (c == null) {
                status.text = "That code looks incomplete. In your planner, tap Widget and copy the whole code."
            } else connect(c)
        }
        findViewById<Button>(R.id.live).setOnClickListener { toggleLive() }
        findViewById<Button>(R.id.style).setOnClickListener {
            val p = Config.prefs(this)
            val next = if (p.getString("style", "board") == "board") "list" else "board"
            p.edit().putString("style", next).apply()
            PlannerWidget.refreshAll(this)
            render()
        }
        findViewById<Button>(R.id.battery).setOnClickListener { askBattery() }
        findViewById<Button>(R.id.calperm).setOnClickListener {
            if (DeviceCalendar.granted(this)) {
                DeviceCalendar.upload(this, force = true)
                CalendarWidget.refreshAll(this)
                status.text = "Sending your calendars to the planner…"
            } else requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 2)
        }
        findViewById<Button>(R.id.open).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Config.PLANNER_URL)))
        }
        handleLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    override fun onResume() {
        super.onResume()
        SyncService.startIfConfigured(this)
        DeviceCalendar.upload(this)
        CalendarWidget.refreshAll(this)
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2 && DeviceCalendar.granted(this)) {
            DeviceCalendar.upload(this, force = true)
            CalendarWidget.refreshAll(this)
            SyncService.stop(this)
            SyncService.startIfConfigured(this)
        }
        render()
    }

    /** myplanner://connect?feed=CODE, sent by the planner's "Connect widget" button. */
    private fun handleLink(i: Intent?) {
        val data = i?.data ?: return
        if (data.scheme == "myplanner") {
            Config.cleanCode(data.getQueryParameter("feed"))?.let { connect(it) }
        }
    }

    private fun connect(c: String) {
        Config.prefs(this).edit()
            .putString("feed", c)
            .putBoolean("live", true)
            .remove("plan").putLong("syncedAt", 0L)
            .apply()
        askNotifications()
        SyncService.stop(this)
        SyncService.startIfConfigured(this)
        PlannerWidget.refreshAll(this)
        render()
    }

    private fun toggleLive() {
        val p = Config.prefs(this)
        if (p.getString("feed", null) == null) {
            status.text = "Connect your planner first."
            return
        }
        val live = !p.getBoolean("live", false)
        p.edit().putBoolean("live", live).apply()
        if (live) {
            askNotifications()
            SyncService.startIfConfigured(this)
        } else SyncService.stop(this)
        render()
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun askBattery() {
        val pm = getSystemService(PowerManager::class.java)
        try {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } else {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun render() {
        val p = Config.prefs(this)
        val feed = p.getString("feed", null)
        val live = p.getBoolean("live", false)
        val synced = p.getLong("syncedAt", 0L)
        val pm = getSystemService(PowerManager::class.java)
        val unrestricted = pm.isIgnoringBatteryOptimizations(packageName)
        findViewById<Button>(R.id.live).text = if (live) "Turn off live sync" else "Turn on live sync"
        findViewById<Button>(R.id.style).text =
            if (p.getString("style", "board") == "board") "Widget look: paper planner (tap for list)" else "Widget look: scrolling list (tap for paper planner)"
        findViewById<Button>(R.id.calperm).text = when {
            !DeviceCalendar.granted(this) -> "Show my calendars in the planner"
            p.getString("calErr", null) != null -> "Calendars: not sent (update Firebase rules) · tap to retry"
            p.getLong("calUp", 0L) > 0L -> "Calendars: shared with planner ✓ · tap to resend"
            else -> "Calendars: allowed ✓ · tap to send now"
        }
        findViewById<Button>(R.id.battery).text =
            if (unrestricted) "Background use: allowed ✓" else "Allow running in background"
        if (feed != null && code.text.isEmpty()) code.setText(feed)
        status.text = when {
            feed == null -> "Not connected. In your planner, tap Widget, then Connect widget on this device. Or paste the code below."
            !live -> "Connected, live sync is off. The widget shows your last copy."
            synced == 0L -> "Connected. Waiting for your planner…"
            else -> "Connected and live. Last update " +
                java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(java.util.Date(synced)) + "."
        }
    }
}
