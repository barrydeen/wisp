# NIP-04: Encrypted Direct Messages (LEGACY/DEPRECATED)

**Status in Wisp:** Not yet implemented
**Depends on:** NIP-01
**Kind:** 4 (regular)
**DEPRECATED:** Use NIP-17 (kind 14 via gift wrap) instead

## Why Deprecated

- Leaks metadata: relay sees who is messaging whom (p-tags are public)
- Leaks message count and timing
- No forward secrecy
- CBC mode without authentication (malleable ciphertext)
- NIP-17 fixes all of these issues

## Event Structure (for reference only)

```json
{
  "kind": 4,
  "tags": [["p", "<recipient-pubkey-hex>"]],
  "content": "<base64-encoded-ciphertext>?iv=<base64-encoded-iv>"
}
```

## Encryption

1. Compute ECDH shared secret: `shared = secp256k1_ecdh(sender_privkey, recipient_pubkey)`
2. Use first 32 bytes of shared point x-coordinate as AES key
3. Generate random 16-byte IV
4. Encrypt content with AES-256-CBC
5. Encode: `base64(ciphertext) + "?iv=" + base64(iv)`

## Decryption

1. Split content on `?iv=`
2. Compute same ECDH shared secret
3. Decrypt with AES-256-CBC using the shared secret and IV

## Filter for DMs

```json
// Messages TO me
{"kinds": [4], "#p": ["<my-pubkey>"]}
// Messages FROM me
{"kinds": [4], "authors": ["<my-pubkey>"]}
```

## Common Pitfalls

- ECDH shared point must use the x-coordinate only (not full point)
- Different secp256k1 libraries return ECDH results differently — test against known vectors
- The `?iv=` separator is literal, not URL encoding
- Always implement NIP-17 instead for new clients
