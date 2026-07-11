package cooking.zap.app.repo

import cooking.zap.app.nostr.Filter
import cooking.zap.app.nostr.NourishParser
import cooking.zap.app.nostr.NourishScore
import cooking.zap.app.relay.AuthedRelayReader
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayPool
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads a recipe's Nourish health score (kind 30078) from the Pantry relay
 * (`wss://pantry.zap.cooking` = [RelayConfig.MEMBERS_RELAY]) — primary and
 * only store for Android's Nourish read path (concern 2.4a).
 *
 * **Fetch-path audit (Phase 4):** Android does **not** hit public relays or
 * the HTTP API for existing scores; it REQs pantry only. Pantry now serves
 * service-authored kind-30078 to **anonymous** readers
 * (`isPublicNourishFilter` on member-relay), so a signing key is no longer
 * required for the read. [AuthedRelayReader] still handles the reconnect /
 * AUTH-closed race for signed-in sessions that may still receive AUTH
 * challenges on the shared socket — unused for pure anonymous success paths.
 *
 * Results are cached per recipe key. Discovery-filter `#l` REQs are deferred.
 */
class NourishRepository(relayPool: RelayPool) {

    private val reader = AuthedRelayReader(relayPool)
    private val cache = ConcurrentHashMap<String, NourishScore>()

    /**
     * @param hasSigningKey retained for call-site compatibility; no longer
     *   gates the pantry read (anonymous service-key 30078 REQs are public).
     *   Compute remains signing-key gated in the ViewModel.
     */
    suspend fun fetchScore(
        recipeAuthor: String,
        recipeDTag: String,
        @Suppress("UNUSED_PARAMETER") hasSigningKey: Boolean = true,
    ): NourishScore? {
        if (recipeAuthor.isBlank() || recipeDTag.isBlank()) return null
        val key = "$recipeAuthor:$recipeDTag"
        cache[key]?.let { return it }

        val filter = Filter(
            kinds = listOf(NourishParser.KIND),
            authors = listOf(NourishParser.SERVICE_PUBKEY),
            dTags = listOf(NourishParser.dTag(recipeAuthor, recipeDTag)),
            limit = 1,
        )
        val event = reader.read(RelayConfig.MEMBERS_RELAY, filter)
            .firstOrNull { it.kind == NourishParser.KIND } ?: return null
        val parsed = NourishParser.parse(event.content) ?: return null
        cache[key] = parsed
        return parsed
    }

    fun clear() = cache.clear()
}
