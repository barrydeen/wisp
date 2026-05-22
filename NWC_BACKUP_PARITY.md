# NWC Connection Backup — Cross-Platform Parity

Companion to `WALLET_PARITY.md`. This doc captures the contract for
backing up + restoring the Nostr Wallet Connect (NWC) connection
string via a NIP-78 application-specific data event so the same
connection follows the user's nostr key across devices and
platforms.

iOS lands this in `barrydeen/wisp-ios:feat/wallet-nwc-backup`
(PR #167). Android should mirror.

---

## 1. Motivation

The NWC connection string is the credential that authorises Wisp to
pay invoices from the user's lightning wallet. Without a sync
mechanism it's device-local: a user with two devices has to paste
the NWC URI on each one, and re-paste after every reinstall. The
Spark wallet already syncs (the BIP-39 seed is the source of truth);
NWC users were second-class.

The fix: publish an encrypted backup of the NWC URI to relays under
the user's own pubkey, replaceable via NIP-78. The backup follows
the same trust posture as the Spark seed backup — the connection
string is NIP-44 encrypted to the user's own pubkey, so only the
holder of the private key can decrypt.

---

## 2. The contract (locked values)

### 2.1 Event shape

| Field | Value |
| --- | --- |
| `kind` | `30078` (NIP-78 application-specific data) |
| `d` tag | **`nwc-wallet-backup`** (flat, NOT app-namespaced) |
| `client` tag | `["client", "Wisp"]` — optional but recommended |
| `content` | NIP-44 v2 encrypted ciphertext (see §2.2) |

The `d` tag is **flat / non-namespaced** deliberately. iOS + Android
both write to and read from the same tag so a backup published from
either client is restorable from the other without coordination.
**Do not prefix the tag with `wisp-` or platform-specific markers.**

### 2.2 Encryption

NIP-44 v2 self-to-self:

```
key  = nip44.v2.utils.getConversationKey(privateKey, ownPubkey)
ct   = nip44.v2.encrypt(plaintext, key)
event.content = ct
```

Both sender and recipient pubkeys in the conversation key are the
user's own. Plaintext is the raw NWC URI (no JSON wrapping, no
metadata fields):

```
nostr+walletconnect://<wallet-pubkey>?relay=<relay-url>&secret=<...>
```

### 2.3 Replaceable semantics

Per NIP-78 / addressable-event rules, relays drop older events with
the same `(pubkey, kind, d-tag)` tuple in favor of the newest. So
reconnecting / switching the NWC wallet republishes under the same
`d` tag — the prior backup is automatically replaced.

There is **no NIP-09 deletion event** published on disconnect. The
old encrypted ciphertext stays on relays until a new connect
overwrites it. Acceptable trade-off: the ciphertext still requires
the user's private key to decrypt, and the cost of an extra publish
on every disconnect outweighs the benefit (relays sweep stale
events on their own cadence).

---

## 3. Triggers

### 3.1 Publish

Publish the backup on every successful NWC connect, off the connect
path (`Task { … }` / coroutine). Failures are non-fatal and silent
— the connection works locally even if the publish fails; we'll
retry on the next connect.

**Gate**: every account is eligible today. The iOS implementation
keeps an `isRelayBackupSupported` hook as a forward-extensibility
point in case a future signer integration ever needs to opt out
of the publish path; for now the hook always returns true.

### 3.2 Restore

The NWC setup screen searches relays on open. If a backup event
exists for the active pubkey + `d` tag, surface a one-tap
"Restore previous wallet" affordance that:

1. Fetches the event.
2. NIP-44 decrypts the content to the URI.
3. Wires the connection via the existing NWC connect flow.

---

## 4. iOS implementation references

| Concern | File | Symbol |
| --- | --- | --- |
| Backup publish + restore primitives | `NwcBackup.swift` | `dTag`, `publish(…)`, `restore(…)` |
| Triggered on connect | `WalletStore.swift` | `connect(…)` → `Task { publishNwcBackup() }` |
| Surfaced on setup screen | `NwcSetupView.swift` | "Restore previous wallet" row |
| Forward-extensibility gate (currently no-op) | `WalletStore.swift` | `isRelayBackupSupported` |

Search hint on iOS: `grep -n "publishNwcBackup\|NwcBackup\." *.swift`.

---

## 5. Android port checklist

Mirror §2 / §3 exactly. Specific items the Android agent should
action:

- [ ] Event `kind = 30078`, `d`-tag = `"nwc-wallet-backup"` (flat,
      no namespacing).
- [ ] Plaintext is the raw NWC URI string; no JSON wrapper.
- [ ] Encrypt content with NIP-44 v2 self-to-self
      (`getConversationKey(privateKey, ownPubkey)`).
- [ ] Publish on every successful NWC connect, off the main path
      (suspend / coroutine). Best-effort, failures non-fatal.
- [ ] NWC setup screen queries relays for the user's
      `(kind 30078, d=nwc-wallet-backup)` event on open and surfaces
      a "Restore previous wallet" action when one is found.

### 5.1 Interop quick-test

1. iOS device A connects an NWC wallet → wait ~3s.
2. Android device B (same nsec): open NWC setup → "Restore previous
   wallet" is offered, the wallet's alias matches what A connected,
   one-tap restores without re-pasting the URI.
3. Reverse: Android connects → iOS sees the restore offer.

If step 2 doesn't see the backup, check (a) the `d` tag is exactly
`nwc-wallet-backup` (no namespacing), (b) the conversation key uses
the user's own pubkey as the recipient, (c) the relay set both
clients query overlaps.

---

## 6. Locked decisions

| Decision | Choice | Why |
| --- | --- | --- |
| Backup storage kind | **NIP-78 (kind 30078)** | Addressable / replaceable, intended for app-specific data |
| `d` tag | **`nwc-wallet-backup`** (flat) | Cross-platform interop — no namespacing |
| Plaintext format | **Raw URI string** | Minimal — caller already knows it's NWC |
| Encryption | **NIP-44 v2 self-to-self** | Modern, integrity-protected, only the user's key decrypts |
| Publish trigger | **On every connect** | Reconnect / wallet swap replaces the prior backup automatically |
| Disconnect behaviour | **No explicit deletion** | Stale ciphertext still requires private key to decrypt; relays sweep eventually |
| Opt-in checkbox | **No** | Matches the Spark seed backup; the value of the feature is seamless cross-device sync |
