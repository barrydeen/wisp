package com.wisp.app.repo

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class TranslationStatus {
    IDLE,
    IDENTIFYING_LANGUAGE,
    DOWNLOADING_MODEL,
    TRANSLATING,
    DONE,
    ERROR,
    SAME_LANGUAGE
}

data class TranslationState(
    val status: TranslationStatus = TranslationStatus.IDLE,
    val translatedText: String = "",
    val sourceLanguage: String = "",
    val targetLanguage: String = "",
    val errorMessage: String = ""
)

class TranslationRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = ConcurrentHashMap<String, TranslationState>()

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun getState(eventId: String): TranslationState =
        cache[eventId] ?: TranslationState()

    fun translate(eventId: String, content: String) {
        val current = cache[eventId]
        if (current != null && current.status != TranslationStatus.ERROR) return

        val targetTag = TranslateLanguage.fromLanguageTag(Locale.getDefault().language)
        if (targetTag == null) {
            cache[eventId] = TranslationState(
                status = TranslationStatus.ERROR,
                errorMessage = "Unsupported target language"
            )
            _version.value++
            return
        }

        cache[eventId] = TranslationState(status = TranslationStatus.IDENTIFYING_LANGUAGE)
        _version.value++

        scope.launch {
            try {
                val identifier = LanguageIdentification.getClient()
                val detectedTag = identifier.identifyLanguage(content).await()

                if (detectedTag == "und") {
                    cache[eventId] = TranslationState(
                        status = TranslationStatus.ERROR,
                        errorMessage = "Could not detect language"
                    )
                    _version.value++
                    return@launch
                }

                val sourceTag = TranslateLanguage.fromLanguageTag(detectedTag)
                if (sourceTag == null) {
                    cache[eventId] = TranslationState(
                        status = TranslationStatus.ERROR,
                        errorMessage = "Unsupported source language: $detectedTag"
                    )
                    _version.value++
                    return@launch
                }

                if (sourceTag == targetTag) {
                    cache[eventId] = TranslationState(
                        status = TranslationStatus.SAME_LANGUAGE,
                        sourceLanguage = displayName(detectedTag),
                        targetLanguage = displayName(Locale.getDefault().language)
                    )
                    _version.value++
                    return@launch
                }

                cache[eventId] = TranslationState(
                    status = TranslationStatus.DOWNLOADING_MODEL,
                    sourceLanguage = displayName(detectedTag),
                    targetLanguage = displayName(Locale.getDefault().language)
                )
                _version.value++

                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(sourceTag)
                    .setTargetLanguage(targetTag)
                    .build()
                val translator = Translation.getClient(options)

                translator.downloadModelIfNeeded().await()

                cache[eventId] = cache[eventId]!!.copy(status = TranslationStatus.TRANSLATING)
                _version.value++

                val result = translator.translate(content).await()

                cache[eventId] = TranslationState(
                    status = TranslationStatus.DONE,
                    translatedText = result,
                    sourceLanguage = displayName(detectedTag),
                    targetLanguage = displayName(Locale.getDefault().language)
                )
                _version.value++
            } catch (e: Exception) {
                cache[eventId] = TranslationState(
                    status = TranslationStatus.ERROR,
                    errorMessage = e.message ?: "Translation failed"
                )
                _version.value++
            }
        }
    }

    private fun displayName(languageTag: String): String =
        Locale(languageTag).displayLanguage
}
