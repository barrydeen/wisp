# Cheffy Note Review — Android Port Plan

Port of the web frontend's Cheffy Note Photo Review (kind-1 food-photo → draft
comment or reverse-engineered recipe) to `zapcooking/zap_cooking_android`.
Follows house rules: stop-gated phases, one concern per PR, surgical diffs,
investigation before implementation.

**Status (2026-07-09): Phases 0–5 merged; Phase 6 (flag flip + release
prep) in review. The flag flip merges only after the device checklist in
[`QA_NOTE_REVIEW.md`](QA_NOTE_REVIEW.md) passes.**

**Backend-as-API rule holds: zero server work.** All three endpoints already
exist and are client-agnostic (NIP-98 identity, no cookies, no CORS concern
for a native client). Android consumes them exactly as web does.

---

## 1. The web contract (source of truth)

### Endpoints (base `https://zap.cooking`)

| Endpoint | Auth | Body | Success | Typed failures |
|---|---|---|---|---|
| `POST /api/zappy/note-review` | NIP-98, body-hash bound | `{ imageUrl, mode: "comment"\|"recipe", noteText?, noteId? }` | `{ ok:true, output, mode, creditsRemaining? }` | `NOT_MEMBER` (403), `MEMBERSHIP_UNAVAILABLE` (503), `RATE_LIMITED` (429), `NOT_FOOD` (422), `IMAGE_UNREADABLE` (422), 401 |
| `POST /api/zappy/note-review/credit-invoice` | NIP-98, body `{}` | `{}` | `{ ok:true, invoiceId, bolt11, expiresAt }` | `SIGN_FAILED` client-side, generic |
| `GET /api/zappy/note-review/credit-status?id=…` | NIP-98 (u-tag **excludes query string**) | — | `{ ok:true, status: "paid"\|"pending"\|"expired", balance }` | generic |

Server-side facts that shape the client:

- **Fails CLOSED** on membership-service outage (D5 deviation, unlike
  extract-recipe). Client must render `MEMBERSHIP_UNAVAILABLE` as a
  "try again shortly" state, not an upsell.
- Rate limit: **8/hour, 30/day per pubkey**. Regenerates share the budget.
- `noteText` capped at 1000 chars (client trims + caps before signing so the
  payload hash matches the sent bytes).
- Credits are spent **server-side, success-only** — `NOT_FOOD`,
  `IMAGE_UNREADABLE`, and API failures never consume a paid credit. The
  client never does its own spend accounting; `creditsRemaining` is display
  only.
- `NOT_FOOD` fires for CDN fallback images on dead links too (nostr.build
  never 404s), so the client **never echoes the server's line** — it shows a
  hedged "couldn't get a good look" line from a local pool.

### Client invariants to preserve (decisions, not preferences)

1. **D1 — mandatory edit-before-post.** The draft lands in an editable field;
   nothing is ever auto-published. `canPost` gates publish to the `draft`
   phase only (double-post guard).
2. **Publish timeout keeps the SIGNED event.** Retry re-publishes the same
   event id (relays dedupe) — never a second signer round trip.
3. **Disclosure footer** `⚡🍳 via Cheffy · zap.cooking` is applied at
   publish time only, never inside the editable draft. Per-mode defaults:
   comment **off**, recipe **on**. Preference seeds the toggle only from the
   `choose` phase; regenerates preserve the in-session toggle.
4. **The credit-status poll is the sole crediting authority.** Wallet-side
   success signals are advisory. The poll starts before any payment attempt,
   on every path.
5. **Never mint a second invoice while one is live.** In-app payment failure
   or timeout re-presents the SAME bolt11 via the external fallback (a
   BOLT11 settles exactly once — no double-pay risk).
6. **Pending-invoice resume.** If the payer closes between paying and the
   poll observing paid, the invoice id persists locally; next open polls it
   once. Paid → credit + clear; expired → clear silently; check-failure →
   keep (never destroy a potentially-paid invoice).
7. **Signing → loading are distinct phases** (NIP-46/Amber round trips are
   slow; the user needs to know Cheffy is waiting on *their* signer, not the
   server).

---

## 2. What Android already has (reuse, don't rebuild)

- **`Nip98.kt`** — byte-exact port of the web reference, already strips the
  query string from the u-tag. The credit-status GET works as-is.
- **`ZapCookingApi.authedPost` spine** + `HttpClientFactory.getComputeClient()`
  (long-timeout) — the note-review POST is one more method on this spine.
  Vision drafting routinely exceeds the general client's 15s read timeout, so
  compute client is mandatory.
- **Membership**: `getPublicMembership` (batch, unauthenticated) and
  `checkMembershipStatus` (NIP-98). The server 403 stays authoritative;
  client-side status is only for pre-gating UI.
- **Cheffy identity**: `CheffyIcon.kt`, `Cheffy.kt` line pools,
  `CheffyViewModel`/`CheffyScreen` conventions and copy voice.
- **Wallet**: `WalletProvider.payInvoice(bolt11)` with NWC (`NwcRepository`)
  and Spark (`SparkRepository`) implementations — direct analog of web's
  in-app wallet path. `lightning:` URI handling exists (WalletScreen,
  RichContent) for the external fallback.
- **Reply publishing**: ThreadScreen/ComposeScreen already publish kind-1
  NIP-10 replies with relay routing. (`PrivateReplyPublisher` is the
  private-group variant — the public reply path is what Note Review uses.)
- **Signer abstraction**: `LocalSigner`, `RemoteSigner` (NIP-55/Amber;
  `RemoteSignerBridge` is login-time discovery only — no NIP-46 bunker
  signer exists, per finding 0.1), `SignerRejected/CancelledException`
  propagation, `LocalCanSign` for gating signing-dependent UI.
- **Surfaces**: `PostCard` + `ActionBar` on kind-1 notes in FeedScreen /
  OnlyFoodFeedScreen / ThreadScreen — the trigger placements.
- **`FeatureFlags.kt`** — the dark-ship pattern for Phases 1–5 below.
- **Preferences repos** (e.g. `ZapPreferences`, `KeyBackupPreferences`) — the
  house pattern for the disclosure prefs and pending-invoice persistence
  (replacing web's `zapcooking_*` localStorage keys).

## 3. Gaps (net-new Android work)

1. **Image-URL extraction parity.** Web's `src/lib/imageUrls.ts` is the
   single source of truth: extension regex
   `\.(jpg|jpeg|png|gif|webp|svg|bmp|avif)(\?.*)?$` on the pathname, plus an
   extensionless-host rescue list (`image.nostr.build`, `imgur.com`,
   `primal.b-cdn.net`, `media.tenor.com`, `i.ibb.co`) with exact-or-subdomain
   matching (never substring). The server validates with the SAME module, so
   an Android extractor that's looser than web will surface a trigger the
   server then 400s. Port it as `ImageUrls.kt` with unit tests mirroring
   `imageUrls`' cases. (Android's `RichContent.kt` has its own media
   detection — investigate reuse first, but parity with the web module wins
   over reuse if they diverge.)
2. **The note-review API methods + result types** on `ZapCookingApi`.
3. **The modal itself** — Compose `ModalBottomSheet` (Android idiom for the
   web modal) with the full phase machine.
4. **Publish-timeout signed-event retry** — check whether the existing reply
   publisher exposes "give me the signed event on relay timeout"; if not,
   it needs an `explicit-with-timeout` mode like web's `postComment`.
5. **Prefs**: disclosure per-mode toggles + pending invoice (DataStore or
   SharedPreferences per house pattern).

---

## Phase 0 — Investigation (STOP GATE, no code) — ✅ complete (findings + decisions below; docs PR #146)

Deliverable: findings comment on the tracking issue. Implementation PRs do
not start until each is answered.

- **0.1 NIP-98 × external signers under polling.** Web re-signs a fresh
  NIP-98 header on every 3s credit-status poll. LocalSigner: silent, fine.
  Amber (NIP-55): does each kind-27235 sign prompt the user, or does the
  auto-approve grant cover it? RemoteSigner (bunker): what's the round-trip
  cost at 3s cadence? Decide: (a) rely on auto-approval, (b) widen poll
  interval for external signers, or (c) reuse one signed header across polls
  within its validity window — the u-tag is identical (query excluded), but
  verify against `nip98.server.ts` whether the verifier enforces
  freshness/single-use before assuming reuse is legal.
- **0.2 Reply publisher audit.** Confirm the public NIP-10 reply path:
  where it lives, whether it can return the signed event on relay timeout,
  whether it does inbox-aware (NIP-65) routing like web's
  `buildInboxAwareRelaySet`, and whether it appends the NIP-89 client tag.
- **0.3 Trigger placement.** Web shows the Cheffy icon inline in
  `NoteActionBar` (right-aligned) AND as a `PostActionsMenu` overflow item,
  rendered only when `extractImageUrls(content)` is non-empty. Map to
  Android: `ActionBar` inline icon + PostCard overflow entry. Confirm
  ActionBar has room on small widths; overflow-only is the fallback.
- **0.4 READ_ONLY accounts.** No signing key → no NIP-98 → feature is
  invisible or shows a sign-in nudge? Match whatever Sous Chef image
  extraction does today (`LocalCanSign` gating precedent).
- **0.5 RichContent reuse vs. port** for image extraction (gap #1 above).
- **0.6 Wallet detection.** Web routes in-app for wallet kinds 3 (NWC) and
  4 (Spark), external otherwise. Confirm the Android analog:
  `WalletModeRepository` / `WalletProvider` — what states mean "has in-app
  wallet"?

## Phase 1 — API layer (PR 1) — ✅ merged (PR #147)

`ZapCookingApi` additions, no UI:

- `requestNoteReview(imageUrl, mode, noteText?, noteId?, signer)` →
  sealed `NoteReviewResult`: `Success(output, creditsRemaining?)`,
  `NotMember`, `MembershipUnavailable`, `RateLimited(retryAfter?)`,
  `DeadEnd` (collapses `NOT_FOOD` + `IMAGE_UNREADABLE` — the UI treats them
  identically and must not echo the server line), `SignFailed`,
  `Error(message)`. Trim + cap `noteText` to 1000 chars **before**
  serializing (payload-hash binding). Compute client.
- `requestCreditInvoice(signer)` → `CreditInvoice(invoiceId, bolt11, expiresAt)`.
  Body is exactly `{}`.
- `checkCreditStatus(signer, invoiceId)` → `paid|pending|expired` + balance.
  Query param on the URL, u-tag naturally excludes it via `Nip98.normalizeUrl`.
- Unit tests: response-code → sealed-type mapping, noteText capping,
  payload-hash/body-bytes identity, credit-status URL vs u-tag divergence.

## Phase 2 — Draft flow, members only (PR 2, flag `NOTE_REVIEW_ENABLED = false`) — ✅ merged (PR #148)

- `ImageUrls.kt` port + tests (or the 0.5 reuse decision).
- Trigger per 0.3, gated on: flag ∧ canSign ∧ image detected.
- `NoteReviewViewModel` + `ModalBottomSheet`, phases:
  `Choose → Signing → Loading → Draft | DeadEnd | Upsell | Error`.
  - Choose: two mode cards (warm comment / reverse-engineered recipe).
  - Signing/Loading split (invariant 7), Cheffy avatar + rotating
    `THINKING_LINES`-style copy from `Cheffy.kt`.
  - Draft: editable text field, Regenerate (same mode, same budget),
    character-count sanity. **No Post button in this PR** — the publish path
    is Phase 3, mirroring web's dark-ship of posting.
  - DeadEnd: hedged line pool (port `DEAD_END_LINES`), rotate on retry,
    never the server's text.
  - Upsell: static membership card for now (link to membership screen);
    the sats path arrives in Phase 5. `MEMBERSHIP_UNAVAILABLE` renders as
    retryable, NOT as upsell.
- ViewModel unit tests for the phase machine (`phaseForResult` port).

## Phase 3 — Publish path (PR 3) — ✅ merged (PR #149)

- Post button appears (draft phase only, `canPost` guard →
  `Posting → Posted | PostTimeout`).
- Publishes the member-edited text as a NIP-10 reply to the parent kind-1
  via the audited publisher from 0.2, signed by the member.
- `PostTimeout` retains the signed event; Retry re-publishes it (same id,
  no re-sign). Publish-failed keeps the draft intact.
- Posted state: link into ThreadScreen for the new reply (nevent with
  author + kind hints, note1 fallback — port `noteLinkFor`).

## Phase 4 — Disclosure footer + multi-image (PR 4) — ✅ merged (PR #151)

- Footer toggle on the draft phase, shown as a separate non-editable preview
  line (never concatenated into the edit field). Exact string:
  `⚡🍳 via Cheffy · zap.cooking`. Per-mode persisted prefs, defaults
  comment=off / recipe=on, seed-from-pref only on `Choose` (invariant 3).
- Multi-image notes: thumbnail picker strip, defaults to first image,
  selection survives Regenerate, one imageUrl per request.

## Phase 5 — Non-member credits (split as decided: 5a invoice/poll, 5b wallet routing/resume) — ✅ merged (PRs #152, #153)

- Upsell card gains the 21-sat path: price, the static example draft
  (`PAYMENT_CARD_EXAMPLE_DRAFT`), and the "Tied to your Nostr key — works on
  any device" line (true here: web-purchased credits work on Android and
  vice versa — worth surfacing in release notes).
- Buy → invoice → **start the 3s status poll first, on every path**
  (invariant 4; poll cadence per 0.1 decision) → route payment:
  - In-app wallet (per 0.6): `WalletProvider.payInvoice` raced against a 30s
    timeout. Failure/timeout → fallback affordance re-presenting the SAME
    bolt11 (invariant 5): QR + copy + `lightning:` intent (the Android
    analog of web's bitcoin-connect modal).
  - No in-app wallet: straight to QR/copy/`lightning:` intent.
- Paid observed by poll → credit acknowledged, balance shown, flow returns
  to mode selection or auto-runs the pending mode (match web behavior —
  verify in `CheffyNoteReview.svelte` before implementing).
- Pending-invoice persistence + one-shot resume-on-open (invariant 6).
- `creditsRemaining` from draft responses updates the visible balance.
- Tests: payment routing (in-app / external / in-app-failed), poll action
  mapping, resume outcomes (paid/expired/pending/check-failure), same-bolt11
  fallback.

## Phase 6 — Flag flip + release (PR 6) — 🔄 in review (PR #154, merges after device QA)

Flip `NOTE_REVIEW_ENABLED` (retained post-launch as the kill switch, web
precedent), RELEASE_NOTES entry, and the final QA matrix below. The
remaining rows are expanded into a concrete device checklist in
[`QA_NOTE_REVIEW.md`](QA_NOTE_REVIEW.md) — **the flag flip merges only
after that checklist passes.**

Matrix status: bunker (NIP-46) removed as N/A in Phase 0 (no such signer
exists — finding 0.1). Rows marked *pre-cleared 2026-07-09* passed the
Phase 3/4 on-device smoke sessions (local key + Amber) — re-verify only
if the area changed since.

| Axis | Cases |
|---|---|
| Signer | LocalSigner *(pre-cleared 2026-07-09)*, Amber NIP-55 incl. sign + reject *(pre-cleared 2026-07-09)*, READ_ONLY hidden *(pre-cleared 2026-07-09 — trigger gating)* |
| Membership | Pro Kitchen member happy path, both modes *(pre-cleared 2026-07-09)*; non-member 0 credits → **QA doc**; non-member with credits → **QA doc**; membership service down (fails closed) → **QA doc** (needs backend cooperation) |
| Wallet | NWC → **QA doc**; Spark → **QA doc**; none (external fallback) → **QA doc**; in-app timeout → fallback SAME invoice → **QA doc** |
| Image | extension URL *(pre-cleared 2026-07-09)*; regenerate + header-cache behavior *(pre-cleared 2026-07-09)*; dead link → dead-end *(pre-cleared 2026-07-09)*, credit-NOT-spent variant → **QA doc**; extensionless rescued host → **QA doc**; multi-image picker → **QA doc**; imageless = no trigger *(pre-cleared 2026-07-09 — trigger gating)* |
| Publish | happy path *(pre-cleared 2026-07-09)*; signer rejects → draft intact *(pre-cleared 2026-07-09 — Amber session)*; relay timeout → retry same event, no duplicate reply → **QA doc** |
| Edge | sheet lifecycle (dismiss/reopen/rotation) *(pre-cleared 2026-07-09)*; disclosure defaults per mode *(pre-cleared 2026-07-09 — footer session)*; rate limited (429 copy) → **QA doc**; resume paid-while-closed → **QA doc**; cross-platform credit ledger (Android ↔ web) → **QA doc** |

---

## Explicitly out of scope

- Any server/endpoint changes (including revisiting fail-closed — that's
  frontend issue #512, not this port).
- iOS/Capacitor parity.
- Streaming drafts, image upload (the endpoint takes URLs only — OpenAI
  fetches; our infra never does).
- Changing rate limits, price, or the disclosure string (product spec).
- Inline ActionBar trigger — requires ActionBar slot rework per finding
  0.3 / decision 2; tracked in issue #150.

---

## Phase 0 findings

Investigated 2026-07-09 against `main` (f31c9da) and
`zapcooking/frontend` master (`src/lib/nip98.server.ts`,
`src/lib/nip98.ts`, `src/lib/imageUrls.ts` fetched from GitHub raw).

### 0.1 NIP-98 × signers under polling

**Signer inventory correction first.** The codebase has exactly two
`NostrSigner` implementations: `LocalSigner` and `RemoteSigner`
(`nostr/NostrSigner.kt:53`, `:92`). **There is no NIP-46 bunker signer
anywhere in the app** — `RemoteSigner` *is* the NIP-55/Amber signer
(ContentResolver first, intent fallback, `NostrSigner.kt:100-104`), and
`RemoteSignerBridge` (`NostrSigner.kt:230`) is only login-time discovery
helpers. This plan's §2 line "RemoteSigner (bunker), RemoteSignerBridge
(NIP-55/Amber)" is wrong, and the Phase 6 QA matrix row "bunker (NIP-46)"
tests a signer that doesn't exist. (Also: the build spec §1 and CLAUDE.md
say "LocalSigner only / NIP-55 removed" — both wrong; `SigningMode.REMOTE`
is live, e.g. `Navigation.kt:384-388`.)

**Cost per kind-27235 sign:**
- `LocalSigner`: in-process Schnorr sign (`NostrSigner.kt:59-68`),
  sub-millisecond, silent. 3s cadence with a fresh sign per poll is fine.
- `RemoteSigner` (Amber): one ContentResolver IPC per sign when the kind
  is auto-approved (`tryContentResolverSign`, `NostrSigner.kt:135-160`) —
  tens of ms, silent. **But kind 27235 is NOT in
  `RemoteSignerBridge.DEFAULT_PERMISSIONS`** (`NostrSigner.kt:232` — the
  list covers 0,1,3,5,6,7,9734,10000,10002,22242,30000,30023 + nip44).
  Without a grant, the CR query returns no cursor and `signEvent` falls
  back to `signEventViaIntent` — a full-screen Amber approval activity —
  **per sign**. At 3s cadence that is an approval prompt every 3 seconds:
  unusable.

**Is header reuse legal? Yes.** The verifier
(`nip98.server.ts`, `verifyNip98`) checks, in order: kind == 27235,
`verifyEvent` signature, then freshness —

```ts
const DEFAULT_MAX_SKEW_SECONDS = 60;
if (Math.abs(now - (event.created_at || 0)) > maxSkew) {
  return { ok: false, reason: 'stale-timestamp' };
}
```

— then u-tag/method/payload/expectedPubkey. That is the **only**
freshness/expiry check. There is no nonce, no jti, no replay cache — the
verifier is stateless. A signed header is therefore valid for any number
of requests until `created_at` is >60s old. For the credit-status GET the
u-tag is constant across polls (`Nip98.normalizeUrl` drops the query,
`Nip98.kt:49-62`, and the verifier normalizes identically), and GETs carry
no payload tag, so one signed header legally covers ~15-20 polls.

**Decision: (c) — reuse one signed header across polls within a ~45s TTL**
(15s safety margin against clock skew; the ±60s window also tolerates
modest client-clock drift). Implement as a small header cache keyed on
`(method, normalizedUrl)` inside the credit-status poll loop (or a
`CachedNip98Header` helper next to `Nip98.authHeader`,
`Nip98.kt:114-122`) — re-sign only on expiry. This makes poll cadence
signer-independent: keep 3s for all signer types; Amber users see at most
one sign per ~45s, via CR if granted, via one intent prompt otherwise.

Secondary: add `{"type":"sign_event","kind":27235}` to
`DEFAULT_PERMISSIONS` so future Amber logins auto-approve NIP-98 (existing
sessions can "always allow" on first prompt). This also benefits
`checkMembershipStatus` and every other `authedPost`
(`ZapCookingApi.kt:105`).

**Phase impacts:** Phase 1's `checkCreditStatus` should accept the cached
header (or the cache lives in the poll loop in Phase 5 — either way, sign
once per TTL window, not per call). Phase 6 QA matrix: delete the
"bunker (NIP-46)" case, keep LocalSigner / Amber / READ_ONLY. §2's signer
bullet needs the bunker mention removed.

### 0.2 Reply publisher audit

**Where it lives:** the public kind-1 NIP-10 reply path is
`ComposeViewModel.publish()` → private `publishNote()`
(`viewmodel/ComposeViewModel.kt:706`, `:836`). ThreadScreen only surfaces
`onReply` callbacks that route into the compose flow. Findings:

- **NIP-10 tags:** yes — `Nip10.buildReplyTags(replyTo, hint)` with a
  relay hint from `outboxRouter.getRelayHint(replyTo.pubkey)`
  (`ComposeViewModel.kt:851-854`), plus p-tags for content mentions
  (`:856-862`).
- **NIP-65 inbox routing:** yes — `outboxRouter.publishToInbox(msg,
  inboxPubkeys)` where `inboxPubkeys` = parent author + mentioned pubkeys
  (`:1019-1022`, `:1057-1061`; `OutboxRouter.kt:322`), falling back to
  `relayPool.sendToWriteRelays`, with one reconnect-and-retry pass
  (`:1063-1072`).
- **NIP-89 client tag:** yes — `Nip89.clientTag()` appended when
  `interfacePrefs.isClientTagEnabled()` (`:977-979`; `Nip89.kt:16`).
- **Signed event on relay timeout: NO.** `publishNote` signs (`:1054`),
  fire-and-forgets, and `relayPool.trackPublish(event.id, sentCount)`
  (`:1078`) only drives a transient `BroadcastState` toast with a 5s OK
  collector (`RelayPool.kt:701-719`). The signed event never escapes the
  method; on total send failure it returns 0 and the event is dropped. It
  is also deeply coupled to composer state (drafts, undo countdown,
  gallery/poll/schedule modes, PoW handoff) — invariant 2 (retry same
  event id, no re-sign) cannot be satisfied through it.

**Recommendation:** Phase 3 builds a small dedicated publisher (e.g.
`NoteReviewReplyPublisher`, ~80 lines) rather than refactoring
`publishNote`. It reuses the exact same primitives: `Nip10.buildReplyTags`
+ `outboxRouter.getRelayHint` for tags, `Nip89.clientTag()` per the same
pref, `signer.signEvent(kind=1, …)` **once**, send via
`outboxRouter.publishToInbox(msg, setOf(parentAuthor))` with
`sendToWriteRelays` fallback, then await the first accepted
`PublishResult` on `relayPool.publishResults`
(`RelayPool.kt:214-215` — a public SharedFlow of per-relay OK results,
`PublishResult(relayUrl, eventId, accepted, message)`) under
`withTimeoutOrNull`. Return a sealed result carrying the **signed event**
on timeout so the ViewModel holds it for retry (re-send the same
`ClientMessage`, same id — relays dedupe). On success, mirror the local
bookkeeping so the UI updates immediately: `eventRepo.addEvent(event)` +
`addReplyCount` on parent and root (`ComposeViewModel.kt:1080-1090`).
This confirms gap #4: the `explicit-with-timeout` mode is net-new.

### 0.3 Trigger placement

**Measured width constraint — the inline ActionBar does NOT reliably have
room.** `ActionBar` (`ui/component/ActionBar.kt:68`) is a non-scrolling
`Row` with five fixed 48dp slots (react `:106`, reply `:157`, repost
`:181`, zap `:220`, bookmark `:286`) + four 8dp spacers = **272dp fixed**,
plus up to four inline count labels (like/reply/repost/zapSats, ~12-28dp
each when present). PostCard gives it `Modifier.weight(1f)` in a row that
also holds a 20dp expand chevron (`PostCard.kt:878-914`), inside a column
with 16dp horizontal padding per side (`PostCard.kt:269`). On a 360dp
screen that leaves ~308dp for the bar — **already over budget today when
several counts render** (272 + counts > 308; trailing slots get squeezed).
Adding a sixth 48dp slot (+8dp spacer) guarantees clipping on common
devices.

**Recommendation:**
1. **Overflow entry (guaranteed baseline):** a `DropdownMenuItem` with
   `CheffyIcon(20.dp)` in PostCard's existing MoreVert menu
   (`PostCard.kt:437-598`), inserted near "Add to list". Gated on
   `FeatureFlags.NOTE_REVIEW_ENABLED ∧ LocalCanSign ∧
   ImageUrls.extractImageUrls(event.content).isNotEmpty()` (the menu
   itself is not canSign-gated, so the gate must be explicit here — see
   0.4). This mirrors web's `PostActionsMenu` item.
2. **Inline (discoverability, web parity):** a *compact* trigger — 22dp
   `CheffyIcon` with a 40dp unbounded-ripple tap target, like the expand
   chevron's idiom, NOT a sixth 48dp slot — placed at the end of the
   action row next to the chevron (`PostCard.kt:906`), rendered only when
   an image is detected. Cost ~28dp. This still eats into the bar's
   headroom on 360dp devices with count-heavy notes, so treat it as
   flag-adjacent: ship it in Phase 2 behind the same flag, QA on a 360dp
   device with all four counts populated, and drop to overflow-only if it
   squeezes the bookmark slot. (Open question 2 below.)

Note the inline position inherits READ_ONLY invisibility for free because
PostCard hides the whole action row when `!LocalCanSign`
(`PostCard.kt:876-877`).

### 0.4 READ_ONLY accounts

Precedents found:
- **Per-note actions are hidden, not nudged:** PostCard renders the entire
  ActionBar row only when `LocalCanSign.current` is true
  (`PostCard.kt:876-877`); `LocalCanSign` is
  `signingMode != SigningMode.READ_ONLY` (`Navigation.kt:1031`), and
  READ_ONLY yields a null signer (`Navigation.kt:390`).
- **Sous Chef** is a drawer *destination*, so it stays reachable and
  nudges at the CTA instead: `canSign = feedViewModel.signer != null`
  (`Navigation.kt:3441`), `!canSign -> onSignIn()` on the import CTA
  (`SousChefScreen.kt:349`), save button
  `enabled = canSign && hasImage && !saving` with the copy "Sign in to
  save this recipe to your account." (`SousChefScreen.kt:472,492`). The
  ViewModel keeps a defensive null-signer guard
  (`SousChefViewModel.kt:93-96`).

**Recommendation:** Note Review's trigger is a per-note action, so it
follows the per-note precedent — **invisible for READ_ONLY**, no nudge.
Inline placement gets this free (0.3); the overflow entry must gate on
`LocalCanSign` explicitly. Keep the Sous-Chef-style defensive null-signer
guard in `NoteReviewViewModel`. This matches Phase 2's stated gate
(flag ∧ canSign ∧ image detected) — no plan change needed, and the Phase 6
QA row "READ_ONLY (hidden/nudge)" resolves to **hidden**.

### 0.5 RichContent reuse vs. parity port

**Verdict: parity port (`ImageUrls.kt`), do not reuse RichContent.**
`RichContent.parseContent` (`ui/component/RichContent.kt:301`) is a
rendering pipeline (segments for text/media/nostr-refs/hashtags/emoji) and
diverges from web `imageUrls.ts` in ways that would either surface
triggers the server 400s, or hide triggers the server would accept:

1. **Extension sets differ.** Android:
   `{jpg, jpeg, png, gif, webp, heic, heif}` (`RichContent.kt:215`). Web:
   `\.(jpg|jpeg|png|gif|webp|svg|bmp|avif)(\?.*)?$` on the *pathname*.
   Android-only `heic/heif` → trigger shown, server rejects the URL.
   Web-only `svg/bmp/avif` → no Android trigger where web shows one.
2. **Extension derivation differs.** Android takes
   `url.substringAfterLast('.').substringBefore('?')` over the whole URL
   string (`RichContent.kt:318,360`) — fragments break it
   (`photo.jpg#x` → ext `jpg#x`, not an image; web parses the URL and
   tests the pathname, which excludes the fragment → image). Web also
   tolerates a query via the regex; both agree on plain `?query` URLs but
   only by different mechanisms.
3. **No extensionless-host rescue in Android.** Web rescues
   `image.nostr.build`, `imgur.com`, `primal.b-cdn.net`,
   `media.tenor.com`, `i.ibb.co` via exact-or-subdomain `matchesHost`
   (never substring), plus `nostr.build` with `/i/` paths, plus any host
   with an `imgproxy` label. Android has none of these; instead it has a
   web-absent Blossom rule (64-hex path → `UnknownMediaSegment`,
   `RichContent.kt:227,268-276`) — Blossom URLs must NOT trigger Note
   Review (extensionless, not on the server's rescue list → server 400).
4. **Tokenization differs.** Android's `combinedRegex`
   (`RichContent.kt:278`) also matches scheme-less bare domains and
   `wss://`; web's `URL_REGEX` is `https?://` only, with trailing
   `[.,;:!?]+` stripped.
5. **imeta promotion.** Android promotes any URL whose kind-1 `imeta` tag
   says `m image/*` (`RichContent.kt:317-323`); web's `extractImageUrls`
   works on raw content only, and the server validates the URL string with
   the same module — an imeta-promoted extensionless URL would pass the
   Android gate and fail server-side. Note Review detection must ignore
   imeta.

**Recommendation:** port `ImageUrls.kt` (`isImageUrl`, `filterImageUrls`
with first-occurrence dedup — load-bearing for the Phase 4 thumbnail
strip — and `extractImageUrls` with the trailing-punctuation strip) as a
pure, JVM-testable object with unit tests mirroring the web cases,
including the negative cases: substring-host attacks
(`notimgur.com`, `imgur.com.evil.example`), fragment URLs, Blossom-hash
URLs, `heic` exclusion. Leave RichContent untouched — its job is
rendering, and its `heic/heif`/Blossom behavior is correct for that job.
Confirms gap #1 as written.

### 0.6 Wallet detection — what "has in-app wallet" means

State inventory:
- `WalletModeRepository.getMode()` → `NONE | NWC | SPARK`, persisted
  per-pubkey in SharedPreferences `wisp_wallet_mode_<pubkey>`
  (`repo/WalletModeRepository.kt:5,20-23`).
- Provider selection: `FeedViewModel.activeWalletProvider` maps
  `SPARK -> sparkRepo; else -> nwcRepo` (`FeedViewModel.kt:337-341`).
  **Footgun: `NONE` also resolves to `nwcRepo`** — so the mode check is
  mandatory; never infer "has wallet" from `activeWalletProvider` alone
  (a stale `nwc_uri` could make it look connected).
- Configured-ness: `NwcRepository.hasConnection()` = saved `nwc_uri` in
  EncryptedSharedPreferences (`NwcRepository.kt:71`);
  `SparkRepository.hasConnection()` = mnemonic present
  (`SparkRepository.kt:130`). `WalletProvider.isConnected` is *live*
  socket state (`WalletProvider.kt:8`) — do not use it for routing; a
  configured-but-idle wallet should still route in-app (`payInvoice`
  establishes the connection on demand, per the existing zap path:
  `ZapSender.kt:201`, `FeedViewModel.payInvoice` `:484-485`).

**Definition for Phase 5 routing:**

```kotlin
val hasInAppWallet =
    walletModeRepo.getMode() != WalletMode.NONE &&
    activeWalletProvider.hasConnection()
```

`true` → attempt `activeWalletProvider.payInvoice(bolt11)` raced against
the 30s timeout, falling back to QR/copy/`lightning:` with the SAME
bolt11 (invariant 5). `false` → straight to external. This is the exact
analog of web's wallet kinds 3 (NWC) / 4 (Spark) routing. READ_ONLY
accounts can still hold a wallet mode, but they never reach the modal
(0.4), so no extra guard is needed.

### Open questions before Phase 1

1. **0.1 — approve the header-reuse design?** (a) 45s-TTL cached NIP-98
   header for the credit-status poll (verifier-legal per the ±60s
   stale-timestamp check, stateless verifier), (b) adding kind 27235 to
   `RemoteSignerBridge.DEFAULT_PERMISSIONS`, and (c) keeping the 3s poll
   cadence for all signer types on that basis.
2. **0.3 — inline trigger: ship the compact adaptive icon in Phase 2, or
   overflow-only?** Measured math says a sixth 48dp ActionBar slot
   doesn't fit on 360dp devices; my recommendation is overflow always +
   compact 22dp inline icon (drop it if 360dp QA shows squeeze), but
   that's a product call.
3. **Plan/spec corrections to land:** no NIP-46 bunker signer exists —
   fix this plan's §2 signer bullet and the Phase 6 QA "Signer" row
   (LocalSigner / Amber / READ_ONLY), and note that
   ZAPCOOKING_ANDROID_BUILD.md §1 + CLAUDE.md still claim
   "LocalSigner-only," which contradicts the live code. Fix in this PR
   series or separately?
4. **0.5 — heic/heif parity.** Strict parity excludes `heic/heif` from
   Note Review triggers even though Android renders them as images and
   iPhone photos commonly use them; the server (same module) would reject
   them anyway, so parity is correct today — flagging in case you want a
   server-side extension addition first (out of scope for this port).

---

## Phase 0 decisions

Decided 2026-07-09 (answers the open questions above; Phase 1 may start):

1. **NIP-98 header caching (0.1).** Cache one signed header per
   `(method, normalized URL, body-hash)` key with a **30s TTL** (half the
   verifier's ±60s stale-timestamp window, so a cached header is never
   presented in its skew-sensitive tail). On a **401 for a request that
   used a cached header**: invalidate, silently re-sign once, retry once.
   A 401 on a freshly signed header is NOT retried — re-signing cannot
   fix it. Kind 27235 is added to
   `RemoteSignerBridge.DEFAULT_PERMISSIONS` (applies to new NIP-55
   logins; existing Amber connections keep their stored grants and get
   one approval prompt, where the user can choose "always allow"). Poll
   cadence stays 3s for all signer types.
2. **Trigger placement (0.3): overflow-menu-only.** No inline ActionBar
   icon — the measured width math (272dp fixed + counts vs ~308dp
   available on 360dp devices) rules out a sixth slot, and the compact
   inline variant isn't worth the squeeze risk. The trigger is a
   `DropdownMenuItem` in PostCard's note menu, gated on
   flag ∧ `LocalCanSign` ∧ image detected.
   *Superseded 2026-07-09 by issue #150's Phase A findings: adaptive
   inline (right-aligned 48dp slot, shown only at ≥348dp measured bar
   width, quoted renders excluded) + the overflow entry retained
   everywhere — both placements coexist on wide screens, web parity.*
3. **Doc corrections land separately** from the implementation PRs:
   PR #146 fixes CLAUDE.md, ZAPCOOKING_ANDROID_BUILD.md §1/§6, the stale
   `Nip98.kt` KDoc ("LocalSigner-only"), this plan's §2 signer bullet,
   and the Phase 6 QA matrix (bunker row removed).
4. **Image extension parity (0.5): strict parity with web
   `imageUrls.ts`.** `heic`/`heif` are **excluded by design** — the
   server validates with the same module and would reject them. If
   iPhone-photo coverage is wanted later, that's a server-side extension
   change first (out of scope for this port).
