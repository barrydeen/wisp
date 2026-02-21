package com.wisp.app.repo

import com.wisp.app.nostr.Nip11
import com.wisp.app.nostr.RelayInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class RelayInfoRepository {
    private val cache = ConcurrentHashMap<String, RelayInfo>()
    private val iconCache = ConcurrentHashMap<String, String>()

    suspend fun getIcon(url: String): String? {
        iconCache[url]?.let { return it }
        val info = fetchAndCache(url)
        return iconCache[url]
    }

    suspend fun prefetchAll(urls: List<String>) {
        withContext(Dispatchers.IO) {
            urls.forEach { url ->
                if (!cache.containsKey(url)) {
                    fetchAndCache(url)
                }
            }
        }
    }

    private suspend fun fetchAndCache(url: String): RelayInfo? {
        if (cache.containsKey(url)) return cache[url]
        val info = Nip11.fetchRelayInfo(url)
        if (info != null) {
            cache[url] = info
            val icon = info.icon ?: faviconUrl(url)
            if (icon != null) iconCache[url] = icon
        } else {
            // Still try favicon fallback
            val favicon = faviconUrl(url)
            if (favicon != null) iconCache[url] = favicon
            cache[url] = RelayInfo(name = null, icon = null, description = null)
        }
        return info
    }

    fun getIconUrl(url: String): String? = iconCache[url]

    /** Synchronous access to cached RelayInfo. Returns null if not yet fetched. */
    fun getInfo(url: String): RelayInfo? = cache[url]

    /** Fetch relay info if not cached, then return it. */
    suspend fun fetchInfo(url: String): RelayInfo? = fetchAndCache(url)

    private fun faviconUrl(relayUrl: String): String? {
        return try {
            val domain = relayUrl
                .replace("wss://", "")
                .replace("ws://", "")
                .trimEnd('/')
                .split("/").first()
            "https://$domain/favicon.ico"
        } catch (_: Exception) {
            null
        }
    }
}
