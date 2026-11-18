package io.github.alfarid.planner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Draws the widget as a paper weekly planner: Greek-key border, "Weekly plan" title,
 * Today's Focus / Priorities / To-Do / Notes on the left and Monday–Sunday on the right.
 */
object BoardRenderer {
    private val ISO = DateTimeFormatter.ISO_LOCAL_DATE
    private val BLOCKS = listOf("am" to "9–12", "pm" to "2–5", "ev" to "6–9")

    private const val PAPER = 0xFFFFFFFF.toInt()
    private const val KEY = 0xFF22336B.toInt()      // border navy
    private const val LINE = 0xFF3A3A3A.toInt()     // box outlines
    private const val RULE = 0xFFB9BEC9.toInt()     // writing lines
    private const val INK = 0xFF1B2A86.toInt()      // blue ink for entries
    private const val FAINT = 0xFF8A93A8.toInt()
    private const val TODAY = 0xFFFFF4D6.toInt()
    private const val GOLD = 0xFFD39A16.toInt()

    private class Entry(val text: String, val chips: String, val done: Boolean, val star: Boolean, val color: Int)

    fun render(plan: Plan?, today: LocalDate, wPx: Int, hPx: Int, connected: Boolean): Bitmap {
        val w = wPx.coerceAtLeast(200)
        val h = hPx.coerceAtLeast(200)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = minOf(w / 760f, h / 1000f).coerceIn(0.35f, 3f)
        c.drawColor(PAPER)

        // ---- Greek key border ----
        val band = 24f * s
        drawKeyFrame(c, w.toFloat(), h.toFloat(), band)

        val inset = band + 12f * s
        val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        val serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)

        // ---- Title ----
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = serifBold; color = 0xFF111111.toInt(); textSize = 46f * s; textAlign = Paint.Align.CENTER }
        val titleBase = inset + 46f * s
        c.drawText("Weekly plan", w / 2f, titleBase, title)
        val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = serif; color = FAINT; textSize = 14f * s; textAlign = Paint.Align.CENTER }
        c.drawText(PlanData.rangeText(today), w / 2f, titleBase + 20f * s, sub)

        val top = titleBase + 34f * s
        val bottom = h - inset
        val gap = 14f * s
        val colW = (w - 2 * inset - gap) / 2f
        val lx = inset
        val rx = inset + colW + gap

        if (plan == null) {
            val msg = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = FAINT; textSize = 16f * s; textAlign = Paint.Align.CENTER }
            c.drawText(if (connected) "Loading your week…" else "Open Planner Widget to connect", w / 2f, (top + bottom) / 2f, msg)
            return bmp
        }

        val ws = PlanData.weekStart(today)
        val weekDates = (0..6).map { ws.plusDays(it.toLong()).format(ISO) }
        val todayIso = today.format(ISO)
        fun label(id: String, def: String) = plan.labels[id]?.takeIf { it.isNotBlank() } ?: def
        fun dayEntries(date: String): List<Entry> =
            plan.tasks.mapNotNull { t ->
                val on = BLOCKS.filter { (id, _) -> t.slots.contains("$date|$id") }
                if (on.isEmpty()) null else Pair(BLOCKS.indexOf(on[0]), Entry(t.text, on.joinToString(" · ") { (id, d) -> label(id, d) }, t.done, t.priority, PlanData.color(t)))
            }.sortedBy { it.first }.map { it.second }

        // ---- Left column ----
        val avail = bottom - top
        val hFocus = avail * 0.25f
        val hPrio = avail * 0.20f
        val hTodo = avail * 0.30f
        var y = top
        val focus = RectF(lx, y, lx + colW, y + hFocus); y += hFocus + gap * 0.8f
        val prioR = RectF(lx, y, lx + colW, y + hPrio); y += hPrio + gap * 0.8f
        val todoR = RectF(lx, y, lx + colW, y + hTodo); y += hTodo + gap * 0.8f
        val notesR = RectF(lx, y, lx + colW, bottom)

        val head = 26f * s
        box(c, focus, "Today's Focus  ·  " + shortDay(today), s, serif, true)
        writeEntries(c, focus, head, dayEntries(todayIso), s, false, "Nothing scheduled today")

        box(c, prioR, "Priorities", s, serif, false)
        val prio = plan.tasks.filter { it.priority && !it.done }.map { t ->
            val next = t.slots.map { it.substringBefore("|") }.filter { it >= todayIso }.minOrNull()
            Entry(t.text, if (next != null) shortDay(LocalDate.parse(next, ISO)) else "", false, false, PlanData.color(t))
        }
        writeEntries(c, prioR, head, prio, s, true, null, ruled = false, chipsAfter = true)

        box(c, todoR, "To-Do", s, serif, false)
        val todo = plan.sections.indices.mapNotNull { si ->
            val ts = plan.tasks.filter { t -> t.section == si && t.slots.any { weekDates.contains(it.substringBefore("|")) } }
            if (ts.isEmpty()) null else Entry(plan.sections[si], "${ts.count { it.done }}/${ts.size}", ts.all { it.done }, false, PlanData.secColor(si))
        }
        writeEntries(c, todoR, head, todo, s, true, null, ruled = true, chipsAfter = true)

        box(c, notesR, "Notes", s, serif, false)
        writeNotes(c, notesR, head, plan.notes[ws.format(ISO)].orEmpty(), s)

        // ---- Right column: the seven days ----
        val dayGap = gap * 0.8f
        val dayH = (bottom - top - dayGap * 6) / 7f
        weekDates.forEachIndexed { i, date ->
            val d = ws.plusDays(i.toLong())
            val r = RectF(rx, top + i * (dayH + dayGap), rx + colW, top + i * (dayH + dayGap) + dayH)
            val name = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US) + "  " + d.monthValue + "/" + d.dayOfMonth
            box(c, r, name, s, serif, date == todayIso)
            writeEntries(c, r, head, dayEntries(date), s, false, null)
        }
        return bmp
    }

    // ---------- pieces ----------

    private fun shortDay(d: LocalDate) =
        d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US) + " " + d.monthValue + "/" + d.dayOfMonth

    private fun box(c: Canvas, r: RectF, label: String, s: Float, serif: Typeface, highlight: Boolean) {
        val head = 26f * s
        if (highlight) c.drawRect(r.left, r.top, r.right, r.top + head, Paint().apply { color = TODAY })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = LINE; strokeWidth = 1.4f * s }
        c.drawRect(r, p)
        c.drawLine(r.left, r.top + head, r.right, r.top + head, p)
        val t = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = serif; color = 0xFF151515.toInt(); textSize = 17f * s; textAlign = Paint.Align.CENTER }
        val txt = TextUtils.ellipsize(label, t, r.width() - 12f * s, TextUtils.TruncateAt.END).toString()
        c.drawText(txt, r.centerX(), r.top + head - 7f * s, t)
    }

    private fun writeEntries(
        c: Canvas, r: RectF, head: Float, items: List<Entry>, s: Float, checkbox: Boolean,
        emptyText: String?, ruled: Boolean = false, chipsAfter: Boolean = false
    ) {
        val lh = 23f * s
        val padX = 9f * s
        val top = r.top + head + 4f * s
        val cap = ((r.bottom - top - 2f * s) / lh).toInt().coerceAtLeast(0)
        val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f * s; color = INK }
        val chip = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11.5f * s; color = FAINT }
        val rule = Paint().apply { color = RULE; strokeWidth = 1f * s }
        val boxP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = LINE; strokeWidth = 1.2f * s }
        val star = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f * s; color = GOLD; textAlign = Paint.Align.RIGHT }
        val bar = Paint().apply { strokeWidth = 3f * s }

        if (items.isEmpty() && emptyText != null && cap > 0) {
            c.drawText(emptyText, r.left + padX, top + lh * 0.72f, chip)
        }
        val shown = if (items.size > cap) (cap - 1).coerceAtLeast(0) else items.size
        val lines = if (ruled || checkbox) cap else shown
        for (i in 0 until lines) {
            val base = top + i * lh + lh * 0.72f
            var x = r.left + padX
            if (ruled) c.drawLine(r.left + padX + (if (checkbox) 18f * s else 0f), top + (i + 1) * lh - 2f * s, r.right - padX, top + (i + 1) * lh - 2f * s, rule)
            if (checkbox) {
                val bs = 11f * s
                val bt = base - bs + 1f * s
                c.drawRect(x, bt, x + bs, bt + bs, boxP)
                if (i < shown && items[i].done) {
                    val path = Path().apply { moveTo(x + 2f * s, bt + bs * 0.55f); lineTo(x + bs * 0.42f, bt + bs - 2f * s); lineTo(x + bs - 1f * s, bt + 1.5f * s) }
                    c.drawPath(path, Paint(boxP).apply { color = INK; strokeWidth = 1.8f * s })
                }
                x += bs + 7f * s
            }
            if (i >= shown) continue
            val e = items[i]
            if (!checkbox) {
                bar.color = e.color
                c.drawLine(x, base - 11f * s, x, base + 3f * s, bar)
                x += 7f * s
            }
            var right = r.right - padX
            if (e.star) right -= 14f * s
            if (e.star) c.drawText("★", r.right - padX, base, star)
            var chipW = 0f
            if (e.chips.isNotEmpty()) chipW = chip.measureText(e.chips) + 6f * s
            val textMax = (right - x - chipW).coerceAtLeast(20f * s)
            ink.color = if (e.done && !checkbox) FAINT else INK
            ink.isStrikeThruText = e.done && !checkbox
            if (!chipsAfter && e.chips.isNotEmpty()) { c.drawText(e.chips, x, base, chip); x += chipW }
            val t = TextUtils.ellipsize(e.text, ink, textMax, TextUtils.TruncateAt.END).toString()
            c.drawText(t, x, base, ink)
            if (chipsAfter && e.chips.isNotEmpty()) c.drawText(e.chips, x + ink.measureText(t) + 6f * s, base, chip)
        }
        if (items.size > shown && cap > 0) {
            val base = top + shown * lh + lh * 0.72f
            c.drawText("+ ${items.size - shown} more", r.left + padX + (if (checkbox) 18f * s else 7f * s), base, chip)
        }
    }

    private fun writeNotes(c: Canvas, r: RectF, head: Float, note: String, s: Float) {
        val lh = 23f * s
        val padX = 9f * s
        val top = r.top + head + 4f * s
        val cap = ((r.bottom - top - 2f * s) / lh).toInt().coerceAtLeast(0)
        val dots = Paint().apply { color = RULE; strokeWidth = 1f * s; pathEffect = DashPathEffect(floatArrayOf(3f * s, 3f * s), 0f) }
        for (i in 1..cap) c.drawLine(r.left + padX, top + i * lh - 2f * s, r.right - padX, top + i * lh - 2f * s, dots)
        if (note.isBlank()) return
        val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f * s; color = INK }
        val maxW = r.width() - 2 * padX
        val lines = mutableListOf<String>()
        note.trim().split("\n").forEach { para ->
            var cur = ""
            para.split(" ").forEach { word ->
                val next = if (cur.isEmpty()) word else "$cur $word"
                if (ink.measureText(next) <= maxW) cur = next else { if (cur.isNotEmpty()) lines += cur; cur = word }
            }
            lines += cur
        }
        lines.take(cap).forEachIndexed { i, ln ->
            val t = if (i == cap - 1 && lines.size > cap) TextUtils.ellipsize("$ln …", ink, maxW, TextUtils.TruncateAt.END).toString()
                    else TextUtils.ellipsize(ln, ink, maxW, TextUtils.TruncateAt.END).toString()
            c.drawText(t, r.left + padX, top + i * lh + lh * 0.72f, ink)
        }
    }

    /** A meander band on all four sides; each side is drawn as a horizontal strip, inner edge at +y. */
    private fun drawKeyFrame(c: Canvas, w: Float, h: Float, b: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = KEY; strokeWidth = b / 9f; strokeJoin = Paint.Join.MITER; strokeCap = Paint.Cap.SQUARE
        }
        fun strip(len: Float) {
            val g = b / 6f
            val unit = 6f * g
            val n = ((len - 2 * b) / unit).toInt().coerceAtLeast(1)
            val start = (len - n * unit) / 2f
            c.drawLine(0f, 0.6f * g, len, 0.6f * g, p)
            c.drawLine(b, 5.4f * g, len - b, 5.4f * g, p)
            val path = Path()
            for (k in 0 until n) {
                val ox = start + k * unit
                path.moveTo(ox, 5.4f * g)
                path.lineTo(ox, 1.8f * g)
                path.lineTo(ox + 4.2f * g, 1.8f * g)
                path.lineTo(ox + 4.2f * g, 4.2f * g)
                path.lineTo(ox + 2.2f * g, 4.2f * g)
                path.lineTo(ox + 2.2f * g, 3.0f * g)
            }
            c.drawPath(path, p)
        }
        c.save(); strip(w); c.restore()
        c.save(); c.translate(w, h); c.rotate(180f); strip(w); c.restore()
        c.save(); c.translate(0f, h); c.rotate(-90f); strip(h); c.restore()
        c.save(); c.translate(w, 0f); c.rotate(90f); strip(h); c.restore()
        // outer edge
        val edge = Paint(p).apply { strokeWidth = b / 9f }
        c.drawRect(b / 18f, b / 18f, w - b / 18f, h - b / 18f, edge)
    }
}
