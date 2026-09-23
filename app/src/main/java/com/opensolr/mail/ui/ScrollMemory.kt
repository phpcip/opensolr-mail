package com.opensolr.mail.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.opensolr.mail.data.AppPrefs
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

/**
 * A list that comes back exactly where it was left, across screens and app restarts. The saved
 * position is applied once the rows are there ([ready]); nothing is written back before that, so
 * an empty first frame can never overwrite it with the top.
 */
@Composable
fun rememberListMemory(prefs: AppPrefs, key: String, ready: Boolean): LazyListState =
    rememberListMemory(key, ready, { prefs.scrollOf(key) }, { i, o -> prefs.saveScroll(key, i, o) })

/** [rememberListMemory] with the position kept wherever [load] and [save] keep it. */
@OptIn(FlowPreview::class)
@Composable
fun rememberListMemory(key: String, ready: Boolean, load: () -> Pair<Int, Int>, save: (Int, Int) -> Unit): LazyListState {
    val saved = remember(key) { load() }
    val state = remember(key) { LazyListState(saved.first, saved.second) }
    // Restored once the rows are there, top included: a list that fills in later otherwise stays on
    // the row it showed while empty and opens half way down.
    var restored by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, ready) {
        if (ready && !restored) {
            state.scrollToItem(saved.first, saved.second)
            restored = true
        }
    }
    LaunchedEffect(key, restored) {
        if (!restored) return@LaunchedEffect
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .debounce(400)
            .collect { (i, o) -> save(i, o) }
    }
    return state
}

/** The same for a scrolling column: restored once its content is tall enough to hold the saved offset. */
@Composable
fun rememberScrollMemory(prefs: AppPrefs, key: String): ScrollState =
    rememberScrollMemory(key, { prefs.scrollOf(key).first }, { v -> prefs.saveScroll(key, v, 0) })

@OptIn(FlowPreview::class)
@Composable
fun rememberScrollMemory(key: String, load: () -> Int, save: (Int) -> Unit): ScrollState {
    val saved = remember(key) { load() }
    val state = remember(key) { ScrollState(0) }
    var restored by remember(key) { mutableStateOf(saved == 0) }
    LaunchedEffect(key, state.maxValue) {
        if (!restored && state.maxValue > 0 && state.maxValue != Int.MAX_VALUE) {
            state.scrollTo(minOf(saved, state.maxValue))
            if (state.maxValue >= saved) restored = true
        }
    }
    LaunchedEffect(key, restored) {
        if (!restored) return@LaunchedEffect
        snapshotFlow { state.value }.debounce(400).collect { save(it) }
    }
    return state
}
