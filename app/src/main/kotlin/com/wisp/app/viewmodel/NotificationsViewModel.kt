package com.wisp.app.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.NotificationGroup
import com.wisp.app.nostr.NotificationSummary
import com.wisp.app.nostr.ProfileData
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.NotificationRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

import com.wisp.app.R

enum class NotificationFilter(val labelResId: Int) {
    ALL(R.string.filter_all),
    REPLIES(R.string.filter_replies),
    REACTIONS(R.string.filter_reactions),
    ZAPS(R.string.filter_zaps),
    REPOSTS(R.string.filter_reposts),
    MENTIONS(R.string.filter_mentions)
}

class NotificationsViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsPrefs = app.getSharedPreferences("wisp_settings", Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NOTIFICATION_FILTER = "notification_filter"
    }

    val notifications: StateFlow<List<NotificationGroup>>
        get() = notifRepo?.notifications ?: MutableStateFlow(emptyList())

    val hasUnread: StateFlow<Boolean>
        get() = notifRepo?.hasUnread ?: MutableStateFlow(false)

    val zapReceived: SharedFlow<Unit>
        get() = notifRepo?.zapReceived ?: MutableSharedFlow()

    val replyReceived: SharedFlow<Unit>
        get() = notifRepo?.replyReceived ?: MutableSharedFlow()

    val notifReceived: SharedFlow<Unit>
        get() = notifRepo?.notifReceived ?: MutableSharedFlow()

    val eventRepository: EventRepository?
        get() = eventRepo

    val contactRepository: ContactRepository?
        get() = contactRepo

    val summary24h: StateFlow<NotificationSummary>
        get() = notifRepo?.summary24h ?: MutableStateFlow(NotificationSummary())

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _filter = MutableStateFlow(loadSavedFilter())
    val filter: StateFlow<NotificationFilter> = _filter

    private val _filteredNotifications = MutableStateFlow<List<NotificationGroup>>(emptyList())
    val filteredNotifications: StateFlow<List<NotificationGroup>> = _filteredNotifications

    private var notifRepo: NotificationRepository? = null
    private var eventRepo: EventRepository? = null
    private var contactRepo: ContactRepository? = null

    fun init(notificationRepository: NotificationRepository, eventRepository: EventRepository, contactRepository: ContactRepository) {
        notifRepo = notificationRepository
        eventRepo = eventRepository
        contactRepo = contactRepository
        startPeriodicRefresh()
        startFilterCombine()
    }

    private fun startPeriodicRefresh() {
        viewModelScope.launch {
            while (true) {
                delay(60_000L)
                notifRepo?.refreshSplits()
            }
        }
    }

    private fun startFilterCombine() {
        viewModelScope.launch {
            combine(notifications, _filter) { notifs, filterType ->
                when (filterType) {
                    NotificationFilter.ALL -> notifs
                    NotificationFilter.REPLIES -> notifs.filterIsInstance<NotificationGroup.ReplyNotification>()
                    NotificationFilter.REACTIONS -> notifs.filterIsInstance<NotificationGroup.ReactionGroup>()
                    NotificationFilter.ZAPS -> notifs.filter {
                        it is NotificationGroup.ReactionGroup &&
                            NotificationGroup.ZAP_EMOJI in it.reactions
                    }
                    NotificationFilter.REPOSTS -> notifs.filter {
                        it is NotificationGroup.ReactionGroup &&
                            NotificationGroup.REPOST_EMOJI in it.reactions
                    }
                    NotificationFilter.MENTIONS -> notifs.filter {
                        it is NotificationGroup.MentionNotification || it is NotificationGroup.QuoteNotification
                    }
                }
            }.collect { _filteredNotifications.value = it }
        }
    }

    fun setFilter(filter: NotificationFilter) {
        _filter.value = filter
        settingsPrefs.edit().putString(PREF_NOTIFICATION_FILTER, filter.name).apply()
    }

    private fun loadSavedFilter(): NotificationFilter {
        val saved = settingsPrefs.getString(PREF_NOTIFICATION_FILTER, null)
        return saved?.let {
            try { NotificationFilter.valueOf(it) } catch (_: IllegalArgumentException) { null }
        } ?: NotificationFilter.ALL
    }

    fun isFollowing(pubkey: String): Boolean {
        return contactRepo?.isFollowing(pubkey) ?: false
    }

    fun refresh(onRefresh: () -> Unit) {
        _isRefreshing.value = true
        onRefresh()
        viewModelScope.launch {
            delay(3000)
            _isRefreshing.value = false
        }
    }

    fun markRead() {
        notifRepo?.markRead()
    }

    fun getProfileData(pubkey: String): ProfileData? {
        return eventRepo?.getProfileData(pubkey)
    }
}
