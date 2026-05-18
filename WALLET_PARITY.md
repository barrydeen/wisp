# Wisp Wallet — Cross-Platform Parity Spec

Single running doc that owns every wallet parity concern between iOS and
Android: the deterministic derivation contract, the UX flows around
wallet attach / detach, the wallet-tab UI (dashboard, settings, send /
receive sheets, transaction history), and the cross-feature touchpoints
that the wallet exposes elsewhere in the app (zap setup prompt, zap
attribution).

iOS is the reference implementation. Android (this repo) matches the
same behavior. Any change here MUST land on both platforms — agents
working in either repo should read this doc first, then keep it updated
as state evolves.

The goal: a user signing in with their nsec on either platform — fresh
install, no relay backup — gets the **same mnemonic**, the **same Spark
wallet**, and (if previously registered) the **same Lightning address**.
The wallet tab UI and every flow that leads into or out of it look the
same on both platforms.

> This doc supersedes the earlier `NSEC_WALLET_PARITY.md`,
> `WALLET_CONNECT_ANDROID_PARITY.md`, and `WALLET_PARITY_PLAN.md`
> (all deleted) — their content has been folded into the sections
> below.

---

## 1. The derivation contract (must match byte-for-byte)

This is the part that, if it diverges, breaks cross-device recovery.
There is no graceful fallback — if iOS and Android disagree on a single
bit, the user "loses" their funds when switching platforms.

### 1.1 Entropy

```
prk   = HKDF-Extract(salt = UTF8("wisp-spark-wallet-v1"), ikm = privkey)
okm   = HKDF-Expand(prk,  info = UTF8("entropy"), L = 16)
entropy = okm  // 16 bytes
```

- `privkey` is the **32-byte raw secp256k1 private key** (the bytes inside
  the nsec, after bech32 decoding). Never the nsec string itself.
- HKDF is RFC 5869 with **SHA-256**.
- The salt string is **versioned**: `wisp-spark-wallet-v1`. If we ever need
  to change the derivation, we bump to `v2` so existing v1 wallets remain
  reachable from the same nsec by trying v1 first.
- The `info` parameter is the ASCII string `entropy` (7 bytes).
- Output length is exactly 16 bytes → produces a 12-word BIP39 mnemonic.

### 1.2 Mnemonic

Standard BIP39 over the 16 bytes of entropy:

- Wordlist: **BIP39 English** (2048 words, the canonical Bitcoin wordlist).
- Checksum: first **4 bits** of SHA-256(entropy) appended to the entropy
  bits (16 bytes = 128 bits → 132 bits → 12 × 11-bit groups → 12 words).
- Words separated by single ASCII spaces, all lowercase.

Both platforms should validate the mnemonic with the same checksum logic
before persisting.

### 1.3 Reference values for testing

Both platforms must produce these exact vectors. If either diverges,
the derivation contract has broken — go back to §1.1 and audit the
salt/info encoding, HKDF implementation, or BIP39 checksum logic.

Generated on Android via `SparkDerivationTest.kt` against
`Keys.deriveSparkEntropy` + `SparkRepository.entropyToMnemonic`. iOS
should reproduce these by feeding the same privkeys through
`SparkWallet.deriveSparkEntropy` + `Bip39.mnemonic(fromEntropy:)`.

```
Vector 1:
  privkey (hex):  0101010101010101010101010101010101010101010101010101010101010101
  entropy (hex):  75119b77539f7c55289cfd67c6f85ee2
  mnemonic:       insect mimic tape poet water clever pen panic guitar daughter bless session

Vector 2:
  privkey (hex):  0202020202020202020202020202020202020202020202020202020202020202
  entropy (hex):  8d7fd646909ed7facc43212f4705d573
  mnemonic:       miracle wrong museum cancel uniform word country goddess consider deal inspire trade
```

Android regression: `app/src/test/kotlin/com/wisp/app/nostr/SparkDerivationTest.kt`
runs both vectors on every `:app:testDebugUnitTest` invocation. iOS
should add an equivalent XCTest with the same hardcoded expectations.

---

## 2. UX flow parity

### 2.1 Account create (fresh nsec)

1. Generate new keypair.
2. Show phase string equivalent to **"creating new wallet"** while
   `WALLET_SETUP` is in progress.
3. Derive mnemonic silently from the privkey (algorithm above). Persist
   it locally with flags:
   - `spark_mnemonic = <12 words>`
   - `spark_is_default = true`
   - `seed_backup_acked = true`  (suppress the backup nag — the nsec
     itself is the backup)
4. Wait for the Spark SDK to connect (with a 15 s timeout).
5. Auto-register a Lightning address. Up to **3 attempts**:
   - Generate a candidate username (algorithm in §2.5).
   - `checkLightningAddressAvailable(username)`.
   - If available, `registerLightningAddress(username, description = "Wisp wallet")`.
   - Break on success.
6. Set wallet mode to SPARK.
7. Continue with the rest of onboarding (relay list publish, profile
   publish — the profile's `lud16` field is populated from the registered
   address if any).

If the Spark connect times out, the user still gets the mnemonic locally
and the address registration is just skipped — they can retry from the
Wallet tab later.

### 2.2 Sign in with existing nsec on a new device

**Currently: deferred wallet attachment** (both platforms agree on this).

- At login: derive nothing automatically, do not auto-connect Spark.
- On first visit to the Wallet tab: if there's no `spark_mnemonic`
  locally and the user has an nsec and `skipAutoCreate` is not set,
  expose the **"Use my default wallet"** entry point inside the Spark
  sub-screen (see §2.6). The text reads *"Derived from your Nostr key —
  no extra backup needed."*
- Tapping the entry point: re-derive (deterministic), persist, connect
  Spark. The SDK loads the existing wallet (balance + registered address
  come back automatically — same mnemonic = same wallet).
- After connect, fetch and display the existing Lightning address via
  `getLightningAddress()`.

Rationale: avoids surprising network/SDK work at login; user opts in by
visiting the wallet tab. Trade-off: a returning user briefly sees an
empty wallet state until they tap in. Acceptable.

### 2.3 Disconnect ("Switch Wallet")

Default (nsec-derived) wallets get a button labeled **"Switch Wallet"**
(NOT "Delete Wallet"). Body copy:

> *"Disconnect this wallet so you can use your default wallet or restore
> a different one. Your funds stay safe — they're tied to your Nostr key
> and the Spark wallet remains active."*

Tapping it:
- Clears local mnemonic + flags (`spark_mnemonic`, `spark_is_default`,
  `seed_backup_acked`).
- Sets `walletMode = NONE`.
- Sets `skipAutoCreate = true` so the app does NOT silently re-derive
  on next launch.
- Does **NOT** delete the Lightning address registration on Spark — the
  address remains active and routable even though the local wallet is
  disconnected.
- Clears UI state (balance, status, connection string).

To reconnect: same "Use my default wallet" entry point (it ignores
`skipAutoCreate` because the user is explicitly tapping it). The flag
exists to prevent silent recreation, not to lock the user out.

The settings section that hosts this button is titled **"Disconnect
Wallet"** (not "Danger Zone"). Inside, the button label varies by mode:

- NWC: `Disconnect wallet`
- Default Spark (nsec-derived): `Switch to a different wallet`
- Custom Spark: `Delete wallet`

### 2.4 Custom (non-default) wallet deletion

Out of scope for derivation parity, but for reference: custom Spark
wallets (where the user provided their own mnemonic) show a **"Delete
Wallet"** button that requires a typed `DELETE` confirmation. Same on
both platforms.

### 2.5 Username generation

Format: `{color}{animal}{NN}` — all lowercase, no separator, where `NN`
is a two-digit number in `[10, 99]`. Pick `color` and `animal` uniformly
at random from a cryptographically-strong RNG (SecureRandom on Android,
`SystemRandomNumberGenerator` on iOS).

Note: cross-platform username **algorithmic** parity isn't strictly
required — each user only does account-create on one platform, and a
user returning to a different platform sees their existing address via
`getLightningAddress()` regardless of how it was generated. The
wordlists are aligned anyway so fresh creates "feel" consistent across
platforms.

Wordlists (must match exactly):

```
COLORS (28 entries):
  blue, red, green, gold, silver, amber, coral, violet, jade, ruby,
  teal, cyan, crimson, ivory, bronze, copper, indigo, scarlet, azure,
  pearl, onyx, sage, rose, slate, plum, lime, rust, mint

ANIMALS (38 entries):
  panda, wolf, fox, falcon, otter, raven, tiger, eagle, dolphin, hawk,
  lynx, bear, owl, cobra, bison, crane, gecko, heron, koala, lemur,
  moose, newt, ocelot, puma, quail, robin, shark, swift, viper, wren,
  yak, zebra, badger, cougar, drake, finch, gopher, hound
```

Total namespace before collision: 28 × 38 × 90 = 95,760. Combined with
the 3-attempt retry loop on `checkLightningAddressAvailable`, collisions
should be rare.

### 2.6 Wallet Connect screen layout

The Wallet Connect entry point is a **two-tier** flow on both platforms.

```
Connect a Wallet              (top-level mode picker — Screen 1)
├── Spark wallet              → opens Screen 2
│   ├── Use my default wallet (gated on hasKeypair())
│   ├── Create new wallet
│   ├── Restore from seed phrase
│   └── Restore from relays
└── Nostr Wallet Connect      → existing NWC paste-string flow
```

The nested structure exists because:

- **Use my default wallet** *is* a Spark wallet — just one with a
  deterministically derived seed. It belongs visually next to the other
  Spark options.
- **NWC** is a genuinely different wallet type (external provider, no
  seed under our control). It deserves equal top-level billing.
- The user only has to make **one decision** at the top: self-custody
  embedded (Spark) vs external (NWC). The seed-source choice is a
  follow-up.

The trade-off is one extra tap to reach the most-common entry point.
Acceptable — the alternative is a flat mode picker where the default
wallet option lives under a button labeled *Create a New Wallet*, which
actively misleads users trying to recover an existing wallet.

#### Screen 1: Top-level mode picker

Centered column, generous vertical spacing:

| Element | Content |
|---|---|
| Logo | `bolt.circle.fill` (or Material equivalent), 52pt, theme zap color (warm orange) |
| Title | **"Connect a wallet"** (title2 weight bold) |
| Subtitle | *"Send and receive Lightning payments, and zap anyone on Nostr."* (subhead, secondary, centred, 2 lines) |
| *spacer* | flexible |
| Spark row | (see below) |
| NWC row | (see below) |

The two rows live at the **bottom** with the logo + copy stack pushed
up by a flexible spacer.

**Spark row:**

| | |
|---|---|
| Leading icon | Spark logo (28×28), theme zap color |
| Title | **"Spark wallet"** (subhead, semibold) |
| Subtitle | *"Self-custody, embedded. Use your default wallet or restore from seed/relays."* (caption, secondary) |
| Trailing | chevron right (12pt, tertiary) |
| Background | subtle surface variant, 14pt corner radius |
| Tap | navigate to Screen 2 |

**NWC row:**

| | |
|---|---|
| Leading icon | NWC logo (32×32) |
| Title | **"Nostr Wallet Connect"** (subhead, semibold) |
| Subtitle | *"Paste a connection string from Alby, Zeus, Rizful, Minibits, etc."* (caption, secondary) |
| Trailing | chevron right |
| Tap | existing NWC setup flow |

> Do not name competitor apps that aren't in the list above. The list
> is intentional.

#### Screen 2: Spark sub-screen

Reached by tapping the Spark row.

**Header:**

| Element | Content |
|---|---|
| Trailing toolbar | "Close" button — dismisses the entire wallet setup, returns to mode picker (or the empty wallet state if user came from there) |
| Logo | "Spark + Breez" combined logo, 22pt tall |
| Subtitle | *"Self-custodial Lightning, powered by Spark and Breez."* (subhead, secondary, centred, 2 lines) |

**Option rows** (12pt vertical spacing):

1. **Use my default wallet** — *gated on `hasKeypair()`*. Hidden for
   watch-only and remote-signer-only sessions.
   - Icon: `key.fill`, 22pt, theme zap color
   - Title: *"Use my default wallet"*
   - Subtitle: *"Derived from your Nostr key — no extra backup needed."*
   - Tap: derive deterministically from privkey (§1), persist mnemonic
     + `spark_is_default = true`, clear `skipAutoCreate`, connect Spark,
     dismiss to dashboard.

2. **Create new wallet** — produces a non-default Spark wallet.
   - Icon: `plus.circle.fill` (or Material `AddCircle`)
   - Title: *"Create new wallet"*
   - Subtitle: *"Generate a fresh 12-word seed phrase"*
   - Tap: generate BIP39 mnemonic, show words on a confirm screen
     requiring the user to acknowledge backup, then connect Spark.
   - Must NOT set `spark_is_default`. This is the only path that
     genuinely needs the seed-backup nag.

3. **Restore from seed phrase**
   - Icon: `arrow.uturn.backward.circle.fill` (or Material `Restore`)
   - Title: *"Restore from seed phrase"*
   - Subtitle: *"12 words from a Spark-based wallet"*
   - Tap: existing 12-word entry / validate / connect flow.

4. **Restore from relays**
   - Icon: `icloud.and.arrow.down.fill` (or Material `CloudDownload`)
   - Title: *"Restore from relays"*
   - Subtitle: *"Encrypted backup from another device"*
   - Tap: existing NIP-78 encrypted-backup search/restore flow.

A back button in the toolbar's leading position returns to Screen 1
and resets any sub-mode state.

#### Copy reference (string resources)

Both platforms use these strings verbatim. Localize together if/when
localization happens.

```
wallet_connect_title             = "Connect a wallet"
wallet_connect_subtitle          = "Send and receive Lightning payments, and zap anyone on Nostr."

wallet_spark_title               = "Spark wallet"
wallet_spark_subtitle            = "Self-custody, embedded. Use your default wallet or restore from seed/relays."

wallet_nwc_title                 = "Nostr Wallet Connect"
wallet_nwc_subtitle              = "Paste a connection string from Alby, Zeus, Rizful, Minibits, etc."

spark_setup_subtitle             = "Self-custodial Lightning, powered by Spark and Breez."

wallet_use_default               = "Use my default wallet"
wallet_default_subtitle          = "Derived from your Nostr key — no extra backup needed."

wallet_create_title              = "Create new wallet"
wallet_create_subtitle           = "Generate a fresh 12-word seed phrase"

wallet_restore_seed_title        = "Restore from seed phrase"
wallet_restore_seed_subtitle     = "12 words from a Spark-based wallet"

wallet_restore_relays_title      = "Restore from relays"
wallet_restore_relays_subtitle   = "Encrypted backup from another device"
```

---

## 3. Wallet Dashboard

### 3.1 Universal wallet icon

Use this icon **everywhere "wallet" is referenced** — bottom nav,
sidebar drawer, settings, "Set up wallet" prompts, navigation
breadcrumbs. The icon is fixed and **does not change based on the
user's zap-icon preference**: the zap-icon setting controls only the
lightning/zap glyph used on post action bars and elsewhere; the wallet
icon stays the credit-card silhouette across themes and zap-icon
variants.

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24">
  <path fill="currentColor" fill-rule="evenodd" d="M3 7.25C3 5.45507 4.45507 4 6.25 4h11.5C19.5449 4 21 5.45507 21 7.25v9.5C21 18.5449 19.5449 20 17.75 20H6.25C4.45507 20 3 18.5449 3 16.75v-9.5zM4.5 9v7.75c0 .9665.7835 1.75 1.75 1.75h11.5c.9665 0 1.75-.7835 1.75-1.75V9H4.5z"/>
</svg>
```

`currentColor` so it inherits whatever tint the theme/state hands it
(selected = accent, unselected = secondary). Drop into
`res/drawable/ic_wallet.xml` on Android as a Vector Drawable
(Asset Studio handles the SVG conversion).

### 3.2 Balance card

- **Centered balance number, large rounded font.** Smooth digit morph
  on value change (Compose: `AnimatedContent` keyed on the sat value
  with `slideInVertically` + `fadeIn`). Tap the balance to toggle the
  `* * * * *` hidden state.
- **Balance unit pill picker, not a dropdown.** Three chips:
  `1,000 sats`, `₿ 1,000`, `⚡ 1,000`. Selected chip uses accent stroke
  + 10%-opacity accent fill; unselected uses secondary stroke. **The
  bolt glyph is a Material Icon (`bolt`), not the U+26A1 emoji** — the
  emoji always renders yellow regardless of theme tint, breaking the
  chip's selected/unselected contrast.
- **Pulse animation while the displayed value isn't trustworthy yet.**
  Pulse opacity 1.0 ↔ 0.35, 0.9 s ease-in-out, repeating. Active
  conditions: *not yet connected* OR *no balance has landed yet*.
  Stops the moment a real balance value arrives. **Do not also gate
  the pulse on a "fetch in flight" flag** — that causes the balance
  card to keep oscillating during routine refreshes, and on iOS the
  implicit animation context broadened enough to visibly shift the
  balance's frame between centered and off-center. We tried it;
  reverted.

### 3.3 Balance load behavior

For Spark (NWC is fine as-is — it's a single relay round-trip):

- **First fetch after connect must be a synced fetch.** Read the SDK
  with the synced flag set (`ensureSynced: true` on iOS Breez SDK; the
  Android Breez SDK has the equivalent). Returning the SDK's on-disk
  cache reads back tens of seconds of stale balance from the previous
  session, until the SDK's internal `.synced` event eventually fires a
  follow-up. Force the sync so the first balance shown is current.
- **Reactive refresh on `.synced` event** can stay non-synced — by
  definition that path runs *after* a sync just landed.
- **`disconnect()` does not clear display state on its own.** The
  connect flows (`connectNwc`, `connectSpark`) need to explicitly
  clear the previous wallet's `balanceMsats`, `nwcNodeAlias`,
  `nwcMethods`, `lightningAddress`, and `transactions` before wiring
  up the new wallet. Otherwise pasting a new NWC URI renders the old
  node's name and a stale balance for several seconds — until the
  user leaves the wallet tab and comes back, which re-fires
  `startIfConfigured` and pulls fresh metadata. After the explicit
  clear, also schedule `refreshNwcNodeAlias` / `refreshLightningAddress`
  immediately so the dashboard fills in without a tab round-trip.
- The app-launch reconnect path goes through the equivalent of
  `switchToMode` and **intentionally keeps cached values** so the user
  sees their last-known balance instantly on a warm cold-launch.

### 3.4 Top bar

Wallet-mode logo on the left (Spark+Breez logo for Spark, NWC logo for
NWC), refresh icon + settings gear on the right. Refresh shows an
inline spinner while a fetch is in flight.

### 3.5 Banners

- **Seed-backup banner** below the top bar, Spark-only, hides once the
  user acknowledges. Tap routes to the recovery phrase screen.
  - For **default wallets** (`isDefaultWallet == true`): render a
    *welcome* card instead of a warning — blue/accent tint, key icon,
    text *"Your default wallet is secured by your key. Derived from
    your Nostr key — restores on any device when you sign in. Tap to
    also save your seed phrase as a backup."*
  - For **custom wallets** (`isDefaultWallet == false`): render the
    existing warning — amber/zap tint, alert icon, text *"Back up your
    recovery phrase. Tap to view and save your seed words."*
- **Reconnecting banner** between the balance and action row whenever
  `isConnected == false` post-startup. Subtle amber/secondary styling.

### 3.6 Send / Receive action row

Two orange filled circles, side by side, centered horizontally.
Up-arrow icon for Send, down-arrow for Receive, caption text below
each.

### 3.7 Recent transactions

Anchored to the bottom of the screen with a "more" affordance to push
the full history.

---

## 4. Wallet Settings

Match this section breakdown exactly on both platforms.

### 4.1 Section ordering

1. **Lightning Address** (Spark only)
2. **Wallet Connection** (NWC only)
3. **Wallet Info** (Spark only)
4. **Display**
5. **Security** (Spark only)
6. **Disclaimer card**
7. **Disconnect Wallet** (renamed from "Danger Zone")
8. **Powered-by footer**

### 4.2 Lightning Address (Spark only)

Current address with a copy icon, plus Change / Remove buttons; or a
"Set up lightning address" CTA when none.

### 4.3 Wallet Connection (NWC only)

Collapsed header with NWC logo + node alias **and nothing else**. Do
not put the lud16 as a subtitle under the alias — it's already shown
as a "Lightning address" row in the expanded details, and showing it
twice in adjacent rows is just noise. Tap to expand the details panel:
Service pubkey, Client pubkey, Relay(s), Encryption, Lightning
address, Supported methods chips.

### 4.4 Wallet Info (Spark only)

Collapsed header with the Spark + Breez logo; tap to expand
(Wallet ID, Network, SDK version).

### 4.5 Display

Hide balance toggle + balance-unit picker.

### 4.6 Security (Spark only)

Recovery phrase nav row (with "Not acknowledged" subtitle in accent
color until acked) + Relay backup state (idle / publishing / success /
error).

### 4.7 Disclaimer card

"Wisp never holds user funds…" — secondary surface, info icon, single
paragraph.

### 4.8 Disconnect Wallet (formerly "Danger Zone")

The button label and confirm copy vary by wallet type:

| Mode | Button | Confirm body |
|---|---|---|
| NWC | `Disconnect wallet` | "Your NWC connection will be removed. You can reconnect at any time." |
| Default Spark (nsec-derived) | `Switch to a different wallet` | "Disconnect this wallet so you can use a different one. Your funds stay safe — they're tied to your Nostr key and the wallet remains active." |
| Custom Spark | `Delete wallet` | "This will permanently delete your Spark wallet from this device. Make sure you have your recovery phrase before proceeding." |

Footer caption below the button changes accordingly so the user knows
what the action does *before* tapping.

### 4.9 Powered-by footer

Spark+Breez logo + SDK version, or NWC logo, desaturated to ~55%
opacity.

### 4.10 Copy-icon rule

Only show a copy icon on rows whose value is genuinely useful to copy
— pubkeys, relay URLs, lightning address, wallet ID. **Do not show a
copy icon on**: Encryption ("NIP-44" / "NIP-04"), Network ("Mainnet"),
SDK version. Those are display-only metadata; a copy button next to
them adds noise and is a misleading tap target.

---

## 5. Send / Receive sheets

### 5.1 Send

Single multipurpose input that auto-detects bolt11, lightning address,
LNURL, and `bitcoin:` URIs. Show parsed details (amount, recipient,
memo) before the confirm button. Inline error if the input is
unparseable.

### 5.2 Receive

Amount + memo fields, "Generate invoice" CTA, then a QR code with the
invoice underneath, copy-to-clipboard tap on the invoice text. Live
"Waiting for payment…" indicator that flips to a success animation
when the payment lands.

Both sheets dismiss-on-success with a brief confirmation toast.

---

## 6. Transaction history

### 6.1 Row layout

Per row:
- **Counterparty avatar** in a circle (40 dp on iOS) when the
  counterparty's nostr identity is known; otherwise an up-arrow
  (sent) in a red-tinted circle or a down-arrow (received) in a
  green-tinted circle.
- Two-line layout: title (display name when resolved, otherwise the
  bolt11 memo or "Sent" / "Received") with date below; amount + fee
  on the right (red `-` prefix for sent, green `+` for received).
- Tap a row to expand inline details (memo, payment hash, settled
  timestamp, full fee breakdown). No separate detail screen.

### 6.2 Counterparty resolution

For an `incoming` row: prefer `tx.counterpartyPubkey` if the wallet
backend set it (currently always null), then fall back to a
`paymentHash → senderPubkey` map populated by the kind-9735 ingest
path (see §6.3).

For an `outgoing` row: same fallback, but to the `paymentHash →
recipientPubkey` map populated by the zap-send flow at the moment the
zap invoice is fetched.

### 6.3 Incoming zap attribution

iOS hooks attribution recording into the existing
`NotificationsViewModel` kind-9735 ingest paths (cold backfill + the
live `notif` subscription + the DM-relay zap subscription). No
parallel subscription is opened — those filters already match
`#p = activePubkey`, which is exactly the set of receipts that could
correspond to a wallet transaction landing on this account.

For each kind-9735 receipt the active user receives:

1. Pull the zapper's pubkey from the embedded kind-9734 description
   (NIP-57 receipt format).
2. Decode the bolt11 tag for its payment hash.
3. Persist `paymentHash → senderPubkey` to a sender-attribution map
   (UserDefaults on iOS; SharedPreferences or DataStore on Android),
   500-entry FIFO cap matching the recipient map.

No-op when either piece is missing — receipts produced by remote LSPs
occasionally arrive with malformed descriptions or non-standard
bolt11.

The transaction row's counterparty resolution then reads from the
direction-appropriate map.

---

## 7. "Set up wallet" cross-feature prompt

When the user taps Zap on a post and no wallet is configured, surface
a confirmation dialog (not a full sheet):

- **Title**: "Send Money" if fiat mode is on, "Send Zap" otherwise.
- **Message**: "Set up a wallet to send {money|zaps} to other users."
- **Buttons**: "Set Up Wallet" (primary) → switches to the wallet
  tab; "Cancel".

The wallet tab switch should be cross-component — fire whatever the
platform's equivalent of an `openWalletTab` notification is so the
prompt can live anywhere in the app and still hand off correctly.

---

## 8. Visual detail rules

- Balance digits use a digit-morph transition (Compose:
  `AnimatedContent` with vertical slide on the digit text) so
  individual digits move rather than the whole number cross-fading.
- Spark + Breez and NWC logos render at full color in the top bar,
  desaturated to ~55% opacity in the powered-by footer.
- All long monospaced values (pubkeys, wallet IDs) truncate in the
  middle (`…`), not at the end.
- Section headers above each settings group are caption-sized,
  secondary-tinted, semibold, with 4 dp of horizontal padding inside
  the surface card.
- Buttons that style their own label (custom backgrounds, custom
  foreground colors) must explicitly opt out of the system tint —
  `.buttonStyle(.plain)` on iOS, the equivalent on Compose. Without
  it the system overrides the inner Text's foreground color and the
  button reads as "system blue" on a custom-styled pill, breaking
  the wallet's orange/accent color palette.

---

## 9. Locked decisions (do not diverge without updating this doc)

| Decision | Choice | Why |
|---|---|---|
| Auto-connect Spark on sign-in? | **No** — deferred until Wallet tab | Avoid network surprise at login; both platforms behave the same. |
| Username algorithm parity | Match wordlists + format; randomness need not match | A given user only generates once; existing addresses are fetched from Spark on either platform. |
| Re-derivation after "Switch Wallet" | Manual only (set `skipAutoCreate`) | Avoid the "I disconnected, why is it back?" footgun. |
| Wallet Connect hierarchy | Two-tier (Spark / NWC at top, sub-options under Spark) | See §2.6 rationale. |
| Default wallet labeling | **"Use my default wallet"** / *"default wallet"* — never "Wisp wallet" or "wisp wallet" | Avoids implying a third wallet type alongside Spark / NWC. |
| Disconnect section header | **"Disconnect Wallet"** (not "Danger Zone") | "Switch Wallet" for default wallets isn't destructive; "Danger Zone" misframes it. |
| Wallet icon | Fixed credit-card silhouette (§3.1) — does NOT change with zap-icon preference | Wallet ≠ zap glyph; the two settings control different visuals. |
| Balance card pulse trigger | *not yet connected* OR *no balance has landed yet* — NOT a generic "fetch in flight" flag | Routine refreshes shouldn't oscillate the card; we tried gating on the fetch flag and reverted. |
| First Spark balance fetch | **Synced** (`ensureSynced: true`); reactive refreshes on `.synced` can be non-synced | Cached SDK reads serve stale data on a cold session. |
| Copy icon scope | Only on values worth copying (pubkeys, relays, lud16, wallet ID) — not on Encryption / Network / SDK version | Misleading tap target on display-only metadata. |

---

## 10. Platform implementation references

### 10.1 iOS (`wisp-ios` repo)

| Concern | File | Symbol |
|---|---|---|
| HKDF entropy derivation | `SparkWallet.swift` | `deriveSparkEntropy(privkey:)` |
| BIP39 mnemonic from entropy | `Bip39.swift` | `mnemonic(fromEntropy:)` |
| Generate & persist default wallet | `SparkWallet.swift` | `generateDefaultFromPrivkey(_:)` |
| Default-wallet flag | `SparkWallet.swift` | `isDefaultWallet()` |
| `canUseDefaultWallet` getter | `WalletStore.swift` | `canUseDefaultWallet` |
| Tap-to-derive entry point | `WalletStore.swift` | `useDefaultWallet()` |
| `skipAutoCreate` flag setter | `WalletStore.swift` | `setSkipAutoCreate(for:)` |
| Auto-create during signup | `SignUpViewModel.swift` | `startWalletSetup()` |
| Auto-register Lightning address | `SignUpViewModel.swift` | `registerSparkLightningAddressIfReady()` |
| Top-level mode picker | `WalletView.swift` | `WalletModeSelectionView` |
| Spark sub-screen | `SparkSetupView.swift` | `pickSection` + `useDefault()` |
| Switch Wallet button + alert | `wisp/WalletSettingsView.swift` | `dangerSection` + `showSwitchAlert` |
| Welcome banner on dashboard | `WalletView.swift` | `walletWelcomeCard` |
| Settings layout | `wisp/WalletSettingsView.swift` | (whole view) |
| Send sheet | `SendInvoiceSheet` | in `WalletView.swift` |
| Receive sheet | `ReceiveInvoiceSheet` | in `WalletView.swift` |
| Transaction row | `TransactionRowView` | (transaction history) |
| Zap attribution | `NotificationsViewModel.swift` | kind-9735 ingest hooks |

### 10.2 Android (this repo)

| Concern | File | Lines |
|---|---|---|
| HKDF entropy derivation | `app/src/main/kotlin/com/wisp/app/nostr/Keys.kt` | 72–86 |
| BIP39 mnemonic generation | `app/src/main/kotlin/com/wisp/app/repo/SparkRepository.kt` | 150–170 |
| Generate & persist default wallet | `app/src/main/kotlin/com/wisp/app/repo/SparkRepository.kt` | 130–140 |
| Clear mnemonic on disconnect | `app/src/main/kotlin/com/wisp/app/repo/SparkRepository.kt` | 213–221 |
| Auto-create during onboarding | `app/src/main/kotlin/com/wisp/app/viewmodel/OnboardingViewModel.kt` | 172–182 |
| Auto-register Lightning address | `app/src/main/kotlin/com/wisp/app/viewmodel/OnboardingViewModel.kt` | 233–258 |
| `generateUsername()` | `app/src/main/kotlin/com/wisp/app/viewmodel/OnboardingViewModel.kt` | 106–127 |
| "Use my default wallet" trigger | `app/src/main/kotlin/com/wisp/app/viewmodel/WalletViewModel.kt` | 390–425 (`maybeAutoCreateDefaultWallet()`) |
| Wallet Connect screen | `app/src/main/kotlin/com/wisp/app/ui/screen/WalletScreen.kt` | top-level layout — needs restructure per §11.2 |
| `deleteWallet()` (Switch Wallet handler) | `app/src/main/kotlin/com/wisp/app/viewmodel/WalletViewModel.kt` | 740–791 |
| `Switch Wallet` confirm screen | `app/src/main/kotlin/com/wisp/app/ui/screen/WalletScreen.kt` | 3399–3500 |
| `fetchLightningAddressFromWallet()` | `app/src/main/kotlin/com/wisp/app/viewmodel/WalletViewModel.kt` | 880–892 |

String resources to keep aligned with the §2.6 copy reference:
`wallet_use_default`, `wallet_default_subtitle`, `wallet_connect_title`,
`wallet_connect_subtitle`, `wallet_spark_title`, `wallet_spark_subtitle`,
`wallet_nwc_title`, `wallet_nwc_subtitle`, `spark_setup_subtitle`,
`wallet_create_title`, `wallet_create_subtitle`, `wallet_restore_seed_title`,
`wallet_restore_seed_subtitle`, `wallet_restore_relays_title`,
`wallet_restore_relays_subtitle`.

---

## 11. Per-platform port checklists

### 11.1 iOS

Derivation + flow:

- [x] HKDF salt is the **literal string** `"wisp-spark-wallet-v1"` UTF-8
      encoded. (`SparkWallet.deriveSparkEntropy`)
- [x] HKDF `info` is the literal string `"entropy"`.
- [x] Output length is exactly **16 bytes**.
- [x] BIP39 mnemonic uses **English** wordlist and **4-bit** checksum.
      (`Bip39.entropyToMnemonic`)
- [x] Mnemonic words are joined with single ASCII spaces, all lowercase.
- [x] Local persistence flag set on first create:
      `spark_is_default_<pubkey> = true` in UserDefaults.
      (`SparkWallet.generateDefaultFromPrivkey`)
- [x] Sign-in flow does **not** auto-derive or auto-connect.
      (`WalletStore.startIfConfigured` is a no-op when `mode == nil`.)
- [x] Wallet Connect screen is two-tier per §2.6:
      `WalletModeSelectionView` (Spark / NWC) → `SparkSetupView`
      (Use default / Create / Restore seed / Restore relays).
- [x] "Use my default wallet" row gated on `canUseDefaultWallet`,
      derives + connects on tap.
- [x] Disconnect button on default wallet says "Switch to a different
      wallet"; section header is "Disconnect Wallet" (not "Danger Zone").
- [x] Switch Wallet sets `wallet_skip_auto_create_<pubkey>` and does
      **not** call `deleteLightningAddress()`.
- [ ] Username generator uses the exact 28 colors × 38 animals × `[10,99]`
      space and a CSPRNG. (Currently uses Breez default generator.)
- [ ] Test vectors in §1.3 produce identical mnemonics on iOS.
      (Android values now committed; iOS just needs to verify and lock
      them in an XCTest.)

Dashboard / settings UI (assumed to already match — verify on next
audit pass).

### 11.2 Android

Android's current Wallet Connect screen flattens the two tiers into a
single screen with three top-level buttons:

```
Create a New Wallet       (top-level)  ← misleading: this is also where Use Default lives
Restore Existing Wallet   (top-level)
Nostr Wallet Connect      (top-level)
```

The "Use my default wallet" entry point — which calls
`maybeAutoCreateDefaultWallet()` — is only reachable via the
misleading *Create a New Wallet* path. Users trying to recover an
existing wallet have no obvious entry point.

**Restructure per §2.6**:

- [ ] Replace the top-level layout with the two-row picker (Spark /
      NWC) described in §2.6 Screen 1.
- [ ] Add a Spark sub-screen matching §2.6 Screen 2 with four option
      rows (or three when `hasKeypair() == false`).
- [ ] Move the `maybeAutoCreateDefaultWallet()` entry point to the new
      "Use my default wallet" row at the top of the Spark sub-screen.
      Ignore `skipAutoCreate` on explicit tap.
- [ ] Add the string resources from §2.6 to `strings.xml`.
- [ ] Verify the existing flows still wire through:
      - Create new wallet → existing BIP39-generate + confirm-backup flow
      - Restore from seed phrase → existing 12-word entry flow
      - Restore from relays → existing NIP-78 backup search flow
      - Nostr Wallet Connect → existing NWC paste-string flow
- [ ] Disconnect flow on a default wallet says **"Switch Wallet"** and
      the body copy refers to the wallet as your *default wallet* —
      never "Wisp wallet" or "wisp wallet".
- [ ] Settings section header renamed from "Danger Zone" to
      **"Disconnect Wallet"** (per §4.8).
- [ ] Dashboard welcome banner for default wallets per §3.5 (blue/accent
      tint, key icon, "secured by your key" copy) — separate from the
      existing amber warning banner for custom wallets.

Dashboard / settings UI items from §3–§8 to spot-check against iOS:

- [ ] Universal wallet icon (§3.1) used everywhere wallet is referenced.
- [ ] Balance card behavior (§3.2): pill picker, digit morph, pulse
      conditions exactly as listed.
- [ ] First-fetch-after-connect uses synced flag (§3.3).
- [ ] Settings sections in the order from §4.1, with copy-icon rules
      from §4.10.

---

## 12. Open items & cross-platform tests

### 12.1 Open

- **Test vectors in §1.3 are now LOCKED on Android.** iOS just needs to
  reproduce them in an XCTest and check the box in §11.1.
- **Sign-in auto-connect decision** is currently "no". If we want to
  revisit (e.g. fetch and show the Lightning address on the profile
  immediately on login), it needs to land on both platforms in lockstep.
- **NIP-78 relay backup** is a separate flow (custom mnemonics backed
  up to relays). Out of scope here, but worth documenting eventually so
  the two restore paths — re-derive vs. relay-restore — don't fight
  each other in the UI.
- **Username generator parity** — iOS still uses the Breez-default
  generator; needs to be rewritten to the §2.5 wordlist for parity.

### 12.2 Cross-platform manual test

Run this after any Android or iOS change to derivation or the wallet
connect screens:

1. Fresh install platform A, log in with an nsec that has an existing
   Lightning address registered on Breez via platform B.
2. Open Wallet tab → *Connect a Wallet* → **Spark wallet** → **Use my
   default wallet**.
3. Wait for connect.
4. Verify the dashboard shows the **same Lightning address** and the
   **same balance** as platform B displays for that nsec.
5. Repeat in reverse (B → A).

If step 4 or 5 fails, the derivation contract has drifted — go back to
§1 and run the test vectors on both platforms to find the divergence.
