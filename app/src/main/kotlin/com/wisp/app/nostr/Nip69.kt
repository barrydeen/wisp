package com.wisp.app.nostr

/**
 * NIP-69 / Kind 6969: Zap Polls
 *
 * Experimental poll format where votes are cast via Lightning zaps.
 * Each zap request (kind 9734) includes a poll_option tag indicating
 * the chosen option. Results are tallied by sats amount.
 */
object Nip69 {
    const val KIND_ZAP_POLL = 6969

    data class ZapPollOption(val index: Int, val label: String)

    fun buildZapPollTags(
        options: List<ZapPollOption>,
        valueMinimum: Long? = null,
        valueMaximum: Long? = null,
        consensusThreshold: Int? = null,
        closedAt: Long? = null,
        relayUrls: List<String> = emptyList()
    ): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        for (option in options) {
            tags.add(listOf("poll_option", option.index.toString(), option.label))
        }
        if (valueMinimum != null) {
            tags.add(listOf("value_minimum", valueMinimum.toString()))
        }
        if (valueMaximum != null) {
            tags.add(listOf("value_maximum", valueMaximum.toString()))
        }
        if (consensusThreshold != null) {
            tags.add(listOf("consensus_threshold", consensusThreshold.toString()))
        }
        if (closedAt != null) {
            tags.add(listOf("closed_at", closedAt.toString()))
        }
        for (url in relayUrls) {
            tags.add(listOf("relay", url))
        }
        return tags
    }

    fun parseZapPollOptions(event: NostrEvent): List<ZapPollOption> {
        return event.tags
            .filter { it.size >= 3 && it[0] == "poll_option" }
            .mapNotNull { tag ->
                val index = tag[1].toIntOrNull() ?: return@mapNotNull null
                ZapPollOption(index, tag[2])
            }
    }

    fun parseValueMinimum(event: NostrEvent): Long? {
        return event.tags
            .firstOrNull { it.size >= 2 && it[0] == "value_minimum" }
            ?.get(1)
            ?.toLongOrNull()
    }

    fun parseValueMaximum(event: NostrEvent): Long? {
        return event.tags
            .firstOrNull { it.size >= 2 && it[0] == "value_maximum" }
            ?.get(1)
            ?.toLongOrNull()
    }

    fun parseConsensusThreshold(event: NostrEvent): Int? {
        return event.tags
            .firstOrNull { it.size >= 2 && it[0] == "consensus_threshold" }
            ?.get(1)
            ?.toIntOrNull()
            ?.coerceIn(0, 100)
    }

    fun parseClosedAt(event: NostrEvent): Long? {
        return event.tags
            .firstOrNull { it.size >= 2 && it[0] == "closed_at" }
            ?.get(1)
            ?.toLongOrNull()
    }

    fun isZapPollClosed(event: NostrEvent): Boolean {
        val closedAt = parseClosedAt(event) ?: return false
        return System.currentTimeMillis() / 1000 > closedAt
    }

    /** Extract relay URLs from a zap poll event's relay tags. */
    fun parseZapPollRelays(event: NostrEvent): List<String> {
        return event.tags
            .filter { it.size >= 2 && it[0] == "relay" }
            .map { it[1] }
    }

    /**
     * Extract the poll_option index from a kind 9735 zap receipt.
     * The description tag contains the serialized kind 9734 zap request
     * which may include a ["poll_option", "<index>"] tag.
     */
    fun getZapPollOptionFromZapReceipt(zapReceipt: NostrEvent): Int? {
        val description = zapReceipt.tags
            .firstOrNull { it.size >= 2 && it[0] == "description" }
            ?.get(1) ?: return null
        return try {
            val zapRequest = NostrEvent.fromJson(description)
            zapRequest.tags
                .firstOrNull { it.size >= 2 && it[0] == "poll_option" }
                ?.get(1)
                ?.toIntOrNull()
        } catch (_: Exception) {
            null
        }
    }
}
