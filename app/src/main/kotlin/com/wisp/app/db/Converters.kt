package com.wisp.app.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

object TagsConverter {
    private val json = Json { ignoreUnknownKeys = true }

    fun tagsToJson(tags: List<List<String>>): String {
        val array = buildJsonArray {
            for (tag in tags) {
                add(buildJsonArray { for (t in tag) add(JsonPrimitive(t)) })
            }
        }
        return array.toString()
    }

    fun jsonToTags(jsonStr: String): List<List<String>> {
        if (jsonStr.isBlank()) return emptyList()
        return try {
            val array = json.parseToJsonElement(jsonStr).jsonArray
            array.map { tagArr ->
                tagArr.jsonArray.map { it.jsonPrimitive.content }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
