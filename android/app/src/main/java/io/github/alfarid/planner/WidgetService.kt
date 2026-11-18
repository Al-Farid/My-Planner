package io.github.alfarid.planner

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.time.LocalDate

/** Supplies the rows of the widget's scrolling list. */
class WidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)
}

private class Factory(private val ctx: Context) : RemoteViewsService.RemoteViewsFactory {
    private var rows: List<Row> = emptyList()

    private val ink = Color.parseColor("#101D33")
    private val faint = Color.parseColor("#8794AE")
    private val todayBg = Color.parseColor("#FFF8E2")
    private val prioBg = Color.parseColor("#FFF1BF")

    override fun onCreate() {}
    override fun onDataSetChanged() { rows = PlanData.rows(PlanData.load(ctx), LocalDate.now()) }
    override fun onDestroy() {}
    override fun getCount(): Int = rows.size
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 3
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows.getOrNull(position) ?: return RemoteViews(ctx.packageName, R.layout.row_note)
        val rv = when (row) {
            is Row.Header -> RemoteViews(ctx.packageName, R.layout.row_header).apply {
                setTextViewText(R.id.h_title, row.title)
                setTextViewText(R.id.h_sub, row.sub)
                setInt(R.id.row_root, "setBackgroundColor", if (row.today) todayBg else Color.TRANSPARENT)
            }
            is Row.Item -> RemoteViews(ctx.packageName, R.layout.row_task).apply {
                setInt(R.id.bar, "setBackgroundColor", row.color)
                setTextViewText(R.id.chips, row.chips)
                setViewVisibility(R.id.chips, if (row.chips.isEmpty()) View.GONE else View.VISIBLE)
                val label = if (row.sub.isNotEmpty()) "${row.text}  ·  ${row.sub}" else row.text
                if (row.done) {
                    val s = SpannableString(label)
                    s.setSpan(StrikethroughSpan(), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setTextViewText(R.id.text, s)
                    setTextColor(R.id.text, faint)
                } else {
                    setTextViewText(R.id.text, label)
                    setTextColor(R.id.text, ink)
                }
                setViewVisibility(R.id.star, if (row.priority) View.VISIBLE else View.GONE)
                setInt(R.id.row_root, "setBackgroundColor", if (row.priority && !row.done) prioBg else Color.TRANSPARENT)
            }
            is Row.Note -> RemoteViews(ctx.packageName, R.layout.row_note).apply {
                setTextViewText(R.id.note, row.text)
            }
        }
        rv.setOnClickFillInIntent(R.id.row_root, Intent())
        return rv
    }
}
