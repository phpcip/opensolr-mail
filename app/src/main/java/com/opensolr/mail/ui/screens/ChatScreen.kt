package com.opensolr.mail.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.opensolr.mail.R
import com.opensolr.mail.chat.MailChat
import com.opensolr.mail.ui.AppViewModel
import com.opensolr.mail.ui.DialogButton
import com.opensolr.mail.ui.Hairline
import com.opensolr.mail.ui.Haptics
import com.opensolr.mail.ui.IconBtn
import com.opensolr.mail.ui.Markdown
import com.opensolr.mail.ui.TopBar
import com.opensolr.mail.ui.bottomInset
import com.opensolr.mail.ui.hapticClickable
import com.opensolr.mail.ui.rememberPress
import com.opensolr.mail.ui.theme.LocalPalette
import com.opensolr.mail.ui.tile
import com.opensolr.mail.ui.tint

private val Corner = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)

/** A command of the chat: what to type, what it does, an example that fills the box. */
private data class ChatCommand(val name: String, val usage: String, val text: Int, val example: String)

private val COMMANDS = listOf(
    ChatCommand("search", "/search <words>", R.string.cmd_search, "/search invoice march"),
    ChatCommand("time", "/time <place>", R.string.cmd_time, "/time Tokyo"),
    ChatCommand("rate", "/rate <amount> <currency> to <currency>", R.string.cmd_rate, "/rate 100 EUR to RON"),
    ChatCommand("vat", "/vat <country codes>", R.string.cmd_vat, "/vat RO, DE"),
    ChatCommand("vatcheck", "/vatcheck <VAT number>", R.string.cmd_vatcheck, "/vatcheck IE6388047V"),
    ChatCommand("distance", "/distance <place> to <place>", R.string.cmd_distance, "/distance Paris to Berlin"),
    ChatCommand("place", "/place <place or address>", R.string.cmd_place, "/place Santa Clara, California"),
    ChatCommand("postal", "/postal <postal code or town>", R.string.cmd_postal, "/postal 10115 Berlin"),
    ChatCommand("ip", "/ip <IP address>", R.string.cmd_ip, "/ip 8.8.8.8"),
    ChatCommand("language", "/language <text>", R.string.cmd_language, "/language Acest produs este excelent"),
    ChatCommand("translate", "/translate <from>-<to> <text>", R.string.cmd_translate, "/translate en-es What is this?"),
    ChatCommand("help", "/help", R.string.cmd_help, "/help"),
)

/**
 * The mail chat: the reader asks, the assistant searches their own mail on the server and writes the answer as it
 * comes, the mails it names linked to their conversations. One question at a time; New chat clears it.
 */
@Composable
fun ChatScreen(vm: AppViewModel) {
    val p = LocalPalette.current
    val view = LocalView.current
    var text by rememberSaveable { mutableStateOf("") }
    var help by remember { mutableStateOf(false) }
    val lines = vm.chatLines
    val listState = rememberLazyListState()

    // A new message goes to the bottom; while the answer is written the list follows it only when the reader is there.
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1) }
    val lastText = lines.lastOrNull()?.text
    LaunchedEffect(lastText, vm.chatProgress) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lines.isNotEmpty() && listState.canScrollForward && lastVisible >= lines.size - 1) listState.scrollToItem(lines.size - 1, Int.MAX_VALUE / 2)
    }

    fun send() {
        if (vm.chatBusy || text.isBlank()) return
        Haptics.tick(view, true)
        vm.chatSend(text)
        text = ""
    }

    Column(Modifier.fillMaxSize()) {
        TopBar(stringResource(R.string.chat), onBack = { vm.back() }) {
            IconBtn(R.drawable.ic_help, { help = true }, contentDescription = stringResource(R.string.chat_commands))
            val press = rememberPress()
            Text(
                stringResource(R.string.chat_new), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = press.tint(p.ink),
                modifier = Modifier.padding(start = 4.dp, end = 4.dp).tile(press) { Haptics.tick(view, false); vm.chatNew() }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
        Hairline()
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (lines.isEmpty()) item {
                Text(
                    stringResource(R.string.chat_intro), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = p.muted, modifier = Modifier.padding(vertical = 24.dp, horizontal = 8.dp),
                )
            }
            itemsIndexed(lines) { i, line ->
                if (line.role == "user") UserBubble(line.text)
                else AssistantBubble(line, progress = if (i == lines.size - 1 && line.text.isEmpty()) vm.chatProgress else null, label = { vm.chatLabel(it) }, onOpen = { vm.chatOpen(view.context, it) })
            }
        }
        // "/" first: the commands that start with what is typed, above the box; a tap puts one in it.
        val typed = text.trimStart()
        val suggestions = if (typed.startsWith("/") && ' ' !in typed && '\n' !in typed) COMMANDS.filter { it.name.startsWith(typed.drop(1).lowercase()) } else emptyList()
        if (suggestions.isNotEmpty()) {
            Hairline()
            Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).background(p.band).verticalScroll(rememberScrollState())) {
                suggestions.forEach { c ->
                    Column(
                        Modifier.fillMaxWidth().hapticClickable { text = "/" + c.name + " " }.padding(horizontal = 14.dp, vertical = 9.dp),
                    ) {
                        Text(c.usage, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = p.ink)
                        Text(stringResource(c.text), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = p.muted)
                    }
                }
            }
        }
        Hairline()
        Row(
            Modifier.fillMaxWidth().background(p.toolFill).padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 8.dp + bottomInset()),
            verticalAlignment = Alignment.Bottom,
        ) {
            com.opensolr.mail.ui.OutlinedTextBox(
                // While the answer is written the box keeps its focus and its words, and takes no new ones.
                value = text, onValueChange = { if (!vm.chatBusy) text = it.take(MailChat.MAX_CHARS) },
                placeholder = { Text(stringResource(R.string.chat_hint), color = p.muted) },
                minLines = 1, maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = p.accent, unfocusedBorderColor = p.hairline, cursorColor = p.accent,
                    focusedTextColor = p.ink, unfocusedTextColor = p.ink,
                ),
                // The length allowed, counted as the reader writes
                supportingText = if (text.length >= MailChat.MAX_CHARS * 3 / 4) {
                    { Text(java.text.NumberFormat.getIntegerInstance().format(text.length) + " / " + java.text.NumberFormat.getIntegerInstance().format(MailChat.MAX_CHARS), color = if (text.length >= MailChat.MAX_CHARS) p.accent else p.muted) }
                } else null,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            if (vm.chatBusy) {
                Box(Modifier.padding(bottom = 6.dp).size(44.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), color = p.accent, trackColor = p.hairline, strokeWidth = 2.5.dp)
                }
            } else {
                IconBtn(
                    R.drawable.ic_send, { send() }, tint = p.accent, enabled = text.isNotBlank(), strong = true,
                    contentDescription = stringResource(R.string.chat_send), modifier = Modifier.padding(bottom = 6.dp).size(44.dp),
                )
            }
        }
    }

    if (help) CommandsDialog(onPick = { text = it; help = false }, onDismiss = { help = false })
}

@Composable
private fun UserBubble(text: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        androidx.compose.foundation.text.selection.SelectionContainer {
            Text(
                text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = p.onAccentFill,
                modifier = Modifier.widthIn(max = 320.dp).background(p.accentFill, Corner).padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun AssistantBubble(line: AppViewModel.ChatLine, progress: String?, label: (String) -> String?, onOpen: (String) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(p.band, Corner).border(1.dp, p.hairline, Corner).padding(12.dp)) {
        when {
            line.text.isEmpty() -> ProgressLine(progress ?: stringResource(R.string.chat_reading))
            line.error -> Text(line.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = p.muted)
            else -> {
                // Each mail the answer links (mail:<id>) becomes a tap that opens its conversation.
                val (md, ids) = remember(line.text, label) { mailLinks(line.text, label) }
                // A last paragraph made only of links: the sources, shown as pills under the answer
                val (body, pills) = remember(md) { sourcePills(md) }
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Markdown(body, onRef = { i -> ids.getOrNull(i)?.let(onOpen) })
                }
                if (pills.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
                    Spacer(Modifier.height(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        pills.forEach { (text, ref) -> SourcePill(text) { ids.getOrNull(ref)?.let(onOpen) } }
                    }
                }
            }
        }
    }
}

private val PILL = androidx.compose.foundation.shape.RoundedCornerShape(50)
private val SOURCE_LINK = Regex("\\[([^\\]\\n]+)\\]\\(ref:(\\d+)\\)")
private val SOURCE_LINE = Regex("^\\s*(?:\\[[^\\]\\n]+\\]\\(ref:\\d+\\)\\s*(?:·\\s*)?)+$")

/** [md] without its last paragraph when that paragraph is only links, and those links as (text, ref) pills. */
private fun sourcePills(md: String): Pair<String, List<Pair<String, Int>>> {
    val at = md.lastIndexOf("\n\n")
    val tail = if (at < 0) "" else md.substring(at + 2)
    if (tail.isBlank() || !SOURCE_LINE.matches(tail)) return md to emptyList()
    return md.substring(0, at) to SOURCE_LINK.findAll(tail).map { it.groupValues[1] to it.groupValues[2].toInt() }.toList()
}

/** One source: a discreet pill on one line, its text cut with an ellipsis when long; a tap opens it. */
@Composable
private fun SourcePill(text: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    val press = rememberPress()
    Text(
        text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = press.tint(p.ink),
        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.background(p.paper, PILL).border(1.dp, if (press.on) p.accent else p.hairline, PILL)
            .hapticClickable { onClick() }.padding(horizontal = 12.dp, vertical = 5.dp),
    )
}

/** What the assistant is doing, with a pulsing dot in the accent. */
@Composable
private fun ProgressLine(text: String) {
    val p = LocalPalette.current
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.25f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "dot")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).alpha(pulse).background(p.accent, Corner))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = p.muted)
    }
}

/**
 * The answer with every mail: link, linked or written bare, turned into a (ref:n) link that reads the mail's subject or the
 * file's name (a link whose text is the link itself gets that text too), and the links in that order.
 */
private fun mailLinks(text: String, label: (String) -> String?): Pair<String, List<String>> {
    val ids = ArrayList<String>()
    // The source line the server adds last: a link it holds that the answer already shows (same text) is not shown twice
    val tail = text.lastIndexOf("\n\n").takeIf { it >= 0 && AppViewModel.CHAT_ANY_LINK.containsMatchIn(text.substring(it)) } ?: text.length
    val shownBefore = HashSet<String>()
    val md = AppViewModel.CHAT_ANY_LINK.replace(text) { m ->
        val link = m.groupValues[2].ifEmpty { m.groupValues[3] }
        var n = ids.indexOf(link)
        if (n < 0) { ids += link; n = ids.size - 1 }
        val given = m.groupValues[1].trim()
        // A link written as its address, a number ("1", "[1]") or nothing reads the mail's subject or the file's name
        val bare = given.isEmpty() || given.startsWith("mail:") || given == link || given.trim('[', ']', ' ', '#').all { it.isDigit() }
        val shown = (if (bare) label(link) ?: given.ifEmpty { link } else given).replace("[", "(").replace("]", ")")
        if (m.range.first >= tail && shown.lowercase() in shownBefore) return@replace "\u0000"
        if (m.range.first < tail) shownBefore += shown.lowercase()
        "[" + shown + "](ref:$n)"
    }
    // The links left out of the source line take their separators with them
    return md.replace(Regex("(\\s*·\\s*)?\u0000(\\s*·\\s*)?")) { r -> if (r.groupValues[1].isNotEmpty() && r.groupValues[2].isNotEmpty()) " · " else "" }.trimEnd() to ids
}

/** The commands, each with what it does and an example a tap puts in the box. */
@Composable
private fun CommandsDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_commands)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.chat_commands_intro), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = p.muted)
                Spacer(Modifier.height(8.dp))
                COMMANDS.forEach { c ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(c.usage, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = p.ink)
                        Text(stringResource(c.text), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = p.muted)
                        val press = rememberPress()
                        Text(
                            c.example, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = press.tint(p.accent),
                            modifier = Modifier.padding(top = 4.dp).tile(press, rim = p.hairline) { onPick(c.example) }.padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                    Hairline()
                }
            }
        },
        confirmButton = { DialogButton(stringResource(R.string.ops_close), onDismiss) },
        containerColor = p.paper, titleContentColor = p.ink, textContentColor = p.ink,
    )
}
