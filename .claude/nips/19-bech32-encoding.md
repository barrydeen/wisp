# NIP-19: Bech32-Encoded Entities

**Status in Wisp:** Implemented (npub/nsec/note)
**File:** `Nip19.kt`
**Depends on:** NIP-01

## Overview

Human-readable encoding for Nostr entities using Bech32/Bech32m.

## Basic Types (Bech32)

| Prefix | Encodes | Length | Example |
|--------|---------|--------|---------|
| `npub` | Public key | 32 bytes | `npub1qqqq...` |
| `nsec` | Private key | 32 bytes | `nsec1qqqq...` |
| `note` | Event ID | 32 bytes | `note1qqqq...` |

### Encoding

1. Take 32-byte value (pubkey/privkey/event ID)
2. Convert from 8-bit to 5-bit groups
3. Encode as Bech32 with appropriate HRP (human-readable prefix)

### Decoding

1. Validate Bech32 checksum
2. Verify HRP matches expected prefix
3. Convert 5-bit groups back to 8-bit
4. Verify data is exactly 32 bytes

## Shareable Types (TLV-encoded, Bech32)

| Prefix | Encodes | Contains |
|--------|---------|----------|
| `nprofile` | Profile + hints | pubkey + relay URLs |
| `nevent` | Event + hints | event ID + relay URLs + author |
| `naddr` | Replaceable event | kind + pubkey + d-tag + relay URLs |
| `nrelay` | Relay URL | relay URL |

### TLV Format

Each entry: `[type(1 byte)][length(1 byte)][value(length bytes)]`

| Type | Meaning | Value |
|------|---------|-------|
| 0 | Special (depends on prefix) | 32-byte key/id, or kind+pubkey+d-tag |
| 1 | Relay | UTF-8 relay URL |
| 2 | Author | 32-byte pubkey |
| 3 | Kind | 32-bit big-endian unsigned integer |

### nprofile Example

```
TLV: [0x00][0x20][<32-byte-pubkey>][0x01][0x18][wss://relay.example.com]
```

### nevent Example

```
TLV: [0x00][0x20][<32-byte-event-id>][0x01][0x18][wss://relay.example.com][0x02][0x20][<32-byte-author>]
```

### naddr Example

```
TLV: [0x00][0x02][<d-tag-utf8>][0x01][0x18][wss://relay.example.com][0x02][0x20][<32-byte-author>][0x03][0x04][<4-byte-kind-be>]
```

## Wisp Implementation

Currently implements basic types only:
- `Nip19.npubEncode(pubkey: ByteArray): String`
- `Nip19.nsecEncode(privkey: ByteArray): String`
- `Nip19.noteEncode(eventId: ByteArray): String`
- `Nip19.npubDecode(npub: String): ByteArray`
- `Nip19.nsecDecode(nsec: String): ByteArray`

**Not yet implemented:** nprofile, nevent, naddr TLV encoding/decoding.

## Common Pitfalls

- `nsec` is a private key — NEVER display or transmit it without user consent
- Bech32 is case-insensitive but must be all-lowercase or all-uppercase (not mixed)
- Basic types are simple byte encoding; shareable types use TLV
- naddr uses the d-tag value (not event ID) as the special value
- Multiple relay entries (type 1) are allowed and encouraged for redundancy
- Kind in TLV is 4 bytes big-endian (not variable-length)
