package com.wisp.app.viewmodel

import android.app.Application
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

enum class NotificationFilter(val label: String) {
    ALL("All"),
    REPLIES("Replies"),
    REACTIONS("Reactions"),
    ZAPS("Zaps"),
    REPOSTS("Reposts"),
    MENTIONS("Mentions")
}

class NotificationsViewModel(app: Application) : AndroidViewModel(app) {

    val notifications: StateFlow<List<NotificationGroup>>
        get() = notifRepo?.notifications ?: MutableStateFlow(emptyList())

    val hasUnread: StateFlow<Boolean>
        get() = notifRepo?.hasUnread ?: MutableStateFlow(false)

    val zapReceived: SharedFlow<Unit>
        get() = notifRepo?.zapReceived ?: MutableSharedFlow()

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

    private val _filter = MutableStateFlow(NotificationFilter.ALL)
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
