package com.wisp.app.nostr

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

data class Filter(
    val kinds: List<Int>? = null,
    val authors: List<String>? = null,
    val ids: List<String>? = null,
    val eTags: List<String>? = null,
    val pTags: List<String>? = null,
    val dTags: List<String>? = null,
    val tTags: List<String>? = null,
    val since: Long? = null,
    val until: Long? = null,
    val limit: Int? = null,
    val search: String? = null
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        kinds?.let { put("kinds", buildJsonArray { it.forEach { k -> add(JsonPrimitive(k)) } }) }
        authors?.let { put("authors", buildJsonArray { it.forEach { a -> add(JsonPrimitive(a)) } }) }
        ids?.let { put("ids", buildJsonArray { it.forEach { id -> add(JsonPrimitive(id)) } }) }
        eTags?.let { put("#e", buildJsonArray { it.forEach { e -> add(JsonPrimitive(e)) } }) }
        pTags?.let { put("#p", buildJsonArray { it.forEach { p -> add(JsonPrimitive(p)) } }) }
        dTags?.let { put("#d", buildJsonArray { it.forEach { d -> add(JsonPrimitive(d)) } }) }
        tTags?.let { put("#t", buildJsonArray { it.forEach { t -> add(JsonPrimitive(t)) } }) }
        since?.let { put("since", JsonPrimitive(it)) }
        until?.let { put("until", JsonPrimitive(it)) }
        limit?.let { put("limit", JsonPrimitive(it)) }
        search?.let { put("search", JsonPrimitive(it)) }
    }
}
