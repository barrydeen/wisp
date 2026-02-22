package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.NotificationGroup
import com.wisp.app.nostr.ProfileData
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.NotificationRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class NotificationsViewModel(app: Application) : AndroidViewModel(app) {

    val notifications: StateFlow<List<NotificationGroup>>
        get() = notifRepo?.notifications ?: MutableStateFlow(emptyList())

    val hasUnread: StateFlow<Boolean>
        get() = notifRepo?.hasUnread ?: MutableStateFlow(false)

    val zapReceived: SharedFlow<Unit>
        get() = notifRepo?.zapReceived ?: MutableSharedFlow()

    val eventRepository: EventRepository?
        get() = eventRepo

    val contactRepository: ContactRepository?
        get() = contactRepo

    private var notifRepo: NotificationRepository? = null
    private var eventRepo: EventRepository? = null
    private var contactRepo: ContactRepository? = null

    fun init(notificationRepository: NotificationRepository, eventRepository: EventRepository, contactRepository: ContactRepository) {
        notifRepo = notificationRepository
        eventRepo = eventRepository
        contactRepo = contactRepository
        startPeriodicRefresh()
    }

    private fun startPeriodicRefresh() {
        viewModelScope.launch {
            while (true) {
                delay(60_000L)
                notifRepo?.refreshSplits()
            }
        }
    }

    fun isFollowing(pubkey: String): Boolean {
        return contactRepo?.isFollowing(pubkey) ?: false
    }

    fun markRead() {
        notifRepo?.markRead()
    }

    fun getProfileData(pubkey: String): ProfileData? {
        return eventRepo?.getProfileData(pubkey)
    }
}
