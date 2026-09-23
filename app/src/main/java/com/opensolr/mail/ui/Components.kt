package com.opensolr.mail.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.opensolr.mail.ui.theme.LocalPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SHAPE = RoundedCornerShape(2.dp)

/** The header row of every screen: fill, hairline under it, actions on the right. */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val p = LocalPalette.current
    Row(
        modifier = Modifier.fillMaxWidth().background(p.band).padding(horizontal = 6.dp).height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconBtn(com.opensolr.mail.R.drawable.ic_back, onBack)
        else Spacer(Modifier.width(10.dp))
        Text(
            title, style = MaterialTheme.typography.titleMedium, color = p.ink, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        actions()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.hairline))
}

/** A square tap target around a monochrome line icon. */
@Composable
fun IconBtn(@DrawableRes icon: Int, onClick: () -> Unit, tint: Color? = null, enabled: Boolean = true, strong: Boolean = false) {
    val p = LocalPalette.current
    val view = androidx.compose.ui.platform.LocalView.current
    Box(
        modifier = Modifier.size(44.dp).clickable(enabled = enabled) {
            if (strong) Haptics.tick(view, true) else Haptics.tap(view)
            onClick()
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = if (enabled) tint ?: p.ink else p.hairline, modifier = Modifier.size(22.dp))
    }
}

/** Filled accent button. */
@Composable
fun AccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = LocalPalette.current
    val view = androidx.compose.ui.platform.LocalView.current
    Box(
        modifier = modifier.heightIn(min = 46.dp).background(if (enabled) p.accentFill else p.hairline, SHAPE)
            .clickable(enabled = enabled) { Haptics.tick(view, true); onClick() }.padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = p.onAccentFill) }
}

/** Bordered button on the quiet fill. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    val p = LocalPalette.current
    Row(
        modifier = modifier.heightIn(min = 44.dp).background(p.buttonFill, SHAPE).border(1.dp, p.hairline, SHAPE)
            .hapticClickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, tint = p.ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = p.ink)
    }
}

/** An on/off chip for filters. */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        modifier = Modifier.background(if (selected) p.accentFill else p.buttonFill, SHAPE)
            .border(1.dp, if (selected) p.accentFill else p.hairline, SHAPE)
            .hapticClickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = if (selected) p.onAccentFill else p.ink, maxLines = 1)
    }
}

/** A single-line or multi-line text input on a bordered field. */
@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    email: Boolean = false,
    minHeight: Int = 44,
) {
    val p = LocalPalette.current
    Box(modifier = modifier.heightIn(min = minHeight.dp).padding(PaddingValues(horizontal = 12.dp, vertical = 11.dp))) {
        if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = p.muted)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = singleLine,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = p.ink),
            cursorBrush = SolidColor(p.accent),
            keyboardOptions = if (email) KeyboardOptions(keyboardType = KeyboardType.Email) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalPalette.current.hairline))
}

@Composable
fun SectionLabel(text: String) {
    val p = LocalPalette.current
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelMedium, color = p.muted,
        modifier = Modifier.fillMaxWidth().background(p.paper).padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

/** Solr highlight fragments: <em> becomes bold, every other tag goes. */
fun highlighted(fragment: String) = buildAnnotatedString {
    val clean = fragment.replace(Regex("<(?!/?em>)[^>]*>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
    var i = 0
    while (i < clean.length) {
        val s = clean.indexOf("<em>", i)
        if (s < 0) { append(clean.substring(i)); break }
        append(clean.substring(i, s))
        val e = clean.indexOf("</em>", s)
        if (e < 0) { append(clean.substring(s + 4)); break }
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(clean.substring(s + 4, e)) }
        i = e + 5
    }
}

/** Every date the app shows: mm/dd/yyyy hh:mm:ss. */
fun fmtDate(ms: Long): String = if (ms <= 0) "" else SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US).format(Date(ms))

fun fmtSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1024 -> String.format(Locale.US, "%d KB", bytes / 1024)
    else -> "$bytes B"
}

/** A zone of a settings-like screen, as in Opensolr Photos: tinted heading with a chevron, folded until tapped. */
@Composable
fun Zone(title: String, badge: Int, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth().scrollMark(LocalScrollMarks.current, title, title)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp)
                .background(p.accent.copy(alpha = 0.18f), SHAPE)
                .clickable { Haptics.tick(view, strong = false); onToggle() }
                .padding(start = 8.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown else androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, tint = p.accent, modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp), fontWeight = FontWeight.Bold, color = p.ink, modifier = Modifier.weight(1f))
            if (badge > 0) {
                Text(
                    badge.toString(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = p.onAccentFill,
                    modifier = Modifier.background(p.accentFill, SHAPE).padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = open,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(220)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(180)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(140)),
        ) {
            androidx.compose.foundation.layout.Column {
                Spacer(Modifier.height(10.dp))
                content()
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

/** A section inside a zone: a lighter heading with its own chevron, folded until tapped, and remembered. */
@Composable
fun SubZone(title: String, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { Haptics.tick(view, strong = false); onToggle() }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown else androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null, tint = p.accent, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = p.ink)
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = open,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(200)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(160)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)),
        ) {
            androidx.compose.foundation.layout.Column(Modifier.padding(start = 26.dp, bottom = 8.dp)) { content() }
        }
        Hairline()
    }
}

/** A label and its value on one line, with a rule under it. */
@Composable
fun InfoRow(label: String, value: String) {
    val p = LocalPalette.current
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = p.muted, modifier = Modifier.padding(end = 16.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), color = p.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        Hairline()
    }
}

/** An accent-edged note: a warning or a status the reader has to see. */
@Composable
fun Notice(text: String, title: String? = null) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(p.accent))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).background(p.band).padding(horizontal = 16.dp, vertical = 14.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = p.ink)
                Spacer(Modifier.height(4.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, color = p.muted)
        }
    }
}

/** The big screen title with its back arrow, as in Opensolr Photos. */
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)?) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconBtn(com.opensolr.mail.R.drawable.ic_back, onBack)
        Text(title, style = MaterialTheme.typography.headlineSmall, color = p.ink, modifier = Modifier.padding(start = if (onBack == null) 4.dp else 0.dp))
    }
}

/** The edges of the cards under a conversation of several messages, so a thread reads as a stack. */
@Composable
fun StackEdges(count: Int) {
    if (count < 2) return
    val p = LocalPalette.current
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(start = 52.dp, end = 10.dp).height(3.dp).background(p.chip, androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)).border(1.dp, p.hairline, androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)))
        if (count > 2) Box(Modifier.fillMaxWidth().padding(start = 60.dp, end = 18.dp).height(3.dp).background(p.band, androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)).border(1.dp, p.hairline, androidx.compose.foundation.shape.RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)))
    }
}

/** The number of messages of a conversation, on a small bordered tile like the other row badges. */
@Composable
fun CountBadge(count: Int) {
    if (count < 2) return
    val p = LocalPalette.current
    Box(Modifier.padding(start = 6.dp).background(p.chip, SHAPE).border(1.dp, p.hairline, SHAPE).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(count.toString(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = p.ink)
    }
}

/** The flag of a flagged message, on a warm tile that stands out more than the attachment badge. */
@Composable
fun FlagBadge() {
    val p = LocalPalette.current
    Box(
        Modifier.padding(start = 6.dp).background(p.flagTile, SHAPE).border(1.dp, p.flagRim, SHAPE).padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Icon(painterResource(com.opensolr.mail.R.drawable.ic_flag), null, tint = p.flagMark, modifier = Modifier.size(13.dp))
    }
}

/** The paperclip of a message with attachments, on a small tinted tile so it reads at a glance. */
@Composable
fun AttachBadge() {
    val p = LocalPalette.current
    Box(
        Modifier.padding(start = 6.dp).background(p.accent.copy(alpha = 0.10f), SHAPE)
            .border(1.dp, p.accent.copy(alpha = 0.30f), SHAPE).padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Icon(painterResource(com.opensolr.mail.R.drawable.ic_attach), null, tint = p.accent, modifier = Modifier.size(13.dp))
    }
}
