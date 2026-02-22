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

// --- NIP-55 Remote Signer (Content Resolver) ---

/**
 * Delegates all signing/encryption to an external signer app via NIP-55 content resolver.
 * After permissions are granted during login, all operations run silently in the background
 * without launching the signer's UI.
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
        return querySignEvent(eventJson, unsigned)
    }

    override suspend fun nip44Encrypt(plaintext: String, peerPubkeyHex: String): String {
        return queryContentResolver("NIP44_ENCRYPT", plaintext, peerPubkeyHex)
    }

    override suspend fun nip44Decrypt(ciphertext: String, peerPubkeyHex: String): String {
        return queryContentResolver("NIP44_DECRYPT", ciphertext, peerPubkeyHex)
    }

    private suspend fun querySignEvent(
        eventJson: String,
        unsigned: NostrEvent
    ): NostrEvent = withContext(Dispatchers.IO) {
        val uri = Uri.parse("content://${signerPackage}.SIGN_EVENT")
        val projection = arrayOf(eventJson, "", npub)
        android.util.Log.d("RemoteSigner", "querySignEvent: uri=$uri, npub=$npub, kind=${unsigned.kind}")
        val cursor = contentResolver.query(uri, projection, null, null, null)
            ?: throw Exception("Signer ($signerPackage) returned no cursor for SIGN_EVENT kind=${unsigned.kind}")

        cursor.use {
            if (!it.moveToFirst()) throw Exception("Signer returned empty cursor for SIGN_EVENT")
            if (it.getColumnIndex("rejected") >= 0) throw Exception("Signer rejected SIGN_EVENT")

            // Prefer full signed event from "event" column
            val eventIdx = it.getColumnIndex("event")
            if (eventIdx >= 0) {
                val signedJson = it.getString(eventIdx)
                if (!signedJson.isNullOrBlank()) {
                    return@withContext NostrEvent.fromJson(signedJson)
                }
            }

            // Fallback: apply signature to our unsigned event
            val sig = it.getString(it.getColumnIndex("signature"))
                ?: it.getString(it.getColumnIndex("result"))
                ?: throw Exception("Signer returned no signature")
            return@withContext unsigned.withSignature(sig)
        }
    }

    private suspend fun queryContentResolver(
        method: String,
        data: String,
        peerPubkey: String = ""
    ): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse("content://${signerPackage}.$method")
        val projection = arrayOf(data, peerPubkey, npub)
        android.util.Log.d("RemoteSigner", "query: uri=$uri, method=$method")
        val cursor = contentResolver.query(uri, projection, null, null, null)
            ?: throw Exception("Signer ($signerPackage) returned no cursor for $method")

        cursor.use {
            if (!it.moveToFirst()) throw Exception("Signer returned empty cursor for $method")
            if (it.getColumnIndex("rejected") >= 0) throw Exception("Signer rejected $method")

            val resIdx = it.getColumnIndex("result")
            if (resIdx >= 0) {
                return@withContext it.getString(resIdx)
                    ?: throw Exception("Signer returned null result for $method")
            }

            val sigIdx = it.getColumnIndex("signature")
            if (sigIdx >= 0) {
                return@withContext it.getString(sigIdx)
                    ?: throw Exception("Signer returned null signature")
            }

            return@withContext it.getString(0)
                ?: throw Exception("Signer returned null in column 0 for $method")
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
