package com.wisp.app.viewmodel

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.nostr.Nip02
import com.wisp.app.nostr.Nip65
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.ProfileData
import com.wisp.app.nostr.toHex
import com.wisp.app.relay.OnboardingPhase
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayPool
import com.wisp.app.relay.RelayProber
import com.wisp.app.repo.BlossomRepository
import com.wisp.app.repo.ContactRepository
import com.wisp.app.repo.KeyRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class SuggestionSection(
    val profiles: List<ProfileData> = emptyList(),
    val isLoading: Boolean = true
)

enum class SectionType { ACTIVE_NOW, CREATORS, NEWS }

class OnboardingViewModel(app: Application) : AndroidViewModel(app) {
    private val keyRepo = KeyRepository(app)
    private val blossomRepo = BlossomRepository(app)

    // Relay discovery state (background)
    private val _phase = MutableStateFlow(OnboardingPhase.DISCOVERING)
    val phase: StateFlow<OnboardingPhase> = _phase

    private val _discoveredRelays = MutableStateFlow<List<RelayConfig>?>(null)
    val discoveredRelays: StateFlow<List<RelayConfig>?> = _discoveredRelays

    private val _probingUrl = MutableStateFlow<String?>(null)
    val probingUrl: StateFlow<String?> = _probingUrl

    // Profile form state (foreground)
    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name

    private val _about = MutableStateFlow("")
    val about: StateFlow<String> = _about

    private val _picture = MutableStateFlow("")
    val picture: StateFlow<String> = _picture

    private val _uploading = MutableStateFlow<String?>(null)
    val uploading: StateFlow<String?> = _uploading

    private val _publishing = MutableStateFlow(false)
    val publishing: StateFlow<Boolean> = _publishing

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    // Suggestion sections
    private val _activeNow = MutableStateFlow(SuggestionSection())
    val activeNow: StateFlow<SuggestionSection> = _activeNow

    private val _creators = MutableStateFlow(SuggestionSection())
    val creators: StateFlow<SuggestionSection> = _creators

    private val _news = MutableStateFlow(SuggestionSection())
    val news: StateFlow<SuggestionSection> = _news

    private val _selectedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    val selectedPubkeys: StateFlow<Set<String>> = _selectedPubkeys

    private var suggestionsJob: Job? = null

    companion object {
        private const val TAG = "OnboardingSuggestions"
        val CREATOR_PUBKEYS = listOf(
            "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d", // fiatjaf
            "e2ccf7cf20403f3f2a4a55b328f0de3be38558a7d5f33632fdaaefc726c1c8eb"  // utxo
        )
        private val ACTIVE_RELAYS = listOf("wss://premium.primal.net", "wss://nostr.wine")
        private const val NEWS_RELAY = "wss://news.utxo.one"
    }

    fun updateName(value: String) { _name.value = value }
    fun updateAbout(value: String) { _about.value = value }

    fun uploadImage(contentResolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            try {
                _uploading.value = "Uploading..."
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("Cannot read file")
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
                val url = blossomRepo.uploadMedia(bytes, mimeType, ext)
                _picture.value = url
                _uploading.value = null
            } catch (e: Exception) {
                _error.value = "Upload failed: ${e.message}"
                _uploading.value = null
            }
        }
    }

    fun startDiscovery() {
        val keypair = keyRepo.getKeypair() ?: return
        // Reload prefs to the new pubkey (ViewModel may have been created before signUp)
        val pubHex = keypair.pubkey.toHex()
        keyRepo.reloadPrefs(pubHex)
        blossomRepo.reload(pubHex)
        viewModelScope.launch {
            val relays = RelayProber.discoverAndSelect(
                keypair = keypair,
                onPhase = { phase -> _phase.value = phase },
                onProbing = { url -> _probingUrl.value = url }
            )
            _probingUrl.value = null
            _discoveredRelays.value = relays
        }
    }

    /**
     * Finish profile step: save relays, init relay pool, publish kind 0.
     * Returns true if successful.
     */
    fun finishProfile(relayPool: RelayPool): Boolean {
        val keypair = keyRepo.getKeypair() ?: return false
        val relays = _discoveredRelays.value ?: RelayConfig.DEFAULTS

        return try {
            _publishing.value = true

            // Save and connect to discovered relays
            keyRepo.saveRelays(relays)
            relayPool.updateRelays(relays)

            _phase.value = OnboardingPhase.BROADCASTING

            // Publish kind 10002 relay list so other users can find us
            val relayTags = Nip65.buildRelayTags(relays)
            val relayListEvent = NostrEvent.create(
                privkey = keypair.privkey,
                pubkey = keypair.pubkey,
                kind = 10002,
                content = "",
                tags = relayTags
            )
            relayPool.sendToWriteRelays(ClientMessage.event(relayListEvent))

            // Publish kind 0 profile if user filled in any field
            if (_name.value.isNotBlank() || _about.value.isNotBlank() || _picture.value.isNotBlank()) {
                val content = buildJsonObject {
                    if (_name.value.isNotBlank()) put("name", JsonPrimitive(_name.value))
                    if (_about.value.isNotBlank()) put("about", JsonPrimitive(_about.value))
                    if (_picture.value.isNotBlank()) put("picture", JsonPrimitive(_picture.value))
                }.toString()

                val event = NostrEvent.create(
                    privkey = keypair.privkey,
                    pubkey = keypair.pubkey,
                    kind = 0,
                    content = content
                )
                relayPool.sendToWriteRelays(ClientMessage.event(event))
            }

            _publishing.value = false
            true
        } catch (e: Exception) {
            _error.value = "Failed: ${e.message}"
            _publishing.value = false
            false
        }
    }

    /**
     * Load follow suggestions from relays in three parallel sections.
     */
    fun loadSuggestions(relayPool: RelayPool) {
        if (suggestionsJob != null) return
        _selectedPubkeys.value = emptySet()

        suggestionsJob = viewModelScope.launch {
            // Wait for at least one relay to connect
            relayPool.awaitAnyConnected(minCount = 1, timeoutMs = 5_000)

            // Launch all three sections in parallel
            launch { loadActiveNow(relayPool) }
            launch { loadCreators(relayPool) }
            launch { loadNews(relayPool) }
        }
    }

    /**
     * Collect events for a subscription until EOSE or timeout, whichever comes first.
     * Returns immediately when the expected number of EOSE signals arrive.
     */
    private suspend fun collectUntilEose(
        relayPool: RelayPool,
        subId: String,
        expectedEose: Int,
        timeoutMs: Long,
        onEvent: (NostrEvent) -> Unit
    ) {
        val done = CompletableDeferred<Unit>()
        val eoseCount = java.util.concurrent.atomic.AtomicInteger(0)

        val collectJob = viewModelScope.launch {
            relayPool.relayEvents.collect { relayEvent ->
                if (relayEvent.subscriptionId == subId) onEvent(relayEvent.event)
            }
        }
        val eoseJob = viewModelScope.launch {
            relayPool.eoseSignals.collect { id ->
                if (id == subId && eoseCount.incrementAndGet() >= expectedEose) {
                    done.complete(Unit)
                }
            }
        }

        withTimeoutOrNull(timeoutMs) { done.await() }
        collectJob.cancel()
        eoseJob.cancel()
        relayPool.closeOnAllRelays(subId)
    }

    private suspend fun loadActiveNow(relayPool: RelayPool) {
        try {
            val subId = "onb-active"
            val since = System.currentTimeMillis() / 1000 - 20 * 60
            val filter = Filter(kinds = listOf(1), since = since, limit = 200)
            val reqMsg = ClientMessage.req(subId, filter)
            for (url in ACTIVE_RELAYS) relayPool.sendToRelayOrEphemeral(url, reqMsg)

            val authors = mutableSetOf<String>()
            collectUntilEose(relayPool, subId, ACTIVE_RELAYS.size, 3_000) { event ->
                authors.add(event.pubkey)
            }

            if (authors.isEmpty()) {
                _activeNow.value = SuggestionSection(isLoading = false)
                return
            }

            val selected = authors.shuffled().take(20)
            val profiles = fetchProfiles(relayPool, selected, "onb-active-p", ACTIVE_RELAYS)
            _activeNow.value = SuggestionSection(profiles = profiles, isLoading = false)
        } catch (e: Exception) {
            Log.e(TAG, "loadActiveNow failed: ${e.message}")
            _activeNow.value = SuggestionSection(isLoading = false)
        }
    }

    private suspend fun loadCreators(relayPool: RelayPool) {
        try {
            val profiles = fetchProfiles(relayPool, CREATOR_PUBKEYS, "onb-creators", null)
            _creators.value = SuggestionSection(profiles = profiles, isLoading = false)
        } catch (e: Exception) {
            Log.e(TAG, "loadCreators failed: ${e.message}")
            _creators.value = SuggestionSection(isLoading = false)
        }
    }

    private suspend fun loadNews(relayPool: RelayPool) {
        try {
            val subId = "onb-news"
            val filter = Filter(kinds = listOf(1), limit = 100)
            val reqMsg = ClientMessage.req(subId, filter)
            relayPool.sendToRelayOrEphemeral(NEWS_RELAY, reqMsg)

            val authors = mutableSetOf<String>()
            collectUntilEose(relayPool, subId, 1, 3_000) { event ->
                authors.add(event.pubkey)
            }

            if (authors.isEmpty()) {
                _news.value = SuggestionSection(isLoading = false)
                return
            }

            val profiles = fetchProfiles(relayPool, authors.toList(), "onb-news-p", listOf(NEWS_RELAY))
            _news.value = SuggestionSection(profiles = profiles, isLoading = false)
        } catch (e: Exception) {
            Log.e(TAG, "loadNews failed: ${e.message}")
            _news.value = SuggestionSection(isLoading = false)
        }
    }

    /**
     * Fetch kind 0 profiles for a list of pubkeys. Returns as soon as EOSE arrives or timeout.
     */
    private suspend fun fetchProfiles(
        relayPool: RelayPool,
        pubkeys: List<String>,
        subId: String,
        relayUrls: List<String>?
    ): List<ProfileData> {
        if (pubkeys.isEmpty()) return emptyList()

        val filter = Filter(kinds = listOf(0), authors = pubkeys)
        val reqMsg = ClientMessage.req(subId, filter)
        val expectedEose: Int

        if (relayUrls != null) {
            for (url in relayUrls) relayPool.sendToRelayOrEphemeral(url, reqMsg)
            expectedEose = relayUrls.size
        } else {
            relayPool.sendToReadRelays(reqMsg)
            expectedEose = 1
        }

        val profiles = mutableMapOf<String, ProfileData>()
        collectUntilEose(relayPool, subId, expectedEose, 3_000) { event ->
            if (event.kind == 0) {
                val profile = ProfileData.fromEvent(event)
                if (profile != null) {
                    val existing = profiles[profile.pubkey]
                    if (existing == null || profile.updatedAt > existing.updatedAt) {
                        profiles[profile.pubkey] = profile
                    }
                }
            }
        }

        return profiles.values.toList()
    }

    fun toggleFollowAll(section: SectionType) {
        val profiles = when (section) {
            SectionType.ACTIVE_NOW -> _activeNow.value.profiles
            SectionType.CREATORS -> _creators.value.profiles
            SectionType.NEWS -> _news.value.profiles
        }
        val pubkeys = profiles.map { it.pubkey }.toSet()
        val current = _selectedPubkeys.value
        val allSelected = pubkeys.all { it in current }
        _selectedPubkeys.value = if (allSelected) {
            current - pubkeys
        } else {
            current + pubkeys
        }
    }

    fun togglePubkey(pubkey: String) {
        val current = _selectedPubkeys.value
        _selectedPubkeys.value = if (pubkey in current) {
            current - pubkey
        } else {
            current + pubkey
        }
    }

    /**
     * Finish onboarding: publish kind 3 follow list (if any selected), mark complete.
     */
    fun finishOnboarding(
        relayPool: RelayPool,
        contactRepo: ContactRepository,
        selectedPubkeys: Set<String>
    ) {
        val keypair = keyRepo.getKeypair() ?: return

        // Always follow self + any selected pubkeys
        val allFollows = selectedPubkeys + keypair.pubkey.toHex()

        // Build follow list
        var follows = contactRepo.getFollowList()
        for (pubkey in allFollows) {
            follows = Nip02.addFollow(follows, pubkey)
        }
        val tags = Nip02.buildFollowTags(follows)
        val event = NostrEvent.create(
            privkey = keypair.privkey,
            pubkey = keypair.pubkey,
            kind = 3,
            content = "",
            tags = tags
        )
        relayPool.sendToWriteRelays(ClientMessage.event(event))
        contactRepo.updateFromEvent(event)

        keyRepo.markOnboardingComplete()
    }
}
