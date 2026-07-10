# Cheffy Note Review — pre-release code audit (Phase 6 / PR #154)

Audited 2026-07-09 against `main` @ `cd5d00a` (Phases 1–5 merged) plus the
#154 branch. Every claim below was verified in source, not taken from the
plan. Ratings: **BLOCKER** (hold the flag flip), **SHOULD-FIX** (fix
before or immediately after release), **NOTE** (record; act at leisure).

**Verdict: HOLD #154.** Two blockers, both in the account-switch ×
money-path composition — an axis neither the unit suites nor the QA
checklist covers (both assume a single identity per session). All four
top findings share one root cause: note-review session state (header
cache, pending-invoice store, open sheet, running jobs) is scoped to the
process/app, while identity is scoped to the account. The fixes are
small and contained.

---

## 1. Money paths

### B1 (BLOCKER) — Pending-invoice store is global; a second account can be shown, and pay, the first account's invoice

`NoteReviewPreferences` uses one process-global prefs file
(`NoteReviewPreferences.kt:46,83` — `"note_review_prefs"`, no pubkey in
the name), unlike the house per-account pattern
(`ZapPreferences.kt:15` — `prefsName(pubkeyHex)`).

The failure sequence, all in verified code:

1. Account A taps Buy → invoice minted and stored globally
   (`NoteReviewViewModel.kt:364-366`). Invoices live ~10 minutes.
2. User switches to account B (one drawer tap; `Navigation.kt:434-450`).
3. B opens Note Review, hits the upsell, taps Buy. Buy-time resolution
   loads A's stored invoice (`NoteReviewViewModel.kt:324-326`) and
   status-checks it **with B's signer** (`:328`). The server 403s the
   pubkey mismatch (`credit-status +server.ts`: `metadata.pubkey !==
   authPubkey → 403`), which `mapCreditStatusResponse` maps to `Error`
   (`ZapCookingApi.kt:579-581`).
4. A failed check is deliberately treated as pending (invariant 6,
   `NoteReviewViewModel.kt:338-343`), and A's invoice is clock-live →
   **B is shown A's bolt11** (`:344-352`). If B pays it, the server
   credits the pubkey the invoice is bound to — **A. B's 21 sats buy A a
   draft.**
5. Variant: if A's invoice is clock-expired at step 4, B's mint
   **overwrites** the global store entry (`:364-366`) — if A had paid
   and closed before the poll observed it, A's resume can no longer find
   the invoice (the exact orphan the buy-time resolution exists to
   prevent, reintroduced across accounts).

Web has the same global-localStorage shape, but Android ships
first-class in-app multi-account switching, so exposure is materially
higher. **Unpinned** — no test covers any cross-account scenario.

*Remedy:* per-pubkey prefs file (one-line, house pattern), or persist the
owner pubkey with the invoice and ignore/clear entries whose owner ≠ the
active signer.

### B2 (BLOCKER) — Nip98HeaderCache is not identity-scoped and survives account switch

The cache key is `(method, normalizedUrl, payloadHash)` — no pubkey
(`Nip98HeaderCache.kt:33,88-92`). The cache lives inside the
`ZapCookingApi` instance (`ZapCookingApi.kt:39`) constructed once per
`FeedViewModel` (`FeedViewModel.kt:323`), and **account switch reloads
FeedViewModel in place** (`Navigation.kt:434-450`,
`FeedViewModel.kt:505-518`) — nothing recreates the api or clears the
cache.

So for 30s (the TTL) after a switch, any note-review request whose
(method, URL, body-hash) matches one account A signed is sent with
**A's Authorization header**, without ever consulting B's signer
(`Nip98HeaderCache.authHeader` returns the cached entry before touching
the signer). The crisp money case: the credit-invoice body is always
exactly `{}` (`ZapCookingApi` `EMPTY_JSON_BODY`), so the key is
identical across accounts — B buying within 30s of A's mint attempt
mints an invoice **bound to A's pubkey**. Credit-status GETs share keys
trivially (u-tag excludes the query by design).

**Unpinned** — the cache suite (`Nip98HeaderCacheTest`) covers TTL,
keying, and 401 retry, never a signer change.

*Remedy:* add the signer's pubkey to `Key` (the signer is already a
parameter of `authHeader`/`withAuthHeader`) — a one-line key change —
and/or drop the cache on `reloadForNewAccount()`.

### Verified sound (money)

- **Single live invoice within one identity** — every entry point funnels
  through buy-time resolution before minting (`:319-356`); Buy is
  phase-guarded with PAYING set synchronously (`:305,310-317`), so no
  interleaving of buy/resume/buy-time can double-mint in-session.
  *Pinned:* `buyWhileALiveInvoiceExists_rePresentsIt_neverMintsASecond`,
  `resume_pending_isReusedAtBuyTime_neverTwoInvoices` (both enforce
  structurally: no invoice response queued, a re-mint hangs the test).
- **Crediting only on server observations** — three crediting sites
  (poll `:451-453`, buy-time `:334-335`, resume `:182-185`), each gated
  on a server `checkCreditStatus` PAID; wallet results are advisory only
  (`:406-411`). *Pinned:* `advisoryFailure_doesNotBlockTheCredit…`,
  `inAppSuccess_…`. See N1 — this is broader than the literal "poll is
  the only code that credits," but the invariant's intent holds.
- **Persistence cleared only by paid/expired observations** — the six
  `clearPendingInvoice` call sites (`:183,186,334,353,451,456`) are all
  downstream of a server PAID/EXPIRED; close/open/startOver never clear.
  *Pinned:* `mintPersistsTheInvoice…`, `pollObservedExpiry…`,
  `resume_paid/expired/checkFailure…`.
- **payInvoice ownership** — `inAppPayJob` in `viewModelScope`, launched
  on `Dispatchers.Default` (`:400`); the 30s race cancels only the await,
  never the attempt (`:404-405`). See N2 for the sheet-close behavior.

### S2 (SHOULD-FIX) — Back during an in-flight mint orphans the invoice client-side

`backFromPaying` cancels `payJob` (`:419-420`). If cancellation lands
after the HTTP request is sent but before `storePendingInvoice`
(`:364-366`), the server has minted an invoice the client never records:
it isn't shown, isn't stored, and the next Buy mints a second one. No
double-pay risk (the orphan is never presented), but it burns the
6/hour invoice budget and breaches the letter of invariant 5. Web cannot
hit this (JS fetch isn't cancelled). **Unpinned.**

*Remedy:* handle the mint response under `withContext(NonCancellable)`
(state + store), then gate `startPoll`/`routePayment` on
`phase == PAYING`.

## 2. Identity

### S1 (SHOULD-FIX) — The whole note-review session survives account switch

`onSwitchAccount` (`Navigation.kt:434-450`) resets feeds, wallet, and
groups but never clears `noteReviewTarget` and never calls
`noteReviewViewModel.onSheetClosed()`. The sheet host lives outside the
NavHost (`Navigation.kt:4723+`), so `popUpTo(0)` navigation does not
unmount it, and the ViewModel is activity-scoped. Consequences:

- A running poll/pay/draft job continues under **account A's captured
  signer** while the app session is now B's.
- If the sheet stays interactable, Post wires `feedViewModel.signer` at
  tap time (`Navigation.kt:4775-4780`) — **now B** — so a draft
  requested (and possibly paid for) as A would be signed and published
  by B.

**Unpinned.** *Remedy:* in `onSwitchAccount`, clear `noteReviewTarget`
and call `onSheetClosed()` (the `walletViewModel.suspendForAccountSwitch`
precedent, one line away in the same block).

### Prefs scoping summary

- `PendingInvoiceStore`: global → **B1** (money).
- Disclosure prefs: global → accounts share the "via Cheffy" toggle
  memory. Cosmetic; web's localStorage is equally global. **NOTE (N10),**
  fixed for free by the B1 remedy if per-pubkey scoping is chosen.

## 3. Cross-phase compositions

- **(a) Footer × retry — sound, structurally.** The retry path is
  `publishSigned(event, parent)` — it has no content parameter at all
  (`NoteReviewReplyPublisher.kt:70,102`), and the footer is applied in
  exactly one place, `post()`'s hand-off (`NoteReviewViewModel.kt:534`).
  *Pinned:* `postTimeoutRetry_neverReappliesTheFooter` (single footer
  occurrence + publish() never re-entered).
- **(b) Auto-run after crediting — correct but unpinned.** `onCredited`
  calls `run(mode, …)` (`:485`), and `run` reads live state at request
  time (`:637` — `st.imageUrl` derives from `selectedImageIndex`;
  `disclosureOn` is untouched by `run`/`onCredited`, and re-seeding only
  happens in `choose()` from CHOOSE). So the paid auto-run carries the
  selected image and the in-session toggle. *Unpinned:*
  `paidObservation_creditsAndAutoRunsThePendingMode` asserts mode only —
  extend it to pin image + toggle (N6).
- **(c) Paid arriving per phase.** The poll only exists between
  `startPayment` and a terminal/cancel point, so PAYING is the only
  phase a poll observation normally lands in. The member CANNOT be in
  DRAFT with a live poll: leaving PAYING goes through credited
  (poll ends, then LOADING→DRAFT), expired (ends), Back (cancels,
  `:418-421`), or close/open/startOver (cancel). The one race: Back and
  paid in the same instant — `onCredited` has no phase guard (`:472`),
  so a just-returned-to-UPSELL session can be yanked into LOADING→DRAFT.
  Money-correct (the credit is real and auto-run consumes it as
  designed), mildly surprising UX. **NOTE (N3)**; remedy: auto-run only
  when `phase == PAYING`, banner-only otherwise (the resume precedent).
  `onInvoiceExpired` DOES guard the phase flip (`:492-500`) — sane in
  every phase.
- **(d) Sheet close per phase.** `onSheetClosed` → `cancelAllWork`
  cancels all six jobs (`:198-209`: request, post, pay, poll, inAppPay,
  resume); both close paths call it (`Navigation.kt:4743-4749`,
  `:4794-4799`). *Pinned for the poll:*
  `sheetClose_cancelsAHangingPoll_withoutLeakingTicks` (worst case: hung
  on the wire). Closing during POSTING abandons the OK-await after the
  event may have been broadcast — the reply can exist without the user
  seeing "Posted"; acceptable and inherent to closing mid-publish
  (**NOTE, N11**). Stale VM state after close is inert: `open()` resets
  wholesale (`:153-171`).

## 4. Concurrency

- **Post** — `canPost` enforced in `post()` with POSTING entered
  synchronously (`:522,535`). *Pinned:* `post_isANoOpFromEveryNonDraftPhase`
  including a hanging-publisher POSTING double-tap.
- **Buy** — guarded (`:305` + synchronous PAYING `:310`), but the test
  (`buy_isANoOpOutsideUpsell`) only exercises CHOOSE; a PAYING
  double-tap is guarded by the same line yet unpinned as such
  (**NOTE, N7**).
- **Regenerate / mode double-tap** — job-replacement semantics
  (`requestJob?.cancel()` + relaunch, `:611-613`): last tap wins, one
  live request. Mode cards leave composition on the synchronous SIGNING
  flip. Sane; unpinned.
- **Image selection mid-request** — `selectImage` is a bounds-guarded
  state write (`:217-221`; out-of-range pinned). The picker isn't
  rendered during SIGNING/LOADING, and `run` reads state at launch, so
  the worst case is the newest selection winning. *Pinned for the
  draft-exists case:* `selectingAnImageWithADraft_onlyArmsTheNextRequest`.

## 5. Server-trust boundaries

- **Dead-end is structurally message-less** — `NoteReviewResult.DeadEnd`
  is a `data object` (no message field); the API layer collapses
  NOT_FOOD/IMAGE_UNREADABLE before any string survives
  (`ZapCookingApi.mapNoteReviewResponse`), and the UI line comes from the
  local pool. *Pinned:* `map_notFoodAndImageUnreadable_collapseToDeadEnd`,
  `consecutiveDeadEnds_endToEnd…` (asserts the server line never leaks).
- **NOT_MEMBER while balance believed positive** → UPSELL, server
  authoritative. *Pinned:*
  `draftResponsesUpdateTheVisibleBalance_andNotMemberIsAuthoritative`.
  However `creditsRemaining`/`resumeAck` are retained, so a later CHOOSE
  render can show a stale "you have N drafts" banner (**NOTE, N4**;
  remedy: clear both on NotMember).
- **Server strings in UI** — beyond the two sanctioned paths (429 line,
  mint-failure payError — pinned by
  `invoiceMintFailure_fallsBackToUpsellWithTheServerLine`), the ERROR
  phase's sub-line renders `resp?.error` from the default mapping arm
  (`ZapCookingApi.kt:534-535` → sheet `NoteReviewSheet.kt:160-161`),
  including 401's \"Authentication required\". This mirrors web's
  `phaseForResult` default arm and the plan's `Error(message)` contract —
  sanctioned-by-parity, but it IS a server-string-to-UI path the audit
  brief didn't enumerate (**NOTE, N5**).

## 6. Release hygiene

- **#154 diff** — verified: `FeatureFlags.kt` + `CHEFFY_NOTE_REVIEW_PLAN.md`
  + `QA_NOTE_REVIEW.md` + `RELEASE_NOTES.md` only; nothing else under
  `src/main` (`git diff main...HEAD --stat`).
- **Logging** — no `Log.*`/`println` in any note-review surface
  (ViewModel, sheet, publisher, prefs, cheffy/, API); no bolt11, invoice
  id, pubkey, or draft content is logged anywhere in the feature. The
  only logging in touched shared files predates the feature
  (e.g. ComposeViewModel's GALLERY tag).
- **Kill switch** — effective by construction: the flag gates the single
  trigger (`PostCard.kt:456`); the sheet host renders only when
  `noteReviewTarget != null` (`Navigation.kt:4723`), and the only
  setters of that state are the three flag-gated menu wires
  (`Navigation.kt:1237, 2511, 3622`). No nav route reaches the sheet.
  With the flag false, the VM, resume check, prefs, and publisher are
  unreachable. *Unpinned* (no Compose UI test asserts flag-off
  invisibility — acceptable for a compile-time const, **NOTE, N8**).

---

## Finding index

| # | Rating | One-line remedy |
|---|---|---|
| B1 | BLOCKER | Scope `NoteReviewPreferences` per pubkey (house `prefsName(pubkeyHex)` pattern) or store+enforce the invoice's owner pubkey. |
| B2 | BLOCKER | Add the signer pubkey to `Nip98HeaderCache.Key` (and/or clear the cache in `reloadForNewAccount`). |
| S1 | SHOULD-FIX | `onSwitchAccount`: clear `noteReviewTarget` + call `onSheetClosed()`. |
| S2 | SHOULD-FIX | Mint response handling under `NonCancellable`; gate poll/route on `phase == PAYING`. |
| N1 | NOTE | Document: crediting = any server PAID observation (poll, buy-time, resume), not the poll alone. |
| N2 | NOTE | Sheet close cancels the in-flight wallet attempt; resume is the money safety net — consider an app-scope if provider-side abandonment matters. |
| N3 | NOTE | `onCredited`: auto-run only from PAYING, banner otherwise. |
| N4 | NOTE | Clear `creditsRemaining`/`resumeAck` on NotMember. |
| N5 | NOTE | ERROR sub-line renders server `error` strings (web-parity default arm) — accepted, recorded. |
| N6 | NOTE | Extend `paidObservation…` test to pin image selection + disclosure toggle through auto-run. |
| N7 | NOTE | Add a PAYING double-tap case to the Buy no-op test. |
| N8 | NOTE | Kill-switch is inspection-verified; no automated flag-off test. |
| N9 | NOTE | Hygiene clean: #154 diff minimal; no sensitive-value logging. |
| N10 | NOTE | Disclosure prefs are global across accounts (cosmetic; solved by B1's per-pubkey remedy). |
| N11 | NOTE | Close during POSTING can publish without showing "Posted" — inherent, acceptable. |

**Recommendation:** fix B1 + B2 (+S1, ideally S2) in one small
`fix(note-review)` PR with cross-account tests (fake a signer swap over
a warm header cache; two-store/two-pubkey invoice scenarios), then
re-run this audit's §1–2 checks and proceed with the QA checklist and
the #154 merge. The device QA doc should also gain one row: switch
accounts mid-payment and verify the sheet closes and no cross-account
invoice appears.
