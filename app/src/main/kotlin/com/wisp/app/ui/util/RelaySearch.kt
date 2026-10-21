package com.wisp.app.ui.util

/**
 * Relay address fields double as a filter over the relays already listed below them.
 * The typed text is normalized the same way as the listed URLs (no scheme, no trailing
 * slash, case-insensitive), so "wss://nos" and "nos" both match wss://nos.lol.
 */
fun relaySearchQuery(input: String): String =
    input.trim()
        .removePrefix("wss://")
        .removePrefix("ws://")
        .removeSuffix("/")
        .lowercase()

fun relayMatchesQuery(url: String, query: String): Boolean =
    query.isEmpty() || relaySearchQuery(url).contains(query)
