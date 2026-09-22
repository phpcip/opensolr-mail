package com.opensolr.mail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AVATAR_COLORS = longArrayOf(
    0xFFC05520, 0xFF2F6F8F, 0xFF5B7F3A, 0xFF8A4F9E, 0xFF9C6B1E, 0xFF3F5AA8, 0xFFA23B5A, 0xFF2E7D6B,
    0xFF7A5230, 0xFF4E6E9E, 0xFF8C3F2B, 0xFF52794F, 0xFF6B4FA0, 0xFF3D7F91, 0xFFA05C7B, 0xFF5E6B2E,
)

/** First letter of the first and of the last name; without a name, the first two letters before the @. */
fun initialsOf(name: String, email: String): String {
    val words = name.replace(Regex("[\"'()<>\\[\\]]"), " ").trim().split(Regex("\\s+")).filter { w -> w.firstOrNull()?.isLetterOrDigit() == true }
    return when {
        words.size >= 2 -> "${words.first().first()}${words.last().first()}"
        words.size == 1 -> words[0].take(2)
        else -> email.substringBefore('@').filter { it.isLetterOrDigit() }.take(2)
    }.uppercase().ifBlank { "?" }
}

/** The same sender always gets the same colour. */
fun colorOf(key: String): Color {
    val h = key.lowercase().trim().hashCode()
    return Color(AVATAR_COLORS[((h % AVATAR_COLORS.size) + AVATAR_COLORS.size) % AVATAR_COLORS.size])
}

@Composable
fun Avatar(name: String, email: String, size: Dp = 34.dp) {
    Box(Modifier.size(size).background(colorOf(email.ifBlank { name }), CircleShape), contentAlignment = Alignment.Center) {
        Text(
            initialsOf(name, email),
            style = TextStyle(fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFFFFF)),
            maxLines = 1,
        )
    }
}
