# NIP-49: Private Key Encryption (`ncryptsec`)

**Status in Wisp:** Implemented (encrypt + decrypt)
**File:** `Nip49.kt` (bech32 via `Nip19.kt`)
**Depends on:** NIP-19 (bech32)

## Overview

A private key encrypted with a password, encoded as `ncryptsec1…`. Used for
signing in with a password-protected key and for exporting a backup that is
safe to store in a password manager or cloud drive.

## Payload layout (91 bytes, bech32 HRP `ncryptsec`)

| Offset | Size | Field |
|--------|------|-------|
| 0 | 1 | version — always `0x02` |
| 1 | 1 | `log_n` — scrypt work factor as a power of two |
| 2 | 16 | salt |
| 18 | 24 | XChaCha20 nonce |
| 42 | 1 | key security byte (also the AEAD associated data) |
| 43 | 48 | ciphertext (32-byte key + 16-byte Poly1305 tag) |

## Key derivation

```
SYMMETRIC_KEY = scrypt(password = NFKC(password) as UTF-8,
                       salt = SALT, N = 2^log_n, r = 8, p = 1, dkLen = 32)
```

NFKC normalisation is mandatory — without it a password typed on a different
device/IME can produce different bytes and fail to decrypt.

`log_n` sets both cost and memory: `128 * r * 2^log_n` bytes.

| log_n | Memory | Notes |
|-------|--------|-------|
| 16 | 64 MiB | our default for exports; what most clients emit |
| 18 | 256 MiB | |
| 20 | 1 GiB | already beyond many phones |
| 22 | 4 GiB | spec maximum in practice |

`Nip49` rejects `log_n > 22` and maps an allocation failure to
`Nip49Error.TooExpensive` rather than crashing.

## Encryption

```
CIPHERTEXT = XChaCha20-Poly1305(plaintext = 32-byte privkey,
                                associated_data = KEY_SECURITY_BYTE,
                                nonce = NONCE, key = SYMMETRIC_KEY)
```

XChaCha20-Poly1305 is not in BouncyCastle directly: `Nip49` derives the subkey
with HChaCha20 (draft-irtf-cfrg-xchacha §2.2) over `nonce[0..16]`, then runs
BouncyCastle's IETF `ChaCha20Poly1305` with nonce `00000000 || nonce[16..24]`.

## Key security byte

| Value | Meaning |
|-------|---------|
| `0x00` | key is known to have been handled insecurely |
| `0x01` | key is not known to have been handled insecurely |
| `0x02` | the client does not track this |

We do not track key handling, so exports carry `0x02`. The byte is
authenticated as associated data — flipping it fails the tag.

## Usage in this app

```kotlin
// Export (off the main thread — scrypt is slow by design)
val ncryptsec = Nip49.encrypt(keypair.privkey, password)

// Sign-in
val privkey = Nip49.decrypt(ncryptsec, password)   // throws Nip49Error
```

- `AuthViewModel.logIn()` parks an `ncryptsec1…` in `pendingNcryptsec`;
  `NcryptsecUnlockDialog` prompts for the password and calls
  `unlockPendingNcryptsec()`, which decrypts on `Dispatchers.Default`.
- `EncryptedKeyExportSection` (Keys screen + backup step) produces an export
  with copy / QR / save-to-file.

## Failure modes

| Error | Cause |
|-------|-------|
| `Nip49Error.WrongPassword` | Poly1305 tag mismatch — wrong password or tampered payload (indistinguishable) |
| `Nip49Error.Malformed` | not an ncryptsec, bad bech32, wrong length, unsupported version |
| `Nip49Error.TooExpensive` | `log_n` demands more memory than the device has |

## Test vector

`Nip49Test` decrypts the spec's published ncryptsec with password `nostr`
(log_n 16) to `3501454135014541350145413501453fefb02227e449e57cf4d3a3ce05378683`.
That single case covers scrypt, HChaCha20, the AEAD, the AAD binding and the
bech32 layout at once.

## Cautions

- Never publish an ncryptsec to relays — a pile of them makes cracking easier.
- Encryption is non-deterministic (fresh salt + nonce), so two exports of the
  same key are not linkable.
