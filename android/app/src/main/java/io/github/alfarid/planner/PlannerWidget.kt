package io.github.alfarid.planner

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/** The home-screen Weekly plan widget. */
class PlannerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
        manager.notifyAppWidgetViewDataChanged(ids, R.id.list)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, id)
    }

    override fun onEnabled(context: Context) {
        SyncService.startIfConfigured(context)
    }

    companion object {
        fun refreshAll(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, PlannerWidget::class.java))
            if (ids.isEmpty()) return
            ids.forEach { update(ctx, manager, it) }
            manager.notifyAppWidgetViewDataChanged(ids, R.id.list)
        }

        fun update(ctx: Context, manager: AppWidgetManager, id: Int) {
            val prefs = Config.prefs(ctx)
            if (prefs.getString("style", "board") == "board") {
                try { updateBoard(ctx, manager, id); return } catch (e: Throwable) { /* fall back to the list */ }
            }
            updateList(ctx, manager, id)
        }

        /** The paper-planner look: the whole week drawn as one picture sized to the widget. */
        private fun updateBoard(ctx: Context, manager: AppWidgetManager, id: Int) {
            val o = manager.getAppWidgetOptions(id)
            val land = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            var wDp = if (land) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
            var hDp = if (land) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
            if (wDp <= 0) wDp = 380
            if (hDp <= 0) hDp = 500
            val density = ctx.resources.displayMetrics.density
            var scale = density
            val longest = maxOf(wDp, hDp) * scale
            if (longest > 1300f) scale *= 1300f / longest
            val prefs = Config.prefs(ctx)
            val bmp = BoardRenderer.render(ctx,
                PlanData.load(ctx), LocalDate.now(),
                (wDp * scale).toInt(), (hDp * scale).toInt(), prefs.getString("feed", null) != null,
                prefs.getInt("boardTransp", 0)
            )
            val rv = RemoteViews(ctx.packageName, R.layout.widget_board)
            rv.setImageViewBitmap(R.id.board, bmp)
            val open = if (prefs.getString("feed", null) == null)
                PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            else
                PendingIntent.getActivity(ctx, 1, Intent(Intent.ACTION_VIEW, Uri.parse(Config.PLANNER_URL)), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            rv.setOnClickPendingIntent(R.id.board_root, open)
            manager.updateAppWidget(id, rv)
        }

        @Suppress("DEPRECATION")
        private fun updateList(ctx: Context, manager: AppWidgetManager, id: Int) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_planner)

            val svc = Intent(ctx, WidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            rv.setRemoteAdapter(R.id.list, svc)
            rv.setEmptyView(R.id.list, R.id.empty)

            val prefs = Config.prefs(ctx)
            val synced = prefs.getLong("syncedAt", 0L)
            val status = when {
                prefs.getString("feed", null) == null -> "  ·  tap to connect"
                synced == 0L -> "  ·  connecting"
                else -> "  ·  " + SimpleDateFormat("h:mm a", Locale.US).format(Date(synced))
            }
            rv.setTextViewText(R.id.range, PlanData.rangeText(LocalDate.now()) + status)
            rv.setTextViewText(
                R.id.empty,
                if (prefs.getString("feed", null) == null) "Open the Planner Widget app to connect your planner."
                else "Loading your week…"
            )

            val openPlanner = PendingIntent.getActivity(
                ctx, 1, Intent(Intent.ACTION_VIEW, Uri.parse(Config.PLANNER_URL)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val openApp = PendingIntent.getActivity(
                ctx, 2, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Rows need a mutable template; Android 14 only allows that for an explicit intent.
            val rowTemplate = PendingIntent.getActivity(
                ctx, 3, Intent(ctx, OpenPlannerActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            rv.setOnClickPendingIntent(R.id.open, openPlanner)
            rv.setOnClickPendingIntent(R.id.title, openPlanner)
            rv.setOnClickPendingIntent(R.id.range, openApp)
            rv.setOnClickPendingIntent(R.id.empty, openApp)
            rv.setPendingIntentTemplate(R.id.list, rowTemplate)

            manager.updateAppWidget(id, rv)
        }
    }
}
