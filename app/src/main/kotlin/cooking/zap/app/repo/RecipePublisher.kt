package cooking.zap.app.repo

import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Nip89
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.RecipeFormats
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.relay.HttpClientFactory
import cooking.zap.app.relay.RelayConfig
import cooking.zap.app.relay.RelayPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Publishes a recipe as a signed event in the primary [RecipeFormat]
 * ([RecipeFormats.primary] — NIP-23 `kind 30023` today; the seam lets a future
 * format become primary without touching this class) — the shared create spine
 * (Sous Chef "Save" today; the manual recipe-create modal later).
 *
 * Mirrors the web create flow: serialize via the primary [RecipeFormat]
 * ([RecipeFormats.primary] — NIP-23 today), **re-host the
 * cover image** through Blossom so the recipe owns its image (with a fallback
 * to the source URL if re-host fails — Save never blocks on it), sign with the
 * local key, and broadcast to the author's write relays **and**
 * [RelayConfig.ARTICLES_RELAYS] (the "all" publish the web does, so the recipe
 * shows up in the Recipes feed). The just-signed event is cached locally so
 * the detail screen can render it **optimistically** without waiting on relay
 * propagation. Requires a signing key — READ_ONLY can't publish.
 */
class RecipePublisher(
    private val relayPool: RelayPool,
    private val eventRepo: EventRepository,
    private val blossomRepo: BlossomRepository,
    private val httpClient: OkHttpClient = HttpClientFactory.getGeneralClient(),
) {
    sealed interface Result {
        /** [author]/[dTag] address the just-published recipe (cached locally). */
        data class Published(val author: String, val dTag: String) : Result
        data class Error(val message: String) : Result
    }

    /**
     * Sous Chef "Save" path: the recipe carries a single **source image URL**
     * (from the imported recipe) that we re-host through Blossom so the recipe
     * owns its image. Re-host failure falls back to the source URL (Save never
     * blocks on it). Unchanged — this is the original 2.2 entry point.
     */
    suspend fun publish(
        recipe: RecipeParser.Recipe,
        categories: List<String>,
        signer: NostrSigner?,
        includeClientTag: Boolean,
    ): Result = withContext(Dispatchers.IO) {
        if (signer == null) return@withContext Result.Error("Sign in to save recipes.")
        val title = recipe.title?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.Error("This recipe needs a title to publish.")
        // Image required, mirroring the web (it blocks publish with no image).
        val sourceImage = recipe.image?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.Error("Add an image to publish this recipe.")

        val imageUrl = try {
            reHost(sourceImage, signer) ?: sourceImage
        } catch (e: CancellationException) {
            throw e
        }
        publishCore(recipe, categories, listOf(imageUrl), signer, includeClientTag, title)
    }

    /**
     * Manual recipe-compose path: images are **already hosted** on Blossom
     * (uploaded from the device by the compose screen, which blocks publish
     * until every upload has resolved), so no re-host — every URL goes straight
     * into an `image` tag (first = cover), mirroring the web's multi-image
     * create. Title/image are guaranteed by the screen's validation, but
     * re-checked here so the publisher is never the one to sign a bad event.
     */
    suspend fun publish(
        recipe: RecipeParser.Recipe,
        categories: List<String>,
        imageUrls: List<String>,
        signer: NostrSigner?,
        includeClientTag: Boolean,
    ): Result = withContext(Dispatchers.IO) {
        if (signer == null) return@withContext Result.Error("Sign in to publish recipes.")
        val title = recipe.title?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.Error("This recipe needs a title to publish.")
        val images = imageUrls.filter { it.isNotBlank() }
        if (images.isEmpty()) return@withContext Result.Error("Add an image to publish this recipe.")
        publishCore(recipe, categories, images, signer, includeClientTag, title)
    }

    /**
     * Shared serialize → sign → broadcast core. [imageUrls] are final hosted
     * URLs (re-hosted or device-uploaded); [title] is pre-validated non-blank.
     * Caches the signed event first so the detail screen can render it
     * optimistically, then broadcasts to the author's write relays **and**
     * [RelayConfig.ARTICLES_RELAYS] (the web's "all" publish).
     */
    private suspend fun publishCore(
        recipe: RecipeParser.Recipe,
        categories: List<String>,
        imageUrls: List<String>,
        signer: NostrSigner,
        includeClientTag: Boolean,
        title: String,
    ): Result {
        // Signing/publish can throw — convert to Result.Error (never leave the
        // caller stuck in "Publishing"); still propagate cancellation.
        return try {
            // Serialize via the primary (write) format — NIP-23 today. The
            // unsigned event is byte-identical to the previous direct
            // RecipeSerializer call.
            val unsigned = RecipeFormats.primary.serialize(recipe, title, imageUrls, categories)
            val tags = unsigned.tags.toMutableList()
            if (includeClientTag) tags.add(Nip89.clientTag())

            val event = signer.signEvent(unsigned.kind, unsigned.content, tags)
            // Cache first so the detail screen renders optimistically (no relay round-trip).
            eventRepo.cacheEvent(event)

            val msg = ClientMessage.event(event)
            relayPool.sendToWriteRelays(msg)
            // Also broadcast to the article relays the Recipes feed reads.
            for (url in RelayConfig.ARTICLES_RELAYS) relayPool.sendToRelayOrEphemeral(url, msg)

            Result.Published(author = signer.pubkeyHex, dTag = RecipeFormats.primary.slug(title))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error("Couldn't publish this recipe — ${e.message ?: "please try again"}.")
        }
    }

    /**
     * Fetch the remote image and re-upload to Blossom; null on any failure (→
     * caller falls back to the source URL). Bounded: a tight call timeout and a
     * [MAX_IMAGE_BYTES] cap (oversize/unknown-length-overrun → fallback) so
     * "Save never blocks on re-host" holds even for a huge/slow image.
     */
    private suspend fun reHost(url: String, signer: NostrSigner): String? = try {
        val client = httpClient.newBuilder()
            .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            val body = resp.body
            if (!resp.isSuccessful || body == null) {
                null
            } else if (body.contentLength() in 1..Long.MAX_VALUE && body.contentLength() > MAX_IMAGE_BYTES) {
                null // declared oversize
            } else {
                val bytes = readCapped(body.byteStream(), MAX_IMAGE_BYTES)
                if (bytes == null || bytes.isEmpty()) {
                    null // overran the cap, or empty
                } else {
                    val mime = body.contentType()?.toString()?.substringBefore(';')?.trim()
                        ?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
                    val ext = mime.substringAfterLast('/', "jpg").ifBlank { "jpg" }
                    blossomRepo.uploadMedia(bytes, mime, ext, signer)
                }
            }
        }
    } catch (e: CancellationException) {
        throw e // never swallow cancellation
    } catch (e: Exception) {
        null // fall back to the source URL
    }

    /** Read the stream, returning null if it exceeds [max] (don't buffer a huge image). */
    private fun readCapped(input: java.io.InputStream, max: Long): ByteArray? = input.use { stream ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = stream.read(buf)
            if (n == -1) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 10L * 1024 * 1024 // 10 MB
    }
}
