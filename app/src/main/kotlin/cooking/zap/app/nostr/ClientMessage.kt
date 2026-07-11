package cooking.zap.app.nostr

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

object ClientMessage {
    private val json = Json { encodeDefaults = false }

    /**
     * Build a REQ frame. Encodes via [Json.encodeToString] so tag filters like
     * `"#l"` survive verbatim on the wire (not Kotlin map toString / renamed keys).
     */
    fun req(subscriptionId: String, filter: Filter): String {
        val arr = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subscriptionId))
            add(filter.toJsonObject())
        }
        return json.encodeToString(JsonArray.serializer(), arr)
    }

    fun req(subscriptionId: String, filters: List<Filter>): String {
        val arr = buildJsonArray {
            add(JsonPrimitive("REQ"))
            add(JsonPrimitive(subscriptionId))
            for (filter in filters) add(filter.toJsonObject())
        }
        return json.encodeToString(JsonArray.serializer(), arr)
    }

    fun event(event: NostrEvent): String {
        val eventJson = Json.parseToJsonElement(event.toJson())
        val arr = buildJsonArray {
            add(JsonPrimitive("EVENT"))
            add(eventJson)
        }
        return json.encodeToString(JsonArray.serializer(), arr)
    }

    fun auth(event: NostrEvent): String {
        val eventJson = Json.parseToJsonElement(event.toJson())
        val arr = buildJsonArray {
            add(JsonPrimitive("AUTH"))
            add(eventJson)
        }
        return json.encodeToString(JsonArray.serializer(), arr)
    }

    fun close(subscriptionId: String): String {
        val arr = buildJsonArray {
            add(JsonPrimitive("CLOSE"))
            add(JsonPrimitive(subscriptionId))
        }
        return json.encodeToString(JsonArray.serializer(), arr)
    }
}
