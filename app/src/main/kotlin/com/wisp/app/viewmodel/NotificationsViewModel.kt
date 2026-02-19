package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.wisp.app.nostr.NotificationItem
import com.wisp.app.nostr.ProfileData
import com.wisp.app.repo.EventRepository
import com.wisp.app.repo.NotificationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NotificationsViewModel(app: Application) : AndroidViewModel(app) {

    val notifications: StateFlow<List<NotificationItem>>
        get() = notifRepo?.notifications ?: MutableStateFlow(emptyList())

    val hasUnread: StateFlow<Boolean>
        get() = notifRepo?.hasUnread ?: MutableStateFlow(false)

    private var notifRepo: NotificationRepository? = null
    private var eventRepo: EventRepository? = null

    fun init(notificationRepository: NotificationRepository, eventRepository: EventRepository) {
        notifRepo = notificationRepository
        eventRepo = eventRepository
    }

    fun markRead() {
        notifRepo?.markRead()
    }

    fun getProfileData(pubkey: String): ProfileData? {
        return eventRepo?.getProfileData(pubkey)
    }
}
