package cooking.zap.app.nostr

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Abstraction over event signing and NIP-44 encrypt/decrypt.
 * [LocalSigner] uses keys directly; [RemoteSigner] delegates to an external app via NIP-55.
 */
interface NostrSigner {
    val pubkeyHex: String
    suspend fun signEvent(kind: Int, content: String, tags: List<List<String>> = emptyList(), createdAt: Long = System.currentTimeMillis() / 1000): NostrEvent
    suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String
    suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String

    /**
     * Silent-only variants that NEVER launch an approval UI: they return null instead of falling
     * back to the intent path. Used to retry a background delete-sign once after a rejection
     * without popping the signer app in the user's face. The default delegates to the normal
     * method — correct for [LocalSigner], which signs locally and never prompts; [RemoteSigner]
     * overrides to use only the ContentResolver path.
     */
    suspend fun signEventSilently(kind: Int, content: String, tags: List<List<String>> = emptyList(), createdAt: Long = System.currentTimeMillis() / 1000): NostrEvent? =
        signEvent(kind, content, tags, createdAt)
    suspend fun nip44EncryptSilently(plaintext: String, peerPubkeyHex: String): String? =
        nip44Encrypt(plaintext, peerPubkeyHex)
}

/** Post-failure policy for a background delete-sign. See [signerDeleteFailureAction]. */
enum class SignerFailureAction { RETRY_SILENT, GIVE_UP }

/**
 * How a background delete-sign failure should be handled:
 *  - [SignerCancelledException] (the user actively dismissed the prompt) → [GIVE_UP]. Re-prompting
 *    a user who just dismissed is exactly the popup behavior we're eliminating.
 *  - [SignerRejectedException] → [RETRY_SILENT]. A rejection may be an automatic permissions
 *    auto-reject rather than a human, so retry once via the silent ContentResolver path.
 *  - anything else → [GIVE_UP].
 */
fun signerDeleteFailureAction(error: Throwable): SignerFailureAction = when (error) {
    is SignerCancelledException -> SignerFailureAction.GIVE_UP
    is SignerRejectedException -> SignerFailureAction.RETRY_SILENT
    else -> SignerFailureAction.GIVE_UP
}

/**
 * Signs events and performs NIP-44 operations locally using the private key.
 */
class LocalSigner(
    private val privkey: ByteArray,
    private val pubkey: ByteArray
) : NostrSigner {
    override val pubkeyHex: String = pubkey.toHex()

    override suspend fun signEvent(kind: Int, content: String, tags: List<List<String>>, createdAt: Long): NostrEvent {
        return NostrEvent.create(
            privkey = privkey,
            pubkey = pubkey,
            kind = kind,
            content = content,
            tags = tags,
            createdAt = createdAt
        )
    }

    override suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String {
        val convKey = Nip44.getConversationKey(privkey, peerPubkeyHex.hexToByteArray())
        return Nip44.encrypt(plaintext, convKey)
    }

    override suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String {
        val convKey = Nip44.getConversationKey(privkey, peerPubkeyHex.hexToByteArray())
        return Nip44.decrypt(ciphertext, convKey)
    }
}

// --- NIP-55 Remote Signer (ContentResolver + Intent fallback) ---

class SignerRejectedException(message: String) : Exception(message)
class SignerCancelledException(message: String) : Exception(message)

/**
 * Delegates all signing/encryption to an external signer app via NIP-55 (e.g. Amber).
 * Tries the ContentResolver (silent mode) first; if the signer returns no cursor
 * (permissions not yet granted / "always ask" mode), falls back to intent-based signing
 * which launches the signer's approval UI via [SignerIntentBridge].
 */
class RemoteSigner(
    override val pubkeyHex: String,
    private val contentResolver: ContentResolver,
    private val signerPackage: String
) : NostrSigner {

    private val npub: String = Nip19.npubEncode(pubkeyHex.hexToByteArray())

    override suspend fun signEvent(kind: Int, content: String, tags: List<List<String>>, createdAt: Long): NostrEvent {
        val unsigned = NostrEvent.createUnsigned(pubkeyHex, kind, content, tags, createdAt)
        val eventJson = unsigned.toJson()
        return tryContentResolverSign(eventJson, unsigned) ?: signEventViaIntent(eventJson, unsigned)
    }

    override suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String {
        return tryContentResolver("NIP44_ENCRYPT", plaintext, peerPubkeyHex)
            ?: nip44ViaIntent("nip44_encrypt", plaintext, peerPubkeyHex)
    }

    override suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String {
        return tryContentResolver("NIP44_DECRYPT", ciphertext, peerPubkeyHex)
            ?: nip44ViaIntent("nip44_decrypt", ciphertext, peerPubkeyHex)
    }

    // Silent-only: ContentResolver path exclusively, no intent fallback. Returns null on any
    // failure (unavailable cursor OR an explicit silent rejection) so a retry never pops Amber.
    override suspend fun signEventSilently(kind: Int, content: String, tags: List<List<String>>, createdAt: Long): NostrEvent? {
        val unsigned = NostrEvent.createUnsigned(pubkeyHex, kind, content, tags, createdAt)
        return try {
            tryContentResolverSign(unsigned.toJson(), unsigned)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun nip44EncryptSilently(plaintext: String, peerPubkeyHex: String): String? {
        return try {
            tryContentResolver("NIP44_ENCRYPT", plaintext, peerPubkeyHex)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun tryContentResolverSign(
        eventJson: String,
        unsigned: NostrEvent
    ): NostrEvent? = withContext(Dispatchers.IO) {
        val uri = Uri.parse("content://${signerPackage}.SIGN_EVENT")
        val cursor = contentResolver.query(uri, arrayOf(eventJson, "", npub), null, null, null)
            ?: return@withContext null

        cursor.use {
            if (!it.moveToFirst()) return@withContext null
            if (it.getColumnIndex("rejected") >= 0) throw SignerRejectedException("Signer rejected SIGN_EVENT kind=${unsigned.kind}")

            val eventIdx = it.getColumnIndex("event")
            if (eventIdx >= 0) {
                val signedJson = it.getString(eventIdx)
                if (!signedJson.isNullOrBlank()) return@withContext NostrEvent.fromJson(signedJson)
            }

            val sigIdx = it.getColumnIndex("signature")
            val resIdx = it.getColumnIndex("result")
            val sig = (if (sigIdx >= 0) it.getString(sigIdx) else null)
                ?: (if (resIdx >= 0) it.getString(resIdx) else null)
                ?: return@withContext null
            return@withContext unsigned.withSignature(sig)
        }
    }

    private suspend fun tryContentResolver(
        method: String,
        data: String,
        peerPubkey: String = ""
    ): String? = withContext(Dispatchers.IO) {
        val uri = Uri.parse("content://${signerPackage}.$method")
        val cursor = contentResolver.query(uri, arrayOf(data, peerPubkey, npub), null, null, null)
            ?: return@withContext null

        cursor.use {
            if (!it.moveToFirst()) return@withContext null
            if (it.getColumnIndex("rejected") >= 0) throw SignerRejectedException("Signer rejected $method")

            val resIdx = it.getColumnIndex("result")
            if (resIdx >= 0) return@withContext it.getString(resIdx)

            val sigIdx = it.getColumnIndex("signature")
            if (sigIdx >= 0) return@withContext it.getString(sigIdx)

            return@withContext it.getString(0)
        }
    }

    private suspend fun signEventViaIntent(eventJson: String, unsigned: NostrEvent): NostrEvent {
        val intent = buildSignerIntent("nostrsigner:$eventJson", "sign_event") {
            putExtra("id", unsigned.id)
        }
        return when (val result = SignerIntentBridge.requestSign(intent)) {
            is SignResult.Success -> {
                if (!result.event.isNullOrBlank()) NostrEvent.fromJson(result.event)
                else unsigned.withSignature(result.result)
            }
            is SignResult.Rejected -> throw SignerRejectedException("Signer rejected SIGN_EVENT kind=${unsigned.kind}")
            is SignResult.Cancelled -> throw SignerCancelledException("Signing cancelled by user")
        }
    }

    private suspend fun nip44ViaIntent(type: String, data: String, peerPubkeyHex: String): String {
        val crMethod = if (type == "nip44_encrypt") "NIP44_ENCRYPT" else "NIP44_DECRYPT"
        val intent = buildSignerIntent("nostrsigner:$data", type) {
            putExtra("pubkey", peerPubkeyHex)
        }
        val result = SignerIntentBridge.requestSignWithRetry(intent) {
            tryContentResolver(crMethod, data, peerPubkeyHex)
        }
        return when (result) {
            is SignResult.Success -> result.result
            is SignResult.Rejected -> throw SignerRejectedException("Signer rejected $type")
            is SignResult.Cancelled -> throw SignerCancelledException("$type cancelled by user")
        }
    }

    private inline fun buildSignerIntent(
        uriString: String,
        type: String,
        extras: Intent.() -> Unit = {}
    ): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        `package` = signerPackage
        putExtra("type", type)
        putExtra("current_user", npub)
        extras()
    }
}

/**
 * Helpers for NIP-55 signer discovery and login (intent-based, only used once at login time).
 */
object RemoteSignerBridge {
    /** NIP-55 permissions requested at login time for all signing operations used by this app. */
    const val DEFAULT_PERMISSIONS = """[{"type":"sign_event","kind":0},{"type":"sign_event","kind":1},{"type":"sign_event","kind":3},{"type":"sign_event","kind":5},{"type":"sign_event","kind":6},{"type":"sign_event","kind":7},{"type":"sign_event","kind":9734},{"type":"sign_event","kind":10000},{"type":"sign_event","kind":10002},{"type":"sign_event","kind":22242},{"type":"sign_event","kind":30000},{"type":"sign_event","kind":30023},{"type":"nip44_encrypt"},{"type":"nip44_decrypt"}]"""

    fun isSignerAvailable(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        return context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }

    fun buildGetPublicKeyIntent(permissions: String? = null): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("type", "get_public_key")
            if (permissions != null) putExtra("permissions", permissions)
        }
}
