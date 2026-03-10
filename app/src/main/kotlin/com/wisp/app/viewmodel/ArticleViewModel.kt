package com.wisp.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.repo.EventRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ArticleViewModel : ViewModel() {
    private val _article = MutableStateFlow<NostrEvent?>(null)
    val article: StateFlow<NostrEvent?> = _article

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title

    private val _coverImage = MutableStateFlow<String?>(null)
    val coverImage: StateFlow<String?> = _coverImage

    private val _publishedAt = MutableStateFlow<Long?>(null)
    val publishedAt: StateFlow<Long?> = _publishedAt

    private val _hashtags = MutableStateFlow<List<String>>(emptyList())
    val hashtags: StateFlow<List<String>> = _hashtags

    fun loadArticle(kind: Int, author: String, dTag: String, eventRepo: EventRepository) {
        viewModelScope.launch {
            val cached = eventRepo.findAddressableEvent(kind, author, dTag)
            if (cached != null) {
                parseAndEmit(cached)
                return@launch
            }

            eventRepo.requestAddressableEvent(kind, author, dTag)

            // Poll for arrival with timeout
            var elapsed = 0L
            while (elapsed < 8000) {
                delay(200)
                elapsed += 200
                val event = eventRepo.findAddressableEvent(kind, author, dTag)
                if (event != null) {
                    parseAndEmit(event)
                    return@launch
                }
            }
            _isLoading.value = false
        }
    }

    private fun parseAndEmit(event: NostrEvent) {
        _article.value = event
        _title.value = event.tags.firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1)
        _coverImage.value = event.tags.firstOrNull { it.size >= 2 && it[0] == "image" }?.get(1)
        _publishedAt.value = event.tags.firstOrNull { it.size >= 2 && it[0] == "published_at" }?.get(1)?.toLongOrNull()
        _hashtags.value = event.tags.filter { it.size >= 2 && it[0] == "t" }.map { it[1] }
        _isLoading.value = false
    }
}
