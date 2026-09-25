package com.opensolr.mail.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.opensolr.mail.data.MailDb
import com.opensolr.mail.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * A person's picture: the phone contact's, else the Fastmail card's, else their Gravatar; the initials
 * while none is found. Pictures are kept in memory and Gravatar answers on disk, a missing one included.
 */
@Composable
fun PersonPhoto(photo: String?, name: String, email: String, size: Dp = 34.dp) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(memory.get(keyOf(photo, email))?.bitmap, photo, email) {
        value = withContext(Dispatchers.IO) { load(context, photo, email) }
    }
    val b = bitmap
    if (b != null) Image(b, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    else Avatar(name, email, size)
}

private class Found(val bitmap: ImageBitmap?)

private val memory = LruCache<String, Found>(400)

private fun keyOf(photo: String?, email: String) = photo ?: ("g:" + email.trim().lowercase())

private fun load(context: Context, photo: String?, email: String): ImageBitmap? {
    val key = keyOf(photo, email)
    memory.get(key)?.let { return it.bitmap }
    val own = when {
        photo == null -> null
        photo.startsWith("fm:") -> runCatching {
            val (acc, href) = photo.removePrefix("fm:").split('\u0001', limit = 2)
            MailDb.get(context).fmPhoto(acc, href)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }.getOrNull()
        else -> runCatching { context.contentResolver.openInputStream(Uri.parse(photo))?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
    }
    // A failed request is not remembered: the next showing asks again.
    val found = own?.asImageBitmap()?.let { Found(it) } ?: gravatar(context, email) ?: return null
    memory.put(key, found)
    return found.bitmap
}

/** Gravatar by the SHA-256 of the address; a 404 is remembered for a week so the same address is not asked again. Null when it could not be asked. */
private fun gravatar(context: Context, email: String): Found? {
    val clean = email.trim().lowercase()
    if (!clean.contains('@')) return Found(null)
    val hash = MessageDigest.getInstance("SHA-256").digest(clean.toByteArray()).joinToString("") { "%02x".format(it) }
    val dir = File(context.cacheDir, "gravatar").apply { mkdirs() }
    val img = File(dir, "$hash.img")
    val none = File(dir, "$hash.none")
    val week = 7L * 24 * 60 * 60 * 1000
    if (img.exists() && System.currentTimeMillis() - img.lastModified() < week) return Found(BitmapFactory.decodeFile(img.path)?.asImageBitmap())
    if (none.exists() && System.currentTimeMillis() - none.lastModified() < week) return Found(null)
    return runCatching {
        Http.client.newCall(Request.Builder().url("https://www.gravatar.com/avatar/$hash?s=160&d=404").build()).execute().use { r ->
            when {
                r.code == 404 -> { none.writeText(""); img.delete(); Found(null) }
                r.isSuccessful -> {
                    val bytes = r.body?.bytes() ?: return@use null
                    img.writeBytes(bytes); none.delete()
                    Found(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap())
                }
                else -> null
            }
        }
    }.getOrNull()
}
