package com.opensolr.mail.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.opensolr.mail.R
import com.opensolr.mail.data.Address
import com.opensolr.mail.data.MailDb
import com.opensolr.mail.search.ContactBook
import com.opensolr.mail.ui.AppViewModel
import com.opensolr.mail.ui.ComposeInit
import com.opensolr.mail.ui.FastScroller
import com.opensolr.mail.ui.Haptics
import com.opensolr.mail.ui.IconBtn
import com.opensolr.mail.ui.PersonPhoto
import com.opensolr.mail.ui.Screen
import com.opensolr.mail.ui.TopBar
import com.opensolr.mail.ui.bottomInset
import com.opensolr.mail.ui.hapticClickable
import com.opensolr.mail.ui.theme.LocalPalette

/**
 * Everyone the reader can write to, from the phone (when allowed), Fastmail and, with an Opensolr account, the
 * mail index: merged into one stored list, one card per person under its letter, read a page at a time as the
 * list scrolls, the place kept, the same fast scroller as the mail lists, a search box to narrow them down, and
 * Compose on each. Nothing here edits or removes a contact, and nothing says where one came from.
 */
@Composable
fun ContactsScreen(vm: AppViewModel) {
    val p = LocalPalette.current
    val context = LocalContext.current
    val view = LocalView.current
    val book = remember { ContactBook(context) }
    var allowed by remember { mutableStateOf(book.contactsAllowed()) }
    var version by remember { mutableIntStateOf(0) }
    var rebuilding by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // The stored list is rebuilt when it is missing, old, or its sources changed; the Fastmail books are read
    // again when last read long enough ago, and a change there rebuilds it once more.
    LaunchedEffect(allowed) {
        rebuilding = true
        try {
            if (runCatching { book.refreshPeople() }.getOrDefault(false)) version++
            if (runCatching { com.opensolr.mail.dav.ContactsSync.refresh(context) }.getOrDefault(false)) {
                if (runCatching { book.refreshPeople(force = true) }.getOrDefault(false)) version++
            }
        } finally {
            rebuilding = false
        }
    }
    // How many people each letter holds: the list's shape, known before any page is read.
    var groups by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }
    LaunchedEffect(version, query) {
        if (query.isNotEmpty()) kotlinx.coroutines.delay(200)
        groups = withContext(Dispatchers.IO) { runCatching { MailDb.get(context).peopleLetters(query) }.getOrDefault(emptyList()) }
    }
    val shape = remember(groups) {
        val g = groups.orEmpty()
        val heads = IntArray(g.size)
        val bases = IntArray(g.size)
        var item = 0
        var person = 0
        g.forEachIndexed { i, (_, n) -> heads[i] = item; bases[i] = person; item += n + 1; person += n }
        Shape(heads, bases, item)
    }
    val pages = remember(version, query) { mutableStateMapOf<Int, List<MailDb.PersonRow>>() }
    val loading = remember(version, query) { HashSet<Int>() }
    val listState = com.opensolr.mail.ui.rememberListMemory(vm.prefs, if (query.isBlank()) "contacts" else "contacts_search", groups != null)
    // The pages around what is on screen are read as it scrolls, one page ahead each way.
    LaunchedEffect(version, query, shape) {
        androidx.compose.runtime.snapshotFlow {
            val items = listState.layoutInfo.visibleItemsInfo
            (items.firstOrNull()?.index ?: 0) to (items.lastOrNull()?.index ?: 0)
        }.collect { (first, last) ->
            if (shape.items == 0) return@collect
            val from = (shape.personAt(first) / PAGE - 1).coerceAtLeast(0)
            val to = shape.personAt(last) / PAGE + 1
            for (page in from..to) {
                if (page in pages || !loading.add(page)) continue
                val rows = withContext(Dispatchers.IO) { runCatching { MailDb.get(context).peoplePage(query, page * PAGE, PAGE) }.getOrNull() }
                loading.remove(page)
                if (rows != null) pages[page] = rows
            }
        }
    }
    val scrollIndex = remember(groups) {
        com.opensolr.mail.ui.ScrollIndex().apply { groups.orEmpty().forEach { (letter, n) -> head(letter); rows(n) } }
    }

    val activity = activityOf(context)
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> allowed = granted }
    fun allowContacts() {
        // Asked once and refused for good: Android shows no dialog any more, so the app's settings open instead.
        val refusedForGood = vm.prefs.contactsAsked && activity?.shouldShowRequestPermissionRationale(android.Manifest.permission.READ_CONTACTS) == false
        if (refusedForGood) {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        } else {
            vm.prefs.contactsAsked = true
            ask.launch(android.Manifest.permission.READ_CONTACTS)
        }
    }

    Column(Modifier.fillMaxSize().background(p.paper)) {
        TopBar(stringResource(R.string.contacts_title), onBack = { vm.back() })
        Row(Modifier.fillMaxWidth().background(p.band).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_search), null, tint = p.muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(stringResource(R.string.contacts_search), style = MaterialTheme.typography.bodyLarge, color = p.muted)
                BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = p.ink), cursorBrush = SolidColor(p.accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) Icon(
                painterResource(R.drawable.ic_close), stringResource(R.string.clear), tint = p.muted,
                modifier = Modifier.size(28.dp).clickable { Haptics.tick(view, false); query = "" }.padding(7.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
        if (rebuilding && (groups.isNullOrEmpty())) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = p.accent, trackColor = p.hairline)
        if (!allowed) Row(Modifier.fillMaxWidth().hapticClickable(onClick = { allowContacts() }).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).border(1.dp, p.accent, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_contact), null, tint = p.accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.suggest_allow_contacts), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = p.accent)
        }
        Box(Modifier.weight(1f)) {
            val g = groups
            if (g != null && g.isEmpty() && !rebuilding) {
                Text(stringResource(R.string.contacts_none), style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(24.dp))
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(shape.items) { i ->
                    val gi = shape.groupOf(i)
                    if (gi >= 0 && shape.heads[gi] == i) {
                        LetterHeader(g?.getOrNull(gi)?.first.orEmpty())
                    } else {
                        val k = shape.personAt(i)
                        val person = pages[k / PAGE]?.getOrNull(k % PAGE)
                        if (person == null) CardPlaceholder()
                        else ContactCard(person) {
                            Haptics.tick(view, true)
                            vm.go(Screen.Compose(ComposeInit(to = Address(person.name, person.emails.first()).formatted() + ", ")))
                        }
                    }
                }
                item { Spacer(Modifier.height(bottomInset() + 8.dp)) }
            }
            FastScroller(listState, scrollIndex)
        }
    }
}

/** Where each letter's heading sits in the list and how many people come before it. */
private class Shape(val heads: IntArray, val bases: IntArray, val items: Int) {
    /** The letter an item belongs to: the last heading at or before it. */
    fun groupOf(item: Int): Int {
        var lo = 0
        var hi = heads.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (heads[mid] <= item) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    /** The person an item shows; a heading counts as the first person under it. */
    fun personAt(item: Int): Int {
        val g = groupOf(item)
        if (g < 0) return 0
        return bases[g] + (item - heads[g] - 1).coerceAtLeast(0)
    }
}

private const val PAGE = 100

@Composable
private fun LetterHeader(letter: String) {
    val p = LocalPalette.current
    Text(
        letter, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = p.accent,
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp),
    )
}

/** The shape of a card while its page is read. */
@Composable
private fun CardPlaceholder() {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(2.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).background(p.paper, shape).border(1.dp, p.hairline, shape).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(p.hairline, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth(0.5f).height(14.dp).background(p.hairline, shape))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(0.7f).height(10.dp).background(p.hairline, shape))
        }
    }
}

/** A quiet card: the picture, the name, every address, number and postal address, and Compose. */
@Composable
private fun ContactCard(person: MailDb.PersonRow, onCompose: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(2.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp).background(p.paper, shape).border(1.dp, p.hairline, shape).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PersonPhoto(person.photo, person.name, person.emails.first(), 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                person.name.ifBlank { person.emails.first() }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val lines = (if (person.name.isBlank()) person.emails.drop(1) else person.emails) + person.phones
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            person.addresses.forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(8.dp))
        IconBtn(R.drawable.ic_compose, onCompose, contentDescription = stringResource(R.string.contacts_write))
    }
}
