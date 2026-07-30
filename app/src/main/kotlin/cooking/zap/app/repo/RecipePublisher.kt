package cooking.zap.app.repo

import cooking.zap.app.nostr.ClientMessage
import cooking.zap.app.nostr.Nip89
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.RecipeDeletion
import cooking.zap.app.nostr.RecipeFormats
import cooking.zap.app.nostr.RecipeParser
import cooking.zap.app.nostr.UnsignedRecipeEvent
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
 * (Sous Chef Publish today; the manual recipe-create modal later).
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
     * Outcome of [delete]. Deliberately **not** a [Result] variant: publish
     * outcomes are matched exhaustively at their call sites, and a third case
     * there would only ever be unreachable.
     */
    sealed interface DeleteResult {
        data object Deleted : DeleteResult
        data class Error(val message: String) : DeleteResult
    }

    /**
     * Sous Chef Publish path: the recipe carries a single **source image URL**
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
     * Recipe-edit path: republish [original] as a **replacement** at the same
     * address, through the same spine the create paths use, so an edit reaches
     * exactly the relays the recipe was published to. Images are already hosted
     * (same contract as the manual compose publish above) — no re-host.
     *
     * The address is not re-derived from the (possibly changed) title; it comes
     * off [original] inside the format's `serializeEdit`, which is also what
     * carries over the tags the model does not represent.
     *
     * Serialized by **[original]'s own format**, not [RecipeFormats.primary]: an
     * edit replaces a specific event, and re-encoding it into a different format
     * would leave the original live at its own address rather than replacing it.
     * Those are the same object today and this is what keeps them from having to
     * be.
     *
     * Only the author may edit — the same reason [delete] refuses: an event
     * signed by anyone else replaces nothing, because a replaceable address is
     * `(kind, pubkey, d)`. Refused here rather than published, so the publisher
     * is never the one to sign an event that cannot do what the screen said.
     */
    suspend fun publishEdit(
        original: NostrEvent,
        recipe: RecipeParser.Recipe,
        categories: List<String>,
        imageUrls: List<String>,
        signer: NostrSigner?,
        includeClientTag: Boolean,
    ): Result = withContext(Dispatchers.IO) {
        if (signer == null) return@withContext Result.Error("Sign in to edit recipes.")
        if (original.pubkey != signer.pubkeyHex) {
            return@withContext Result.Error("You can only edit your own recipes.")
        }
        val title = recipe.title?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.Error("This recipe needs a title to publish.")
        val images = imageUrls.filter { it.isNotBlank() }
        if (images.isEmpty()) return@withContext Result.Error("Add an image to publish this recipe.")
        val format = RecipeFormats.forEvent(original)
            ?: return@withContext Result.Error("This recipe can't be edited from this device.")

        publishCore(recipe, categories, images, signer, includeClientTag, title) {
            format.serializeEdit(recipe, title, images, categories, original) to
                RecipeParser.dTag(original)
        }
    }

    /**
     * Shared serialize → sign → broadcast core. [imageUrls] are final hosted
     * URLs (re-hosted or device-uploaded); [title] is pre-validated non-blank.
     * Caches the signed event first so the detail screen can render it
     * optimistically, then broadcasts to the author's write relays **and**
     * [RelayConfig.ARTICLES_RELAYS] (the web's "all" publish).
     *
     * [encode] produces the unsigned event **and the address it lands at**, as
     * one value, because those two must agree: the create paths derive both from
     * the title, the edit path takes both off the original event. Returning the
     * `d` separately from the encoder is how a future format that does not slug
     * its identifier from the title stays correct here without a second branch.
     */
    private suspend fun publishCore(
        recipe: RecipeParser.Recipe,
        categories: List<String>,
        imageUrls: List<String>,
        signer: NostrSigner,
        includeClientTag: Boolean,
        title: String,
        encode: () -> Pair<UnsignedRecipeEvent, String> = {
            // Serialize via the primary (write) format — NIP-23 today. The
            // unsigned event is byte-identical to the previous direct
            // RecipeSerializer call.
            RecipeFormats.primary.serialize(recipe, title, imageUrls, categories) to
                RecipeFormats.primary.slug(title)
        },
    ): Result {
        // Signing/publish can throw — convert to Result.Error (never leave the
        // caller stuck in "Publishing"); still propagate cancellation.
        return try {
            val (unsigned, dTag) = encode()
            val tags = unsigned.tags.toMutableList()
            if (includeClientTag) tags.add(Nip89.clientTag())

            val event = signer.signEvent(unsigned.kind, unsigned.content, tags)
            // Cache first so the detail screen renders optimistically (no relay round-trip).
            eventRepo.cacheEvent(event)

            broadcast(event)

            Result.Published(author = signer.pubkeyHex, dTag = dTag)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error("Couldn't publish this recipe — ${e.message ?: "please try again"}.")
        }
    }

    /**
     * Delete a published recipe — the web `handleDelete` fan-out, on the same
     * [broadcast] path the recipe was published on. Publishing and deleting a
     * recipe reach **the same relays** by construction: the web's delete misses
     * pantry precisely because its two paths are written separately, and that
     * is the defect this shared helper exists to not reproduce.
     *
     * Two events, per [RecipeDeletion]: the blanked replacement (kind = the
     * recipe's own kind, so it is pantry-mirrored exactly like the publish and
     * is what removes the recipe there) and the kind-5 deletion request (kind 5
     * is outside [PANTRY_MIRROR_KINDS], and pantry would reject it unauthed
     * anyway — see the [broadcast] comment). Neither carries a NIP-89 client
     * tag: a tombstone stays minimal, matching `GroceryEvents`/`MealPlanEvents`.
     *
     * Applies **both** locally through [EventRepository.addEvent] — the same
     * inbound path a relay round-trip would take — so the local tombstone, the
     * removal, and the address timestamp are whatever the protocol path
     * produces, not a second implementation of it.
     *
     * Only the author may delete: an event signed by anyone else would be
     * ignored by every relay, so it is refused here rather than published.
     * A recipe dated past the future-date ceiling is refused too
     * ([RecipeDeletion.isDeletableNow]) — its tombstone would be dropped by
     * every reader, so reporting success would be a lie the local eviction then
     * acted on.
     */
    suspend fun delete(event: NostrEvent, signer: NostrSigner?): DeleteResult =
        withContext(Dispatchers.IO) {
            if (signer == null) return@withContext DeleteResult.Error("Sign in to delete recipes.")
            if (event.pubkey != signer.pubkeyHex) {
                return@withContext DeleteResult.Error("You can only delete your own recipes.")
            }
            // One `now` for the check and the stamp, so a delete can't pass the
            // ceiling test and then be signed past it a tick later.
            val now = System.currentTimeMillis() / 1000
            if (!RecipeDeletion.isDeletableNow(event, now)) {
                return@withContext DeleteResult.Error(
                    "This recipe is dated too far in the future to delete from this device."
                )
            }
            try {
                val createdAt = RecipeDeletion.deletionTimestamp(event, now)

                val replacement = signer.signEvent(
                    kind = event.kind,
                    content = RecipeDeletion.TOMBSTONE_CONTENT,
                    tags = RecipeDeletion.blankedReplacementTags(event),
                    createdAt = createdAt,
                )
                broadcast(replacement)

                val deletionRequest = signer.signEvent(
                    kind = 5,
                    content = RecipeDeletion.DELETION_REQUEST_CONTENT,
                    tags = RecipeDeletion.deletionRequestTags(event),
                    createdAt = createdAt,
                )
                broadcast(deletionRequest)

                // Local state last, and both halves through addEvent — the same
                // inbound path a relay echo would take. The replacement becomes
                // the cached event for the address (so an addressable lookup
                // resolves to the tombstone rather than to the stale recipe, or
                // to nothing, while the echo is in flight); addEvent's
                // blanked-replacement guard is what keeps it out of the article
                // feed. The kind-5 then marks the id and the address deleted
                // and drops the recipe itself from the caches/feeds.
                eventRepo.addEvent(replacement)
                eventRepo.addEvent(deletionRequest)

                DeleteResult.Deleted
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DeleteResult.Error("Couldn't delete this recipe — ${e.message ?: "please try again"}.")
            }
        }

    /**
     * The recipe write surface: the author's write relays **and**
     * [RelayConfig.ARTICLES_RELAYS] (the web's "all" publish, so the recipe
     * shows up in the Recipes feed), plus the pantry mirror for mirrored kinds.
     * Every recipe event — publish and delete alike — goes through here.
     *
     * Mirror kind 30023 to pantry so the live recipe-count dashboard
     * stays current. Do NOT add MEMBERS_RELAY to ARTICLES_RELAYS —
     * that set drives recipe reads. Kind-gated: pantry's write policy
     * exempts only KindRecipe (30023) from NIP-42 auth; kind 35000
     * (gated) hits auth + membership and would be silently rejected
     * for most users — follow-up once authed 35000 writes + client
     * auth during publish are confirmed. Android's publish is
     * fire-and-forget (no OK wait), so a silently-rejected kind
     * would be invisible — reinforcing why the kind-gate matters
     * for that follow-up. Mirrors frontend PR #534
     * (PANTRY_MIRROR_KINDS).
     */
    private fun broadcast(event: NostrEvent) {
        val msg = ClientMessage.event(event)
        relayPool.sendToWriteRelays(msg)
        // Also broadcast to the article relays the Recipes feed reads.
        for (url in RelayConfig.ARTICLES_RELAYS) relayPool.sendToRelayOrEphemeral(url, msg)

        if (event.kind in PANTRY_MIRROR_KINDS) {
            val pantry = RelayConfig.MEMBERS_RELAY
            val pantryKey = pantry.trimEnd('/').lowercase()
            val alreadyTargeted = RelayConfig.ARTICLES_RELAYS.any {
                it.trimEnd('/').lowercase() == pantryKey
            }
            if (!alreadyTargeted) {
                relayPool.sendToRelayOrEphemeral(pantry, msg)
            }
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

        /**
         * Kinds mirrored to pantry.zap.cooking on recipe publish.
         * Only 30023 today — see publishCore pantry-mirror comment.
         */
        private val PANTRY_MIRROR_KINDS = setOf(30023)
    }
}
