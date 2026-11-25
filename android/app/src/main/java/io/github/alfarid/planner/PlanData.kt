package io.github.alfarid.planner

import android.content.Context
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** One task as the planner publishes it for the widget. */
data class Task(
    val text: String,
    val section: Int,
    val done: Boolean,
    val priority: Boolean,
    val slots: List<String>,
    val due: String = ""
)

data class Habit(val name: String, val days: Set<String>, val from: String = "2026-11-23")

data class Plan(
    val sections: List<String>,
    val tasks: List<Task>,
    val notes: Map<String, String>,
    val updated: Long,
    val labels: Map<String, String> = emptyMap(),
    val habits: List<Habit> = emptyList(),
    val secDue: Map<String, String> = emptyMap()
)

/** A line in the widget list. */
sealed class Row {
    data class Header(val title: String, val sub: String, val today: Boolean) : Row()
    data class Item(
        val text: String,
        val chips: String,
        val color: Int,
        val done: Boolean,
        val priority: Boolean,
        val sub: String
    ) : Row()
    data class Note(val text: String) : Row()
}

object PlanData {
    private val BLOCKS = listOf("am" to "9–12", "pm" to "2–5", "ev" to "6–9")
    private val COLORS = intArrayOf(
        0xFF2F6FD0.toInt(), 0xFF1F8F6A.toInt(), 0xFFC2721A.toInt(),
        0xFF7A4FC4.toInt(), 0xFFB63B63.toInt(), 0xFF4A7A2C.toInt()
    )
    private val ISO = DateTimeFormatter.ISO_LOCAL_DATE

    fun load(ctx: Context): Plan? = parse(Config.prefs(ctx).getString("plan", null))

    fun parse(json: String?): Plan? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val secs = o.optJSONArray("sections")
            val sections = (0 until (secs?.length() ?: 0)).map { secs!!.optString(it) }
            val arr = o.optJSONArray("tasks")
            val tasks = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val t = arr!!.optJSONObject(i) ?: return@mapNotNull null
                val l = t.optJSONArray("l")
                Task(
                    text = t.optString("x"),
                    section = t.optInt("s", 0),
                    done = t.optBoolean("d", false),
                    priority = t.optBoolean("p", false),
                    slots = (0 until (l?.length() ?: 0)).map { l!!.optString(it) },
                    due = t.optString("u", "")
                )
            }
            val n = o.optJSONObject("notes")
            val notes = mutableMapOf<String, String>()
            n?.keys()?.forEach { k -> notes[k] = n.optString(k) }
            val b = o.optJSONObject("blocks")
            val labels = mutableMapOf<String, String>()
            b?.keys()?.forEach { key -> labels[key] = b.optString(key) }
            val ha = o.optJSONArray("habits")
            val habits = (0 until (ha?.length() ?: 0)).mapNotNull { i ->
                val h = ha!!.optJSONObject(i) ?: return@mapNotNull null
                val dd = h.optJSONArray("d")
                Habit(h.optString("n"), (0 until (dd?.length() ?: 0)).map { dd!!.optString(it) }.toSet(), h.optString("f", "2026-11-23"))
            }
            val sd = o.optJSONObject("secDue")
            val secDue = mutableMapOf<String, String>()
            sd?.keys()?.forEach { k -> secDue[k] = sd.optString(k) }
            Plan(sections, tasks, notes, o.optLong("updated", 0L), labels, habits, secDue)
        } catch (e: Exception) {
            null
        }
    }

    fun weekStart(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun rangeText(day: LocalDate): String {
        val ws = weekStart(day)
        val we = ws.plusDays(6)
        val f = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        return "${ws.format(f)} – ${we.format(f)}"
    }

    private fun shortDay(d: LocalDate): String =
        d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US) + " " + d.monthValue + "/" + d.dayOfMonth

    private fun blocksOn(t: Task, date: String, plan: Plan): List<String> =
        BLOCKS.filter { (id, _) -> t.slots.contains("$date|$id") }
            .map { (id, def) -> plan.labels[id]?.takeIf { it.isNotBlank() } ?: def }

    fun color(t: Task) = COLORS[Math.floorMod(t.section, COLORS.size)]
    fun secColor(si: Int) = COLORS[Math.floorMod(si, COLORS.size)]

    fun rows(plan: Plan?, today: LocalDate): List<Row> {
        if (plan == null) return emptyList()
        val out = mutableListOf<Row>()
        val ws = weekStart(today)
        val weekDates = (0..6).map { ws.plusDays(it.toLong()).format(ISO) }
        val todayIso = today.format(ISO)

        fun dayItems(date: String): List<Row.Item> =
            plan.tasks.mapNotNull { t ->
                val first = BLOCKS.indexOfFirst { (id, _) -> t.slots.contains("$date|$id") }
                if (first < 0) null else Pair(first, t to blocksOn(t, date, plan))
            }.sortedBy { it.first }.map { (_, p) ->
                val (t, b) = p
                Row.Item(t.text, b.joinToString(" · "), color(t), t.done, t.priority, "")
            }

        // Today's focus
        out += Row.Header("Today's Focus", shortDay(today), true)
        val todayItems = dayItems(todayIso)
        if (todayItems.isEmpty()) out.add(Row.Note("Nothing scheduled today")) else out.addAll(todayItems)

        // Priorities: starred, not done
        val prio = plan.tasks.filter { it.priority && !it.done }
        if (prio.isNotEmpty()) {
            out += Row.Header("Priorities", "", false)
            prio.forEach { t ->
                val next = t.slots.map { it.substringBefore("|") }.filter { it >= todayIso }.minOrNull()
                val sub = if (next != null) shortDay(LocalDate.parse(next, ISO)) else "not scheduled"
                out += Row.Item(t.text, "", color(t), false, true, sub)
            }
        }

        // To-do: sections with tasks this week
        val todo = plan.sections.indices.mapNotNull { si ->
            val ts = plan.tasks.filter { t -> t.section == si && t.slots.any { weekDates.contains(it.substringBefore("|")) } }
            if (ts.isEmpty()) null else Triple(si, ts.count { it.done }, ts.size)
        }
        if (todo.isNotEmpty()) {
            out += Row.Header("To-Do", "this week", false)
            todo.forEach { (si, d, n) ->
                out += Row.Item(plan.sections[si], "$d/$n", COLORS[si % COLORS.size], d == n, false, "")
            }
        }

        // The seven days
        weekDates.forEachIndexed { i, date ->
            val d = ws.plusDays(i.toLong())
            val items = dayItems(date)
            val title = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
            val sub = "${d.monthValue}/${d.dayOfMonth}" + if (items.isNotEmpty()) " · ${items.count { it.done }}/${items.size} done" else ""
            out += Row.Header(title, sub, date == todayIso)
            if (items.isEmpty()) out.add(Row.Note("Free")) else out.addAll(items)
        }

        // Notes for this week
        val note = plan.notes[ws.format(ISO)]
        if (!note.isNullOrBlank()) {
            out += Row.Header("Notes", "", false)
            out += Row.Note(note.trim())
        }
        return out
    }
}
