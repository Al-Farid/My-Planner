package io.github.alfarid.planner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts live sync after the device restarts or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SyncService.startIfConfigured(context)
        PlannerWidget.refreshAll(context)
    }
}
