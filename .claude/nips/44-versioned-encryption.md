# NIP-44: Versioned Encryption

**Status in Wisp:** Not yet implemented
**Depends on:** NIP-01
**Required by:** NIP-17 (private DMs)

## Overview

Standard encryption scheme for Nostr, replacing NIP-04. Uses
XChaCha20 + HMAC-SHA256 with HKDF key derivation.

## Version

Current version: **2** (version byte `0x02` prefix on all payloads)

## Encryption Scheme

### Key Derivation

1. ECDH: `shared_point = secp256k1_ecdh(privkey_a, pubkey_b)`
2. Extract x-coordinate (32 bytes) of shared point
3. HKDF-SHA256:
   - Salt: none (empty)
   - IKM: shared x-coordinate (32 bytes)
   - Info: `"nip44-v2"` (UTF-8)
   - Output: 76 bytes
     - Bytes 0-31: `encryption_key` (ChaCha20)
     - Bytes 32-75: `nonce_base` (for nonce generation)
     - (Bytes 44-75: HMAC key)

Actually, the exact split:
- Conversation key = HKDF-Extract + Expand with `"nip44-v2"` info -> 32 bytes
- Per-message: random 32-byte nonce -> HKDF to derive ChaCha key + nonce + HMAC key

### Per-Message Keys (Simplified Flow)

1. Generate random 32-byte `nonce`
2. Use HKDF-Expand with conversation key and nonce to derive:
   - `chacha_key` (32 bytes)
   - `chacha_nonce` (12 bytes)
   - `hmac_key` (32 bytes)

### Encryption

1. Pad plaintext (see padding below)
2. Encrypt padded plaintext with XChaCha20 using derived key + nonce
3. Compute HMAC-SHA256 over `nonce || ciphertext` with HMAC key
4. Payload: `version(1) || nonce(32) || ciphertext(variable) || hmac(32)`
5. Base64-encode the payload

### Decryption

1. Base64-decode payload
2. Verify version byte == `0x02`
3. Extract nonce (32 bytes), ciphertext, and HMAC (last 32 bytes)
4. Derive per-message keys from conversation key + nonce
5. Verify HMAC
6. Decrypt with XChaCha20
7. Remove padding

## Padding

Messages are padded to hide their exact length:
- Minimum padded size: 32 bytes
- Maximum padded size: 65535 bytes
- Padding calculates the next power of 2 boundary with step increments
- Format: `[2-byte big-endian length][plaintext][zero padding]`

The 2-byte length prefix stores the actual message length (max 65535).

## Conversation Key Caching

The ECDH + HKDF conversation key is the same for any pair of users.
Cache it per `(my_privkey, their_pubkey)` pair to avoid repeated ECDH.

## Implementation Dependencies

- HKDF-SHA256 (extract + expand)
- XChaCha20 (or ChaCha20 with extended nonce)
- HMAC-SHA256
- secp256k1 ECDH

## Common Pitfalls

- Version byte MUST be checked — future versions may use different algorithms
- HMAC must be verified BEFORE decryption (encrypt-then-MAC)
- Padding is mandatory — unpadded messages are invalid
- The conversation key is symmetric — same key for both directions
- Nonce MUST be random for each message (never reuse)
- Don't confuse with NIP-04 encryption (AES-CBC, deprecated)
- Maximum message size is 65535 bytes (2-byte length prefix limit)
- Test against NIP-44 test vectors before shipping
