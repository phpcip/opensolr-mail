package com.opensolr.mail.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.opensolr.mail.R
import com.opensolr.mail.jmap.Notes
import com.opensolr.mail.ui.AppViewModel
import com.opensolr.mail.ui.Field
import com.opensolr.mail.ui.Hairline
import com.opensolr.mail.ui.IconBtn
import com.opensolr.mail.ui.RefreshBox
import com.opensolr.mail.ui.Screen
import com.opensolr.mail.ui.TopBar
import com.opensolr.mail.ui.bottomInset
import com.opensolr.mail.ui.fmtDate
import com.opensolr.mail.ui.hapticClickable
import com.opensolr.mail.ui.FastScroller
import com.opensolr.mail.ui.theme.LocalPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The Notes of one Fastmail account, the same ones Fastmail shows on the web. */
@Composable
fun NotesScreen(vm: AppViewModel, acc: String) {
    val p = LocalPalette.current
    val account = vm.store.get(acc) ?: return
    var notes by remember { mutableStateOf<List<Notes.Note>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(acc, tick) {
        loading = true
        try {
            notes = Notes(vm.getApplication(), account).list()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            vm.message = e.message
        } finally {
            loading = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.notes), onBack = { vm.back() }) {
            IconBtn(R.drawable.ic_add, { vm.go(Screen.NoteEdit(acc, null)) })
        }
        RefreshBox(refreshing = loading, onRefresh = { tick++ }, modifier = Modifier.weight(1f)) {
            val listState = com.opensolr.mail.ui.rememberListMemory(vm.prefs, "notes_" + acc, !notes.isNullOrEmpty())
            val scrollIndex = remember(notes) {
                // Notes are titled by the month they were last changed in.
                val month = java.text.SimpleDateFormat("LLLL yyyy", java.util.Locale.getDefault())
                com.opensolr.mail.ui.ScrollIndex().apply {
                    notes.orEmpty().forEach { n -> row(month.format(java.util.Date(n.updated)).replaceFirstChar { it.uppercase() }) }
                    if (notes?.isEmpty() == true) row()
                    row()
                }
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(notes.orEmpty(), key = { it.id }) { n ->
                    Column(Modifier.fillMaxWidth().hapticClickable { vm.go(Screen.NoteEdit(acc, n.id)) }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (n.pinned) {
                                Icon(painterResource(R.drawable.ic_pin), null, tint = p.accent, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(n.title.ifBlank { stringResource(R.string.untitled) }, style = MaterialTheme.typography.titleSmall, color = p.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(fmtDate(n.updated), style = MaterialTheme.typography.bodySmall, color = p.muted)
                        }
                        Text(n.preview, style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Hairline()
                }
                if (notes?.isEmpty() == true) item {
                    Text(stringResource(R.string.no_notes), style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(32.dp))
                }
                item { Spacer(Modifier.height(bottomInset())) }
            }
            FastScroller(listState, scrollIndex)
        }
    }
}

@Composable
fun NoteEditScreen(vm: AppViewModel, acc: String, noteId: String?) {
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val account = vm.store.get(acc) ?: return
    val notes = remember { Notes(vm.getApplication(), account) }
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var content by remember { mutableStateOf<Notes.Content?>(null) }
    var pinned by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(noteId == null) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(noteId) {
        if (noteId == null) return@LaunchedEffect
        try {
            notes.open(noteId)?.let { c -> content = c; title = c.title; text = c.text; pinned = c.pinned }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            vm.message = e.message
        }
        loaded = true
    }
    val dirty = loaded && (title != (content?.title ?: "") || text != (content?.text ?: "") || pinned != (content?.pinned ?: false))

    fun saveAndLeave() {
        if (!dirty || (title.isBlank() && text.isBlank())) { vm.back(); return }
        if (saving) return
        saving = true
        scope.launch(com.opensolr.mail.ui.Guard) {
            try {
                notes.save(noteId, title, text, content?.uuid, content?.created, pinned)
                vm.back()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.message = e.message
            } finally {
                saving = false
            }
        }
    }

    BackHandler { saveAndLeave() }

    Column(Modifier.fillMaxSize()) {
        TopBar(if (noteId == null) stringResource(R.string.new_note) else stringResource(R.string.notes), onBack = { saveAndLeave() }) {
            IconBtn(R.drawable.ic_pin, { pinned = !pinned }, tint = if (pinned) p.accent else null)
            if (noteId != null) IconBtn(R.drawable.ic_delete, {
                scope.launch(com.opensolr.mail.ui.Guard) {
                    runCatching { notes.delete(noteId) }.onFailure { vm.message = it.message }.onSuccess { vm.back() }
                }
            })
            IconBtn(R.drawable.ic_check, { saveAndLeave() }, tint = p.accent)
        }
        if (!loaded) {
            Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(16.dp))
        } else {
            val noteScroll = com.opensolr.mail.ui.rememberScrollMemory("note_" + acc + "_" + noteId, { vm.positions["note_" + acc + "_" + noteId]?.first ?: 0 }, { v -> vm.positions["note_" + acc + "_" + noteId] = v to 0 })
            val noteMarks = remember { com.opensolr.mail.ui.ScrollMarks() }
            Box(Modifier.weight(1f)) {
                androidx.compose.runtime.CompositionLocalProvider(com.opensolr.mail.ui.LocalScrollMarks provides noteMarks) {
                Column(Modifier.fillMaxSize().verticalScroll(noteScroll)) {
                Field(title, { title = it }, stringResource(R.string.title), Modifier.fillMaxWidth())
                Hairline()
                Field(text, { text = it }, stringResource(R.string.note_hint), Modifier.fillMaxWidth(), singleLine = false, minHeight = 400)
                Spacer(Modifier.height(bottomInset()))
            }
                }
                FastScroller(noteScroll, noteMarks)
            }
        }
    }
}
