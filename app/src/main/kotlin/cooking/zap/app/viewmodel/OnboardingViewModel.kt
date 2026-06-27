package cooking.zap.app.viewmodel

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.Keys
import cooking.zap.app.nostr.Nip02
import cooking.zap.app.nostr.Nip65
import cooking.zap.app.nostr.LocalSigner
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.FoodHashtags
import cooking.zap.app.nostr.ProfileData
import cooking.zap.app.nostr.toHex
import cooking.zap.app.relay.OnboardingPhase
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayPool
import cooking.zap.app.relay.RelayProber
import cooking.zap.app.nostr.Nip78
import cooking.zap.app.repo.BlossomRepository
import cooking.zap.app.repo.ContactRepository
import cooking.zap.app.repo.ExtendedNetworkRepository
import cooking.zap.app.repo.KeyRepository
import cooking.zap.app.repo.SparkRepository
import cooking.zap.app.repo.WalletMode
import cooking.zap.app.repo.WalletModeRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class SuggestionSection(
    val profiles: List<ProfileData> = emptyList(),
    val isLoading: Boolean = true
)

enum class SectionType { ACTIVE_NOW, CREATORS }

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

    private val _selectedPubkeys = MutableStateFlow<Set<String>>(emptySet())
    val selectedPubkeys: StateFlow<Set<String>> = _selectedPubkeys

    private var suggestionsJob: Job? = null

    companion object {
        private const val TAG = "OnboardingSuggestions"

        /**
         * zap.cooking curator account — seeds "Meet the creators" and is pre-selected.
         * Aliases the canonical [ExtendedNetworkRepository.ZC_CURATOR_PUBKEY] so the
         * literal lives in exactly one place.
         */
        const val ZC_PUBKEY = ExtendedNetworkRepository.ZC_CURATOR_PUBKEY

        /** Shown for Zap Cooking even if its kind-0 profile didn't load, so the
         *  pre-selected follow stays visible and toggleable. */
        private val ZC_FALLBACK_PROFILE = ProfileData(
            pubkey = ZC_PUBKEY,
            name = "Zap Cooking",
            displayName = "Zap Cooking",
            about = null,
            picture = null,
            banner = null,
            nip05 = null,
            lud16 = null,
            updatedAt = 0L,
        )

        /** Cap on curated creators shown so "Meet the creators" stays browsable. */
        private const val MAX_CREATORS = 24

        /** Cap on "Active in the kitchen" food posters shown. */
        private const val MAX_ACTIVE = 20

        private val COLORS = listOf(
            "blue", "red", "green", "gold", "silver", "amber", "coral",
            "violet", "jade", "ruby", "teal", "cyan", "crimson", "ivory",
            "bronze", "copper", "indigo", "scarlet", "azure", "pearl",
            "onyx", "sage", "rose", "slate", "plum", "lime", "rust", "mint"
        )
        private val ANIMALS = listOf(
            "panda", "wolf", "fox", "falcon", "otter", "raven", "tiger",
            "eagle", "dolphin", "hawk", "lynx", "bear", "owl", "cobra",
            "bison", "crane", "gecko", "heron", "koala", "lemur",
            "moose", "newt", "ocelot", "puma", "quail", "robin",
            "shark", "swift", "viper", "wren", "yak", "zebra",
            "badger", "cougar", "drake", "finch", "gopher", "hound"
        )

        private fun generateUsername(): String {
            val random = java.security.SecureRandom()
            val color = COLORS[random.nextInt(COLORS.size)]
            val animal = ANIMALS[random.nextInt(ANIMALS.size)]
            val number = random.nextInt(90) + 10 // 10-99
            return "$color$animal$number"
        }
    }

    fun updateName(value: String) { _name.value = value }
    fun updateAbout(value: String) { _about.value = value }

    fun uploadImage(contentResolver: ContentResolver, uri: Uri, signer: NostrSigner? = null) {
        viewModelScope.launch {
            try {
                _uploading.value = "Uploading..."
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw Exception("Cannot read file")
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "bin"
                val url = blossomRepo.uploadMedia(bytes, mimeType, ext, signer)
                _picture.value = url
                _uploading.value = null
            } catch (e: Exception) {
                _error.value = "Upload failed: ${e.message}"
                _uploading.value = null
            }
        }
    }

    fun startDiscovery(sparkRepo: SparkRepository? = null, walletModeRepo: WalletModeRepository? = null) {
        // Reset state from any previous onboarding run (ViewModel survives logout)
        suggestionsJob?.cancel()
        suggestionsJob = null
        _activeNow.value = SuggestionSection()
        _creators.value = SuggestionSection()
        _selectedPubkeys.value = emptySet()
        _publishing.value = false
        _error.value = null

        // Use real keypair if available, otherwise generate a throwaway for probing
        val realKeypair = keyRepo.getKeypair()
        val keypair = realKeypair ?: Keys.generate()
        val pubHex = keyRepo.getPubkeyHex() ?: keypair.pubkey.toHex()
        keyRepo.reloadPrefs(pubHex)
        blossomRepo.reload(pubHex)

        // Reload wallet repos so mnemonic + mode are stored under the correct pubkey prefs
        walletModeRepo?.reload(pubHex)

        // Derive the default Spark wallet deterministically from the user's nsec
        // so it is recoverable on any device by signing in with the same key.
        // Skip if no real keypair yet (we're only probing relays with a throwaway).
        if (sparkRepo != null && realKeypair != null) {
            sparkRepo.reload(pubHex)
            try {
                sparkRepo.generateDefaultFromPrivkey(realKeypair.privkey)
            } catch (e: Exception) {
                Log.w(TAG, "Default wallet derivation failed: ${e.message}")
            }
        }

        viewModelScope.launch {
            val relays = RelayProber.discoverAndSelect(
                keypair = keypair,
                onPhase = { phase -> _phase.value = phase },
                onProbing = { url -> _probingUrl.value = url }
            )
            _probingUrl.value = null
            _discoveredRelays.value = relays

            // Start Spark connection after relay discovery to avoid network contention
            if (sparkRepo != null) {
                launch {
                    try {
                        sparkRepo.connect()
                    } catch (e: Exception) {
                        Log.w(TAG, "Wallet pre-connect failed: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Finish profile step: save relays, init relay pool, set up wallet, publish kind 0.
     * Returns true if successful.
     */
    suspend fun finishProfile(
        relayPool: RelayPool,
        sparkRepo: SparkRepository? = null,
        walletModeRepo: WalletModeRepository? = null,
        signer: NostrSigner? = null
    ): Boolean {
        val s = signer ?: keyRepo.getKeypair()?.let { LocalSigner(it.privkey, it.pubkey) } ?: return false
        val discovered = _discoveredRelays.value ?: RelayConfig.DEFAULTS
        // Always include The Pantry (zap.cooking's members relay) in the new
        // account's NIP-65 list. Read+write so the user publishes to and reads
        // from it from day one; membership AUTH is enforced relay-side.
        val relays = if (discovered.any { it.url.equals(RelayConfig.MEMBERS_RELAY, ignoreCase = true) }) {
            discovered
        } else {
            discovered + RelayConfig(RelayConfig.MEMBERS_RELAY, read = true, write = true)
        }

        return try {
            _publishing.value = true

            keyRepo.saveRelays(relays)
            relayPool.updateRelays(relays)

            // Wait for Spark wallet and register lightning address
            var lightningAddress: String? = null
            if (sparkRepo != null) {
                _phase.value = OnboardingPhase.WALLET_SETUP
                try {
                    val connected = withTimeoutOrNull(15_000) {
                        sparkRepo.isConnected.first { it }
                    }
                    if (connected != null) {
                        for (attempt in 1..3) {
                            val username = generateUsername()
                            val available = sparkRepo.checkLightningAddressAvailable(username)
                                .getOrNull() ?: false
                            if (available) {
                                val addr = sparkRepo.registerLightningAddress(username).getOrNull()
                                if (addr != null) {
                                    lightningAddress = addr
                                    break
                                }
                            }
                        }
                        walletModeRepo?.setMode(WalletMode.SPARK)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Wallet setup failed: ${e.message}")
                }
            }

            _phase.value = OnboardingPhase.BROADCASTING

            val relayTags = Nip65.buildRelayTags(relays)
            val relayListEvent = s.signEvent(kind = 10002, content = "", tags = relayTags)
            val relayListMsg = ClientMessage.event(relayListEvent)
            relayPool.sendToWriteRelays(relayListMsg)
            for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
                relayPool.sendToRelayOrEphemeral(url, relayListMsg)
            }

            val content = buildJsonObject {
                if (_name.value.isNotBlank()) put("name", JsonPrimitive(_name.value))
                if (_about.value.isNotBlank()) put("about", JsonPrimitive(_about.value))
                if (_picture.value.isNotBlank()) put("picture", JsonPrimitive(_picture.value))
                if (lightningAddress != null) put("lud16", JsonPrimitive(lightningAddress))
            }.toString()

            if (content != "{}") {
                val event = s.signEvent(kind = 0, content = content)
                val profileMsg = ClientMessage.event(event)
                relayPool.sendToWriteRelays(profileMsg)
                for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
                    relayPool.sendToRelayOrEphemeral(url, profileMsg)
                }
            }

            // NIP-78 relay backup is only useful for non-default wallets — the
            // default wallet's mnemonic is derived from the nsec, and Breez
            // remembers its lightning address registration server-side, so
            // there's nothing extra to persist on relays.
            val privkey = keyRepo.getKeypair()?.privkey
            if (sparkRepo != null && privkey != null && !sparkRepo.isDefaultWallet(privkey)) {
                val mnemonic = sparkRepo.getMnemonic()
                if (mnemonic != null) {
                    viewModelScope.launch {
                        try {
                            val backupEvent = Nip78.createBackupEvent(s, mnemonic)
                            val backupMsg = ClientMessage.event(backupEvent)
                            relayPool.sendToWriteRelays(backupMsg)
                        } catch (e: Exception) {
                            Log.w(TAG, "Mnemonic backup failed: ${e.message}")
                        }
                    }
                }
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
     * Load food-first follow suggestions in two parallel sections: curated creators
     * (the ZC curator's food seed) and recent #foodstr posters. Zap Cooking is
     * pre-selected so a new user follows it by default.
     */
    fun loadSuggestions(relayPool: RelayPool, extendedNetworkRepo: ExtendedNetworkRepository) {
        if (suggestionsJob != null) return
        _selectedPubkeys.value = setOf(ZC_PUBKEY)

        suggestionsJob = viewModelScope.launch {
            // Wait for at least one relay to connect
            relayPool.awaitAnyConnected(minCount = 1, timeoutMs = 5_000)

            launch { loadActiveNow(relayPool) }
            launch { loadCreators(relayPool, extendedNetworkRepo) }
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

    /**
     * "Active in the kitchen": recent #foodstr posters from the OnlyFood relays.
     * Mirrors the OnlyFood feed query (notes/reposts/polls tagged with the food
     * hashtags) and dedups by author. No `since` floor — the food stream is lower
     * volume, so we take the newest window to avoid an empty section.
     */
    private suspend fun loadActiveNow(relayPool: RelayPool) {
        try {
            val subId = "onb-active"
            val filter = Filter(
                kinds = FeedSubscriptionManager.ONLY_FOOD_KINDS,
                tTags = FoodHashtags.ALL,
                limit = 200,
            )
            val reqMsg = ClientMessage.req(subId, filter)
            val relays = FeedSubscriptionManager.ONLY_FOOD_RELAYS
            for (url in relays) relayPool.sendToRelayOrEphemeral(url, reqMsg)

            val authors = mutableSetOf<String>()
            collectUntilEose(relayPool, subId, relays.size, 8_000) { event ->
                authors.add(event.pubkey)
            }

            if (authors.isEmpty()) {
                _activeNow.value = SuggestionSection(isLoading = false)
                return
            }

            val selected = authors.shuffled().take(MAX_ACTIVE)
            val profiles = fetchProfiles(relayPool, selected, "onb-active-p", relays)
            _activeNow.value = SuggestionSection(profiles = profiles, isLoading = false)
        } catch (e: Exception) {
            Log.e(TAG, "loadActiveNow failed: ${e.message}")
            _activeNow.value = SuggestionSection(isLoading = false)
        }
    }

    /**
     * "Meet the creators": the ZC curator's food-seed follows ([ExtendedNetworkRepository.ensureFoodSeedLoaded]).
     * Zap Cooking is always included and listed first (it is pre-selected).
     */
    private suspend fun loadCreators(relayPool: RelayPool, extendedNetworkRepo: ExtendedNetworkRepository) {
        try {
            extendedNetworkRepo.ensureFoodSeedLoaded()
            val seed = extendedNetworkRepo.getFoodSeedPubkeys()
            val pubkeys = (listOf(ZC_PUBKEY) + seed).distinct().take(MAX_CREATORS)
            val profiles = fetchProfiles(relayPool, pubkeys, "onb-creators", FeedSubscriptionManager.ONLY_FOOD_RELAYS)
            // Always keep a Zap Cooking card (it's pre-selected) — fall back to a
            // placeholder if its kind-0 didn't load, so the follow stays visible and
            // toggleable. Zap Cooking first; remaining creators follow.
            val withZc = if (profiles.any { it.pubkey == ZC_PUBKEY }) profiles else profiles + ZC_FALLBACK_PROFILE
            val ordered = withZc.sortedByDescending { it.pubkey == ZC_PUBKEY }
            _creators.value = SuggestionSection(profiles = ordered, isLoading = false)
        } catch (e: Exception) {
            Log.e(TAG, "loadCreators failed: ${e.message}")
            // Even on failure, keep Zap Cooking visible since it's pre-selected.
            _creators.value = SuggestionSection(profiles = listOf(ZC_FALLBACK_PROFILE), isLoading = false)
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
        collectUntilEose(relayPool, subId, expectedEose, 8_000) { event ->
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
     * Suspend so contactRepo is updated before Navigation proceeds to feed.
     */
    suspend fun finishOnboarding(
        relayPool: RelayPool,
        contactRepo: ContactRepository,
        selectedPubkeys: Set<String>,
        signer: NostrSigner? = null
    ) {
        val s = signer ?: keyRepo.getKeypair()?.let { LocalSigner(it.privkey, it.pubkey) } ?: return
        val myPubkey = s.pubkeyHex

        val allFollows = selectedPubkeys + myPubkey

        var follows = contactRepo.getFollowList()
        for (pubkey in allFollows) {
            follows = Nip02.addFollow(follows, pubkey)
        }
        val tags = Nip02.buildFollowTags(follows)
        val event = s.signEvent(kind = 3, content = "", tags = tags)
        contactRepo.updateFromEvent(event)
        keyRepo.markOnboardingComplete()

        // Fire-and-forget relay publishing
        viewModelScope.launch {
            val msg = ClientMessage.event(event)
            relayPool.sendToWriteRelays(msg)
            for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
                relayPool.sendToRelayOrEphemeral(url, msg)
            }
        }
    }
}
