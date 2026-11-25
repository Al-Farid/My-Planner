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
import java.time.LocalDate
import java.time.YearMonth

/** Home-screen month calendar: phone calendars, scheduled planner tasks and deadlines. */
class CalendarWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
        SyncService.startIfConfigured(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val p = Config.prefs(context)
        when (intent.action) {
            PREV -> p.edit().putInt("calOffset", p.getInt("calOffset", 0) - 1).apply()
            NEXT -> p.edit().putInt("calOffset", p.getInt("calOffset", 0) + 1).apply()
            NOW -> p.edit().putInt("calOffset", 0).apply()
            else -> { super.onReceive(context, intent); return }
        }
        refreshAll(context)
    }

    companion object {
        const val PREV = "io.github.alfarid.planner.CAL_PREV"
        const val NEXT = "io.github.alfarid.planner.CAL_NEXT"
        const val NOW = "io.github.alfarid.planner.CAL_NOW"
        const val CAL_URL = "https://al-farid.github.io/My-Planner/#calendar"

        fun refreshAll(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, CalendarWidget::class.java))
            ids.forEach { update(ctx, manager, it) }
        }

        private fun action(ctx: Context, a: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(ctx, code, Intent(ctx, CalendarWidget::class.java).setAction(a),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun update(ctx: Context, manager: AppWidgetManager, id: Int) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_calendar)
            try {
                val o = manager.getAppWidgetOptions(id)
                val land = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                var wDp = if (land) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                var hDp = if (land) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
                if (wDp <= 0) wDp = 380
                if (hDp <= 0) hDp = 360
                var scale = ctx.resources.displayMetrics.density
                val longest = maxOf(wDp, hDp) * scale
                if (longest > 1300f) scale *= 1300f / longest
                val headerPx = 48f * scale // matches the 48dp tap row in widget_calendar.xml
                val today = LocalDate.now()
                val month = YearMonth.from(today).plusMonths(Config.prefs(ctx).getInt("calOffset", 0).toLong())
                val bmp = CalendarRenderer.render(ctx, PlanData.load(ctx), month, today, (wDp * scale).toInt(), (hDp * scale).toInt(), headerPx,
                    Config.prefs(ctx).getInt("calTransp", 15))
                rv.setImageViewBitmap(R.id.cal_img, bmp)
            } catch (e: Throwable) { /* keep the last picture */ }
            rv.setOnClickPendingIntent(R.id.cal_prev, action(ctx, PREV, 11))
            rv.setOnClickPendingIntent(R.id.cal_next, action(ctx, NEXT, 12))
            rv.setOnClickPendingIntent(R.id.cal_title, action(ctx, NOW, 13))
            rv.setOnClickPendingIntent(R.id.cal_img, PendingIntent.getActivity(ctx, 14,
                Intent(Intent.ACTION_VIEW, Uri.parse(CAL_URL)), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            manager.updateAppWidget(id, rv)
        }
    }
}
