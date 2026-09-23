package com.opensolr.mail.dav

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import com.opensolr.mail.data.MailAccount
import java.util.TimeZone
import java.util.UUID

/**
 * Fastmail calendars inside Android's own calendar store, so Google Calendar (or any calendar app)
 * shows and edits them. Two-way: server changes come down by ETag, local edits go up by PUT/DELETE.
 * Properties the phone does not model (guests, organizer, ...) are kept from the original event.
 */
class CalendarSync(private val context: Context, private val account: MailAccount) {

    private val resolver: ContentResolver = context.contentResolver
    private val sysAccount = Account(account.username, ACCOUNT_TYPE)
    private val dav = Dav(context, account)

    suspend fun run() {
        ensureAccount(context, account.username)
        val remote = discover()
        val local = localCalendars()

        remote.forEach { rc ->
            val id = local[rc.href]?.first ?: insertCalendar(rc)
            updateCalendar(id, rc)
            upload(id, rc)
            if (local[rc.href]?.second != rc.ctag || rc.ctag.isEmpty()) download(id, rc)
            setCtag(id, rc.ctag)
        }
        local.filterKeys { href -> remote.none { it.href == href } }.values.forEach { (id, _) ->
            resolver.delete(syncUri(ContentUris.withAppendedId(Calendars.CONTENT_URI, id)), null, null)
        }
    }

    data class RemoteCalendar(val href: String, val name: String, val color: Int?, val ctag: String, val writable: Boolean)

    private suspend fun discover(): List<RemoteCalendar> {
        val root = "https://caldav.fastmail.com/dav/"
        val principal = dav.propfind(root, 0, PROPFIND_PRINCIPAL).firstOrNull()?.props?.get("current-user-principal")
            ?: throw IllegalStateException("No CalDAV principal")
        val principalUrl = dav.resolve(root, principal)
        val home = dav.propfind(principalUrl, 0, PROPFIND_HOME).firstOrNull()?.props?.get("calendar-home-set")
            ?: throw IllegalStateException("No calendar home")
        val homeUrl = dav.resolve(principalUrl, home)
        return dav.propfind(homeUrl, 1, PROPFIND_CALENDARS).filter { e ->
            e.children["resourcetype"]?.contains("calendar") == true &&
                (e.children["supported-calendar-component-set:comp"]?.contains("VEVENT") ?: true)
        }.map { e ->
            val privileges = e.children["current-user-privilege-set"].orEmpty()
            RemoteCalendar(
                href = dav.resolve(homeUrl, e.href),
                name = e.props["displayname"].orEmpty().ifBlank { e.href.trimEnd('/').substringAfterLast('/') },
                color = parseColor(e.props["calendar-color"]),
                ctag = e.props["getctag"].orEmpty(),
                writable = privileges.isEmpty() || privileges.any { it == "write" || it == "write-content" || it == "all" },
            )
        }
    }

    private fun localCalendars(): Map<String, Pair<Long, String>> {
        val out = HashMap<String, Pair<Long, String>>()
        resolver.query(
            Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars._SYNC_ID, Calendars.CAL_SYNC1),
            "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?", arrayOf(sysAccount.name, ACCOUNT_TYPE), null,
        )?.use { c -> while (c.moveToNext()) out[c.getString(1) ?: ""] = c.getLong(0) to (c.getString(2) ?: "") }
        return out
    }

    private fun insertCalendar(rc: RemoteCalendar): Long {
        val v = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, sysAccount.name)
            put(Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
            put(Calendars._SYNC_ID, rc.href)
            put(Calendars.OWNER_ACCOUNT, sysAccount.name)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.VISIBLE, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
            put(Calendars.ALLOWED_REMINDERS, "${Reminders.METHOD_ALERT},${Reminders.METHOD_DEFAULT}")
            put(Calendars.ALLOWED_AVAILABILITY, "${Events.AVAILABILITY_BUSY},${Events.AVAILABILITY_FREE}")
        }
        val uri = resolver.insert(syncUri(Calendars.CONTENT_URI), v) ?: throw IllegalStateException("Calendar insert failed")
        return ContentUris.parseId(uri)
    }

    private fun updateCalendar(id: Long, rc: RemoteCalendar) {
        val v = ContentValues().apply {
            put(Calendars.NAME, rc.name)
            put(Calendars.CALENDAR_DISPLAY_NAME, rc.name)
            put(Calendars.CALENDAR_COLOR, rc.color ?: account.color)
            put(Calendars.CALENDAR_ACCESS_LEVEL, if (rc.writable) Calendars.CAL_ACCESS_OWNER else Calendars.CAL_ACCESS_READ)
        }
        resolver.update(syncUri(ContentUris.withAppendedId(Calendars.CONTENT_URI, id)), v, null, null)
    }

    private fun setCtag(id: Long, ctag: String) {
        resolver.update(syncUri(ContentUris.withAppendedId(Calendars.CONTENT_URI, id)), ContentValues().apply { put(Calendars.CAL_SYNC1, ctag) }, null, null)
    }

    // ---------------- down ----------------

    private data class LocalEvent(val id: Long, val href: String?, val etag: String?, val dirty: Boolean, val deleted: Boolean, val original: String?)

    private fun localEvents(calId: Long): List<LocalEvent> {
        val out = ArrayList<LocalEvent>()
        resolver.query(
            syncUri(Events.CONTENT_URI),
            arrayOf(Events._ID, Events._SYNC_ID, Events.SYNC_DATA1, Events.DIRTY, Events.DELETED, Events.ORIGINAL_SYNC_ID),
            "${Events.CALENDAR_ID} = ?", arrayOf(calId.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) out += LocalEvent(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) == 1, c.getInt(4) == 1, c.getString(5))
        }
        return out
    }

    private suspend fun download(calId: Long, rc: RemoteCalendar) {
        val remote = dav.propfind(rc.href, 1, PROPFIND_ETAGS)
            .filter { !it.href.endsWith("/") && it.props["getetag"] != null }
            .associate { dav.resolve(rc.href, it.href) to it.props["getetag"]!! }
        val masters = localEvents(calId).filter { it.original == null }
        val byHref = masters.filter { it.href != null }.associateBy { it.href!! }

        val gone = byHref.values.filter { it.href !in remote && !it.dirty }.map { it.href!! }
        if (gone.isNotEmpty()) {
            resolver.applyBatch(CalendarContract.AUTHORITY, ArrayList(gone.map { href ->
                android.content.ContentProviderOperation.newDelete(syncUri(Events.CONTENT_URI))
                    .withSelection("${Events.CALENDAR_ID} = ? AND (${Events._SYNC_ID} = ? OR ${Events.ORIGINAL_SYNC_ID} = ?)", arrayOf(calId.toString(), href, href)).build()
            }))
        }

        val changed = remote.filter { (href, etag) -> byHref[href]?.let { !it.dirty && it.etag != etag } ?: true }.keys.toList()
        changed.chunked(50).forEach { chunk ->
            val body = buildString {
                append("<?xml version=\"1.0\" encoding=\"utf-8\"?><c:calendar-multiget xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:prop><d:getetag/><c:calendar-data/></d:prop>")
                chunk.forEach { append("<d:href>").append(xmlEscape(java.net.URI(it).rawPath)).append("</d:href>") }
                append("</c:calendar-multiget>")
            }
            // Every series of the chunk goes into the calendar store in one batch, not one call per event.
            val ops = ArrayList<android.content.ContentProviderOperation>()
            dav.report(rc.href, 1, body).forEach { e ->
                val href = dav.resolve(rc.href, e.href)
                val data = e.props["calendar-data"] ?: return@forEach
                val etag = e.props["getetag"].orEmpty()
                runCatching { seriesOps(calId, href, etag, data, ops) }
            }
            if (ops.isNotEmpty()) resolver.applyBatch(CalendarContract.AUTHORITY, ops)
        }
    }

    private fun deleteSeries(calId: Long, href: String) {
        resolver.delete(
            syncUri(Events.CONTENT_URI),
            "${Events.CALENDAR_ID} = ? AND (${Events._SYNC_ID} = ? OR ${Events.ORIGINAL_SYNC_ID} = ?)",
            arrayOf(calId.toString(), href, href),
        )
    }

    /** The operations that replace the local copy of one .ics resource: the master event, its exceptions and their reminders. */
    private fun seriesOps(calId: Long, href: String, etag: String, ics: String, ops: ArrayList<android.content.ContentProviderOperation>) {
        val cal = Ical.parse(ics) ?: return
        val events = cal.children.filter { it.name == "VEVENT" }
        val master = events.firstOrNull { it.first("RECURRENCE-ID") == null } ?: events.firstOrNull() ?: return
        ops += android.content.ContentProviderOperation.newDelete(syncUri(Events.CONTENT_URI))
            .withSelection("${Events.CALENDAR_ID} = ? AND (${Events._SYNC_ID} = ? OR ${Events.ORIGINAL_SYNC_ID} = ?)", arrayOf(calId.toString(), href, href)).build()
        val masterValues = values(master, calId).apply {
            put(Events._SYNC_ID, href)
            put(Events.SYNC_DATA1, etag)
            put(Events.SYNC_DATA2, master.first("UID")?.value.orEmpty())
            put(Events.SYNC_DATA3, ics)
            put(Events.DIRTY, 0)
        }
        val masterAt = ops.size
        ops += android.content.ContentProviderOperation.newInsert(syncUri(Events.CONTENT_URI)).withValues(masterValues).build()
        reminderOps(masterAt, master, ops)

        val masterStart = Ical.time(master.first("DTSTART"))
        events.filter { it !== master && it.first("RECURRENCE-ID") != null }.forEach { ex ->
            val rid = Ical.time(ex.first("RECURRENCE-ID")) ?: return@forEach
            val v = values(ex, calId).apply {
                put(Events.ORIGINAL_SYNC_ID, href)
                put(Events.ORIGINAL_INSTANCE_TIME, rid.ms)
                put(Events.ORIGINAL_ALL_DAY, if (masterStart?.allDay == true) 1 else 0)
                put(Events.DIRTY, 0)
                remove(Events.RRULE); remove(Events.EXDATE); remove(Events.RDATE); remove(Events.DURATION)
                if (!containsKey(Events.DTEND)) {
                    val start = getAsLong(Events.DTSTART) ?: rid.ms
                    put(Events.DTEND, start + durationOf(ex, start))
                }
            }
            val at = ops.size
            ops += android.content.ContentProviderOperation.newInsert(syncUri(Events.CONTENT_URI)).withValues(v).build()
            reminderOps(at, ex, ops)
        }
    }

    private fun reminderOps(eventAt: Int, e: Ical.Component, ops: ArrayList<android.content.ContentProviderOperation>) {
        e.children.filter { it.name == "VALARM" }.forEach { a ->
            val ms = a.first("TRIGGER")?.value?.let { Ical.duration(it) } ?: return@forEach
            ops += android.content.ContentProviderOperation.newInsert(syncUri(Reminders.CONTENT_URI))
                .withValueBackReference(Reminders.EVENT_ID, eventAt)
                .withValue(Reminders.MINUTES, (-ms / 60_000).toInt().coerceAtLeast(0))
                .withValue(Reminders.METHOD, Reminders.METHOD_ALERT)
                .build()
        }
    }

    private fun values(e: Ical.Component, calId: Long): ContentValues {
        val v = ContentValues()
        v.put(Events.CALENDAR_ID, calId)
        v.put(Events.TITLE, e.first("SUMMARY")?.value?.let(Ical::unescape).orEmpty())
        v.put(Events.DESCRIPTION, e.first("DESCRIPTION")?.value?.let(Ical::unescape).orEmpty())
        v.put(Events.EVENT_LOCATION, e.first("LOCATION")?.value?.let(Ical::unescape).orEmpty())
        val start = Ical.time(e.first("DTSTART")) ?: Ical.Time(System.currentTimeMillis(), false, "UTC")
        v.put(Events.DTSTART, start.ms)
        v.put(Events.ALL_DAY, if (start.allDay) 1 else 0)
        v.put(Events.EVENT_TIMEZONE, if (start.allDay) "UTC" else start.zone)
        v.put(Events.STATUS, when (e.first("STATUS")?.value?.uppercase()) {
            "CANCELLED" -> Events.STATUS_CANCELED
            "TENTATIVE" -> Events.STATUS_TENTATIVE
            else -> Events.STATUS_CONFIRMED
        })
        v.put(Events.AVAILABILITY, if (e.first("TRANSP")?.value?.uppercase() == "TRANSPARENT") Events.AVAILABILITY_FREE else Events.AVAILABILITY_BUSY)
        val rrule = e.first("RRULE")?.value
        if (rrule != null) {
            v.put(Events.RRULE, rrule)
            v.put(Events.DURATION, Ical.durationOf(durationOf(e, start.ms).coerceAtLeast(if (start.allDay) 86_400_000 else 0)))
            val ex = e.all("EXDATE").flatMap { p -> p.value.split(',').mapNotNull { Ical.time(Ical.Prop("EXDATE", p.params, it)) } }
            if (ex.isNotEmpty()) v.put(Events.EXDATE, ex.joinToString(",") { if (it.allDay) Ical.date(it.ms) else Ical.utc(it.ms) })
            val rd = e.all("RDATE").flatMap { p -> p.value.split(',').mapNotNull { Ical.time(Ical.Prop("RDATE", p.params, it)) } }
            if (rd.isNotEmpty()) v.put(Events.RDATE, rd.joinToString(",") { if (it.allDay) Ical.date(it.ms) else Ical.utc(it.ms) })
        } else {
            v.put(Events.DTEND, start.ms + durationOf(e, start.ms))
            val end = Ical.time(e.first("DTEND"))
            v.put(Events.EVENT_END_TIMEZONE, if (start.allDay) "UTC" else end?.zone ?: start.zone)
        }
        v.put(Events.HAS_ALARM, if (e.children.any { it.name == "VALARM" }) 1 else 0)
        return v
    }

    private fun durationOf(e: Ical.Component, startMs: Long): Long {
        Ical.time(e.first("DTEND"))?.let { return (it.ms - startMs).coerceAtLeast(0) }
        e.first("DURATION")?.value?.let { Ical.duration(it) }?.let { return it }
        return if (Ical.time(e.first("DTSTART"))?.allDay == true) 86_400_000L else 0L
    }

    // ---------------- up ----------------

    private suspend fun upload(calId: Long, rc: RemoteCalendar) {
        if (!rc.writable) return
        val all = localEvents(calId)
        val dirtySeries = LinkedHashSet<Long>()
        all.filter { it.dirty }.forEach { e ->
            if (e.original == null) dirtySeries += e.id
            else all.firstOrNull { it.original == null && it.href == e.original }?.let { dirtySeries += it.id }
        }
        for (id in dirtySeries) {
            val master = all.first { it.id == id }
            try {
                if (master.deleted) {
                    master.href?.let { dav.delete(it, master.etag) }
                    master.href?.let { deleteSeries(calId, it) } ?: resolver.delete(syncUri(ContentUris.withAppendedId(Events.CONTENT_URI, id)), null, null)
                    continue
                }
                val create = master.href == null
                val uid = readString(id, Events.SYNC_DATA2)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
                val href = master.href ?: (rc.href.trimEnd('/') + "/" + uid + ".ics")
                val ics = serialize(calId, id, href, uid)
                val etag = dav.put(href, ics, master.etag, create)
                val clean = ContentValues().apply {
                    put(Events._SYNC_ID, href); put(Events.SYNC_DATA2, uid); put(Events.SYNC_DATA3, ics); put(Events.DIRTY, 0)
                    if (etag != null) put(Events.SYNC_DATA1, etag) else putNull(Events.SYNC_DATA1)
                }
                resolver.update(syncUri(ContentUris.withAppendedId(Events.CONTENT_URI, id)), clean, null, null)
                resolver.update(
                    syncUri(Events.CONTENT_URI), ContentValues().apply { put(Events.DIRTY, 0); put(Events.ORIGINAL_SYNC_ID, href) },
                    "${Events.CALENDAR_ID} = ? AND ${Events.ORIGINAL_ID} = ?", arrayOf(calId.toString(), id.toString()),
                )
                resolver.delete(syncUri(Events.CONTENT_URI), "${Events.CALENDAR_ID} = ? AND ${Events.ORIGINAL_SYNC_ID} = ? AND ${Events.DELETED} = 1", arrayOf(calId.toString(), href))
            } catch (e: Dav.ConflictException) {
                master.href?.let { resolver.update(syncUri(ContentUris.withAppendedId(Events.CONTENT_URI, id)), ContentValues().apply { put(Events.DIRTY, 0); putNull(Events.SYNC_DATA1) }, null, null) }
            }
        }
    }

    private fun readString(id: Long, col: String): String? =
        resolver.query(syncUri(ContentUris.withAppendedId(Events.CONTENT_URI, id)), arrayOf(col), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** The .ics for a series: what the server had, with every property the phone models replaced by the phone's values. */
    private fun serialize(calId: Long, masterId: Long, href: String, uid: String): String {
        val original = readString(masterId, Events.SYNC_DATA3)?.let { Ical.parse(it) }
        val keep = original?.children?.filter { it.name == "VTIMEZONE" }.orEmpty()
        val oldEvents = original?.children?.filter { it.name == "VEVENT" }.orEmpty()
        val sb = StringBuilder()
        fun line(s: String) { sb.append(Ical.fold(s)).append("\r\n") }
        line("BEGIN:VCALENDAR"); line("VERSION:2.0"); line("PRODID:-//Opensolr//Opensolr Mail//EN")
        keep.forEach { writeComponent(it, ::line) }

        val rows = ArrayList<ContentValues>()
        resolver.query(
            syncUri(Events.CONTENT_URI), null,
            "${Events.CALENDAR_ID} = ? AND (${Events._ID} = ? OR ${Events.ORIGINAL_ID} = ? OR ${Events.ORIGINAL_SYNC_ID} = ?) AND ${Events.DELETED} = 0",
            arrayOf(calId.toString(), masterId.toString(), masterId.toString(), href), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val v = ContentValues()
                android.database.DatabaseUtils.cursorRowToContentValues(c, v)
                rows += v
            }
        }
        val masterRow = rows.firstOrNull { it.getAsLong(Events._ID) == masterId } ?: rows.first()
        val masterOld = oldEvents.firstOrNull { it.first("RECURRENCE-ID") == null }
        writeEvent(masterRow, uid, null, masterOld, ::line)
        rows.filter { it !== masterRow }.forEach { ex ->
            val rid = ex.getAsLong(Events.ORIGINAL_INSTANCE_TIME) ?: return@forEach
            val old = oldEvents.firstOrNull { e -> e.first("RECURRENCE-ID")?.let { Ical.time(it)?.ms } == rid }
            writeEvent(ex, uid, rid, old, ::line, masterRow.getAsInteger(Events.ALL_DAY) == 1)
        }
        line("END:VCALENDAR")
        return sb.toString()
    }

    private fun writeComponent(c: Ical.Component, line: (String) -> Unit) {
        line("BEGIN:${c.name}")
        c.props.forEach { p -> line(p.name + p.params.entries.joinToString("") { ";${it.key}=${it.value}" } + ":" + p.value) }
        c.children.forEach { writeComponent(it, line) }
        line("END:${c.name}")
    }

    private fun writeEvent(v: ContentValues, uid: String, recurrenceId: Long?, old: Ical.Component?, line: (String) -> Unit, masterAllDay: Boolean = false) {
        val modeled = setOf(
            "UID", "DTSTAMP", "SUMMARY", "DESCRIPTION", "LOCATION", "DTSTART", "DTEND", "DURATION", "RRULE", "EXDATE", "RDATE",
            "RECURRENCE-ID", "STATUS", "TRANSP", "SEQUENCE", "LAST-MODIFIED",
        )
        line("BEGIN:VEVENT")
        line("UID:$uid")
        line("DTSTAMP:" + Ical.utc(System.currentTimeMillis()))
        line("LAST-MODIFIED:" + Ical.utc(System.currentTimeMillis()))
        val seq = (old?.first("SEQUENCE")?.value?.toIntOrNull() ?: 0) + 1
        line("SEQUENCE:$seq")
        val allDay = v.getAsInteger(Events.ALL_DAY) == 1
        val start = v.getAsLong(Events.DTSTART) ?: System.currentTimeMillis()
        val zone = v.getAsString(Events.EVENT_TIMEZONE)?.takeIf { it.isNotBlank() } ?: TimeZone.getDefault().id
        if (recurrenceId != null) {
            line(if (masterAllDay) "RECURRENCE-ID;VALUE=DATE:" + Ical.date(recurrenceId) else "RECURRENCE-ID:" + Ical.utc(recurrenceId))
        }
        line(if (allDay) "DTSTART;VALUE=DATE:" + Ical.date(start) else if (zone == "UTC") "DTSTART:" + Ical.utc(start) else "DTSTART;TZID=$zone:" + Ical.local(start, zone))
        val rrule = v.getAsString(Events.RRULE)
        val end = v.getAsLong(Events.DTEND)
        val duration = v.getAsString(Events.DURATION)?.let { Ical.duration(it) }
        if (rrule.isNullOrBlank() && end != null) {
            line(if (allDay) "DTEND;VALUE=DATE:" + Ical.date(end) else if (zone == "UTC") "DTEND:" + Ical.utc(end) else "DTEND;TZID=$zone:" + Ical.local(end, zone))
        } else if (duration != null) {
            line("DURATION:" + Ical.durationOf(duration))
        }
        if (!rrule.isNullOrBlank() && recurrenceId == null) {
            line("RRULE:$rrule")
            v.getAsString(Events.EXDATE)?.takeIf { it.isNotBlank() }?.let { line(if (allDay) "EXDATE;VALUE=DATE:$it" else "EXDATE:$it") }
            v.getAsString(Events.RDATE)?.takeIf { it.isNotBlank() }?.let { line(if (allDay) "RDATE;VALUE=DATE:$it" else "RDATE:$it") }
        }
        line("SUMMARY:" + Ical.escape(v.getAsString(Events.TITLE).orEmpty()))
        v.getAsString(Events.DESCRIPTION)?.takeIf { it.isNotBlank() }?.let { line("DESCRIPTION:" + Ical.escape(it)) }
        v.getAsString(Events.EVENT_LOCATION)?.takeIf { it.isNotBlank() }?.let { line("LOCATION:" + Ical.escape(it)) }
        line("STATUS:" + when (v.getAsInteger(Events.STATUS)) {
            Events.STATUS_CANCELED -> "CANCELLED"
            Events.STATUS_TENTATIVE -> "TENTATIVE"
            else -> "CONFIRMED"
        })
        line("TRANSP:" + if (v.getAsInteger(Events.AVAILABILITY) == Events.AVAILABILITY_FREE) "TRANSPARENT" else "OPAQUE")
        old?.props?.filter { it.name !in modeled }?.forEach { p -> line(p.name + p.params.entries.joinToString("") { ";${it.key}=${it.value}" } + ":" + p.value) }
        val eventId = v.getAsLong(Events._ID)
        if (eventId != null) {
            resolver.query(syncUri(Reminders.CONTENT_URI), arrayOf(Reminders.MINUTES), "${Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()), null)?.use { c ->
                while (c.moveToNext()) {
                    val m = c.getInt(0)
                    line("BEGIN:VALARM"); line("ACTION:DISPLAY"); line("DESCRIPTION:Reminder"); line("TRIGGER:-PT${m}M"); line("END:VALARM")
                }
            }
        }
        line("END:VEVENT")
    }

    private fun syncUri(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, sysAccount.name)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, ACCOUNT_TYPE)
        .build()

    private fun parseColor(s: String?): Int? {
        val hex = s?.trim()?.removePrefix("#") ?: return null
        return runCatching {
            when (hex.length) {
                6 -> (0xFF000000 or hex.toLong(16)).toInt()
                8 -> (0xFF000000 or hex.substring(0, 6).toLong(16)).toInt()
                else -> null
            }
        }.getOrNull()
    }

    private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        const val ACCOUNT_TYPE = "com.opensolr.mail"

        fun ensureAccount(context: Context, name: String) {
            val am = AccountManager.get(context)
            val a = Account(name, ACCOUNT_TYPE)
            if (am.getAccountsByType(ACCOUNT_TYPE).none { it.name == name }) {
                am.addAccountExplicitly(a, null, null)
            }
            ContentResolver.setIsSyncable(a, CalendarContract.AUTHORITY, 1)
            ContentResolver.setSyncAutomatically(a, CalendarContract.AUTHORITY, true)
            ContentResolver.addPeriodicSync(a, CalendarContract.AUTHORITY, Bundle.EMPTY, 30 * 60L)
        }

        fun removeAccount(context: Context, name: String) {
            val am = AccountManager.get(context)
            am.getAccountsByType(ACCOUNT_TYPE).filter { it.name == name }.forEach { am.removeAccountExplicitly(it) }
        }

        fun requestSync(name: String) {
            ContentResolver.requestSync(Account(name, ACCOUNT_TYPE), CalendarContract.AUTHORITY, Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            })
        }

        private const val PROPFIND_PRINCIPAL = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:current-user-principal/></d:prop></d:propfind>"
        private const val PROPFIND_HOME = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:prop><c:calendar-home-set/></d:prop></d:propfind>"
        private const val PROPFIND_CALENDARS = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\" xmlns:cs=\"http://calendarserver.org/ns/\" xmlns:a=\"http://apple.com/ns/ical/\"><d:prop><d:resourcetype/><d:displayname/><cs:getctag/><a:calendar-color/><c:supported-calendar-component-set/><d:current-user-privilege-set/></d:prop></d:propfind>"
        private const val PROPFIND_ETAGS = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:getetag/><d:resourcetype/></d:prop></d:propfind>"
    }
}
