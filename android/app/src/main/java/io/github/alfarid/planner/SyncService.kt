package io.github.alfarid.planner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.provider.CalendarContract
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stays connected to your planner so the widget updates within a second of any change.
 * Android requires a visible notification while it runs; it uses the quietest level.
 */
class SyncService : Service() {
    private var reg: ListenerRegistration? = null
    private var listeningTo: String? = null
    private var failed = false
    private val handler = Handler(Looper.getMainLooper())
    private val calHandler = Handler(Looper.getMainLooper())
    private var calObserver: ContentObserver? = null
    private val calJob = Runnable {
        DeviceCalendar.upload(this)
        CalendarWidget.refreshAll(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Planner sync", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps the planner widget up to date"
                setShowBadge(false)
            }
        )
        val n = notification("Connecting to your planner…")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTE_ID, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val feed = Config.prefs(this).getString("feed", null)
        if (feed == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (feed != listeningTo || failed) listen(feed)
        watchCalendar()
        return START_STICKY
    }

    private fun listen(feed: String) {
        reg?.remove()
        handler.removeCallbacksAndMessages(null)
        failed = false
        listeningTo = feed
        reg = Config.db(this).collection("feeds").document(feed).addSnapshotListener { snap, err ->
            if (err != null) {
                failed = true
                show("Can't reach your planner (${err.code}). Retrying in 30 s.")
                handler.postDelayed({ listeningTo?.let { listen(it) } }, 30_000L)
                return@addSnapshotListener
            }
            if (snap == null || !snap.exists()) {
                show("No planner found for this widget code. Open your planner once to publish it.")
                return@addSnapshotListener
            }
            val data = snap.data ?: return@addSnapshotListener
            val now = System.currentTimeMillis()
            Config.prefs(this).edit()
                .putString("plan", JSONObject(data as Map<*, *>).toString())
                .putLong("syncedAt", now)
                .apply()
            PlannerWidget.refreshAll(this)
            CalendarWidget.refreshAll(this)
            val src = if (snap.metadata.isFromCache) "offline copy" else "live"
            show("Widget $src · updated " + SimpleDateFormat("h:mm a", Locale.US).format(Date(now)))
        }
    }

    /** When anything in the phone's calendars changes, resend them (after a short pause) and redraw. */
    private fun watchCalendar() {
        if (!DeviceCalendar.granted(this)) return
        if (calObserver == null) {
            calObserver = object : ContentObserver(calHandler) {
                override fun onChange(selfChange: Boolean) {
                    calHandler.removeCallbacks(calJob)
                    calHandler.postDelayed(calJob, 10_000L)
                }
            }
            try {
                contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, calObserver!!)
            } catch (e: Exception) { calObserver = null }
        }
        calHandler.removeCallbacks(calJob)
        calHandler.postDelayed(calJob, 3_000L)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_planner)
            .setContentTitle("Planner sync")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun show(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTE_ID, notification(text))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        calHandler.removeCallbacksAndMessages(null)
        calObserver?.let { try { contentResolver.unregisterContentObserver(it) } catch (e: Exception) { } }
        calObserver = null
        reg?.remove()
        reg = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "planner_sync"
        private const val NOTE_ID = 7

        fun startIfConfigured(ctx: Context) {
            val p = Config.prefs(ctx)
            if (p.getString("feed", null) == null || !p.getBoolean("live", false)) return
            try {
                ctx.startForegroundService(Intent(ctx, SyncService::class.java))
            } catch (e: Exception) {
                // Android may refuse a start from the background; opening the app restarts it.
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SyncService::class.java))
        }
    }
}
