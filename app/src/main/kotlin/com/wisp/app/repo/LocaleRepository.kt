package com.wisp.app.repo

import android.app.Application
import android.os.Build
import android.app.LocaleManager
import android.os.LocaleList
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleRepository {
    data class Language(val code: String, val displayName: String)

    val supportedLanguages = listOf(
        Language("system", "System Default"),
        Language("en", "English"),
        Language("de", "Deutsch"),
        Language("es", "Español"),
        Language("fr", "Français"),
        Language("it", "Italiano"),
        Language("ja", "日本語"),
        Language("ko", "한국어"),
        Language("nl", "Nederlands"),
        Language("pt", "Português"),
        Language("ru", "Русский"),
        Language("zh", "中文")
    )

    fun applyLanguage(context: Context, languageCode: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            if (languageCode == "system") {
                localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
            } else {
                val locale = Locale.forLanguageTag(languageCode)
                localeManager.applicationLocales = LocaleList(locale)
            }
        } else {
            val localeList = if (languageCode == "system") {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(languageCode)
            }
            AppCompatDelegate.setApplicationLocales(localeList)
        }
    }

    fun getLanguageDisplayName(code: String): String {
        return supportedLanguages.find { it.code == code }?.displayName ?: code
    }

    fun getCurrentLocale(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = android.app.Application().getSystemService(LocaleManager::class.java)
            val locales = localeManager.applicationLocales
            return if (locales.isEmpty) "system" else locales[0]?.language ?: "system"
        } else {
            val locales = AppCompatDelegate.getApplicationLocales()
            return if (locales.isEmpty) "system" else locales[0]?.language ?: "system"
        }
    }
}
