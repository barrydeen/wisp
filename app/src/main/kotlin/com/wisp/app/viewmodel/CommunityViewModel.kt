package com.wisp.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.ProfileData
import com.wisp.app.nostr.toHex
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.SubscriptionManager
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.KeyRepository
import com.wisp.app.repo.MetadataFetcher
import com.wisp.app.repo.ProfileRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CommunityViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)

    private val _profiles = MutableStateFlow<List<ProfileData>>(emptyList())
    val profiles: StateFlow<List<ProfileData>> = _profiles

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private var relayPool: RelayPool? = null
    private var metadataFetcher: MetadataFetcher? = null
    private var profileRepo: ProfileRepository? = null
    private var contactRepo: ContactRepository? = null
    private var subManager: SubscriptionManager? = null

    private var refreshJob: Job? = null
    private var autoRefreshJob: Job? = null
    private var initialized = false

    fun init(
        relayPool: RelayPool,
        metadataFetcher: MetadataFetcher,
        profileRepo: ProfileRepository,
        contactRepo: ContactRepository,
        keyRepo: KeyRepository,
        subManager: SubscriptionManager
    ) {
        if (initialized) return
        initialized = true
        this.relayPool = relayPool
        this.metadataFetcher = metadataFetcher
        this.profileRepo = profileRepo
        this.contactRepo = contactRepo
        this.subManager = subManager

        refresh()
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                delay(45_000)
                refresh()
            }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val pool = relayPool ?: return@launch
            val sub = subManager ?: return@launch
            val profRepo = profileRepo ?: return@launch
            val contacts = contactRepo ?: return@launch
            val fetcher = metadataFetcher ?: return@launch
            val myPubkey = keyRepo.getKeypair()?.pubkey?.toHex()

            _isLoading.value = _profiles.value.isEmpty()

            val subId = "community"
            val now = System.currentTimeMillis() / 1000
            val filter = Filter(kinds = listOf(1), since = now - 60, limit = 200)

            // Start collector BEFORE sending REQ so no events are missed
            val authorPubkeys = mutableSetOf<String>()
            val collectJob = launch {
                pool.relayEvents.collect { relayEvent ->
                    if (relayEvent.subscriptionId == subId && relayEvent.event.kind == 1) {
                        authorPubkeys.add(relayEvent.event.pubkey)
                    }
                }
            }

            pool.sendToReadRelays(ClientMessage.req(subId, filter))

            // Wait for EOSE or timeout
            sub.awaitEoseWithTimeout(subId, 10_000)
            collectJob.cancel()
            sub.closeSubscription(subId)

            // Filter out own pubkey and followed users
            val discoverable = authorPubkeys
                .filter { it != myPubkey && !contacts.isFollowing(it) }

            // Queue missing profiles for fetching
            val withProfiles = mutableListOf<ProfileData>()
            val missingPubkeys = mutableListOf<String>()
            for (pubkey in discoverable) {
                val profile = profRepo.get(pubkey)
                if (profile != null) {
                    withProfiles.add(profile)
                } else {
                    missingPubkeys.add(pubkey)
                    fetcher.addToPendingProfiles(pubkey)
                }
            }

            // Emit profiles we already have
            val result = withProfiles.toMutableList()

            // Poll briefly for newly fetched profiles
            if (missingPubkeys.isNotEmpty()) {
                repeat(10) {
                    delay(500)
                    val iterator = missingPubkeys.iterator()
                    while (iterator.hasNext()) {
                        val pk = iterator.next()
                        val profile = profRepo.get(pk)
                        if (profile != null) {
                            result.add(profile)
                            iterator.remove()
                        }
                    }
                    if (missingPubkeys.isEmpty()) return@repeat
                }
            }

            _profiles.value = result.shuffled().take(50)
            _isLoading.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
        autoRefreshJob?.cancel()
        subManager?.closeSubscription("community")
    }
}
