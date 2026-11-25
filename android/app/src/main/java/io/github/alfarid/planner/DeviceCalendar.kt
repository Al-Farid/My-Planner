package io.github.alfarid.planner

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Reads the calendars this phone or tablet already shows (Samsung, Google accounts, holidays,
 * Islamic dates…) and sends them to your planner so the Calendar tab on every device shows them.
 */
object DeviceCalendar {
    data class Cal(val name: String, val color: Int, val account: String)
    /** start/end are dates (end inclusive); time is "HH:mm" for timed events, "" for all-day. */
    data class Ev(val title: String, val start: String, val end: String, val time: String, val cal: Int, val color: Int)

    private val ISO = DateTimeFormatter.ISO_LOCAL_DATE
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    fun granted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun read(ctx: Context, from: LocalDate, to: LocalDate): Pair<List<Cal>, List<Ev>> {
        if (!granted(ctx)) return Pair(emptyList(), emptyList())
        val cr = ctx.contentResolver
        val zone = ZoneId.systemDefault()
        // Calendars that are switched on in the phone's calendar app
        val cals = mutableListOf<Cal>()
        val calIndex = mutableMapOf<Long, Int>()
        try {
            cr.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.CALENDAR_COLOR,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Calendars.VISIBLE
                ), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(4) != 1) continue
                    calIndex[c.getLong(0)] = cals.size
                    cals += Cal(c.getString(1) ?: "Calendar", c.getInt(2), c.getString(3) ?: "")
                }
            }
        } catch (e: Exception) { return Pair(emptyList(), emptyList()) }

        val begin = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, begin)
        ContentUris.appendId(b, end)
        val evs = mutableListOf<Ev>()
        try {
            cr.query(
                b.build(),
                arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.CALENDAR_ID,
                    CalendarContract.Instances.DISPLAY_COLOR
                ), null, null, CalendarContract.Instances.BEGIN + " ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val k = calIndex[c.getLong(4)] ?: continue
                    val s = c.getLong(1)
                    val e = maxOf(c.getLong(2), s)
                    val allDay = c.getInt(3) == 1
                    val sd: LocalDate
                    val ed: LocalDate
                    var time = ""
                    if (allDay) {
                        sd = Instant.ofEpochMilli(s).atZone(ZoneOffset.UTC).toLocalDate()
                        ed = maxOf(sd, Instant.ofEpochMilli(e).atZone(ZoneOffset.UTC).toLocalDate().minusDays(1))
                    } else {
                        val zs = Instant.ofEpochMilli(s).atZone(zone)
                        sd = zs.toLocalDate()
                        ed = maxOf(sd, Instant.ofEpochMilli(maxOf(s, e - 1)).atZone(zone).toLocalDate())
                        time = zs.format(HM)
                    }
                    evs += Ev(c.getString(0) ?: "", sd.format(ISO), ed.format(ISO), time, k, c.getInt(5))
                }
            }
        } catch (e: Exception) { /* provider unavailable */ }
        return Pair(cals, evs)
    }

    fun hex(c: Int) = String.format("#%06X", 0xFFFFFF and c)

    /** Send the window last month … four months ahead to the planner, only when something changed. */
    fun upload(ctx: Context, force: Boolean = false) {
        val p = Config.prefs(ctx)
        val feed = p.getString("feed", null) ?: return
        if (!granted(ctx)) return
        val today = LocalDate.now()
        val from = today.withDayOfMonth(1).minusMonths(1)
        val to = today.withDayOfMonth(1).plusMonths(5).minusDays(1)
        val (cals, evs) = read(ctx, from, to)
        val calList = cals.map { mapOf("n" to it.name, "c" to hex(it.color), "a" to it.account) }
        val evList = evs.take(4000).map { e ->
            val m = mutableMapOf<String, Any>("t" to e.title.take(120), "d" to e.start, "k" to e.cal)
            if (e.end != e.start) m["e"] = e.end
            if (e.time.isNotEmpty()) m["tm"] = e.time
            m
        }
        val sig = (calList.toString() + evList.toString()).hashCode()
        val last = p.getLong("calUp", 0L)
        if (!force && sig == p.getInt("calSig", 0) && System.currentTimeMillis() - last < 6 * 3600_000L) return
        val data = mapOf("updated" to System.currentTimeMillis(), "cals" to calList, "ev" to evList)
        Config.db(ctx).collection("devcal").document(feed).set(data)
            .addOnSuccessListener {
                p.edit().putInt("calSig", sig).putLong("calUp", System.currentTimeMillis()).remove("calErr").apply()
            }
            .addOnFailureListener { err ->
                p.edit().putString("calErr", err.message ?: "error").apply()
            }
    }
}
