package cooking.zap.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import cooking.zap.app.nostr.FoodTopics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * UI state for the onboarding "What do you like to cook?" step. The candidate
 * list is the static, sectioned [FoodTopics] taxonomy (mirrors the web) — there
 * is no relay fetch, so this step is fast and works offline.
 *
 * Selections are stored as normalized nostr hashtags ([FoodTopics.toHashtag]).
 * Publishing is delegated to [FeedViewModel.followHashtags] once the user
 * continues (one kind-30015 interest set); this class only owns the pick state.
 */
class TopicOnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val _selectedTopics = MutableStateFlow<Set<String>>(emptySet())
    val selectedTopics: StateFlow<Set<String>> = _selectedTopics

    /** Toggle a topic by its [FoodTopics] display label; stored normalized. */
    fun toggleTopic(displayTag: String) {
        val hashtag = FoodTopics.toHashtag(displayTag)
        if (hashtag.isEmpty()) return
        val current = _selectedTopics.value
        _selectedTopics.value = if (hashtag in current) current - hashtag else current + hashtag
    }

    fun reset() {
        _selectedTopics.value = emptySet()
    }
}
