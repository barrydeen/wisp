package com.wisp.app.nostr

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Abstraction over event signing and NIP-44 encrypt/decrypt.
 * LocalSigner uses keys directly; RemoteSigner delegates to an external app via NIP-55.
 */
interface NostrSigner {
    val pubkeyHex: String
    suspend fun signEvent(kind: Int, content: String, tags: List<List<String>> = emptyList(), createdAt: Long = System.currentTimeMillis() / 1000): NostrEvent
    suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String
    suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String
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

// --- NIP-55 Remote Signer (Content Resolver + Intent Fallback) ---

class SignerRejectedException(message: String) : Exception(message)
class SignerCancelledException(message: String) : Exception(message)

/**
 * Delegates all signing/encryption to an external signer app via NIP-55.
 * Tries the ContentResolver (silent mode) first. If the signer returns no cursor
 * (permissions not granted / "always ask" mode), falls back to intent-based signing
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

        // Try silent ContentResolver first
        val crResult = tryContentResolverSign(eventJson, unsigned)
        if (crResult != null) return crResult

        // Fall back to intent-based signing
        return signEventViaIntent(eventJson, unsigned)
    }

    override suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String {
        return tryContentResolver("NIP44_ENCRYPT", plaintext, peerPubkeyHex)
            ?: nip44ViaIntent("nip44_encrypt", plaintext, peerPubkeyHex)
    }

    override suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String {
        return tryContentResolver("NIP44_DECRYPT", ciphertext, peerPubkeyHex)
            ?: nip44ViaIntent("nip44_decrypt", ciphertext, peerPubkeyHex)
    }

    // --- ContentResolver (silent mode) ---

    private suspend fun tryContentResolverSign(
        eventJson: String,
        unsigned: NostrEvent
    ): NostrEvent? = withContext(Dispatchers.IO) {
        val uri = Uri.parse("content://${signerPackage}.SIGN_EVENT")
        val projection = arrayOf(eventJson, "", npub)
        android.util.Log.d("RemoteSigner", "tryContentResolverSign: uri=$uri, npub=$npub, kind=${unsigned.kind}")
        val cursor = contentResolver.query(uri, projection, null, null, null)
            ?: return@withContext null // no cursor → need intent fallback

        cursor.use {
            if (!it.moveToFirst()) return@withContext null
            if (it.getColumnIndex("rejected") >= 0) return@withContext null

            val eventIdx = it.getColumnIndex("event")
            if (eventIdx >= 0) {
                val signedJson = it.getString(eventIdx)
                if (!signedJson.isNullOrBlank()) {
                    return@withContext NostrEvent.fromJson(signedJson)
                }
            }

            val sig = it.getString(it.getColumnIndex("signature"))
                ?: it.getString(it.getColumnIndex("result"))
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
        val projection = arrayOf(data, peerPubkey, npub)
        android.util.Log.d("RemoteSigner", "tryContentResolver: uri=$uri, method=$method")
        val cursor = contentResolver.query(uri, projection, null, null, null)
            ?: return@withContext null

        cursor.use {
            if (!it.moveToFirst()) return@withContext null
            if (it.getColumnIndex("rejected") >= 0) return@withContext null

            val resIdx = it.getColumnIndex("result")
            if (resIdx >= 0) return@withContext it.getString(resIdx)

            val sigIdx = it.getColumnIndex("signature")
            if (sigIdx >= 0) return@withContext it.getString(sigIdx)

            return@withContext it.getString(0)
        }
    }

    // --- Intent fallback (launches signer UI) ---

    private suspend fun signEventViaIntent(
        eventJson: String,
        unsigned: NostrEvent
    ): NostrEvent {
        val intent = buildSignerIntent("nostrsigner:$eventJson", "sign_event") {
            putExtra("id", unsigned.id)
        }
        android.util.Log.d("RemoteSigner", "signEventViaIntent: kind=${unsigned.kind}")
        return when (val result = SignerIntentBridge.requestSign(intent)) {
            is SignResult.Success -> {
                if (!result.event.isNullOrBlank()) {
                    NostrEvent.fromJson(result.event)
                } else {
                    unsigned.withSignature(result.result)
                }
            }
            is SignResult.Rejected -> throw SignerRejectedException("Signer rejected SIGN_EVENT kind=${unsigned.kind}")
            is SignResult.Cancelled -> throw SignerCancelledException("Signing cancelled by user")
        }
    }

    private suspend fun nip44ViaIntent(
        type: String,
        data: String,
        peerPubkeyHex: String
    ): String {
        val intent = buildSignerIntent("nostrsigner:$data", type) {
            putExtra("pubkey", peerPubkeyHex)
        }
        android.util.Log.d("RemoteSigner", "nip44ViaIntent: type=$type")
        return when (val result = SignerIntentBridge.requestSign(intent)) {
            is SignResult.Success -> result.result
            is SignResult.Rejected -> throw SignerRejectedException("Signer rejected $type")
            is SignResult.Cancelled -> throw SignerCancelledException("$type cancelled by user")
        }
    }

    private inline fun buildSignerIntent(
        uriString: String,
        type: String,
        extras: Intent.() -> Unit = {}
    ): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(uriString)).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            `package` = signerPackage
            putExtra("type", type)
            putExtra("current_user", npub)
            extras()
        }
    }
}

/**
 * Helpers for NIP-55 signer discovery and login (intent-based, only used once at login time).
 */
object RemoteSignerBridge {
    fun isSignerAvailable(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        val infos = context.packageManager.queryIntentActivities(intent, 0)
        return infos.isNotEmpty()
    }

    fun buildGetPublicKeyIntent(permissions: String? = null): Intent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        intent.putExtra("type", "get_public_key")
        if (permissions != null) {
            intent.putExtra("permissions", permissions)
        }
        return intent
    }
}
