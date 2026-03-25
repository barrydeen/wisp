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

    fun wrapContext(context: Context, languageCode: String): Context {
        val targetLanguage = if (languageCode == "system") {
            getSystemLanguage(context)
        } else {
            languageCode
        }
        
        if (targetLanguage == null) return context
        
        val locale = Locale.forLanguageTag(targetLanguage)
        Locale.setDefault(locale)
        
        val config = context.resources.configuration
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        
        return context.createConfigurationContext(config)
    }

    fun getSystemLanguage(context: Context): String? {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = context.getSystemService(LocaleManager::class.java)
            val appLocales = localeManager?.applicationLocales
            if (appLocales != null && !appLocales.isEmpty) {
                appLocales[0]?.language
            } else {
                context.resources.configuration.locales[0]?.language
            }
        } else {
            val appLocales = AppCompatDelegate.getApplicationLocales()
            if (!appLocales.isEmpty) {
                appLocales[0]?.language
            } else {
                context.resources.configuration.locales[0]?.language
            }
        }
        
        return locale?.let {
            supportedLanguages.find { lang -> lang.code == it }?.code
        }
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
