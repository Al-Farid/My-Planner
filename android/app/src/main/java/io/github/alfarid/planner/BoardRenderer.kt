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
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Draws the widget in the planner's own style: Weekly plan title, then
 * Today's Focus / Priorities / To-Do / Notes on the left and Monday–Sunday boxes on the right.
 */
object BoardRenderer {
    private val ISO = DateTimeFormatter.ISO_LOCAL_DATE
    private val BLOCKS = listOf("am" to "9–12", "pm" to "2–5", "ev" to "6–9")

    // The planner's light palette
    private const val BG = 0xFFF1F4F9.toInt()
    private const val SURFACE = 0xFFFFFFFF.toInt()
    private const val HEAD = 0xFFF6F8FC.toInt()
    private const val LINE = 0xFFD3DAEA.toInt()
    private const val RULE = 0xFFE6EAF2.toInt()
    private const val INK = 0xFF101D33.toInt()
    private const val MUTED = 0xFF5A6683.toInt()
    private const val FAINT = 0xFF8A93A8.toInt()
    private const val ACCENT = 0xFF1C4EA3.toInt()
    private const val TODAY = 0xFFFFF8E2.toInt()
    private const val TODAY_EDGE = 0xFFD9A21B.toInt()
    private const val PRIO = 0xFFFFF1BF.toInt()
    private const val GOLD = 0xFFD39A16.toInt()
    private const val OFF = 0xFFE3E8F2.toInt()
    private val LIGHT = intArrayOf(
        0xFFDBE8FB.toInt(), 0xFFD8F0E7.toInt(), 0xFFFBE8D2.toInt(),
        0xFFE9E0FA.toInt(), 0xFFFADBE6.toInt(), 0xFFE2F0D6.toInt()
    )

    private var serif: Typeface? = null
    private var sans: Typeface? = null
    private var sansBold: Typeface? = null

    private fun fonts(ctx: Context) {
        if (serif != null) return
        fun load(file: String, wght: Int, fallback: Typeface): Typeface = try {
            Typeface.Builder(ctx.assets, "fonts/$file").setFontVariationSettings("'wght' $wght").build() ?: fallback
        } catch (e: Throwable) { fallback }
        serif = load("cormorant.ttf", 600, Typeface.SERIF)
        sans = load("inter.ttf", 400, Typeface.SANS_SERIF)
        sansBold = load("inter.ttf", 600, Typeface.DEFAULT_BOLD)
    }

    private class Entry(
        val text: String, val blocks: List<Boolean>, val color: Int, val light: Int,
        val done: Boolean, val star: Boolean, val chip: String = "", val sub: String = ""
    )

    fun render(ctx: Context, plan: Plan?, today: LocalDate, wPx: Int, hPx: Int, connected: Boolean): Bitmap {
        fonts(ctx)
        val w = wPx.coerceAtLeast(200)
        val h = hPx.coerceAtLeast(200)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = minOf(w / 760f, h / 1000f).coerceIn(0.35f, 3f)

        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BG }
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 22f * s, 22f * s, bg)

        val pad = 16f * s
        // ---- Title ----
        val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = serif; color = INK; textSize = 40f * s }
        c.drawText("Weekly plan", pad + 2f * s, pad + 34f * s, title)
        val tw = title.measureText("Weekly plan")
        val range = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; color = MUTED; textSize = 13f * s }
        val labels = BLOCKS.joinToString(" · ") { (id, d) -> plan?.labels?.get(id)?.takeIf { it.isNotBlank() } ?: d }
        val sub = PlanData.rangeText(today) + "   ·   " + labels
        c.drawText(TextUtils.ellipsize(sub, range, w - pad * 2 - tw - 14f * s, TextUtils.TruncateAt.END).toString(), pad + tw + 14f * s, pad + 32f * s, range)

        val top = pad + 52f * s
        val bottom = h - pad
        val gap = 12f * s
        val colW = (w - 2 * pad - gap) / 2f
        val lx = pad
        val rx = pad + colW + gap

        if (plan == null) {
            val msg = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; color = MUTED; textSize = 15f * s; textAlign = Paint.Align.CENTER }
            c.drawText(if (connected) "Loading your week…" else "Open Planner Widget to connect", w / 2f, (top + bottom) / 2f, msg)
            return bmp
        }

        val ws = PlanData.weekStart(today)
        val we = ws.plusDays(6)
        val weekDates = (0..6).map { ws.plusDays(it.toLong()).format(ISO) }
        val wsIso = ws.format(ISO)
        val weIso = we.format(ISO)
        val todayIso = today.format(ISO)
        fun light(si: Int) = LIGHT[Math.floorMod(si, LIGHT.size)]

        fun dayEntries(date: String): List<Entry> =
            plan.tasks.mapNotNull { t ->
                val on = BLOCKS.map { (id, _) -> t.slots.contains("$date|$id") }
                val first = on.indexOf(true)
                if (first < 0) null else Pair(first, Entry(t.text, on, PlanData.color(t), light(t.section), t.done, t.priority))
            }.sortedBy { it.first }.map { it.second }

        // ---- Left column ----
        val avail = bottom - top - 3 * gap
        val todayList = dayEntries(todayIso)
        val prio = plan.tasks.filter { t ->
            t.priority && (t.slots.isEmpty() || t.slots.any { val d = it.substringBefore("|"); d >= wsIso && d <= weIso })
        }.sortedBy { if (it.done) 1 else 0 }.map { t ->
            val d = t.slots.map { it.substringBefore("|") }.filter { it >= wsIso && it <= weIso }.minOrNull()
            Entry(t.text, emptyList(), PlanData.color(t), light(t.section), t.done, true,
                sub = if (d != null) shortDay(LocalDate.parse(d, ISO)) else "not scheduled")
        }
        val todo = plan.sections.indices.mapNotNull { si ->
            val ts = plan.tasks.filter { t -> t.section == si && t.slots.any { weekDates.contains(it.substringBefore("|")) } }
            if (ts.isEmpty()) null else Entry(plan.sections[si], emptyList(), PlanData.secColor(si), light(si),
                ts.all { it.done }, false, chip = "${ts.count { it.done }}/${ts.size}")
        }
        val note = plan.notes[wsIso].orEmpty()
        val habits = plan.habits.map { h ->
            Entry(h.name, weekDates.map { h.days.contains(it) }, 0, 0, h.days.contains(todayIso), false)
        }

        // Size the left boxes by what they hold, within limits.
        val head = 30f * s
        val lh = 27f * s
        fun need(n: Int, min: Int) = head + 8f * s + lh * maxOf(n, min)
        var hF = need(todayList.size, 1)
        var hP = need(prio.size, 1)
        var hT = need(todo.size, 1)
        val hNmin = need(0, 2)
        var hH = need(habits.size, 1)
        val sum = hF + hP + hT + hNmin + hH + gap
        if (sum > avail + gap) { val k = (avail - gap) / (sum - gap); hF *= k; hP *= k; hT *= k; hH *= k }
        var y = top
        val rF = RectF(lx, y, lx + colW, y + hF); y += hF + gap
        val rP = RectF(lx, y, lx + colW, y + hP); y += hP + gap
        val rT = RectF(lx, y, lx + colW, y + hT); y += hT + gap
        val rN = RectF(lx, y, lx + colW, bottom - hH - gap)
        val rH = RectF(lx, bottom - hH, lx + colW, bottom)

        card(c, rF, "Today's Focus", shortDay(today), s, today = true, gold = false)
        rows(c, rF, todayList, s, Mode.DAY, "Nothing scheduled today")
        card(c, rP, "Priorities", "★ this week", s)
        rows(c, rP, prio, s, Mode.PRIO, "No starred tasks this week")
        card(c, rT, "To-Do", "sections this week", s)
        rows(c, rT, todo, s, Mode.TODO, "No sections scheduled this week")
        card(c, rN, "Notes", "week of ${ws.monthValue}/${ws.dayOfMonth}", s)
        notes(c, rN, note, s)
        card(c, rH, "Today's Habits", shortDay(today), s)
        rows(c, rH, habits, s, Mode.HABIT, "No habits yet", todayIndex = weekDates.indexOf(todayIso))

        // ---- Right column: the seven days ----
        val dayH = (bottom - top - gap * 6) / 7f
        weekDates.forEachIndexed { i, date ->
            val d = ws.plusDays(i.toLong())
            val r = RectF(rx, top + i * (dayH + gap), rx + colW, top + i * (dayH + gap) + dayH)
            val items = dayEntries(date)
            val subTxt = "${d.monthValue}/${d.dayOfMonth}" + if (items.isNotEmpty()) " · ${items.count { it.done }}/${items.size} done" else ""
            card(c, r, d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US), subTxt, s, today = date == todayIso)
            rows(c, r, items, s, Mode.DAY, "Free")
        }
        return bmp
    }

    private enum class Mode { DAY, PRIO, TODO, HABIT }

    private fun shortDay(d: LocalDate) =
        d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US) + " " + d.monthValue + "/" + d.dayOfMonth

    private fun card(c: Canvas, r: RectF, title: String, sub: String, s: Float, today: Boolean = false, gold: Boolean = today) {
        val rad = 10f * s
        val head = 30f * s
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SURFACE }
        c.drawRoundRect(r, rad, rad, fill)
        // header band
        c.save()
        c.clipRect(r.left, r.top, r.right, r.top + head)
        c.drawRoundRect(r, rad, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (today) TODAY else HEAD })
        c.restore()
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = LINE; strokeWidth = 1f * s }
        c.drawLine(r.left, r.top + head, r.right, r.top + head, edge)
        if (gold) { edge.color = TODAY_EDGE; edge.strokeWidth = 2f * s }
        c.drawRoundRect(r, rad, rad, edge)

        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = serif; color = INK; textSize = 19f * s }
        val sp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; color = MUTED; textSize = 11.5f * s }
        val gapW = 6f * s
        var tw = tp.measureText(title)
        val sw = if (sub.isEmpty()) 0f else sp.measureText(sub) + gapW
        val maxW = r.width() - 16f * s
        val t = if (tw + sw > maxW) TextUtils.ellipsize(title, tp, maxW - sw, TextUtils.TruncateAt.END).toString() else title
        tw = tp.measureText(t)
        val x0 = r.centerX() - (tw + sw) / 2f
        val base = r.top + head - 9f * s
        c.drawText(t, x0, base, tp)
        if (sub.isNotEmpty()) c.drawText(sub, x0 + tw + gapW, base, sp)
    }

    private fun rows(c: Canvas, r: RectF, items: List<Entry>, s: Float, mode: Mode, empty: String, todayIndex: Int = -1) {
        val head = 30f * s
        val lh = 27f * s
        val padX = 10f * s
        val top = r.top + head + 4f * s
        val cap = ((r.bottom - top - 3f * s) / lh).toInt()
        if (cap <= 0) return
        val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; textSize = 14f * s }
        val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; textSize = 11.5f * s; color = MUTED }
        if (items.isEmpty()) {
            c.drawText(empty, r.left + padX, top + lh * 0.66f, small.apply { color = FAINT; textSize = 13f * s })
            return
        }
        val shown = if (items.size > cap) cap - 1 else items.size
        for (i in 0 until shown) {
            val e = items[i]
            val rowTop = top + i * lh
            val mid = rowTop + lh / 2f - 1f * s
            val base = mid + 5f * s
            if (e.star && mode != Mode.TODO) {
                c.drawRoundRect(RectF(r.left + 5f * s, rowTop + 1.5f * s, r.right - 5f * s, rowTop + lh - 1.5f * s), 6f * s, 6f * s,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PRIO })
            }
            var x = r.left + padX
            checkbox(c, x, mid, s, e.done)
            x += 14f * s + 8f * s
            when (mode) {
                Mode.DAY -> { blockBars(c, x, mid, s, e.blocks, e.color); x += 3 * 5f * s + 2 * 2f * s + 8f * s }
                Mode.TODO -> {
                    small.typeface = sansBold; small.color = e.color
                    val cw = small.measureText(e.chip) + 10f * s
                    c.drawRoundRect(RectF(x, mid - 9f * s, x + cw, mid + 9f * s), 5f * s, 5f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = e.light })
                    c.drawText(e.chip, x + 5f * s, mid + 4f * s, small)
                    small.typeface = sans; small.color = MUTED
                    x += cw + 8f * s
                }
                Mode.PRIO, Mode.HABIT -> {}
            }
            var right = r.right - padX
            if (mode == Mode.HABIT) {
                val dot = 4.5f * s
                val step = 12f * s
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                for (k in 0 until 7) {
                    val cx = right - (6 - k) * step - dot
                    p.style = Paint.Style.FILL
                    p.color = if (e.blocks.getOrElse(k) { false }) 0xFF1F8F6A.toInt() else OFF
                    c.drawCircle(cx, mid, dot, p)
                    if (k == todayIndex) {
                        p.style = Paint.Style.STROKE; p.strokeWidth = 1.3f * s; p.color = INK
                        c.drawCircle(cx, mid, dot + 1.2f * s, p)
                    }
                }
                right -= 7 * step + 6f * s
            }
            if (e.star && mode != Mode.TODO) {
                val st = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f * s; color = GOLD; textAlign = Paint.Align.RIGHT }
                c.drawText("★", right, base, st)
                right -= 18f * s
            }
            val subW = if (e.sub.isNotEmpty()) small.measureText(e.sub) + 8f * s else 0f
            ink.color = if (e.done) FAINT else INK
            ink.isStrikeThruText = e.done
            val t = TextUtils.ellipsize(e.text, ink, (right - x - subW).coerceAtLeast(10f * s), TextUtils.TruncateAt.END).toString()
            c.drawText(t, x, base, ink)
            if (e.sub.isNotEmpty()) {
                small.isStrikeThruText = e.done
                c.drawText(e.sub, x + ink.measureText(t) + 8f * s, base, small)
                small.isStrikeThruText = false
            }
        }
        if (items.size > shown) {
            c.drawText("+ ${items.size - shown} more", r.left + padX + 22f * s, top + shown * lh + lh * 0.66f, small)
        }
    }

    private fun checkbox(c: Canvas, x: Float, mid: Float, s: Float, done: Boolean) {
        val sz = 14f * s
        val box = RectF(x, mid - sz / 2f, x + sz, mid + sz / 2f)
        if (done) {
            c.drawRoundRect(box, 3f * s, 3f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = SURFACE; strokeWidth = 2f * s; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            c.drawPath(Path().apply {
                moveTo(box.left + sz * 0.24f, box.top + sz * 0.52f)
                lineTo(box.left + sz * 0.43f, box.top + sz * 0.72f)
                lineTo(box.left + sz * 0.78f, box.top + sz * 0.3f)
            }, tick)
        } else {
            c.drawRoundRect(box, 3f * s, 3f * s, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF9AA5BD.toInt(); strokeWidth = 1.4f * s })
        }
    }

    private fun blockBars(c: Canvas, x: Float, mid: Float, s: Float, on: List<Boolean>, color: Int) {
        val bw = 5f * s
        val bh = 14f * s
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (k in 0 until 3) {
            p.color = if (on.getOrElse(k) { false }) color else OFF
            val bx = x + k * (bw + 2f * s)
            c.drawRoundRect(RectF(bx, mid - bh / 2f, bx + bw, mid + bh / 2f), 1.5f * s, 1.5f * s, p)
        }
    }

    private fun notes(c: Canvas, r: RectF, note: String, s: Float) {
        val head = 30f * s
        val lh = 26f * s
        val padX = 10f * s
        val top = r.top + head
        val cap = ((r.bottom - top - 2f * s) / lh).toInt().coerceAtLeast(0)
        val rule = Paint().apply { color = RULE; strokeWidth = 1f * s }
        for (i in 1 until cap + 1) {
            val yy = top + i * lh
            if (yy < r.bottom - 4f * s) c.drawLine(r.left + 1f * s, yy, r.right - 1f * s, yy, rule)
        }
        if (note.isBlank()) return
        val ink = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; textSize = 13.5f * s; color = INK }
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
            val txt = if (i == cap - 1 && lines.size > cap) "$ln …" else ln
            c.drawText(TextUtils.ellipsize(txt, ink, maxW, TextUtils.TruncateAt.END).toString(), r.left + padX, top + (i + 1) * lh - 8f * s, ink)
        }
    }
}
