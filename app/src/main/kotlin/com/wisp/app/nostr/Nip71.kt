package com.wisp.app.nostr

object Nip71 {
    const val KIND_VIDEO_HORIZONTAL = 21
    const val KIND_VIDEO_VERTICAL = 22

    data class VideoMeta(
        val url: String,
        val mimeType: String? = null,
        val dim: String? = null,
        val thumbnailUrl: String? = null,
        val duration: Int? = null,
        val blurhash: String? = null,
        val hash: String? = null,
        val fallback: List<String> = emptyList()
    )

    fun parseVideoMeta(event: NostrEvent): List<VideoMeta> {
        return event.tags.filter { it.firstOrNull() == "imeta" && it.size > 1 }
            .mapNotNull { tag ->
                val fields = mutableMapOf<String, String>()
                val fallbacks = mutableListOf<String>()
                for (i in 1 until tag.size) {
                    val part = tag[i]
                    val spaceIdx = part.indexOf(' ')
                    if (spaceIdx > 0) {
                        val key = part.substring(0, spaceIdx)
                        val value = part.substring(spaceIdx + 1)
                        if (key == "fallback") fallbacks.add(value)
                        else fields[key] = value
                    }
                }
                val url = fields["url"] ?: return@mapNotNull null
                VideoMeta(
                    url = url,
                    mimeType = fields["m"],
                    dim = fields["dim"],
                    thumbnailUrl = fields["image"],
                    duration = fields["duration"]?.toIntOrNull(),
                    blurhash = fields["blurhash"],
                    hash = fields["x"],
                    fallback = fallbacks
                )
            }
    }

    fun buildVideoTags(
        title: String?,
        media: List<VideoMeta>,
        hashtags: List<String> = emptyList(),
        contentWarning: String? = null
    ): List<List<String>> {
        val tags = mutableListOf<List<String>>()
        if (!title.isNullOrBlank()) {
            tags.add(listOf("title", title))
        }
        for (entry in media) {
            val imetaParts = mutableListOf("imeta")
            imetaParts.add("url ${entry.url}")
            entry.mimeType?.let { imetaParts.add("m $it") }
            entry.dim?.let { imetaParts.add("dim $it") }
            entry.thumbnailUrl?.let { imetaParts.add("image $it") }
            entry.duration?.let { imetaParts.add("duration $it") }
            entry.blurhash?.let { imetaParts.add("blurhash $it") }
            entry.hash?.let { imetaParts.add("x $it") }
            for (fb in entry.fallback) imetaParts.add("fallback $fb")
            tags.add(imetaParts)
        }
        for (hashtag in hashtags) {
            tags.add(listOf("t", hashtag))
        }
        contentWarning?.let { tags.add(listOf("content-warning", it)) }
        return tags
    }

    fun getTitle(event: NostrEvent): String? {
        return event.tags.firstOrNull { it.firstOrNull() == "title" && it.size > 1 }?.get(1)
    }

    fun isVideo(event: NostrEvent): Boolean =
        event.kind == KIND_VIDEO_HORIZONTAL || event.kind == KIND_VIDEO_VERTICAL
}
