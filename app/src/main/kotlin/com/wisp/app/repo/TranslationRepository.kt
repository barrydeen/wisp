package com.wisp.app.repo

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val states = ConcurrentHashMap<String, TranslationState>()
    private val observations = KeyedObservation<String, TranslationState>(this, ::getState)

    /** Coarse global counter for screens (bookmarks, thread, search...) that re-snapshot
     *  all visible translations at once. Feed screens use [stateFor] instead. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun stateFor(eventId: String): Flow<TranslationState> =
        observations.observe(eventId).distinctUntilChanged()

    fun getState(eventId: String): TranslationState =
        states[eventId] ?: TranslationState()

    fun translate(eventId: String, content: String) {
        fun publish(state: TranslationState) = synchronized(this) {
            states[eventId] = state
            observations.publish(eventId)
            _version.value++
        }
        synchronized(this) {
            val current = getState(eventId)
            if (current.status != TranslationStatus.IDLE && current.status != TranslationStatus.ERROR) return
            publish(TranslationState(status = TranslationStatus.IDENTIFYING_LANGUAGE))
        }

        val targetTag = TranslateLanguage.fromLanguageTag(Locale.getDefault().language)
        if (targetTag == null) {
            publish(TranslationState(
                status = TranslationStatus.ERROR,
                errorMessage = "Unsupported target language"
            ))
            return
        }

        scope.launch {
            try {
                val identifier = LanguageIdentification.getClient()
                val detectedTag = try {
                    identifier.identifyLanguage(content).await()
                } finally {
                    identifier.close()
                }

                if (detectedTag == "und") {
                    publish(TranslationState(
                        status = TranslationStatus.ERROR,
                        errorMessage = "Could not detect language"
                    ))
                    return@launch
                }

                val sourceTag = TranslateLanguage.fromLanguageTag(detectedTag)
                if (sourceTag == null) {
                    publish(TranslationState(
                        status = TranslationStatus.ERROR,
                        errorMessage = "Unsupported source language: $detectedTag"
                    ))
                    return@launch
                }

                if (sourceTag == targetTag) {
                    publish(TranslationState(
                        status = TranslationStatus.SAME_LANGUAGE,
                        sourceLanguage = displayName(detectedTag),
                        targetLanguage = displayName(Locale.getDefault().language)
                    ))
                    return@launch
                }

                publish(TranslationState(
                    status = TranslationStatus.DOWNLOADING_MODEL,
                    sourceLanguage = displayName(detectedTag),
                    targetLanguage = displayName(Locale.getDefault().language)
                ))

                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(sourceTag)
                    .setTargetLanguage(targetTag)
                    .build()
                val translator = Translation.getClient(options)

                val result = try {
                    translator.downloadModelIfNeeded().await()
                    publish(getState(eventId).copy(status = TranslationStatus.TRANSLATING))
                    translator.translate(content).await()
                } finally {
                    translator.close()
                }

                publish(TranslationState(
                    status = TranslationStatus.DONE,
                    translatedText = result,
                    sourceLanguage = displayName(detectedTag),
                    targetLanguage = displayName(Locale.getDefault().language)
                ))
            } catch (e: CancellationException) {
                publish(TranslationState())
                throw e
            } catch (e: Exception) {
                publish(TranslationState(
                    status = TranslationStatus.ERROR,
                    errorMessage = e.message ?: "Translation failed"
                ))
            }
        }
    }

    private fun displayName(languageTag: String): String =
        Locale(languageTag).displayLanguage
}
