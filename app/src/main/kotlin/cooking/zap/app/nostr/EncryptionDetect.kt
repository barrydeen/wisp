package cooking.zap.app.nostr

/**
 * Which NIP a ciphertext was encrypted with, detected from its envelope —
 * the Android port of the web's `encryptionService.detectEncryptionMethod`.
 */
enum class EncryptionMethod { NIP04, NIP44 }

/**
 * NIP-04 payloads carry a `?iv=` suffix (base64 ciphertext + IV); NIP-44 is a
 * single base64 blob, so anything else defaults to NIP-44. Same substring test
 * as the web (encryptionService.ts detectEncryptionMethod) — needed to read
 * legacy web grocery lists written before nip44 became the write default.
 */
fun detectEncryptionMethod(ciphertext: String): EncryptionMethod =
    if (ciphertext.contains("?iv=")) EncryptionMethod.NIP04 else EncryptionMethod.NIP44
