package com.opensolr.mail.ui

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.ui.Modifier

/** Rows fade in and out and glide into place when groups fold or the list changes. */
fun LazyItemScope.itemMotion(): Modifier = Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(200), placementSpec = androidx.compose.animation.core.tween(220), fadeOutSpec = androidx.compose.animation.core.tween(150))
