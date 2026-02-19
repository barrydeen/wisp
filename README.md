# Wisp

A minimal, performant Android client for the [Nostr](https://nostr.com) protocol. Built with Kotlin and Jetpack Compose, Wisp prioritizes decentralization, intelligent relay routing, and a clean native experience.

> **Status:** Early alpha (v0.1.0) — actively developed, expect breaking changes.

---

## Table of Contents

- [Why Wisp](#why-wisp)
- [Key Features](#key-features)
- [Screenshots](#screenshots)
- [Architecture](#architecture)
- [Supported NIPs](#supported-nips)
- [Getting Started](#getting-started)
- [Building from Source](#building-from-source)
- [Contributing](#contributing)
- [Roadmap](#roadmap)
- [License](#license)

---

## Why Wisp

Most Nostr clients treat relays as interchangeable dumb pipes. Wisp takes a different approach — it implements the outbox/inbox relay model from day one, routing messages intelligently based on where users actually publish and read. The result is faster event delivery, less wasted bandwidth, and a client that actively promotes the decentralized architecture Nostr was designed for.

Wisp is built to be fast, lightweight, and respectful of both your device and the relay network.

---

## Key Features

### Intelligent Outbox/Inbox Relay Routing

Wisp implements a full outbox/inbox model with relay scoring:

- **Outbox reads**: Fetches a user's posts from their *write relays* (where they actually publish), not from a hardcoded list
- **Inbox writes**: Delivers replies and reactions to a user's *read relays* (where they actually look), ensuring they see your interactions
- **Relay scoring**: Tracks relay reliability and author coverage to optimize which relays to query, minimizing redundant connections
- **Smart relay hints**: When tagging events, selects relay hints that overlap between your outbox and the target's inbox for optimal discoverability
- **Ephemeral connections**: Dynamically opens short-lived relay connections as needed (up to 30) with automatic cleanup after 5 minutes of inactivity
- **Fallback strategies**: Gracefully degrades to broadcast mode for users without published relay lists

### Performance First

- **LRU caching** across events (5,000), profiles, reactions, reposts, and zaps — data is fetched once and reused
- **Off-main-thread processing**: All event parsing and relay communication runs on background dispatchers
- **Debounced UI updates**: Feed emissions are coalesced to one per 16ms frame, preventing excessive recompositions from rapid relay events
- **Deduplication**: Atomic check-then-put prevents the same event from being processed twice across multiple relays
- **Lazy profile loading**: Metadata is fetched asynchronously in batches with periodic sweep cycles
- **Relay cooldowns**: Failed relays get a 5-minute cooldown before retry, preventing connection storms

### Promotes Decentralization from the Start

- No hardcoded "mega-relay" dependency — default relays are starting points, not requirements
- First-class NIP-65 relay list support encourages users to publish their own relay preferences
- Outbox model means the client respects *where users choose to publish*, not where the client developer decided to look
- Blocked relay support (NIP-51 kind 10006) lets users opt out of specific relays entirely
- DM relay sets (NIP-51 kind 10050) allow separate relay infrastructure for private messaging
- Blossom media uploads distribute content across decentralized media servers instead of centralized CDNs

### NWC Wallet Integration

- Full [NIP-47](https://github.com/nostr-protocol/nips/blob/master/47.md) Nostr Wallet Connect support
- Connect any NWC-compatible Lightning wallet via `nostr+walletconnect://` URI
- Check balance, pay invoices, and create invoices directly from the app
- Send Lightning zaps to posts with optional messages
- Zap receipt tracking and display on the feed

### Blossom Media Support

- Upload images and media to decentralized [Blossom](https://github.com/hzrd149/blossom) servers
- Manage your server list (kind 10063) with per-account isolation
- Nostr event-based authentication for uploads (kind 24242)
- Multi-server fallback — tries each configured server until one succeeds
- Automatic EXIF stripping where supported by the server

### Private Messaging

- **NIP-17 gift wrap encryption**: Three-layer privacy model (rumor → seal → gift wrap) with timestamp randomization
- **NIP-44 modern encryption**: ECDH + HKDF + XChaCha20 + HMAC-SHA256, replacing legacy NIP-04
- **Conversation key caching**: Expensive ECDH computations are cached per peer
- **Separate DM relays**: Publish DMs to dedicated relay sets for better privacy

### Safety Controls

- Mute lists (NIP-51 kind 10000) for blocking pubkeys
- Keyword muting for content filtering
- Relay blocking to opt out of specific relays
- All safety lists sync across clients via published Nostr events

### Additional Features

- **Thread view** with NIP-10 reply threading and root resolution
- **Notifications** aggregating mentions, reactions, and zaps
- **Search** for profiles and content
- **Bookmarks and pins** (NIP-51)
- **Custom follow sets** (NIP-51 kind 30000) as alternative feed sources
- **Reposts** with tracking and display
- **Emoji reactions** (NIP-25) with custom emoji picker
- **NIP-05 DNS verification** with caching
- **NIP-19 bech32 encoding** — npub, nsec, note, nevent, nprofile support
- **Nostr URI rendering** in post content (nostr:npub..., nostr:note...)
- **QR code display** for sharing keys and profiles
- **Multiple account support** with per-account encrypted storage
- **Biometric authentication** for key access
- **Relay console** for debugging relay communication
- **Profile editing** with metadata publishing
- **Onboarding flow** with follow suggestions for new users

---

## Screenshots

*Coming soon*

---

## Architecture

Wisp follows an MVVM architecture with clear layer separation:

```
┌─────────────────────────────────────────────┐
│                   UI Layer                   │
│         Jetpack Compose Screens              │
│    (FeedScreen, ThreadScreen, DmScreen...)   │
├─────────────────────────────────────────────┤
│               ViewModel Layer                │
│   FeedViewModel, ThreadViewModel,            │
│   DmConversationViewModel, WalletViewModel   │
├─────────────────────────────────────────────┤
│              Repository Layer                │
│  EventRepo, ContactRepo, DmRepo, NwcRepo,   │
│  RelayListRepo, BlossomRepo, MuteRepo...     │
├─────────────────────────────────────────────┤
│              Protocol Layer                  │
│   Nip01, Nip02, Nip10, Nip17, Nip19,        │
│   Nip25, Nip44, Nip47, Nip51, Nip57, Nip65  │
├─────────────────────────────────────────────┤
│               Relay Layer                    │
│   RelayPool, OutboxRouter, RelayScoreBoard,  │
│   SubscriptionManager, Relay (WebSocket)     │
└─────────────────────────────────────────────┘
```

### Key Design Decisions

- **No database**: All state is held in-memory (LRU caches) or in SharedPreferences/EncryptedSharedPreferences. This keeps the app simple and fast — events are fetched from relays on each session, as Nostr intended.
- **NIP objects**: Each NIP is implemented as a Kotlin `object` with static helper functions (e.g., `Nip17.createGiftWrap()`), making the protocol layer modular and testable.
- **Flow-based reactivity**: SharedFlow for relay events, StateFlow for UI state. No RxJava, no LiveData — pure coroutines.
- **Encrypted key storage**: Private keys never touch plain SharedPreferences. AES256-GCM via Android's EncryptedSharedPreferences.

### Project Structure

```
app/src/main/kotlin/com/wisp/app/
├── nostr/          # Protocol implementations (NipXX.kt objects)
│   ├── Event.kt        # Core event structure, signing, serialization
│   ├── Filter.kt       # Subscription filters
│   ├── Keys.kt         # Key generation and conversion
│   ├── Nip02.kt        # Follow list management
│   ├── Nip10.kt        # Reply threading
│   ├── Nip17.kt        # Gift wrap DMs
│   ├── Nip19.kt        # Bech32 encoding
│   ├── Nip25.kt        # Reactions
│   ├── Nip44.kt        # Modern encryption
│   ├── Nip47.kt        # Wallet Connect
│   ├── Nip51.kt        # Lists (mute, bookmark, pin, follow sets)
│   ├── Nip57.kt        # Zaps
│   └── Nip65.kt        # Relay list metadata
├── relay/          # Relay connection and routing
│   ├── Relay.kt            # WebSocket connection per relay
│   ├── RelayPool.kt        # Connection pool (persistent + ephemeral)
│   ├── OutboxRouter.kt     # Outbox/inbox routing logic
│   ├── RelayScoreBoard.kt  # Relay quality scoring
│   └── SubscriptionManager.kt  # Subscription lifecycle
├── repo/           # Data repositories and persistence
├── viewmodel/      # Screen ViewModels
├── ui/             # Jetpack Compose screens and components
│   ├── screen/         # Full screens (Feed, Thread, DM, etc.)
│   └── component/      # Reusable UI components
└── db/             # Database layer
```

---

## Supported NIPs

| NIP | Description | Status |
|-----|-------------|--------|
| [01](https://github.com/nostr-protocol/nips/blob/master/01.md) | Basic protocol flow | Implemented |
| [02](https://github.com/nostr-protocol/nips/blob/master/02.md) | Follow lists | Implemented |
| [04](https://github.com/nostr-protocol/nips/blob/master/04.md) | Encrypted DMs (legacy) | Implemented (fallback) |
| [05](https://github.com/nostr-protocol/nips/blob/master/05.md) | DNS-based verification | Implemented |
| [09](https://github.com/nostr-protocol/nips/blob/master/09.md) | Event deletion | Implemented |
| [10](https://github.com/nostr-protocol/nips/blob/master/10.md) | Reply threading | Implemented |
| [11](https://github.com/nostr-protocol/nips/blob/master/11.md) | Relay information | Implemented |
| [17](https://github.com/nostr-protocol/nips/blob/master/17.md) | Private DMs (gift wrap) | Implemented |
| [18](https://github.com/nostr-protocol/nips/blob/master/18.md) | Reposts | Implemented |
| [19](https://github.com/nostr-protocol/nips/blob/master/19.md) | Bech32 encoding | Implemented |
| [25](https://github.com/nostr-protocol/nips/blob/master/25.md) | Reactions | Implemented |
| [44](https://github.com/nostr-protocol/nips/blob/master/44.md) | Versioned encryption | Implemented |
| [47](https://github.com/nostr-protocol/nips/blob/master/47.md) | Wallet Connect (NWC) | Implemented |
| [51](https://github.com/nostr-protocol/nips/blob/master/51.md) | Lists | Implemented |
| [57](https://github.com/nostr-protocol/nips/blob/master/57.md) | Lightning zaps | Implemented |
| [65](https://github.com/nostr-protocol/nips/blob/master/65.md) | Relay list metadata | Implemented |

---

## Getting Started

### Requirements

- Android 8.0 (API 26) or higher
- A Nostr keypair (you can generate one in-app or import an existing nsec)

### Installation

APK downloads will be available on the [Releases](../../releases) page once published.

### First Launch

1. **Create or import a key** — Generate a fresh keypair or paste your existing `nsec`
2. **Set up your profile** — The onboarding flow walks you through name, picture, and bio
3. **Follow some people** — Wisp suggests popular accounts to get your feed started
4. **Configure relays** — Your relay list is published as a NIP-65 event so other outbox-aware clients can find you

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

# Install on connected device
./gradlew installDebug
```

### Run Tests

```bash
./gradlew test
```

---

## Contributing

Contributions are welcome! Wisp is an open-source project and we appreciate help from the community.

### How to Contribute

1. **Fork** the repository
2. **Create a branch** for your feature or fix:
   ```bash
   git checkout -b feature/your-feature-name
   ```
3. **Make your changes** — follow the existing code patterns and conventions
4. **Test** your changes on a real device or emulator
5. **Commit** with a clear, descriptive message
6. **Open a pull request** against `main`

### Code Conventions

- **Kotlin** with Jetpack Compose — no XML layouts
- **NIP implementations** go in `NipXX.kt` as Kotlin `object` with static helper functions
- **Events** are created via `NostrEvent.create(privkey, pubkey, kind, content, tags)`
- **Hex encoding** uses `ByteArray.toHex()` / `String.hexToByteArray()` extensions
- **Coroutines** for all async work — `Dispatchers.Default` for CPU-bound, `Dispatchers.IO` for network
- **StateFlow** for UI state, **SharedFlow** for relay events
- Keep functions small and focused. Prefer clarity over cleverness.

### Areas Where Help is Needed

- UI/UX polish and accessibility improvements
- Additional NIP implementations
- Testing — unit tests and integration tests
- Performance profiling and optimization
- Translations and localization
- Documentation improvements

### Reporting Issues

Found a bug or have a feature request? [Open an issue](../../issues) with:

- Steps to reproduce (for bugs)
- Expected vs actual behavior
- Device and Android version
- Relevant logs from the in-app relay console (if applicable)

---

## Roadmap

### Near Term
- [ ] Local database for offline access and faster startup (Room or SQLDelight)
- [ ] Image and video previews in the feed
- [ ] Hashtag following and trending topics
- [ ] Push notifications via UnifiedPush
- [ ] Profile banner images
- [ ] Event deletion (NIP-09) UI
- [ ] Improved thread view with collapsible replies

### Medium Term
- [ ] NIP-42 relay authentication
- [ ] NIP-96 file storage integration
- [ ] Long-form content (NIP-23) reading and publishing
- [ ] Community/group support (NIP-72)
- [ ] Relay discovery and recommendations
- [ ] Advanced search with relay-side filtering
- [ ] Custom emoji packs (NIP-30)
- [ ] Media gallery per profile

### Long Term
- [ ] Marketplace integration (NIP-15)
- [ ] Tor/proxy support for enhanced privacy
- [ ] Offline-first architecture with background sync
- [ ] Widgets for Android home screen
- [ ] Wear OS companion app
- [ ] Full accessibility audit and WCAG compliance

### Ongoing
- [ ] Performance optimization and memory profiling
- [ ] Expanded NIP coverage as the protocol evolves
- [ ] UI refinements based on community feedback
- [ ] Security audits and hardening

---

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Kotlin 2.0 |
| UI Framework | Jetpack Compose (Material 3) |
| Networking | OkHttp 4 (WebSocket) |
| Image Loading | Coil 3 |
| Serialization | kotlinx.serialization |
| Cryptography | secp256k1-kmp (Schnorr), Bouncy Castle (XChaCha20), Android Security Crypto (AES-GCM) |
| Navigation | Jetpack Navigation Compose |
| Media | Media3 / ExoPlayer |
| QR Codes | ZXing |
| Build | Gradle 8.7 / AGP 8.7 |
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
