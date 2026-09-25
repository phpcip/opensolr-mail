package com.opensolr.mail.dav

import android.content.Context
import com.opensolr.mail.data.AccountStore
import com.opensolr.mail.data.MailAccount
import com.opensolr.mail.data.MailDb
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The Fastmail address books of one account, read over CardDAV with the same OAuth token as the mail:
 * a book whose ctag has not moved is not read again, and of a changed book only the cards whose ETag
 * changed are fetched.
 */
class ContactsSync(context: Context, private val account: MailAccount) {

    private val dav = Dav(context, account)
    private val db = MailDb.get(context)

    private class Book(val href: String, val ctag: String)

    suspend fun run() {
        val books = discover()
        db.fmKeepBooks(account.key, books.map { it.href })
        for (book in books) {
            if (book.ctag.isNotEmpty() && db.fmBookCtag(account.key, book.href) == book.ctag) continue
            val self = book.href.toHttpUrl().encodedPath
            val remote = dav.propfind(book.href, 1, PROPFIND_ETAGS)
                .filter { it.href.isNotEmpty() && it.href.trimEnd('/') != self.trimEnd('/') && it.props["getetag"].orEmpty().isNotEmpty() }
                .associate { dav.resolve(book.href, it.href) to it.props["getetag"].orEmpty() }
            val local = db.fmCardEtags(account.key, book.href)
            val changed = remote.filter { (href, etag) -> local[href] != etag }.keys.toList()
            val removed = local.keys - remote.keys
            val cards = ArrayList<Pair<Pair<String, String>, VCard.Card>>()
            changed.chunked(BATCH).forEach { chunk ->
                val body = MULTIGET_HEAD + chunk.joinToString("") { "<d:href>" + xml(it.toHttpUrl().encodedPath) + "</d:href>" } + MULTIGET_TAIL
                dav.report(book.href, 1, body).forEach { e ->
                    val card = VCard.parse(e.props["address-data"].orEmpty()) ?: return@forEach
                    cards += (dav.resolve(book.href, e.href) to e.props["getetag"].orEmpty()) to card
                }
            }
            db.fmStoreBook(account.key, book.href, book.ctag, cards, removed)
        }
    }

    private suspend fun discover(): List<Book> {
        val root = "https://carddav.fastmail.com/dav/"
        val principal = dav.propfind(root, 0, PROPFIND_PRINCIPAL).firstOrNull()?.props?.get("current-user-principal")
            ?: throw IllegalStateException("No CardDAV principal")
        val principalUrl = dav.resolve(root, principal)
        val home = dav.propfind(principalUrl, 0, PROPFIND_HOME).firstOrNull()?.props?.get("addressbook-home-set")
            ?: throw IllegalStateException("No address book home")
        val homeUrl = dav.resolve(principalUrl, home)
        return dav.propfind(homeUrl, 1, PROPFIND_BOOKS)
            .filter { it.children["resourcetype"]?.contains("addressbook") == true }
            .map { Book(dav.resolve(homeUrl, it.href), it.props["getctag"].orEmpty()) }
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    companion object {
        private val lock = Mutex()
        private const val BATCH = 100
        /** Books are looked at again after this long; opening the contacts or writing a message is what asks. */
        const val FRESH_MS = 6 * 60 * 60 * 1000L

        /** Every account's books, when last read more than [maxAgeMs] ago; one run at a time, failures leave what is stored. */
        suspend fun refresh(context: Context, maxAgeMs: Long = FRESH_MS): Boolean = lock.withLock {
            val sp = context.getSharedPreferences("contacts", Context.MODE_PRIVATE)
            var changed = false
            AccountStore.get(context).all().forEach { a ->
                val key = "synced_" + a.key
                if (System.currentTimeMillis() - sp.getLong(key, 0L) < maxAgeMs) return@forEach
                runCatching { ContactsSync(context, a).run() }
                    .onSuccess { sp.edit().putLong(key, System.currentTimeMillis()).apply(); changed = true }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; android.util.Log.w("ContactsSync", "sync failed", it) }
            }
            changed
        }

        private const val PROPFIND_PRINCIPAL = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:current-user-principal/></d:prop></d:propfind>"
        private const val PROPFIND_HOME = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:carddav\"><d:prop><c:addressbook-home-set/></d:prop></d:propfind>"
        private const val PROPFIND_BOOKS = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\" xmlns:cs=\"http://calendarserver.org/ns/\"><d:prop><d:resourcetype/><d:displayname/><cs:getctag/></d:prop></d:propfind>"
        private const val PROPFIND_ETAGS = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:getetag/></d:prop></d:propfind>"
        private const val MULTIGET_HEAD = "<?xml version=\"1.0\" encoding=\"utf-8\"?><c:addressbook-multiget xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:carddav\"><d:prop><d:getetag/><c:address-data/></d:prop>"
        private const val MULTIGET_TAIL = "</c:addressbook-multiget>"
    }
}
