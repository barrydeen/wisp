# Wisp

A minimal, performant Android client for the [Nostr](https://nostr.com) protocol. Built with Kotlin and Jetpack Compose (Material 3), Wisp prioritizes decentralization, intelligent relay routing, strong privacy, and a clean native experience.

> **Status:** v1.0.0 — stable, actively developed.

---

## Table of Contents

- [Why Wisp](#why-wisp)
- [Key Features](#key-features)
- [Architecture](#architecture)
- [Supported NIPs](#supported-nips)
- [Getting Started](#getting-started)
- [Building from Source](#building-from-source)
- [Contributing](#contributing)
- [Tech Stack](#tech-stack)
- [License](#license)

---

## Why Wisp

Most Nostr clients treat relays as interchangeable dumb pipes and lean on a small handful of "mega-relays." Wisp takes a different approach — it implements the full outbox/inbox relay model with reliability scoring, routes messages based on where users actually publish and read, and is built so that decentralization is the default path, not an opt-in.

The result is faster event delivery, less wasted bandwidth, and a client that actively reinforces the architecture Nostr was designed for. Wisp is built to be fast, lightweight, and respectful of both your device and the relay network.

---

## Key Features

### Intelligent Outbox/Inbox Relay Routing

Wisp implements a full NIP-65 outbox/inbox model with relay scoring:

- **Outbox reads** — fetches a user's posts from their *write relays* (where they actually publish), not from a hardcoded list
- **Inbox writes** — delivers replies, reactions, and mentions to the recipient's *read relays* so they actually see them
- **Relay scoring** — `RelayScoreBoard` tracks reliability and author coverage per relay to pick the smallest useful set for each query
- **Smart relay hints** — when tagging events, chooses hints that overlap the author's outbox with the target's inbox for best discoverability
- **Persistent + ephemeral pool** — `RelayPool` maintains up to 30 persistent connections plus up to 50 short-lived ephemeral ones, with idle cleanup and per-relay cooldowns after failures
- **NIP-42 authentication** — signs AUTH challenges only for relays the user has explicitly approved, with persisted approvals, and waits for AUTH before sending sensitive publishes (DMs, group events)
- **NIP-11 relay info** — fetches and respects relay metadata, capabilities, and limitations
- **Tor support** — route relay traffic through an embedded Tor client for enhanced privacy

### Privacy & Private Messaging

- **NIP-17 gift-wrap DMs** — three-layer privacy model (rumor → seal → gift wrap) with timestamp randomization
- **NIP-44 modern encryption** — ECDH + HKDF + XChaCha20 + HMAC-SHA256, with aggressive conversation-key caching (ECDH is expensive; cache it per peer)
- **NIP-04 fallback** — legacy encrypted DMs still read for backward compatibility
- **Dedicated DM relays** — publish DMs to a separate relay set (NIP-51 kind 10050) from your public posts
- **Encrypted media** — optional encrypted image attachments in DMs
- **Optional Amber / NIP-55 remote signer** — keep your `nsec` in a dedicated signer app and never hand it to Wisp at all

### Lightning & Zaps

A built-in non-custodial Lightning wallet powered by [Breez SDK (Spark)](https://github.com/breez/breez-sdk-spark), plus NWC as an alternative:

- **Embedded Spark wallet** — self-custodial Lightning node that runs on-device
- **12-word seed backup** — standard BIP-39 mnemonic recovery
- **Encrypted relay backup** — wallet credentials encrypted to your own pubkey (NIP-44) and published as a NIP-78 app-data event (kind 30078) so you can restore from any session; format-compatible with other Spark wallets
- **NWC (NIP-47)** — connect any `nostr+walletconnect://` compatible wallet as an alternative
- **Lightning address** — configure a reusable LN address for receiving
- **Transaction history** — paginated, with counterparty resolution from zap receipts
- **Zaps** — send (NIP-57), display zap receipts on the feed, and vote in **zap polls** (NIP-69)
- **QR scanning** — scan or import to pay invoices

### Safety & Content Filtering

- **nspam ML classifier** — on-device LightGBM spam model with MurmurHash3 feature hashing; filters out low-quality content without sending anything off-device
- **Social graph filtering** — optional "follows + follows-of-follows" scope built from an on-device social graph database
- **Mute lists** (NIP-51 kind 10000) for blocking pubkeys, synced via Nostr
- **Keyword muting** for content filtering
- **Blocked relays** (NIP-51 kind 10006) to opt out of specific relays entirely
- All safety lists sync across clients via published Nostr events

### Rich Content Types

- **Notes & long-form articles** — NIP-01 short notes and NIP-23 articles (kind 30023), with full reader view
- **Picture posts** (NIP-68, kind 20) and **video posts** (NIP-71, kinds 21/22) with imeta parsing, blurhash, thumbnails, and fallback mirrors
- **Live streams** (NIP-53, kind 30311) — watch and chat on live activities
- **Polls** (NIP-88, kinds 1068/1018) — create and vote on single- or multiple-choice polls
- **Reposts** (NIP-18) with tracking
- **Emoji reactions** (NIP-25) with **custom emoji packs** (NIP-30)
- **Reply threading** (NIP-10) with root resolution and marked e-tags
- **Drafts** (NIP-37, kind 31234) — save unfinished posts to your relays so they follow you across devices
- **Proof-of-work** (NIP-13) — optional PoW mining with configurable difficulty, coroutine-cancellable

### Groups & Communities

- **NIP-29 relay-based groups** — join, browse, chat (kind 9), manage membership, with metadata and admin/member addressable events
- **Group persistence** — joined groups and recent messages are cached locally (ObjectBox) so rooms open instantly
- Group event AUTH gating — group joins, creates, and invites (9021/9007/9009) wait for NIP-42 AUTH before sending, and surface admin failures

### Media & Storage

- **Blossom** — upload images and media to decentralized [Blossom](https://github.com/hzrd149/blossom) servers
- Per-account Blossom server list (kind 10063)
- Nostr-event-based upload auth (kind 24242)
- Multi-server fallback — tries each configured server until one succeeds

### Performance

- **LRU caches** across events (5,000), profiles, reactions, reposts, and zaps — data is fetched once and reused
- **Selective on-device persistence** — ObjectBox stores only the kinds worth keeping warm (notes, articles, media posts, zap receipts, polls, reactions, reposts, profiles) for fast cold-start; everything else stays in RAM
- **Off-main-thread processing** — event parsing, crypto, and relay I/O all run on background dispatchers
- **Frame-debounced UI updates** — feed emissions are coalesced to one per ~16 ms frame, preventing recomposition storms from bursty relay traffic
- **Atomic dedup** — check-then-put guards so the same event never gets processed twice across relays
- **Lazy metadata fetching** — profiles are batched and swept periodically rather than fetched per-event
- **Relay cooldowns** — failed relays get a 5-minute cooldown before retry, preventing connection storms
- **Cancellable PoW mining** — honors coroutine cancellation so cancelled mines release the CPU immediately

### Identity, Keys & Accounts

- **Multiple accounts** with per-account encrypted storage
- **EncryptedSharedPreferences** (AES256-GCM) for private keys — `nsec` never touches plain SharedPreferences
- **Biometric authentication** for key access
- **NIP-55 remote signing** — delegate all signing/encryption to Amber (or another signer app) via Android intents, so Wisp never holds the key
- **NIP-19 bech32** — npub, nsec, note, nevent, nprofile encode/decode, with `nostr:` URI rendering in post content
- **NIP-05 DNS verification** with result caching
- **QR code display** for sharing keys and profiles

### Additional Features

- **Thread view** with full NIP-10 root/reply resolution
- **Notifications** aggregating mentions, reactions, zaps, and reposts
- **Hashtag feeds** and hashtag following
- **Search** for profiles, content, and hashtags
- **Bookmarks** and **pins** (NIP-51), plus custom **follow sets** (kind 30000) as alternative feed sources
- **Relay console and health view** for debugging relay communication
- **Profile editing** with metadata publishing
- **Onboarding flow** — key creation, topic/interest selection, and follow suggestions for new users
- **Translation** of foreign-language posts
- **Fiat conversion** of sats for zaps and balances

---

## Architecture

Wisp follows an MVVM architecture with clear layer separation:

```
┌──────────────────────────────────────────────────────┐
│                      UI Layer                         │
│              Jetpack Compose Screens                  │
│   FeedScreen, ThreadScreen, DmScreen, GroupRoom,      │
│   LiveStream, Article, Wallet, Notifications, ...     │
├──────────────────────────────────────────────────────┤
│                   ViewModel Layer                     │
│  FeedVM, ThreadVM, DmConversationVM, WalletVM,        │
│  GroupRoomVM, LiveStreamVM, SocialGraphVM, ...        │
├──────────────────────────────────────────────────────┤
│                   Repository Layer                    │
│   EventRepo, ContactRepo, DmRepo, NwcRepo, Spark,     │
│   RelayListRepo, BlossomRepo, MuteRepo, GroupRepo,    │
│   MetadataFetcher, SocialGraphDb, SpamAuthorCache...  │
├──────────────────────────────────────────────────────┤
│                    Protocol Layer                     │
│   Nip01 Nip02 Nip04 Nip05 Nip09 Nip10 Nip11 Nip13     │
│   Nip17 Nip18 Nip19 Nip25 Nip29 Nip30 Nip37 Nip42     │
│   Nip44 Nip47 Nip51 Nip53 Nip57 Nip65 Nip68 Nip69     │
│   Nip71 Nip78 Nip88 + Blossom, Bolt11, NostrSigner    │
├──────────────────────────────────────────────────────┤
│                     Relay Layer                       │
│   RelayPool, OutboxRouter, RelayScoreBoard,           │
│   SubscriptionManager, RelayHealthTracker,            │
│   TorManager, Relay (OkHttp WebSocket)                │
└──────────────────────────────────────────────────────┘
```

### Key Design Decisions

- **Mostly in-memory, selectively persistent** — the bulk of state lives in LRU caches; ObjectBox persists a narrow set of kinds (notes, articles, media posts, reactions, reposts, zap receipts, polls, profiles) plus joined groups, so cold starts are fast without the complexity of a full event store. Preferences live in SharedPreferences; private keys live in EncryptedSharedPreferences.
- **NIP objects** — each NIP is implemented as a Kotlin `object` with pure helper functions (e.g., `Nip17.createGiftWrap()`, `Nip44.encrypt()`), making the protocol layer modular and easy to test.
- **Flow-based reactivity** — SharedFlow for relay events, StateFlow for UI state. No RxJava, no LiveData — pure coroutines.
- **Signer abstraction** — `NostrSigner` is an interface. `LocalSigner` holds the key; `RemoteSigner` delegates every sign/encrypt/decrypt to an external NIP-55 app. UI code never sees the difference.
- **Off-main-thread crypto** — all signing, NIP-44 encryption, and PoW mining run on `Dispatchers.Default` with proper cancellation support.
- **Encrypted key storage** — private keys use AES256-GCM via Android's Security Crypto library, never plain SharedPreferences.

### Project Structure

```
app/src/main/kotlin/com/wisp/app/
├── nostr/          # Protocol implementations (NipXX.kt objects)
├── relay/          # WebSocket relay, pool, outbox router, scoring, Tor
├── repo/           # Data repositories, caches, and persistence wrappers
├── db/             # ObjectBox entities (EventEntity, GroupMessageEntity...)
├── ml/             # On-device nspam LightGBM classifier
├── viewmodel/      # Screen ViewModels and coordinators
├── ui/
│   ├── screen/         # Full screens (Feed, Thread, DM, Group, Wallet...)
│   └── component/      # Reusable UI components
└── util/           # Shared utilities
```

---

## Supported NIPs

| NIP | Description | Status |
|-----|-------------|--------|
| [01](https://github.com/nostr-protocol/nips/blob/master/01.md) | Basic protocol flow | ✅ |
| [02](https://github.com/nostr-protocol/nips/blob/master/02.md) | Follow lists | ✅ |
| [04](https://github.com/nostr-protocol/nips/blob/master/04.md) | Encrypted DMs (legacy) | ✅ (fallback) |
| [05](https://github.com/nostr-protocol/nips/blob/master/05.md) | DNS-based verification | ✅ |
| [09](https://github.com/nostr-protocol/nips/blob/master/09.md) | Event deletion | ✅ |
| [10](https://github.com/nostr-protocol/nips/blob/master/10.md) | Reply threading | ✅ |
| [11](https://github.com/nostr-protocol/nips/blob/master/11.md) | Relay information | ✅ |
| [13](https://github.com/nostr-protocol/nips/blob/master/13.md) | Proof of Work | ✅ |
| [17](https://github.com/nostr-protocol/nips/blob/master/17.md) | Private DMs (gift wrap) | ✅ |
| [18](https://github.com/nostr-protocol/nips/blob/master/18.md) | Reposts | ✅ |
| [19](https://github.com/nostr-protocol/nips/blob/master/19.md) | Bech32 encoding | ✅ |
| [23](https://github.com/nostr-protocol/nips/blob/master/23.md) | Long-form articles | ✅ |
| [25](https://github.com/nostr-protocol/nips/blob/master/25.md) | Reactions | ✅ |
| [29](https://github.com/nostr-protocol/nips/blob/master/29.md) | Relay-based groups | ✅ |
| [30](https://github.com/nostr-protocol/nips/blob/master/30.md) | Custom emoji | ✅ |
| [37](https://github.com/nostr-protocol/nips/blob/master/37.md) | Draft events | ✅ |
| [42](https://github.com/nostr-protocol/nips/blob/master/42.md) | Relay AUTH | ✅ |
| [44](https://github.com/nostr-protocol/nips/blob/master/44.md) | Versioned encryption | ✅ |
| [47](https://github.com/nostr-protocol/nips/blob/master/47.md) | Wallet Connect (NWC) | ✅ |
| [51](https://github.com/nostr-protocol/nips/blob/master/51.md) | Lists (mute, bookmark, pin, follow sets, relay sets) | ✅ |
| [53](https://github.com/nostr-protocol/nips/blob/master/53.md) | Live activities | ✅ |
| [55](https://github.com/nostr-protocol/nips/blob/master/55.md) | Android signer (Amber) | ✅ |
| [57](https://github.com/nostr-protocol/nips/blob/master/57.md) | Lightning zaps | ✅ |
| [65](https://github.com/nostr-protocol/nips/blob/master/65.md) | Relay list metadata | ✅ |
| [68](https://github.com/nostr-protocol/nips/blob/master/68.md) | Picture-first posts | ✅ |
| 69 | Zap polls (kind 6969) | ✅ |
| [71](https://github.com/nostr-protocol/nips/blob/master/71.md) | Video posts | ✅ |
| [78](https://github.com/nostr-protocol/nips/blob/master/78.md) | App-specific data (wallet backup) | ✅ |
| [88](https://github.com/nostr-protocol/nips/blob/master/88.md) | Polls | ✅ |

Also: **Blossom** media servers (BUDs 01–03), **NWC** transport, and **bolt11** invoice parsing.

---

## Getting Started

### Requirements

- Android 8.0 (API 26) or higher
- A Nostr keypair (generate one in-app, import an existing `nsec`, or pair Amber for NIP-55 remote signing)

### Installation

APK downloads are available on the [Releases](../../releases) page.

### First Launch

1. **Create, import, or pair a signer** — generate a fresh keypair, paste your `nsec`, or connect Amber for remote signing
2. **Set up your profile** — the onboarding flow walks you through name, picture, and bio
3. **Pick some interests** — topic/interest selection seeds your first follow suggestions
4. **Follow some people** — Wisp suggests popular accounts to get your feed started
5. **Configure relays** — your relay list is published as a NIP-65 event so other outbox-aware clients can find you

---

## Building from Source

### Prerequisites

- [Android Studio](https://developer.android.com/studio) Ladybug or later
- JDK 17
- Android SDK 35

### Build

```bash
# Clone the repository
git clone https://github.com/barrydeen/wisp.git
cd wisp

# Build debug APK
./gradlew assembleDebug

# Install on a connected device
./gradlew installDebug

# Build release APK (R8-minified)
./gradlew assembleRelease
```

Release builds use R8 minification; keep rules live in `app/proguard-rules.pro` and cover kotlinx.serialization, secp256k1 JNI, Bouncy Castle, OkHttp, Coil, Security Crypto, Media3, and ZXing.

---

## Contributing

Contributions are welcome. Wisp is open source and community help makes it better.

### How to Contribute

1. **Fork** the repository
2. **Create a branch** for your feature or fix (`feat/…`, `fix/…`, `refactor/…`, `docs/…`, `chore/…`, `perf/…`)
3. **Make your changes** — follow the existing code patterns and conventions
4. **Test** on a real device or emulator
5. **Commit** with a clear, descriptive message (`<type>: <summary>`)
6. **Open a pull request** against `main` — one concern per PR, small and focused

### Code Conventions

- **Kotlin** with Jetpack Compose — no XML layouts
- **NIP implementations** go in `NipXX.kt` as Kotlin `object` with static helper functions
- **Events** are created via `NostrEvent.create(privkey, pubkey, kind, content, tags)` — or, for remote-signer accounts, through the `NostrSigner` abstraction
- **Hex encoding** uses `ByteArray.toHex()` / `String.hexToByteArray()` extensions
- **Coroutines** for all async work — `Dispatchers.Default` for CPU-bound (crypto, PoW), `Dispatchers.IO` for network
- **StateFlow** for UI state, **SharedFlow** for relay events
- Keep functions small and focused. Prefer clarity over cleverness.

### Areas Where Help is Needed

- UI/UX polish and accessibility improvements
- Additional NIP implementations
- Unit and integration tests
- Performance profiling and optimization
- Translations and localization
- Documentation improvements

### Reporting Issues

[Open an issue](../../issues) with:

- Steps to reproduce (for bugs)
- Expected vs actual behavior
- Device and Android version
- Relevant logs from the in-app relay console (if applicable)

---

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin 2.0 |
| UI Framework | Jetpack Compose (Material 3) |
| Networking | OkHttp 4 (WebSocket) |
| Image Loading | Coil 3 |
| Serialization | kotlinx.serialization |
| Persistence | ObjectBox (selective event store), EncryptedSharedPreferences, SharedPreferences |
| Cryptography | secp256k1-kmp (Schnorr / ECDH), Bouncy Castle (XChaCha20), Android Security Crypto (AES-GCM), javax.crypto (HKDF/HMAC) |
| ML | LightGBM (on-device nspam classifier) with MurmurHash3 feature hashing |
| Navigation | Jetpack Navigation Compose |
| Lightning | Breez SDK Spark + NWC (NIP-47) |
| Media | Media3 / ExoPlayer |
| QR Codes | ZXing |
| Privacy Network | Embedded Tor (optional) |
| Build | Gradle 8.x / AGP 8.x |
| Min SDK | Android 8.0 (API 26) |
| Target SDK | Android 15 (API 35) |

---

## License

Wisp is released under the [MIT License](LICENSE).

```
MIT License

Copyright (c) 2025 Barry Deen

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

Built with care for the Nostr ecosystem.
