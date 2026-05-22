package com.wisp.app.repo

import android.util.Log
import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Nip78
import com.wisp.app.nostr.NostrEvent
import com.wisp.app.nostr.NostrSigner
import com.wisp.app.nostr.RelayMessage
import com.wisp.app.relay.RelayEvent
import com.wisp.app.relay.RelayPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * NIP-78 cross-device sync of UI preferences. Publishes a single
 * NIP-44-encrypted kind 30078 event addressed by the
 * `wisp-app-settings:v1` d-tag whenever any synced setting changes
 * (debounced 4s — matches iOS). On launch, fetches and applies the
 * remote backup non-destructively (each field has its own guard so
 * missing values stay at the local default).
 *
 * Only fires when [InterfacePreferences.isSyncSettingsToRelays] is
 * true. The toggle defaults to on. The user can disable it from the
 * "Cross-device sync" section of the Interface settings screen.
 */
class AppSettingsRepository(
    private val interfacePrefs: InterfacePreferences,
    private val fiatPrefs: FiatPreferences,
    private val zapPrefs: ZapPreferences,
    private val customEmojiRepo: CustomEmojiRepository
) {
    private val TAG = "AppSettingsSync"

    /** Active signer. Set by FeedViewModel after the user logs in / out. */
    @Volatile
    var signer: NostrSigner? = null

    /** Relay pool to publish through / read from. Set once at construction. */
    @Volatile
    var relayPool: RelayPool? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var debounceJob: Job? = null

    init {
        // Register sync callbacks on every prefs source. Any synced-field
        // setter (e.g. `interfacePrefs.setLargeText(...)`) will now bounce
        // off `scheduleSettingsSync()` automatically.
        interfacePrefs.onSyncedFieldChanged = { scheduleSettingsSync() }
        fiatPrefs.onSyncedFieldChanged = { scheduleSettingsSync() }
        zapPrefs.onSyncedFieldChanged = { scheduleSettingsSync() }
        customEmojiRepo.onSyncedFieldChanged = { scheduleSettingsSync() }
    }

    /**
     * Schedule a debounced sync. Called by setting setters whenever a
     * synced field mutates. Coalesces rapid edits — only the last
     * `scheduleSettingsSync()` in a 4s window actually publishes.
     */
    fun scheduleSettingsSync() {
        if (!interfacePrefs.isSyncSettingsToRelays()) return
        val s = signer ?: return
        val pool = relayPool ?: return

        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            try {
                publishNow(s, pool)
            } catch (e: Exception) {
                Log.w(TAG, "publish failed: ${e.message}")
            }
        }
    }

    /** Build the current payload from all synced prefs sources. */
    private fun buildPayload(): Nip78.AppSettingsPayload {
        val mediaLayout = interfacePrefs.getMediaLayoutStyle().key
        val unicodeEmojis = customEmojiRepo.getUnicodeEmojis()
        val emojiFrequency = customEmojiRepo.getEmojiFrequency()
        return Nip78.AppSettingsPayload(
            // Reactions (iOS-only UI today; round-tripped via interfacePrefs).
            defaultReaction = interfacePrefs.getDefaultReaction(),
            defaultReactionEnabled = interfacePrefs.getDefaultReactionEnabled(),
            // Quick zaps
            quickZapEnabled = interfacePrefs.isQuickZapEnabled(),
            quickZapAmountSats = interfacePrefs.getQuickZapAmountSats(),
            quickZapAmountFiat = interfacePrefs.getQuickZapAmountFiat(),
            quickZapMessage = interfacePrefs.getQuickZapMessage(),
            zapIconStyle = if (interfacePrefs.isZapBoltIcon()) "bolt" else "default",
            fiatModeEnabled = fiatPrefs.isFiatMode(),
            fiatCurrency = fiatPrefs.getCurrency(),
            zapPresetsCSV = zapPrefs.toCSV(),
            quickReactions = unicodeEmojis.takeIf { it.isNotEmpty() },
            frequency = emojiFrequency.takeIf { it.isNotEmpty() },
            largeText = interfacePrefs.isLargeText(),
            themeName = interfacePrefs.getTheme(),
            colorScheme = interfacePrefs.getColorScheme(),
            // Local stores ARGB as signed 32-bit Int; convert to unsigned
            // Long so iOS reads it as a non-negative value.
            accentColorARGB = interfacePrefs.getAccentColor().toLong() and 0xFFFFFFFFL,
            autoLoadMedia = interfacePrefs.isAutoLoadMedia(),
            videoAutoplay = interfacePrefs.isVideoAutoPlay(),
            animateAvatars = interfacePrefs.getAnimateAvatars(),
            mediaLayoutStyle = mediaLayout,
            clientTagEnabled = interfacePrefs.isClientTagEnabled(),
            postUndoTimerEnabled = interfacePrefs.isPostUndoTimerEnabled(),
            postUndoTimerSeconds = interfacePrefs.getPostUndoTimerSeconds(),
            postUndoTimerForReplies = interfacePrefs.isPostUndoTimerForReplies(),
            version = 1
        )
    }

    private suspend fun publishNow(signer: NostrSigner, pool: RelayPool) {
        pool.ensureWriteRelaysConnected()
        val payload = buildPayload()
        val event = Nip78.createAppSettingsEvent(signer, payload)
        val sent = pool.sendToWriteRelays(ClientMessage.event(event))
        Log.d(TAG, "publish sent=$sent")
    }

    /**
     * Fetch the latest backup for this user and apply it
     * non-destructively. Called by StartupCoordinator on launch / account
     * switch. Each field has its own null-guard so missing values keep
     * the local default — adding a new field on iOS won't wipe its value
     * on Android (and vice-versa).
     */
    suspend fun restoreSettingsBackup() = kotlinx.coroutines.coroutineScope {
        val s = signer ?: run { Log.d(TAG, "restore skipped: no signer"); return@coroutineScope }
        val pool = relayPool ?: run { Log.d(TAG, "restore skipped: no relay pool"); return@coroutineScope }
        if (!interfacePrefs.isSyncSettingsToRelays()) {
            Log.d(TAG, "restore skipped: sync toggle off")
            return@coroutineScope
        }

        try {
            pool.ensureWriteRelaysConnected()
        } catch (_: Exception) { /* best-effort */ }

        val pubkey = s.pubkeyHex
        val subId = "app-settings-${System.currentTimeMillis()}"
        val filter = Nip78.appSettingsFilter(pubkey)
        val seen = mutableSetOf<String>()
        val events = mutableListOf<NostrEvent>()
        var eoseCount = 0

        // Launch the collectors on the CALLING coroutine's scope (via
        // coroutineScope above) — not the repo's own SupervisorJob/
        // Dispatchers.Default scope. That separation breaks the
        // `yield()` handshake below: with the collectors on a separate
        // scope, yield() doesn't actually dispatch them before sendToAll
        // fires the REQ, so the first batch of replies (which arrive
        // ~3-4s later, well after the collector "should" be live) goes
        // to a SharedFlow with no subscribers and is dropped on the
        // floor.
        val collectJob = launch {
            pool.relayEvents.collect { re: RelayEvent ->
                if (re.subscriptionId == subId && seen.add(re.event.id)) events.add(re.event)
            }
        }
        val eoseJob = launch {
            pool.eoseSignals.collect { id ->
                if (id == subId) eoseCount++
            }
        }
        yield()
        val total = pool.getRelayUrls().size
        val minEose = (total * 2 + 2) / 3
        Log.d(TAG, "restore: REQ $subId to $total relays for d=${Nip78.APP_SETTINGS_D_TAG} author=${pubkey.take(8)}")
        pool.sendToAll(ClientMessage.req(subId, filter))
        withTimeoutOrNull(8_000) {
            while (eoseCount < total) {
                delay(150)
                if (eoseCount >= minEose) break
            }
        }
        collectJob.cancel()
        eoseJob.cancel()
        pool.closeOnAllRelays(subId)
        Log.d(TAG, "restore: EOSE $eoseCount/$total events=${events.size}")

        val newest = events
            .filter { Nip78.extractDTag(it) == Nip78.APP_SETTINGS_D_TAG }
            .maxByOrNull { it.created_at }
        if (newest == null) {
            Log.d(TAG, "restore: no matching d-tag event found")
            return@coroutineScope
        }
        Log.d(TAG, "restore: newest event id=${newest.id.take(8)} created_at=${newest.created_at}")

        val payload = Nip78.decryptAppSettings(s, newest)
        if (payload == null) {
            Log.w(TAG, "restore: decrypt FAILED for event id=${newest.id.take(8)}")
            return@coroutineScope
        }
        Log.d(TAG, "restore: decrypted payload — zapPresetsCSV=${payload.zapPresetsCSV?.take(60)} quickZap=${payload.quickZapEnabled}/${payload.quickZapAmountSats}")
        applyPayload(payload)
        Log.d(TAG, "restore: applied — current presets=${zapPrefs.toCSV().take(80)}")
    }

    /**
     * Apply a decoded payload to local prefs. Each field is guarded —
     * a null in the payload preserves the local value, so older devices
     * with fewer fields don't wipe newer ones.
     *
     * Sync callbacks are temporarily detached so the restore doesn't
     * trigger an immediate re-publish loop. (ZapPrefs `applyCSV` has its
     * own suppress flag.)
     */
    private fun applyPayload(p: Nip78.AppSettingsPayload) {
        val iface = interfacePrefs.onSyncedFieldChanged
        val fiat = fiatPrefs.onSyncedFieldChanged
        val zap = zapPrefs.onSyncedFieldChanged
        val emoji = customEmojiRepo.onSyncedFieldChanged
        interfacePrefs.onSyncedFieldChanged = null
        fiatPrefs.onSyncedFieldChanged = null
        zapPrefs.onSyncedFieldChanged = null
        customEmojiRepo.onSyncedFieldChanged = null
        try {
            // Reactions — round-trip only on Android.
            interfacePrefs.setDefaultReaction(p.defaultReaction)
            interfacePrefs.setDefaultReactionEnabled(p.defaultReactionEnabled)
            // Quick zaps
            p.quickZapEnabled?.let { interfacePrefs.setQuickZapEnabled(it) }
            p.quickZapAmountSats?.let { interfacePrefs.setQuickZapAmountSats(it) }
            p.quickZapAmountFiat?.let { interfacePrefs.setQuickZapAmountFiat(it) }
            p.quickZapMessage?.let { interfacePrefs.setQuickZapMessage(it) }
            p.zapIconStyle?.let { interfacePrefs.setZapBoltIcon(it == "bolt") }
            p.fiatModeEnabled?.let { fiatPrefs.setFiatMode(it) }
            p.fiatCurrency?.let { fiatPrefs.setCurrency(it) }
            p.zapPresetsCSV?.let { zapPrefs.applyCSV(it) }
            // Quick reactions / frequency
            if (p.quickReactions != null || p.frequency != null) {
                customEmojiRepo.applyQuickReactions(p.quickReactions, p.frequency)
            }
            // Appearance
            p.largeText?.let { interfacePrefs.setLargeText(it) }
            p.themeName?.let { interfacePrefs.setTheme(it) }
            interfacePrefs.setColorScheme(p.colorScheme)
            p.accentColorARGB?.let { interfacePrefs.setAccentColor(it.toInt()) }
            // Media
            p.autoLoadMedia?.let { interfacePrefs.setAutoLoadMedia(it) }
            p.videoAutoplay?.let { interfacePrefs.setVideoAutoPlay(it) }
            interfacePrefs.setAnimateAvatars(p.animateAvatars)
            p.mediaLayoutStyle?.let {
                interfacePrefs.setMediaLayoutStyle(InterfacePreferences.MediaLayoutStyle.fromKey(it))
            }
            // Posting
            p.clientTagEnabled?.let { interfacePrefs.setClientTagEnabled(it) }
            p.postUndoTimerEnabled?.let { interfacePrefs.setPostUndoTimerEnabled(it) }
            p.postUndoTimerSeconds?.let { interfacePrefs.setPostUndoTimerSeconds(it) }
            p.postUndoTimerForReplies?.let { interfacePrefs.setPostUndoTimerForReplies(it) }
        } finally {
            interfacePrefs.onSyncedFieldChanged = iface
            fiatPrefs.onSyncedFieldChanged = fiat
            zapPrefs.onSyncedFieldChanged = zap
            customEmojiRepo.onSyncedFieldChanged = emoji
        }
    }

    /** Switch the active account. Re-points the per-user zap prefs and clears the debounce. */
    fun reload(pubkeyHex: String?) {
        debounceJob?.cancel()
        zapPrefs.reload(pubkeyHex)
    }

    fun close() {
        debounceJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val DEBOUNCE_MS = 4_000L
    }
}
