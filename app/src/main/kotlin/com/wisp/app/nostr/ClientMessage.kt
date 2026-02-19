package com.wisp.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

object ClientMessage {
    fun req(subscriptionId: String, filter: Filter): String {
        return buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subscriptionId))
            add(filter.toJsonObject())
        }.toString()
    }

    fun req(subscriptionId: String, filters: List<Filter>): String {
        return buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subscriptionId))
            for (filter in filters) add(filter.toJsonObject())
        }.toString()
    }

    fun event(event: NostrEvent): String {
        val eventJson = Json.parseToJsonElement(event.toJson())
        return buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(eventJson)
        }.toString()
    }

    fun close(subscriptionId: String): String {
        return buildJsonArray {
            add(JsonPrimitive("CLOSE"))
            add(JsonPrimitive(subscriptionId))
        }.toString()
    }
}
