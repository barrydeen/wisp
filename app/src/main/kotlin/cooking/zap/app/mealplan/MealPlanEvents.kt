package cooking.zap.app.mealplan

import cooking.zap.app.nostr.EncryptionMethod
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.NostrSigner
import cooking.zap.app.nostr.Nip09
import cooking.zap.app.nostr.Nip89
import cooking.zap.app.nostr.SignerDecryptGate
import cooking.zap.app.nostr.detectEncryptionMethod
import kotlinx.coroutines.CancellationException

/**
 * Meal-plan event envelope helpers — kind 30078, d-tag `mealplan-{weekId}`,
 * NIP-44 self-encrypted payload. Tags are **exactly** d + client (NO plaintext
 * `a` tags — contract privacy posture). Port of frontend `plannerService.ts`
 * build/decrypt/delete, on top of [Week] + [Schema] (#173).
 *
 * Event `created_at` is pinned to the caller's monotonic stamp (grocery
 * #174 precedent) so replaceable supersession follows [PlannerMutations.stampForPublish]
 * rather than wall-clock ties.
 */
object MealPlanEvents {
    const val KIND = 30078

    /**
     * Contract tag array — literal shape asserted in tests:
     * `[["d","mealplan-YYYY-Www"],["client","Zap Cooking"]]` and nothing else.
     */
    fun buildTags(weekId: String): List<List<String>> = listOf(
        listOf("d", Week.dTagForWeek(weekId)),
        Nip89.clientTag(),
    )

    fun dTagOf(event: NostrEvent): String? =
        event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1)

    fun weekIdFrom(event: NostrEvent): String? =
        dTagOf(event)?.let { Week.weekIdFromDTag(it) }

    /**
     * NIP-44 self-encrypt [plan] and sign with the contract tag set.
     * `created_at` = [Schema.MealPlan.updatedAt] (caller must [PlannerMutations.stampForPublish]).
     */
    suspend fun createPlanEvent(signer: NostrSigner, plan: Schema.MealPlan): NostrEvent {
        val encrypted = signer.nip44Encrypt(Schema.serializeMealPlan(plan), signer.pubkeyHex)
        return signer.signEvent(
            kind = KIND,
            content = encrypted,
            tags = buildTags(plan.week),
            createdAt = plan.updatedAt,
        )
    }

    /**
     * Decrypt + parse one meal-plan event. Crypto denials / malformed payloads
     * → [Schema.MealPlanPayloadResult.DecryptFailed] (never silent empty).
     */
    suspend fun decryptPlanEvent(
        event: NostrEvent,
        weekId: String,
        signer: NostrSigner,
        gate: SignerDecryptGate? = null,
    ): Schema.MealPlanPayloadResult {
        if (event.content.isBlank()) {
            return Schema.MealPlanPayloadResult.DecryptFailed("Event missing content")
        }
        return try {
            val decrypt: suspend () -> String = when (detectEncryptionMethod(event.content)) {
                EncryptionMethod.NIP44 -> {
                    { signer.nip44Decrypt(event.content, event.pubkey) }
                }
                EncryptionMethod.NIP04 -> {
                    { signer.nip04Decrypt(event.content, event.pubkey) }
                }
            }
            val plaintext = if (gate != null) {
                gate.attempt(event.content, decrypt)
                    ?: return Schema.MealPlanPayloadResult.DecryptFailed("Decrypt denied")
            } else {
                decrypt()
            }
            Schema.parseMealPlanPayload(plaintext, weekId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Schema.MealPlanPayloadResult.DecryptFailed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * NIP-09 kind-5 deletion via [Nip09.buildAddressableDeletionTags], plus
     * optional `e` tag when the concrete event id is known.
     * [createdAt] must be ≥ the plan's last publish stamp or NIP-09's
     * up-to-created_at rule leaves the newest version alive.
     */
    suspend fun createDeletionEvent(
        signer: NostrSigner,
        weekId: String,
        eventId: String? = null,
        createdAt: Long = System.currentTimeMillis() / 1000,
    ): NostrEvent {
        val tags = Nip09
            .buildAddressableDeletionTags(KIND, signer.pubkeyHex, Week.dTagForWeek(weekId))
            .toMutableList()
        if (eventId != null) tags.add(listOf("e", eventId))
        return signer.signEvent(
            kind = 5,
            content = "Deleted meal plan",
            tags = tags,
            createdAt = createdAt,
        )
    }
}
