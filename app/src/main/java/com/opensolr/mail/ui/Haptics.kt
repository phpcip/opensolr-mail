package com.opensolr.mail.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalView

/** The taps the app gives back: the knock when a pull to refresh takes, and the lighter tick when a finger moves across something that steps, like the months of the bandwidth chart. */
object Haptics {

    /** Whether anything is played at all. */
    @Volatile
    var enabled: Boolean = true

    /** One tap: [strong] for crossing a major step, the lighter one for a minor step inside it. */
    fun tick(view: View, strong: Boolean) {
        if (!enabled) return
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (strong) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(constant)
    }

    /** The knock a pull to refresh gives back the moment it takes: a gesture that starts something heavy has to be felt, not only seen. */
    fun thud(view: View) {
        if (!enabled) return
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }

    /** The heavy knock of something that matters: a delete, a flag that pins a conversation to the top. */
    fun heavy(view: View) {
        if (!enabled) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.context.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") view.context.getSystemService(android.os.Vibrator::class.java)
        }
        if (vibrator == null || !vibrator.hasVibrator()) { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); return }
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_HEAVY_CLICK)
        else android.os.VibrationEffect.createOneShot(45, 255)
        vibrator.vibrate(effect)
    }

    /** The light tap every button, row, tab and chip gives back. */
    fun tap(view: View) {
        if (!enabled) return
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** A switch flipping: the platform's on and off feel where it has them, a tap elsewhere. */
    fun toggle(view: View, on: Boolean) {
        if (!enabled) return
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(constant)
    }
}

/** [clickable] that also taps back, so nothing in the app is pressed without being felt. */
fun Modifier.hapticClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val view = LocalView.current
    clickable(enabled = enabled) {
        Haptics.tap(view)
        onClick()
    }
}
