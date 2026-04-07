package com.antz.app.nostr

object Nip88 {
    const val KIND_POLL = 1068
    const val KIND_POLL_RESPONSE = 1018

    private val OPTION_ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"

    data class PollOption(val id: String, val label: String)

    enum class PollType { SINGLECHOICE, MULTIPLECHOICE }

    /** Generate a random alphanumeric option ID matching the spec format. */
    fun generateOptionId(): String {
        return (1..9).map { OPTION_ID_CHARS.random() }.joinToString("")
    }

    fun buildPollTags(
        options: List<PollOption>,
        pollType: PollType = PollType.SINGLECHOICE,
        endsAt: Long? = null,
        relayUrls: List<String> = emptyList()
    ): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        for (option in options) {
            tags.add(listOf("option", option.id, option.label))
        }
        for (url in relayUrls) {
            tags.add(listOf("relay", url))
        }
        tags.add(listOf("polltype", pollType.name.lowercase()))
        if (endsAt != null) {
            tags.add(listOf("endsAt", endsAt.toString()))
        }
        return tags
    }

    fun buildResponseTags(pollEventId: String, selectedOptionIds: List<String>): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        tags.add(listOf("e", pollEventId))
        for (optionId in selectedOptionIds) {
            tags.add(listOf("response", optionId))
        }
        return tags
    }

    fun parsePollOptions(event: NostrEvent): List<PollOption> {
        return event.tags
            .filter { it.size >= 3 && it[0] == "option" }
            .map { PollOption(it[1], it[2]) }
    }

    fun parsePollType(event: NostrEvent): PollType {
        val value = event.tags
            .firstOrNull { it.size >= 2 && it[0] == "polltype" }
            ?.get(1)
            ?.lowercase()
        return if (value == "multiplechoice") PollType.MULTIPLECHOICE else PollType.SINGLECHOICE
    }

    fun parseEndsAt(event: NostrEvent): Long? {
        return event.tags
            .firstOrNull { it.size >= 2 && it[0] == "endsAt" }
            ?.get(1)
            ?.toLongOrNull()
    }

    /** Extract relay URLs from a poll event's relay tags. */
    fun parsePollRelays(event: NostrEvent): List<String> {
        return event.tags
            .filter { it.size >= 2 && it[0] == "relay" }
            .map { it[1] }
    }

    fun isPollEnded(event: NostrEvent): Boolean {
        val endsAt = parseEndsAt(event) ?: return false
        return System.currentTimeMillis() / 1000 > endsAt
    }

    fun getPollEventId(event: NostrEvent): String? {
        return event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
    }

    fun getResponseOptionIds(event: NostrEvent): List<String> {
        return event.tags
            .filter { it.size >= 2 && it[0] == "response" }
            .map { it[1] }
    }
}
