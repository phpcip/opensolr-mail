package com.opensolr.mail.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.ime
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opensolr.mail.ui.screens.ComposeScreen
import com.opensolr.mail.ui.screens.MailboxesScreen
import com.opensolr.mail.ui.screens.NoteEditScreen
import com.opensolr.mail.ui.screens.NotesScreen
import com.opensolr.mail.ui.screens.SearchScreen
import com.opensolr.mail.ui.screens.SettingsScreen
import com.opensolr.mail.ui.screens.SetupScreen
import com.opensolr.mail.ui.screens.ThreadListScreen
import com.opensolr.mail.ui.screens.ThreadScreen
import com.opensolr.mail.ui.theme.LocalPalette
import kotlinx.coroutines.delay

/** Insets of the system bars, never less than a floor: some phones report 0 for the gesture bar. */
@Composable
fun topInset(): Dp = maxOf(WindowInsets.statusBars.asPaddingValues().calculateTopPadding(), 24.dp)

@Composable
fun bottomInset(): Dp = maxOf(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(), 16.dp)

@Composable
fun AppRoot(vm: AppViewModel) {
    val p = LocalPalette.current
    val accounts by vm.store.accounts.collectAsState()

    Box(Modifier.fillMaxSize().background(p.paper).padding(top = topInset()).imePadding()) {
        when {
            accounts.isEmpty() -> SetupScreen(vm)
            else -> {
                BackHandler(enabled = vm.stack.size > 1) { vm.back() }
                androidx.compose.animation.AnimatedContent(
                    targetState = vm.screen,
                    transitionSpec = { screenTransition(depth(initialState), depth(targetState)) },
                    contentKey = { it },
                    label = "screens",
                ) { s ->
                when (s) {
                    Screen.Mailboxes -> MailboxesScreen(vm)
                    is Screen.List -> ThreadListScreen(vm, s.view)
                    is Screen.Thread -> ThreadScreen(vm, s.acc, s.threadId)
                    is Screen.Compose -> ComposeScreen(vm, s.init)
                    is Screen.Search -> SearchScreen(vm, s.sheet, s)
                    is Screen.Notes -> NotesScreen(vm, s.acc)
                    is Screen.NoteEdit -> NoteEditScreen(vm, s.acc, s.noteId)
                    Screen.Settings -> SettingsScreen(vm)
                    Screen.Contacts -> com.opensolr.mail.ui.screens.ContactsScreen(vm)
                }
                }
            }
        }

        vm.undo?.let { u ->
            val v = androidx.compose.ui.platform.LocalView.current
            LaunchedEffect(u.id) {
                delay(5000)
                vm.commitUndo(u.id)
            }
            androidx.compose.foundation.layout.Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = bottomInset() + 12.dp, start = 12.dp, end = 12.dp)
                    .background(p.ink).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(u.text, style = MaterialTheme.typography.bodyMedium, color = p.paper, modifier = Modifier.weight(1f))
                val press = rememberPress()
                Text(
                    androidx.compose.ui.res.stringResource(com.opensolr.mail.R.string.undo).uppercase(),
                    style = MaterialTheme.typography.labelLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = if (press.on) p.onAccentFill else p.paper,
                    modifier = Modifier.padding(8.dp).tile(press, fill = p.ink, rim = p.paper.copy(alpha = 0.45f)) { Haptics.tick(v, true); vm.undoLast() }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
            }
        }

        // Mail indexes of other phones on the account: re-use one, or start this phone's own.
        val indexStatus by com.opensolr.mail.index.MailIndexer.status.collectAsState()
        if (indexStatus.choices.isNotEmpty()) {
            val v = androidx.compose.ui.platform.LocalView.current
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {},
                title = { Text(androidx.compose.ui.res.stringResource(com.opensolr.mail.R.string.reuse_title)) },
                text = {
                    Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                        indexStatus.choices.sortedByDescending { maxOf(it.lastIndex, it.created) }.forEach { c ->
                            Column(Modifier.fillMaxWidth().clickable { Haptics.tick(v, true); vm.chooseIndex(c.name) }.padding(vertical = 12.dp)) {
                                val count = java.text.NumberFormat.getIntegerInstance().format(c.numDocs)
                                Text(
                                    androidx.compose.ui.res.stringResource(com.opensolr.mail.R.string.reuse_messages, count),
                                    style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = p.ink,
                                )
                                val at = maxOf(c.lastIndex, c.created)
                                if (at > 0) Text(
                                    androidx.compose.ui.res.stringResource(com.opensolr.mail.R.string.reuse_last, fmtDate(at * 1000L)),
                                    style = MaterialTheme.typography.bodySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = p.muted,
                                )
                            }
                            Hairline()
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { DialogButton(androidx.compose.ui.res.stringResource(com.opensolr.mail.R.string.reuse_new), { vm.chooseIndex(null) }) },
                containerColor = p.paper, titleContentColor = p.ink, textContentColor = p.ink,
            )
        }

        // An action that must finish first: the screen is covered, every touch and the back gesture are swallowed,
        // a spinner and one line say what is going on.
        vm.blocking?.let { text ->
            BackHandler(enabled = true) {}
            Box(
                Modifier.fillMaxSize().background(p.ink.copy(alpha = 0.72f))
                    .clickable(interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.background(p.paper).padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.padding(bottom = 16.dp).size(36.dp), color = p.accent, trackColor = p.hairline, strokeWidth = 3.dp)
                    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = p.ink)
                }
            }
        }

        // The keyboard is open: the first back closes it and nothing else, wherever the reader is. Composed last, so it
        // takes the back gesture before any screen's own handler.
        val imeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        BackHandler(enabled = imeOpen) { keyboard?.hide(); focus.clearFocus() }

        vm.message?.let { msg ->
            LaunchedEffect(msg) {
                delay(3500)
                vm.message = null
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = bottomInset() + 64.dp, start = 12.dp, end = 12.dp)) {
                Text(
                    msg, style = MaterialTheme.typography.bodyMedium, color = p.paper,
                    modifier = Modifier.fillMaxWidth().background(p.ink).hapticClickable { vm.message = null }.padding(14.dp),
                )
            }
        }
    }
}

/** How deep a screen sits: going deeper slides in from the right, going back from the left. */
private fun depth(s: Screen): Int = when (s) {
    Screen.Mailboxes -> 0
    is Screen.List -> 1
    is Screen.Thread, is Screen.Search, is Screen.Notes, Screen.Settings, Screen.Contacts -> 2
    is Screen.Compose, is Screen.NoteEdit -> 3
}

/** A short, quiet slide with a fade; screens at the same depth only cross-fade. */
private fun screenTransition(from: Int, to: Int): androidx.compose.animation.ContentTransform {
    val tween = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(260, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    val fadeIn = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 40))
    val fadeOut = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
    if (from == to) return fadeIn togetherWith fadeOut
    val sign = if (to > from) 1 else -1
    return (androidx.compose.animation.slideInHorizontally(tween) { sign * it / 10 } + fadeIn) togetherWith
        (androidx.compose.animation.slideOutHorizontally(tween) { -sign * it / 14 } + fadeOut)
}
