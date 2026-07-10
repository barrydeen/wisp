# Cheffy Note Review — release QA checklist (Phase 6)

Device checklist for the rows the Phase 3/4 smoke sessions did **not**
pre-clear (see the final matrix in
[`CHEFFY_NOTE_REVIEW_PLAN.md`](CHEFFY_NOTE_REVIEW_PLAN.md) §Phase 6).
**The flag-flip PR merges only after this passes.** Build:
`./gradlew installZapstoreDebug` from the Phase 6 branch
(`NOTE_REVIEW_ENABLED = true`).

Pre-cleared 2026-07-09 (re-verify only if the area changed since): happy
path both modes, Amber sign + reject, READ_ONLY/imageless trigger gating,
regenerate + header-cache behavior, dead-link dead-end rendering, sheet
lifecycle, disclosure footer defaults.

## Prep (once)

**Identities**
- **Key M** — Pro Kitchen member (Part B only).
- **Key N** — non-member with **0 credits**, imported on BOTH this device
  and a web browser at zap.cooking (the ledger checks need the same key
  in both places). Rate limit is 8 drafts/hour per pubkey and web+Android
  share it — Part A budget-counts every draft.

**Wallets** (all on Key N's account)
- NWC connection to a working wallet.
- Funded Spark wallet.
- A deliberately broken NWC connection (URI pointing at an unreachable
  relay) for the timeout row.
- Any external Lightning wallet (different device or app) that can pay a
  QR/copied invoice.

**Test notes** (post from any throwaway account beforehand)
- GOOD: a kind-1 with one clear dish photo (normal `.jpg`/`.png` URL).
- DEAD: a kind-1 whose image URL is a well-formed nostr.build link to a
  nonexistent hash (nostr.build never 404s — the CDN serves a fallback
  image, which is exactly the NOT_FOOD trap the dead-end copy hedges).
- HOSTED: a kind-1 whose image URL is extensionless on a rescue-list host,
  e.g. `https://image.nostr.build/<hash>` with no `.jpg`.
- MULTI: a kind-1 with 3 distinct dish photos.

---

## Part A — one non-member session (Key N), in this order

The order matters: A4 leaves exactly one unspent credit for the ledger
checks, and A6b leaves exactly one for the dead-link no-spend check.
Draft budget: this part performs ~5 Android drafts + 1 web draft — well
inside 8/hour if done in one sitting; A8 then intentionally exhausts it.

### A1 — Upsell card (non-member, 0 credits) `[wallet: any]`
1. On GOOD, open the note menu → "Ask Cheffy about this dish".
2. Pick "Guess the recipe".
- [ ] Signing → loading → **upsell card** (never an error): "Cheffy photo
      review is a Pro Kitchen feature", the example-draft block, primary
      "View membership", "⚡ 21 sats for one draft", and "Tied to your
      Nostr key — works on any device."

### A2 — NWC in-app purchase → paid auto-runs the pending mode `[wallet: working NWC]`
1. From A1's upsell, tap "⚡ 21 sats for one draft".
- [ ] PAYING shows "Paying from your wallet — drafting starts the moment
      it lands." — the wallet-waiting state, **not** the QR.
- [ ] Within a few seconds of the NWC payment settling, the sheet moves
      on its own into loading → **draft** (recipe mode — the mode picked
      in A1 auto-ran; no re-selection was asked).
2. Edit a word, Post.
- [ ] Posted state; "View your reply" opens the thread with the reply.

### A3 — Spark in-app purchase `[wallet: Spark]`
1. Switch the account's wallet to Spark. On GOOD → "Say something nice"
   → upsell (balance is 0 again — A2's credit was spent by its draft).
2. Tap the sats button.
- [ ] Wallet-waiting state → Spark pays → auto-run → **comment** draft.
3. Discard (Start over → dismiss).

### A4 — External path + resume paid-while-closed `[wallet: none]`
1. Disconnect/remove the wallet (mode NONE). On GOOD → any mode → upsell
   → sats button.
- [ ] QR + copy + "Open in wallet" appear **immediately** (no
      wallet-waiting state).
2. Copy the invoice. **Close the sheet** (swipe down).
3. Pay the copied invoice from the external wallet.
4. Reopen "Ask Cheffy" on the same note.
- [ ] Choose screen shows "⚡ Payment received — you have 1 draft. Tied
      to your Nostr key — works on any device."
5. STOP — do not draft. Balance is now exactly **1 unspent credit**.

### A5 — Cross-platform ledger, both directions `[same Key N on web]`
a) **Android → web**: on zap.cooking (Key N), open a food note's Cheffy
   review and request a draft.
- [ ] The web drafts **without asking for payment** (it spent the credit
      bought on Android in A4) and its credit chip shows 0 remaining.
b) **web → Android**: on web, buy exactly one 21-sat credit (pay the
   bc-modal invoice) and close the web modal **without drafting**.
- [ ] Balance is now exactly 1, purchased on web. (Verified by A6 below
      running without an upsell.)

### A6 — Dead link does NOT spend the credit `[balance: exactly 1]`
1. On DEAD → "Ask Cheffy" → either mode.
- [ ] Dead-end line from the hedged pool ("couldn't get a good look" /
      "playing hard to get" register) — **never** a confident "that's not
      food" and never a payment ask.
2. Back → dismiss. On GOOD → "Ask Cheffy" → either mode.
- [ ] Drafts **without an upsell** — the dead-end in step 1 did not
      consume the web-bought credit; this draft does. (This is the
      server's success-only spend observed end-to-end.)

### A7 — In-app timeout → fallback re-presents the SAME invoice `[wallet: broken NWC]`
1. Connect the broken NWC. On GOOD → any mode → upsell → sats button.
- [ ] Wallet-waiting state holds for ~30s.
- [ ] Then: "Your wallet didn't answer. The invoice is still good — pay
      it another way." + the QR/copy affordances.
2. Pay the **shown** QR from the external wallet (do not go Back first).
- [ ] Poll credits → auto-run → draft. Exactly one 21-sat payment left
      your external wallet across this whole row (same bolt11 — no
      second invoice was minted).

### A8 — Rate limit copy `[any wallet]` (LAST — exhausts the hour's budget)
1. On GOOD, regenerate repeatedly until the hourly budget (8, shared with
   every draft above and web's A5a) is exceeded.
- [ ] Error state with "Cheffy needs a breather — you've hit the
      photo-review limit for now." — retryable, not a crash, not an
      upsell.
- [ ] If any credits remained, they were NOT consumed by the 429s
      (re-verify balance later once the window resets).

## Part B — remaining rows, any identity (Key M is fine)

### B1 — Extensionless rescued host `[Key M]`
1. On HOSTED → note menu.
- [ ] "Ask Cheffy about this dish" appears (extensionless
      `image.nostr.build` rescued — finding 0.5 parity).
2. Draft.
- [ ] Server accepts the URL (no 400 / instant error) and drafts.

### B2 — Multi-image picker `[Key M]`
1. On MULTI → "Ask Cheffy".
- [ ] Thumbnail strip on Choose; first photo selected by default.
2. Select photo #3, pick "Say something nice", land on the draft.
- [ ] Strip is also on the Draft phase with #3 still selected.
- [ ] The draft plausibly describes photo #3, not #1.
3. Regenerate.
- [ ] Selection stays on #3; the new draft still reflects #3.

### B3 — Publish relay-timeout retry, no duplicate `[Key M]`
1. Draft on GOOD, then cut connectivity (airplane mode) and tap Post.
- [ ] Publish fails safe: "The relays didn't take that one. Your draft
      is safe — give it another go." — the edited draft is intact.
2. Restore connectivity, Post again → posted. Open the thread.
- [ ] Exactly ONE reply.
3. Best-effort timeout variant (needs a throttled network or a very slow
   relay set): if Post hangs past 15s, expect the PostTimeout copy ("…
   give it another push, and Cheffy won't ask your signer twice"), tap
   "Give it another push" with **no signer prompt** (Amber stays silent —
   same signed event), then verify the thread still shows exactly ONE
   reply.

## Part C — needs backend cooperation

### C1 — Membership service down fails CLOSED `[Key M]`
Requires ops to take the pantry membership route down (or a staging env
with `RELAY_API_SECRET` unset). With it down, request a draft as a
member.
- [ ] "Cheffy can't check your membership right now. Please try again
      shortly." — retryable, and **never** the upsell (deviation D5: we
      don't dun a member over our outage).
- If ops time isn't available, record a skip here — the mapping is
  unit-tested (`membershipUnavailable_isRetryableError_notUpsell`) and
  the fail-closed behavior is server-side.

---

**Sign-off:** all Part A + B rows checked (C1 checked or skip recorded) →
merge the Phase 6 PR.
