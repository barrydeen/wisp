# Cheffy Note Review — Android Port Plan

Port of the web frontend's Cheffy Note Photo Review (kind-1 food-photo → draft
comment or reverse-engineered recipe) to `zapcooking/zap_cooking_android`.
Follows house rules: stop-gated phases, one concern per PR, surgical diffs,
investigation before implementation.

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
- **Signer abstraction**: `LocalSigner`, `RemoteSigner` (bunker),
  `RemoteSignerBridge` (NIP-55/Amber), `SignerRejected/CancelledException`
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

## Phase 0 — Investigation (STOP GATE, no code)

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

## Phase 1 — API layer (PR 1)

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

## Phase 2 — Draft flow, members only (PR 2, flag `NOTE_REVIEW_ENABLED = false`)

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

## Phase 3 — Publish path (PR 3)

- Post button appears (draft phase only, `canPost` guard →
  `Posting → Posted | PostTimeout`).
- Publishes the member-edited text as a NIP-10 reply to the parent kind-1
  via the audited publisher from 0.2, signed by the member.
- `PostTimeout` retains the signed event; Retry re-publishes it (same id,
  no re-sign). Publish-failed keeps the draft intact.
- Posted state: link into ThreadScreen for the new reply (nevent with
  author + kind hints, note1 fallback — port `noteLinkFor`).

## Phase 4 — Disclosure footer + multi-image (PR 4)

- Footer toggle on the draft phase, shown as a separate non-editable preview
  line (never concatenated into the edit field). Exact string:
  `⚡🍳 via Cheffy · zap.cooking`. Per-mode persisted prefs, defaults
  comment=off / recipe=on, seed-from-pref only on `Choose` (invariant 3).
- Multi-image notes: thumbnail picker strip, defaults to first image,
  selection survives Regenerate, one imageUrl per request.

## Phase 5 — Non-member credits (PR 5, largest — consider splitting 5a invoice/poll, 5b wallet routing/resume)

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

## Phase 6 — Flag flip + release (PR 6)

- Flip `NOTE_REVIEW_ENABLED`, RELEASE_NOTES entry, QA matrix:

| Axis | Cases |
|---|---|
| Signer | LocalSigner, Amber (NIP-55), bunker (NIP-46), READ_ONLY (hidden/nudge) |
| Membership | Pro Kitchen member, non-member 0 credits, non-member with credits, membership service down (fails closed) |
| Wallet | NWC, Spark, none (external fallback), in-app timeout → fallback same invoice |
| Image | extension URL, extensionless rescued host, dead link (dead-end, credit NOT spent), multi-image, imageless (no trigger) |
| Publish | happy path, signer rejects, relay timeout → retry same event, verify no duplicate reply |
| Edge | rate limited (429 copy), resume flow paid-while-closed, disclosure defaults per mode |

---

## Explicitly out of scope

- Any server/endpoint changes (including revisiting fail-closed — that's
  frontend issue #512, not this port).
- iOS/Capacitor parity.
- Streaming drafts, image upload (the endpoint takes URLs only — OpenAI
  fetches; our infra never does).
- Changing rate limits, price, or the disclosure string (product spec).
