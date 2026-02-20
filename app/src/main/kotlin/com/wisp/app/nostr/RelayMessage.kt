package com.wisp.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

sealed class RelayMessage {
    data class EventMsg(val subscriptionId: String, val event: NostrEvent) : RelayMessage()
    data class Eose(val subscriptionId: String) : RelayMessage()
    data class Ok(val eventId: String, val accepted: Boolean, val message: String) : RelayMessage()
    data class Notice(val message: String) : RelayMessage()
    data class Auth(val challenge: String) : RelayMessage()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): RelayMessage? {
            return try {
                val array = json.parseToJsonElement(text).jsonArray
                when (array[0].jsonPrimitive.content) {
                    "EVENT" -> {
                        val subId = array[1].jsonPrimitive.content
                        val eventObj = array[2]
                        val event = if (eventObj is JsonArray) {
                            NostrEvent.fromJsonArray(eventObj)
                        } else {
                            json.decodeFromJsonElement(NostrEvent.serializer(), eventObj)
                        }
                        EventMsg(subId, event)
                    }
                    "EOSE" -> Eose(array[1].jsonPrimitive.content)
                    "OK" -> Ok(
                        eventId = array[1].jsonPrimitive.content,
                        accepted = array[2].jsonPrimitive.content.toBoolean(),
                        message = if (array.size > 3) array[3].jsonPrimitive.content else ""
                    )
                    "NOTICE" -> Notice(array[1].jsonPrimitive.content)
                    "AUTH" -> Auth(array[1].jsonPrimitive.content)
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
