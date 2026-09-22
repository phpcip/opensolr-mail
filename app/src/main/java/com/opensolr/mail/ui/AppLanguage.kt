package com.opensolr.mail.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** The language of the app: the phone's own language by default, falling back to English when it is not one the app speaks, or one the owner picks under Settings. */
object AppLanguage {

    /** The languages the app is translated into: tag and the language's own name for itself. */
    val SUPPORTED = listOf(
        "en" to "English",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "ro" to "Română",
        "ja" to "日本語",
        "zh" to "中文",
    )

    private const val PREFS = "app_language"
    private const val KEY = "tag"

    /** The language picked under Settings, or "" for the phone's own. */
    fun chosen(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales
                ?.takeIf { !it.isEmpty }?.get(0)?.language?.takeIf { tag -> SUPPORTED.any { it.first == tag } } ?: ""
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        }

    /** Picks [tag] ("" = the phone's own). */
    @SuppressLint("ApplySharedPref")
    fun choose(activity: Activity, tag: String) {
        val clean = tag.takeIf { t -> SUPPORTED.any { it.first == t } } ?: ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (clean.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(clean)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, clean).commit()
            activity.recreate()
        }
    }

    /** [base] with the chosen language applied, for Android before 13; unchanged otherwise, or when the phone's own language is in force. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
