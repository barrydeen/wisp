# Nostr Protocol — Wisp Development Skill

## Local NIP Reference

Condensed NIP summaries are in `.claude/nips/`. Read these instead of
fetching specs from the web.

```
.claude/nips/README.md              # Index, event kind table, dependency map
.claude/nips/01-basic-protocol.md   # Event structure, filters, messages
.claude/nips/02-follow-list.md      # Kind 3 contact list
.claude/nips/04-encrypted-dm-legacy.md  # Kind 4 (DEPRECATED)
.claude/nips/05-dns-verification.md # NIP-05 identifiers
.claude/nips/09-event-deletion.md   # Kind 5 deletion
.claude/nips/10-reply-threading.md  # E-tag markers, threading
.claude/nips/11-relay-info.md       # Relay information document
.claude/nips/17-private-dm.md       # Gift-wrapped DMs (kinds 14/13/1059)
.claude/nips/19-bech32-encoding.md  # npub/nsec/note/nprofile/nevent
.claude/nips/25-reactions.md        # Kind 7 reactions
.claude/nips/42-authentication.md   # Kind 22242 challenge-response
.claude/nips/44-versioned-encryption.md  # XChaCha20 + HMAC encryption
.claude/nips/51-lists.md            # Kinds 10000-30030 lists
.claude/nips/57-zaps.md             # Lightning zaps (kinds 9734/9735)
.claude/nips/65-relay-list-metadata.md   # Kind 10002, outbox model
```

**Usage:** When implementing a Nostr feature, read the relevant NIP file(s) first.
Check `.claude/nips/README.md` for the dependency map to see what other NIPs
are prerequisites.

## Quick Reference: Event Kinds

| Kind | Name | NIP |
|------|------|-----|
| 0 | Profile Metadata | 01 |
| 1 | Short Text Note | 01 |
| 3 | Follow List | 02 |
| 4 | Encrypted DM (deprecated) | 04 |
| 5 | Deletion | 09 |
| 7 | Reaction | 25 |
| 13 | Seal | 17 |
| 14 | Chat Message | 17 |
| 1059 | Gift Wrap | 17 |
| 9734 | Zap Request | 57 |
| 9735 | Zap Receipt | 57 |
| 10000 | Mute List | 51 |
| 10002 | Relay List | 65 |
| 10050 | DM Relay List | 51 |
| 22242 | Auth | 42 |
| 30000+ | Param. Replaceable Lists | 51 |

## Quick Reference: Client Messages

```
["EVENT", <event-object>]                 # Publish
["REQ", <sub-id>, <filter>, ...]          # Subscribe
["CLOSE", <sub-id>]                       # Unsubscribe
["AUTH", <auth-event>]                    # NIP-42 auth response
```

## Quick Reference: Relay Messages

```
["EVENT", <sub-id>, <event-object>]       # Matching event
["EOSE", <sub-id>]                        # End of stored events
["OK", <event-id>, <bool>, <message>]     # Event acceptance
["NOTICE", <message>]                     # Human-readable message
["AUTH", <challenge>]                     # NIP-42 auth challenge
```

## Quick Reference: Tag Formats

```
["e", <event-id>, <relay-url>, <marker>]  # Event ref (root/reply/mention)
["p", <pubkey>, <relay-url>]              # Pubkey ref
["a", "<kind>:<pubkey>:<d>", <relay>]     # Replaceable event ref
["d", <identifier>]                       # Param. replaceable identifier
["q", <event-id>, <relay-url>]            # Quote
["t", <topic>]                            # Hashtag
["k", <kind-number>]                      # Kind reference
["r", <relay-url>, <read|write>]          # Relay (NIP-65)
```

## Wisp Codebase: Existing Nostr Implementation

### Protocol Layer (`app/src/main/kotlin/com/wisp/app/nostr/`)

| File | Purpose | Key Types |
|------|---------|-----------|
| `Event.kt` | Core event model + signing | `NostrEvent` data class, `NostrEvent.create()`, `computeId()` |
| `Keys.kt` | Key generation + Schnorr signing | `Keys` object, `Keypair` data class, `Keys.generate()`, `Keys.sign()` |
| `Filter.kt` | Subscription filters | `Filter` data class, `toJsonObject()` |
| `ClientMessage.kt` | Client->relay messages | `ClientMessage.req()`, `.event()`, `.close()` |
| `RelayMessage.kt` | Relay->client message parsing | `RelayMessage` sealed class: `EventMsg`, `Eose`, `Ok`, `Notice` |
| `Nip10.kt` | Reply threading tags | `Nip10.buildReplyTags(replyTo)` |
| `Nip19.kt` | Bech32 encode/decode | `Nip19.npubEncode()`, `.nsecDecode()`, `.noteEncode()` |

### Relay Layer (`app/src/main/kotlin/com/wisp/app/relay/`)

| File | Purpose | Key Types |
|------|---------|-----------|
| `Relay.kt` | Single WebSocket connection | `Relay` class, `connect()`, `send()`, `messages: SharedFlow` |
| `RelayPool.kt` | Multi-relay management | `RelayPool`, `updateRelays()`, `sendToWriteRelays()`, dedup via LruCache |
| `RelayConfig.kt` | Relay configuration | `RelayConfig(url, read, write)` |

### Data Layer (`app/src/main/kotlin/com/wisp/app/repo/`)

| File | Purpose | Key Types |
|------|---------|-----------|
| `KeyRepository.kt` | Encrypted key storage | `KeyRepository`, `saveKeypair()`, `getKeypair()`, EncryptedSharedPreferences |
| `EventRepository.kt` | In-memory event cache | `EventRepository`, `feed: StateFlow`, LruCache for events + profiles |

### ViewModels (`app/src/main/kotlin/com/wisp/app/viewmodel/`)

| File | Purpose |
|------|---------|
| `FeedViewModel.kt` | Feed subscription, pagination, relay init |
| `AuthViewModel.kt` | Login/signup/logout, key management |
| `ComposeViewModel.kt` | Post composition, publishing with NIP-10 reply tags |
| `RelayViewModel.kt` | Relay configuration UI state |

### UI (`app/src/main/kotlin/com/wisp/app/ui/`)

| File | Purpose |
|------|---------|
| `screen/FeedScreen.kt` | Main feed display |
| `screen/ThreadScreen.kt` | Thread view |
| `screen/ComposeScreen.kt` | Post composition |
| `screen/ProfileScreen.kt` | User profile display |
| `screen/AuthScreen.kt` | Login/signup |
| `screen/RelayScreen.kt` | Relay management |
| `screen/SettingsScreen.kt` | App settings |
| `component/EventCard.kt` | Single event display component |

## Implementation Conventions

### Creating a New Event Kind

1. Add kind constant or documentation in the relevant NIP file
2. Create the event using `NostrEvent.create(kind = N, ...)`
3. Build tags per the NIP spec
4. Send via `relayPool.sendToWriteRelays(ClientMessage.event(event))`

### Adding a New NIP Implementation

1. Create `NipXX.kt` in `app/src/main/kotlin/com/wisp/app/nostr/`
2. Use an `object` with helper functions (matches `Nip10`, `Nip19` pattern)
3. Keep protocol logic separate from UI/ViewModel
4. Add filter support in `Filter.kt` if new tag types are needed

### Fetching Events

1. Build a `Filter` with the desired criteria
2. Generate a subscription ID (descriptive string)
3. Send via `relayPool.sendToAll(ClientMessage.req(subId, filter))`
4. Collect from `relayPool.events` flow
5. Handle EOSE from `relayPool.eoseSignals` to know when stored events are done

### Handling Replaceable Events

- Kind 0, 3, 10000-19999: Keep only latest per pubkey
- Kind 30000-39999: Keep only latest per pubkey + d-tag
- Always read-modify-write when updating

### Cryptographic Operations

- Key generation: `Keys.generate()` -> `Keypair(privkey, pubkey)`
- Signing: `Keys.sign(privkey, sha256Hash)` -> 64-byte Schnorr sig
- Library: `fr.acinq.secp256k1.Secp256k1` (JNI Android)
- Public keys: x-only format (32 bytes, no prefix)

### Serialization

- Library: `kotlinx.serialization.json`
- Event JSON: `NostrEvent.toJson()` / `NostrEvent.fromJson()`
- Custom: `LongAsStringSerializer` for timestamps
- Hex: `ByteArray.toHex()` / `String.hexToByteArray()`

## NIP Dependency Map

```
NIP-01 (base protocol)
├── NIP-02 (follow list)
├── NIP-05 (DNS verification)
├── NIP-09 (deletion)
├── NIP-10 (threading) ✓
├── NIP-11 (relay info)
├── NIP-19 (bech32) ✓
├── NIP-25 (reactions)
├── NIP-42 (auth)
├── NIP-51 (lists)
├── NIP-65 (relay list metadata)
├── NIP-44 (encryption)
│   └── NIP-17 (private DMs)
└── NIP-57 (zaps)
```

✓ = implemented in Wisp
