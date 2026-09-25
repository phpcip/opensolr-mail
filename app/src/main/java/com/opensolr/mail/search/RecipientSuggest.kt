package com.opensolr.mail.search

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.opensolr.mail.data.AccountStore
import com.opensolr.mail.data.AppPrefs
import com.opensolr.mail.data.MailDb
import com.opensolr.mail.index.MailIndex
import com.opensolr.mail.index.MailIndexer
import com.opensolr.mail.index.SolrClient
import com.opensolr.mail.net.IndexConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** Recipient suggestions for To, Cc and Bcc: the mail index (with an Opensolr account), the Fastmail address books and the phone's contacts, in one list. */
class RecipientSuggest(private val context: Context) {

    data class Suggestion(val name: String, val email: String, val photo: String?)

    private val db = MailDb.get(context)

    fun contactsAllowed(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    suspend fun suggest(typed: String): List<Suggestion> = withContext(Dispatchers.IO) {
        val text = typed.trim().take(100)
        if (text.isEmpty()) return@withContext emptyList()
        val words = foldWords(text)
        val history = runCatching { fromHistory(text, words) }.getOrDefault(emptyList())
        val fastmail = runCatching { fromFastmail(words) }.getOrDefault(emptyList())
        val contacts = if (contactsAllowed()) runCatching { fromContacts(text) }.getOrDefault(emptyList()) else emptyList()
        // One list, one row per address, most written to first: a phone contact, then a Fastmail card, lends
        // its name and picture to the same address wherever it came from.
        val phoneBy = contacts.associateBy { it.email.lowercase() }
        val fmBy = fastmail.associateBy { it.email.lowercase() }
        val known = phoneBy.keys + fmBy.keys
        val photos = if (contactsAllowed()) runCatching { photosOf(history.map { it.second }.filter { it.lowercase() !in known }) }.getOrDefault(emptyMap()) else emptyMap()
        fun best(key: String, name: String, email: String, photo: String?): Suggestion {
            val c = phoneBy[key]
            val f = fmBy[key]
            return Suggestion(c?.name?.ifBlank { null } ?: f?.name?.ifBlank { null } ?: name, email, c?.photo ?: f?.photo ?: photo)
        }
        val merged = LinkedHashMap<String, Suggestion>()
        history.forEach { (name, email) -> val key = email.lowercase(); merged[key] = best(key, name, email, photos[key]) }
        fastmail.forEach { f -> val key = f.email.lowercase(); merged.putIfAbsent(key, best(key, f.name, f.email, f.photo)) }
        contacts.forEach { c -> merged.putIfAbsent(c.email.lowercase(), c) }
        merged.values.take(MAX)
    }

    /** Names and addresses from from_t, to_tm and cc_tm of the messages that match, cached per prefix. */
    private suspend fun fromHistory(text: String, words: List<String>): List<Pair<String, String>> {
        val prefs = AppPrefs(context)
        if (!prefs.signedIn) return emptyList()
        // Only an index this phone already has; a suggestion never creates or configures one.
        val connection = IndexConnection.fromJson(prefs.connectionJson)?.takeIf { it.indexName == MailIndex(context).ownName } ?: return emptyList()
        val keys = AccountStore.get(context).all().map { MailIndexer.indexKey(it) }.sorted()
        if (keys.isEmpty()) return emptyList()
        val cacheKey = keys.joinToString(",") + "|" + words.joinToString(" ")
        memory.get(cacheKey)?.let { return it }
        db.suggestCached(cacheKey, System.currentTimeMillis() - CACHE_MS)?.let { json ->
            val list = decode(json)
            memory.put(cacheKey, list)
            return list
        }
        val r = SolrClient(connection).select(
            listOf(
                "q" to "{!edismax qf=\"$QF\" mm=\"100%\" v=\$uq}",
                "uq" to text,
                "fq" to "{!terms f=account_s v=\$acc}",
                "acc" to keys.joinToString(","),
                "fl" to "from_t,to_tm,cc_tm",
                "rows" to ROWS.toString(),
                "sort" to "score desc,received_dt desc",
            )
        )
        val docs = r.optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
        // Every person on a matching message is read, then only those the typed words fit are kept.
        val seen = HashMap<String, MutableMap<String, Int>>()
        val hits = HashMap<String, Int>()
        val order = ArrayList<String>()
        for (i in 0 until docs.length()) {
            val d = docs.getJSONObject(i)
            val values = ArrayList<String>()
            d.optString("from_t").takeIf { it.isNotBlank() }?.let { values += it }
            listOf("to_tm", "cc_tm").forEach { f -> d.optJSONArray(f)?.let { a -> for (j in 0 until a.length()) values += a.optString(j) } }
            values.forEach { v ->
                val (name, email) = split(v) ?: return@forEach
                if (!fits(words, name, email)) return@forEach
                val key = email.lowercase()
                if (key !in hits) order += key
                hits[key] = (hits[key] ?: 0) + 1
                if (name.isNotEmpty()) seen.getOrPut(key) { HashMap() }.merge(name, 1, Int::plus)
            }
        }
        // Most frequent first; each address under the name it carries most often.
        val list = order.sortedByDescending { hits[it] ?: 0 }.map { key ->
            (seen[key]?.maxByOrNull { it.value }?.key.orEmpty()) to key
        }
        memory.put(cacheKey, list)
        db.suggestStore(cacheKey, encode(list), System.currentTimeMillis() - CACHE_MS)
        return list
    }

    /** Fastmail cards whose name or address the typed words fit, one row per address. */
    private fun fromFastmail(words: List<String>): List<Suggestion> =
        ContactBook(context).fastmail().flatMap { p -> p.emails.filter { fits(words, p.name, it) }.map { Suggestion(p.name, it, p.photo) } }.take(MAX)

    /** The phone's contacts whose name or address matches, through the provider's own filter. */
    private fun fromContacts(text: String): List<Suggestion> {
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Email.CONTENT_FILTER_URI, Uri.encode(text))
        val out = ArrayList<Suggestion>()
        context.contentResolver.query(
            uri,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext() && out.size < MAX) {
                val email = c.getString(0)?.trim().orEmpty()
                if (!email.contains('@')) continue
                out += Suggestion(cleanName(c.getString(1).orEmpty(), email), email, c.getString(2))
            }
        }
        return out.distinctBy { it.email.lowercase() }
    }

    /** Contact photos for addresses that came from the history, in one query. */
    private fun photosOf(emails: List<String>): Map<String, String> {
        if (emails.isEmpty()) return emptyMap()
        val list = emails.map { it.lowercase() }.distinct().take(MAX * 2)
        val marks = list.joinToString(",") { "?" }
        val out = HashMap<String, String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
            "${ContactsContract.CommonDataKinds.Email.ADDRESS} COLLATE NOCASE IN ($marks)",
            list.toTypedArray(), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val email = c.getString(0)?.lowercase() ?: continue
                val photo = c.getString(1) ?: continue
                out.putIfAbsent(email, photo)
            }
        }
        return out
    }

    companion object {
        /** Same weight on every people field; the edge n-gram copies of them let a word match while it is still being typed. */
        private const val QF = "from_t to_tm cc_tm words_ng address_ngk"
        private const val ROWS = 60
        private const val MAX = 8
        private const val CACHE_MS = 24L * 60 * 60 * 1000
        private val memory = LruCache<String, List<Pair<String, String>>>(300)

        /** "Name email" as the index holds it: the address is the part with the @, the rest is the name. */
        fun split(value: String): Pair<String, String>? {
            val email = EMAIL.find(value)?.value?.trimStart('.') ?: return null
            return cleanName(EMAIL.replace(value, " "), email) to email
        }

        /**
         * No spaces, only what a mailbox name may hold, an @ right after it, then the domain (labels of letters,
         * digits and hyphens joined by dots); whatever is around it, <, >, quotes, commas, stays out of the match.
         */
        private val EMAIL = Regex("[\\p{L}\\p{N}!#$%&'*+/=?^_`{|}~.-]+@[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?(?:\\.[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?)+")

        /** A name without stray commas, quotes or spaces at its ends, single spaces inside; empty when it only repeats the address. */
        fun cleanName(raw: String, email: String): String {
            // Brackets, quotes and separators left around the name go; single spaces inside, none at the ends.
            val name = raw.replace(Regex("[<>\"()\\[\\]]"), " ").replace(Regex("\\s+"), " ")
                .trim().trim(',', ';', ':', '\'', '.', '-', ' ').replace(Regex("\\s*,\\s*"), ", ").trim()
            return if (name.equals(email, true)) "" else name
        }

        fun foldWords(text: String): List<String> =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
                .split(Regex("[^\\p{L}\\p{N}@._+-]+")).filter { it.isNotEmpty() }

        /** Every typed word starts a word of the name or of the address. */
        fun fits(words: List<String>, name: String, email: String): Boolean {
            val pool = foldWords(name) + foldWords(email).flatMap { w -> listOf(w) + w.split('@', '.', '_', '-', '+').filter { it.isNotEmpty() } }
            return words.all { w -> pool.any { it.startsWith(w) } }
        }

        private fun encode(list: List<Pair<String, String>>): String =
            JSONArray(list.map { JSONObject().put("n", it.first).put("e", it.second) }).toString()

        private fun decode(json: String): List<Pair<String, String>> = runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { a.getJSONObject(it).let { o -> o.optString("n") to o.optString("e") } }
        }.getOrDefault(emptyList())
    }
}
