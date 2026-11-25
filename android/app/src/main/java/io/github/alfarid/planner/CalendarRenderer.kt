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
    private const val PANEL = 0xFFDCDCE1.toInt()
    private const val CHIP_INK = 0xFF1C1C1E.toInt()
    private const val SEP = 0x33101D33
    private const val TODAY_RING = 0xFF6B7385.toInt()
    private const val FADED = 0xFF9AA1B0.toInt()

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

    /** headerPx = height of the title bar, where the ‹ › tap areas sit. transparency 0–100 (%). */
    fun render(ctx: Context, plan: Plan?, month: YearMonth, today: LocalDate, wPx: Int, hPx: Int, headerPx: Float, transparency: Int = 30): Bitmap {
        fonts(ctx)
        val w = wPx.coerceAtLeast(200)
        val h = hPx.coerceAtLeast(200)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = (minOf(w / 760f, h / 700f)).coerceIn(0.4f, 3f)
        val dp = ctx.resources.displayMetrics.density * (headerPx / (48f * ctx.resources.displayMetrics.density))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Frosted panel; transparency is set in the Planner Widget app
        val bgAlpha = ((100 - transparency.coerceIn(0, 100)) * 2.55f).toInt()
        p.color = PANEL; p.alpha = bgAlpha
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 24f * dp, 24f * dp, p)
        p.alpha = 255

        // ---- header: ‹ NOV ›   (tap areas: 56dp | 110dp | 56dp from the left)
        val ay = headerPx / 2f
        val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = INK; strokeWidth = 2.2f * dp; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val aw = 7f * dp
        val lx = 28f * dp
        c.drawPath(Path().apply { moveTo(lx + aw * 0.55f, ay - aw); lineTo(lx - aw * 0.55f, ay); lineTo(lx + aw * 0.55f, ay + aw) }, arrow)
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sansBold; color = INK; textSize = 21f * dp; textAlign = Paint.Align.CENTER }
        val mName = month.month.getDisplayName(TextStyle.SHORT, Locale.US).uppercase(Locale.US) + if (month.year != today.year) " " + month.year else ""
        c.drawText(mName, 56f * dp + 55f * dp, ay + 7.5f * dp, title)
        val rx = 166f * dp + 28f * dp
        c.drawPath(Path().apply { moveTo(rx - aw * 0.55f, ay - aw); lineTo(rx + aw * 0.55f, ay); lineTo(rx - aw * 0.55f, ay + aw) }, arrow)
        if (!DeviceCalendar.granted(ctx)) {
            val note = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; color = MUTED; textSize = 11f * dp; textAlign = Paint.Align.RIGHT }
            c.drawText("Open Planner Widget to show phone calendars", w - 18f * dp, ay + 4f * dp, note)
        }

        // ---- weekday letters
        val pad = 6f * dp
        val gx = pad
        val gw = w - 2 * pad
        val cellW = gw / 7f
        val wkH = 24f * dp
        val wk = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; color = INK; textSize = 13f * dp; textAlign = Paint.Align.CENTER }
        listOf("S", "M", "T", "W", "T", "F", "S").forEachIndexed { i, d -> c.drawText(d, gx + cellW * (i + 0.5f), headerPx + wkH - 7f * dp, wk) }
        val top = headerPx + wkH + 2f * dp
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value % 7 // Sunday = 0
        val rows = (lead + month.lengthOfMonth() + 6) / 7
        val cellH = (h - 8f * dp - top) / rows
        val sep = Paint().apply { color = SEP; strokeWidth = 1f * dp }
        for (r in 0 until rows) c.drawLine(gx, top + r * cellH, gx + gw, top + r * cellH, sep)

        // ---- what's on each day
        val start = first.minusDays(lead.toLong())
        val end = start.plusDays((rows * 7 - 1).toLong())
        val (_, evs) = DeviceCalendar.read(ctx, start, end)
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
            plan.tasks.forEach { t -> if (t.due.isNotEmpty()) add(t.due, Chip("⚑ " + t.text, DUE, 1, t.done)) }
            plan.secDue.forEach { (sec, d) -> if (plan.sections.contains(sec)) add(d, Chip("⚑ $sec", DUE, 1, false)) }
        }

        val num = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sansBold; textSize = 15f * dp; textAlign = Paint.Align.CENTER }
        val chipP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; textSize = 11f * dp }
        val chipH = 15f * dp
        val gapY = 1.5f * dp
        for (i in 0 until rows * 7) {
            val d = start.plusDays(i.toLong())
            val cx = gx + (i % 7) * cellW
            val cy = top + (i / 7) * cellH
            val inMonth = d.month == month.month
            val iso = d.format(ISO)
            val isToday = d == today
            val numBase = cy + 18f * dp
            if (isToday) {
                p.style = Paint.Style.STROKE; p.color = TODAY_RING; p.strokeWidth = 1.2f * dp
                c.drawRoundRect(RectF(cx + 1.5f * dp, cy + 2f * dp, cx + cellW - 1.5f * dp, cy + cellH - 2f * dp), 6f * dp, 6f * dp, p)
                p.style = Paint.Style.FILL; p.color = INK
                val tw = maxOf(num.measureText(d.dayOfMonth.toString()) + 8f * dp, 22f * dp)
                c.drawRoundRect(RectF(cx + cellW / 2 - tw / 2, cy + 4f * dp, cx + cellW / 2 + tw / 2, cy + 23f * dp), 5f * dp, 5f * dp, p)
                num.color = 0xFFFFFFFF.toInt()
            } else num.color = if (inMonth) INK else FADED
            c.drawText(d.dayOfMonth.toString(), cx + cellW / 2, numBase, num)

            // one copy of an event that two calendars both have (e.g. two Oman holiday calendars);
            // Hijri dates ("13 Jumada …") first, like Samsung Calendar
            val seen = HashSet<String>()
            val items = (byDay[iso] ?: mutableListOf())
                .filter { seen.add(it.kind.toString() + "|" + it.text.trim().lowercase(Locale.US)) }
                .sortedWith(compareBy<Chip>({ it.kind }, { if (it.text.firstOrNull()?.isDigit() == true) 0 else 1 }))
            var y = cy + 27f * dp
            val bottom = cy + cellH - 3f * dp
            val lineH = 13.5f * dp
            val chipPad = 2.5f * dp
            val oneH = lineH + chipPad
            if (bottom - y < oneH) {
                if (items.isNotEmpty()) { p.color = MUTED; c.drawCircle(cx + cellW / 2, cy + cellH - 6f * dp, 2.5f * dp, p) }
                continue
            }
            val textW = cellW - 3f * dp - 6f * dp
            var drawn = 0
            for (k in items.indices) {
                val ch = items[k]
                chipP.typeface = if (ch.kind == 1) sansBold else sans
                // wrap onto a second line when there is room, like Samsung's widget
                val lines = wrap(ch.text, chipP, textW)
                val leftAfter = items.size - k - 1
                val roomForMore = if (leftAfter > 0) oneH + gapY else 0f
                var n = minOf(lines.size, 2)
                if (y + n * lineH + chipPad + roomForMore > bottom) n = 1
                val hNeed = n * lineH + chipPad
                if (y + hNeed > bottom) break
                // keep the last line free for "+N more" when items would be left out
                if (leftAfter > 0 && k > 0 && y + hNeed + gapY + oneH > bottom) break
                val r = RectF(cx + 1.5f * dp, y, cx + cellW - 1.5f * dp, y + hNeed)
                p.color = if (ch.kind == 1) DUE_BG else blend(ch.color, 0.48f)
                p.alpha = if (inMonth) 255 else 120
                c.drawRoundRect(r, 2.5f * dp, 2.5f * dp, p)
                p.alpha = 255
                chipP.color = if (ch.kind == 1) 0xFF8E1F14.toInt() else CHIP_INK
                chipP.isStrikeThruText = ch.done
                chipP.alpha = if (!inMonth || ch.done) 120 else 255
                for (li in 0 until n) {
                    var t = lines[li]
                    if (li == n - 1 && lines.size > n) t = TextUtils.ellipsize(lines.subList(li, lines.size).joinToString(""), chipP, textW, TextUtils.TruncateAt.END).toString()
                    c.drawText(t, r.left + 3f * dp, y + (li + 1) * lineH - 2.5f * dp, chipP)
                }
                y += hNeed + gapY
                drawn++
            }
            if (items.size > drawn) {
                chipP.color = MUTED; chipP.isStrikeThruText = false; chipP.alpha = 255; chipP.typeface = sans
                c.drawText("+${items.size - drawn} more", cx + 4f * dp, minOf(y + lineH - 2f * dp, bottom), chipP)
            }
        }
        return bmp
    }

    /** Break text into lines that fit, splitting mid-word when needed (as Samsung Calendar does). */
    private fun wrap(text: String, paint: TextPaint, maxW: Float): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length && out.size < 4) {
            val n = paint.breakText(text, i, text.length, true, maxW, null).coerceAtLeast(1)
            out += text.substring(i, i + n)
            i += n
        }
        return if (out.isEmpty()) listOf("") else out
    }

    private fun blend(color: Int, a: Float): Int {
        val r = ((color shr 16 and 0xFF) * a + 255 * (1 - a)).toInt()
        val g = ((color shr 8 and 0xFF) * a + 255 * (1 - a)).toInt()
        val b = ((color and 0xFF) * a + 255 * (1 - a)).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
