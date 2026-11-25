package io.github.alfarid.planner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Month view for the calendar widget: phone calendars + scheduled planner tasks + deadlines. */
object CalendarRenderer {
    private val ISO = DateTimeFormatter.ISO_LOCAL_DATE
    private const val BG = 0xFFF1F4F9.toInt()
    private const val SURFACE = 0xFFFFFFFF.toInt()
    private const val LINE = 0xFFD3DAEA.toInt()
    private const val INK = 0xFF101D33.toInt()
    private const val MUTED = 0xFF5A6683.toInt()
    private const val DUE = 0xFFC0392B.toInt()
    private const val DUE_BG = 0xFFFBE3E0.toInt()
    private const val HEAD = 0xFFF6F8FC.toInt()

    private var serif: Typeface? = null
    private var sans: Typeface? = null
    private var sansBold: Typeface? = null
    private fun fonts(ctx: Context) {
        if (serif != null) return
        fun load(file: String, w: Int, fb: Typeface): Typeface = try {
            Typeface.Builder(ctx.assets, "fonts/$file").setFontVariationSettings("'wght' $w").build() ?: fb
        } catch (e: Throwable) { fb }
        serif = load("cormorant.ttf", 600, Typeface.SERIF)
        sans = load("inter.ttf", 400, Typeface.SANS_SERIF)
        sansBold = load("inter.ttf", 600, Typeface.DEFAULT_BOLD)
    }

    private class Chip(val text: String, val color: Int, val kind: Int, val done: Boolean) // kind 0 all-day, 1 due, 2 task, 3 timed

    /** headerPx = height of the title bar, where the ‹ › tap areas sit. */
    fun render(ctx: Context, plan: Plan?, month: YearMonth, today: LocalDate, wPx: Int, hPx: Int, headerPx: Float): Bitmap {
        fonts(ctx)
        val w = wPx.coerceAtLeast(200)
        val h = hPx.coerceAtLeast(200)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = (minOf(w / 760f, h / 700f)).coerceIn(0.4f, 3f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = BG
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 22f * s, 22f * s, p)

        // ---- header: ‹ Month Year ›
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = serif; color = INK; textSize = minOf(34f * s, headerPx * 0.62f); textAlign = Paint.Align.CENTER }
        val name = month.month.getDisplayName(TextStyle.FULL, Locale.US) + " " + month.year
        c.drawText(name, w / 2f, headerPx * 0.68f, title)
        val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = MUTED; strokeWidth = 2.4f * s; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val ay = headerPx / 2f
        val aw = 7f * s
        val lx = headerPx * 0.55f
        c.drawPath(Path().apply { moveTo(lx + aw * 0.6f, ay - aw); lineTo(lx - aw * 0.6f, ay); lineTo(lx + aw * 0.6f, ay + aw) }, arrow)
        val rx = w - headerPx * 0.55f
        c.drawPath(Path().apply { moveTo(rx - aw * 0.6f, ay - aw); lineTo(rx + aw * 0.6f, ay); lineTo(rx - aw * 0.6f, ay + aw) }, arrow)
        if (month != YearMonth.from(today)) {
            val hint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; color = MUTED; textSize = 11f * s; textAlign = Paint.Align.CENTER }
            c.drawText("tap the title for this month", w / 2f, headerPx * 0.96f, hint)
        }

        // ---- grid
        val pad = 10f * s
        val gx = pad
        val gw = w - 2 * pad
        val dayHead = 22f * s
        val gy = headerPx + 2f * s
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value % 7 // Sunday = 0
        val rows = (lead + month.lengthOfMonth() + 6) / 7
        val cellW = gw / 7f
        val cellH = (h - pad - gy - dayHead) / rows

        val card = RectF(gx, gy, gx + gw, h - pad)
        p.color = SURFACE; p.style = Paint.Style.FILL
        c.drawRoundRect(card, 10f * s, 10f * s, p)
        p.color = HEAD
        c.save(); c.clipRect(gx, gy, gx + gw, gy + dayHead); c.drawRoundRect(card, 10f * s, 10f * s, p); c.restore()
        val wk = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sansBold; color = MUTED; textSize = 11.5f * s; textAlign = Paint.Align.CENTER }
        listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEachIndexed { i, d -> c.drawText(d, gx + cellW * (i + 0.5f), gy + dayHead - 7f * s, wk) }
        val line = Paint().apply { color = LINE; strokeWidth = 1f * s }
        val top = gy + dayHead
        c.drawLine(gx, top, gx + gw, top, line)
        for (r in 1 until rows) c.drawLine(gx, top + r * cellH, gx + gw, top + r * cellH, line)
        for (k in 1 until 7) c.drawLine(gx + k * cellW, top, gx + k * cellW, h - pad, line)
        p.style = Paint.Style.STROKE; p.color = LINE; p.strokeWidth = 1f * s
        c.drawRoundRect(card, 10f * s, 10f * s, p)
        p.style = Paint.Style.FILL

        // ---- what's on each day
        val start = first.minusDays(lead.toLong())
        val end = start.plusDays((rows * 7 - 1).toLong())
        val (cals, evs) = DeviceCalendar.read(ctx, start, end)
        val byDay = HashMap<String, MutableList<Chip>>()
        fun add(d: String, ch: Chip) { byDay.getOrPut(d) { mutableListOf() } += ch }
        evs.forEach { e ->
            var d = LocalDate.parse(e.start, ISO)
            val last = LocalDate.parse(e.end, ISO)
            var guard = 0
            while (!d.isAfter(last) && guard++ < 62) {
                if (!d.isBefore(start) && !d.isAfter(end)) add(d.format(ISO), Chip(if (e.time.isEmpty()) e.title else e.time + " " + e.title, e.color or 0xFF000000.toInt(), if (e.time.isEmpty()) 0 else 3, false))
                d = d.plusDays(1)
            }
        }
        if (plan != null) {
            plan.tasks.forEach { t ->
                if (t.due.isNotEmpty()) add(t.due, Chip("⚑ " + t.text, DUE, 1, t.done))
            }
            plan.secDue.forEach { (sec, d) -> if (plan.sections.contains(sec)) add(d, Chip("⚑ $sec", DUE, 1, false)) }
        }

        val num = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sansBold; textSize = 13f * s; textAlign = Paint.Align.CENTER }
        val chipP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; textSize = 10.5f * s }
        val chipH = 15f * s
        for (i in 0 until rows * 7) {
            val d = start.plusDays(i.toLong())
            val cx = gx + (i % 7) * cellW
            val cy = top + (i / 7) * cellH
            val inMonth = d.month == month.month
            val iso = d.format(ISO)
            val isToday = d == today
            val ny = cy + 15f * s
            if (isToday) {
                p.color = INK
                val tw = maxOf(num.measureText(d.dayOfMonth.toString()) + 10f * s, 20f * s)
                c.drawRoundRect(RectF(cx + cellW / 2 - tw / 2, cy + 2f * s, cx + cellW / 2 + tw / 2, cy + 19f * s), 5f * s, 5f * s, p)
                num.color = SURFACE
            } else num.color = if (inMonth) INK else 0xFFB4BCCB.toInt()
            c.drawText(d.dayOfMonth.toString(), cx + cellW / 2, ny, num)
            if (isToday) {
                p.style = Paint.Style.STROKE; p.color = INK; p.strokeWidth = 1.6f * s
                c.drawRect(cx + 1f * s, cy + 1f * s, cx + cellW - 1f * s, cy + cellH - 1f * s, p)
                p.style = Paint.Style.FILL
            }
            val items = (byDay[iso] ?: mutableListOf()).sortedBy { it.kind }
            val avail = cy + cellH - (cy + 22f * s) - 2f * s
            val cap = (avail / (chipH + 2f * s)).toInt().coerceAtLeast(0)
            if (cap == 0) {
                if (items.isNotEmpty()) { p.color = MUTED; c.drawCircle(cx + cellW / 2, cy + cellH - 6f * s, 2.5f * s, p) }
                continue
            }
            val shown = if (items.size > cap) cap - 1 else items.size
            var y = cy + 22f * s
            for (k in 0 until shown) {
                val it = items[k]
                val r = RectF(cx + 2f * s, y, cx + cellW - 2f * s, y + chipH)
                p.color = if (it.kind == 1) DUE_BG else blend(it.color, 0.22f)
                p.alpha = if (inMonth) 255 else 120
                c.drawRoundRect(r, 3f * s, 3f * s, p)
                p.color = it.color; p.alpha = if (inMonth) 255 else 120
                c.drawRect(r.left, r.top, r.left + 2.5f * s, r.bottom, p)
                p.alpha = 255
                chipP.color = if (it.kind == 1) 0xFF8E1F14.toInt() else INK
                chipP.typeface = if (it.kind == 1) sansBold else sans
                chipP.isStrikeThruText = it.done
                chipP.alpha = if (!inMonth || it.done) 130 else 255
                val txt = TextUtils.ellipsize(it.text, chipP, r.width() - 7f * s, TextUtils.TruncateAt.END).toString()
                c.drawText(txt, r.left + 4.5f * s, r.bottom - 4f * s, chipP)
                y += chipH + 2f * s
            }
            if (items.size > shown) {
                chipP.color = MUTED; chipP.isStrikeThruText = false; chipP.alpha = 255; chipP.typeface = sans
                c.drawText("+${items.size - shown} more", cx + 5f * s, y + chipH - 4f * s, chipP)
            }
        }
        if (!DeviceCalendar.granted(ctx)) {
            val note = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; color = MUTED; textSize = 11f * s; textAlign = Paint.Align.RIGHT }
            c.drawText("Open Planner Widget to show your phone calendars", w - pad - 4f * s, headerPx * 0.96f, note)
        }
        return bmp
    }

    private fun blend(color: Int, a: Float): Int {
        val r = ((color shr 16 and 0xFF) * a + 255 * (1 - a)).toInt()
        val g = ((color shr 8 and 0xFF) * a + 255 * (1 - a)).toInt()
        val b = ((color and 0xFF) * a + 255 * (1 - a)).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
