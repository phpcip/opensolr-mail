package com.opensolr.mail.search

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.opensolr.mail.data.AccountStore
import com.opensolr.mail.data.AppPrefs
import com.opensolr.mail.data.MailDb
import com.opensolr.mail.index.MailIndex
import com.opensolr.mail.index.MailIndexer
import com.opensolr.mail.index.SolrClient
import com.opensolr.mail.net.IndexConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Everyone the reader can write to, from every place that knows them: the phone's contacts (when allowed),
 * the Fastmail address books, and, with an Opensolr account, the people in the mail index. Stored as one list,
 * one entry per person: entries sharing an address are merged, the phone lending its name and picture first.
 */
class ContactBook(private val context: Context) {

    data class Person(val name: String, val emails: List<String>, val phones: List<String>, val addresses: List<String>, val photo: String?)

    private val db = MailDb.get(context)
    private val prefs = AppPrefs(context)

    fun contactsAllowed(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /**
     * Rebuilds the stored list of people when it is missing, older than [FRESH_MS], or what feeds it changed
     * (contacts allowed or not, Opensolr connected or not, the accounts); [force] rebuilds anyway. True when rebuilt.
     */
    suspend fun refreshPeople(force: Boolean = false): Boolean = lock.withLock {
        withContext(Dispatchers.IO) {
            val sp = context.getSharedPreferences("contacts", Context.MODE_PRIVATE)
            val sig = listOf(contactsAllowed(), prefs.signedIn, AccountStore.get(context).all().map { it.key }.sorted()).toString()
            val fresh = System.currentTimeMillis() - sp.getLong("people_at", 0L) < FRESH_MS
            if (!force && fresh && sp.getString("people_sig", null) == sig && db.peopleCount() > 0) return@withContext false
            // Everything is read first, then written in one short transaction: no network inside it.
            val phone = if (contactsAllowed()) runCatching { phoneContacts() }.getOrDefault(emptyList()) else emptyList()
            val fm = fastmail()
            val index = runCatching { indexPeople() }.getOrDefault(emptyList())
            db.rebuildPeople { add ->
                phone.forEach { add(it.name, it.emails, it.phones, it.addresses, it.photo) }
                fm.forEach { add(it.name, it.emails, it.phones, it.addresses, it.photo) }
                index.forEach { (name, email) -> add(name, listOf(email), emptyList(), emptyList(), null) }
            }
            sp.edit().putLong("people_at", System.currentTimeMillis()).putString("people_sig", sig).apply()
            true
        }
    }

    /** The Fastmail cards, read once and kept until the stored books change. */
    fun fastmail(): List<Person> {
        val stamp = db.fmStamp
        cache?.takeIf { it.first == stamp }?.let { return it.second }
        val list = db.fmCards().filter { it.emails.isNotEmpty() }.map { c ->
            Person(c.name, c.emails, c.phones, c.addresses, if (c.hasPhoto) photoKey(c.acc, c.href) else null)
        }
        cache = stamp to list
        return list
    }

    /** The phone's contacts that have an address, with their numbers and postal addresses: three queries in all. */
    private fun phoneContacts(): List<Person> {
        val r = context.contentResolver
        val emails = LinkedHashMap<Long, MutableList<String>>()
        val names = HashMap<Long, String>()
        val photos = HashMap<Long, String>()
        r.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Email.CONTACT_ID, ContactsContract.CommonDataKinds.Email.ADDRESS, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val email = c.getString(1)?.trim().orEmpty()
                if (!email.contains('@')) continue
                emails.getOrPut(id) { ArrayList() } += email
                c.getString(2)?.let { names.putIfAbsent(id, it) }
                c.getString(3)?.let { photos.putIfAbsent(id, it) }
            }
        }
        if (emails.isEmpty()) return emptyList()
        val phones = HashMap<Long, MutableList<String>>()
        r.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
            while (c.moveToNext()) { val id = c.getLong(0); if (id in emails) c.getString(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { phones.getOrPut(id) { ArrayList() } += it } }
        }
        val places = HashMap<Long, MutableList<String>>()
        r.query(ContactsContract.CommonDataKinds.StructuredPostal.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.StructuredPostal.CONTACT_ID, ContactsContract.CommonDataKinds.StructuredPostal.FORMATTED_ADDRESS), null, null, null)?.use { c ->
            while (c.moveToNext()) { val id = c.getLong(0); if (id in emails) c.getString(1)?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }?.let { places.getOrPut(id) { ArrayList() } += it } }
        }
        return emails.map { (id, list) ->
            Person(RecipientSuggest.cleanName(names[id].orEmpty(), list.first()), list, phones[id].orEmpty(), places[id].orEmpty(), photos[id])
        }
    }

    /**
     * Everyone in the mail index, from its name-and-address facets, the reader's own addresses left out, paged
     * [FACET_PAGE] at a time; each address under the name it carries most often.
     */
    private suspend fun indexPeople(): List<Pair<String, String>> {
        if (!prefs.signedIn) return emptyList()
        val connection = IndexConnection.fromJson(prefs.connectionJson)?.takeIf { it.indexName == MailIndex(context).ownName } ?: return emptyList()
        val accounts = AccountStore.get(context).all()
        if (accounts.isEmpty()) return emptyList()
        val own = (accounts.map { it.username } + db.identities().map { it.email }).map { it.lowercase() }.toSet()
        val solr = SolrClient(connection)
        val named = HashMap<String, Pair<String, Int>>()
        val emails = LinkedHashMap<String, String>()
        for (field in listOf("from_label_s", "to_label_ss")) {
            var offset = 0
            while (true) {
                val r = solr.select(
                    listOf(
                        "q" to "*:*",
                        "fq" to "{!terms f=account_s v=\$acc}",
                        "acc" to accounts.joinToString(",") { MailIndexer.indexKey(it) },
                        "rows" to "0",
                        "facet" to "true",
                        "facet.field" to field,
                        "facet.sort" to "index",
                        "facet.limit" to FACET_PAGE.toString(),
                        "facet.offset" to offset.toString(),
                        "facet.mincount" to "1",
                    )
                )
                val a = r.optJSONObject("facet_counts")?.optJSONObject("facet_fields")?.optJSONArray(field) ?: break
                var i = 0
                while (i + 1 < a.length()) {
                    val label = a.optString(i)
                    val n = a.optInt(i + 1)
                    i += 2
                    val m = LABEL.find(label) ?: continue
                    val email = m.groupValues[2].trim()
                    val key = email.lowercase()
                    if (key in own) continue
                    emails.putIfAbsent(key, email)
                    val name = RecipientSuggest.cleanName(m.groupValues[1], email)
                    if (name.isNotEmpty() && (named[key]?.second ?: -1) < n) named[key] = name to n
                }
                if (a.length() / 2 < FACET_PAGE) break
                offset += FACET_PAGE
            }
        }
        return emails.map { (key, email) -> named[key]?.first.orEmpty() to email }
    }

    companion object {
        private const val FACET_PAGE = 5000
        /** The stored people are rebuilt after this long, or sooner when what feeds them changes. */
        const val FRESH_MS = 6 * 60 * 60 * 1000L
        private val lock = kotlinx.coroutines.sync.Mutex()
        private val LABEL = Regex("^(.*) \\(([^()]+@[^()]+)\\)$")
        @Volatile private var cache: Pair<Long, List<Person>>? = null

        fun photoKey(acc: String, href: String) = "fm:" + acc + "\u0001" + href
    }
}
