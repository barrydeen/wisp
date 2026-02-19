# Nostr NIP Reference — Wisp Project

Local condensed NIP references for development. Each file is an actionable
developer summary (not a full spec copy).

## Implementation Status

| NIP | File | Status | Notes |
|-----|------|--------|-------|
| 01 | [01-basic-protocol.md](01-basic-protocol.md) | Implemented | Event, Filter, ClientMessage, RelayMessage |
| 02 | [02-follow-list.md](02-follow-list.md) | Not yet | Kind 3 contact list |
| 04 | [04-encrypted-dm-legacy.md](04-encrypted-dm-legacy.md) | Not yet | Deprecated — use NIP-17 |
| 05 | [05-dns-verification.md](05-dns-verification.md) | Not yet | NIP-05 identifiers |
| 09 | [09-event-deletion.md](09-event-deletion.md) | Not yet | Kind 5 deletion |
| 10 | [10-reply-threading.md](10-reply-threading.md) | Implemented | Nip10.kt |
| 11 | [11-relay-info.md](11-relay-info.md) | Not yet | Relay information document |
| 17 | [17-private-dm.md](17-private-dm.md) | Not yet | Gift-wrapped DMs (replaces NIP-04) |
| 19 | [19-bech32-encoding.md](19-bech32-encoding.md) | Implemented | Nip19.kt (npub/nsec/note) |
| 25 | [25-reactions.md](25-reactions.md) | Not yet | Kind 7 reactions |
| 42 | [42-authentication.md](42-authentication.md) | Not yet | Relay authentication |
| 44 | [44-versioned-encryption.md](44-versioned-encryption.md) | Not yet | NIP-44 encryption (for NIP-17) |
| 51 | [51-lists.md](51-lists.md) | Not yet | Lists and sets |
| 57 | [57-zaps.md](57-zaps.md) | Not yet | Lightning zaps |
| 65 | [65-relay-list-metadata.md](65-relay-list-metadata.md) | Not yet | Outbox model relay lists |

## Event Kind Master Table

| Kind | Name | NIP | Replacement | Notes |
|------|------|-----|-------------|-------|
| 0 | Profile Metadata | 01 | Replaceable | JSON content: {name, about, picture, nip05, ...} |
| 1 | Short Text Note | 01 | Regular | Main post type |
| 3 | Follow List | 02 | Replaceable | p-tags = contacts |
| 4 | Encrypted DM (legacy) | 04 | Regular | DEPRECATED — use kind 14 |
| 5 | Event Deletion | 09 | Regular | e-tags and a-tags to delete |
| 7 | Reaction | 25 | Regular | "+" = like, "-" = dislike, emoji |
| 13 | Seal | 17 | Regular | Encrypted signed rumor |
| 14 | Chat Message | 17 | Regular | The actual DM (inside seal) |
| 1059 | Gift Wrap | 17 | Regular | Outer wrapper with random key |
| 9734 | Zap Request | 57 | Regular | Sent to LNURL server |
| 9735 | Zap Receipt | 57 | Regular | Created by LNURL server |
| 10000 | Mute List | 51 | Replaceable | Parameterized |
| 10001 | Pin List | 51 | Replaceable | Parameterized |
| 10002 | Relay List | 65 | Replaceable | Outbox model relays |
| 22242 | Auth | 42 | Regular | Challenge-response |
| 30000+ | Categorized lists | 51 | Param. replaceable | d-tag = category name |

## Kind Ranges (NIP-01)

| Range | Type | Behavior |
|-------|------|----------|
| 0-999 | Regular | Stored by relays |
| 0, 3, 10000-19999 | Replaceable | Latest per pubkey (+d for parameterized) |
| 1000-9999 | Regular | Stored normally |
| 20000-29999 | Ephemeral | Not stored |
| 30000-39999 | Parameterized replaceable | Latest per pubkey+d-tag |

## NIP Dependency Map

```
NIP-01 (base)
├── NIP-02 (follow list)
├── NIP-05 (DNS verification)
├── NIP-09 (deletion)
├── NIP-10 (threading) ← depends on NIP-01 e-tags
├── NIP-11 (relay info)
├── NIP-19 (bech32)
├── NIP-25 (reactions) ← depends on NIP-10 e-tags
├── NIP-42 (auth)
├── NIP-51 (lists)
├── NIP-65 (relay list metadata)
├── NIP-44 (encryption)
│   └── NIP-17 (private DMs) ← depends on NIP-44
└── NIP-57 (zaps) ← depends on NIP-09 (optional)
```

## Common Tag Formats

| Tag | Purpose | Format | Example |
|-----|---------|--------|---------|
| `e` | Event reference | `["e", <event-id>, <relay>, <marker>]` | `["e", "abc...", "", "reply"]` |
| `p` | Pubkey reference | `["p", <pubkey>, <relay>]` | `["p", "def..."]` |
| `a` | Replaceable ref | `["a", "<kind>:<pubkey>:<d>", <relay>]` | `["a", "30023:abc:slug"]` |
| `d` | Identifier | `["d", <value>]` | `["d", "my-list"]` |
| `q` | Quote | `["q", <event-id>, <relay>]` | `["q", "abc...", "wss://..."]` |
| `t` | Hashtag | `["t", <topic>]` | `["t", "nostr"]` |
| `k` | Kind reference | `["k", <kind-number>]` | `["k", "1"]` |
