# Zap Cooking — Android Build Spec

Single running doc that owns the adaptation of this Wisp fork into the
**Zap Cooking** Android app. Same role as `WALLET_PARITY.md`: agents
read this first, execute one concern per PR, stop for confirmation, and
keep this doc current as state evolves.

**Premise:** this fork already ships a production-grade Nostr client
(Spark wallet, NIP-57 zaps, NIP-17 DMs, NIP-65 outbox routing, NIP-23
article rendering, drafts, on-device ML spam filter, ObjectBox cache).
Zap Cooking is a thin food-first layer on top. We do **not** rebuild
Nostr plumbing and we do **not** port all 40+ web routes.

**Backend-as-API rule:** AI and membership are server-side on
`zap.cooking`. The app NEVER holds OpenAI/Strike/Stripe keys. It calls
HTTPS endpoints; it does not reimplement them in Kotlin.

> Verified against `zapcooking/frontend` and `zapcooking/zap_cooking_android`
> at fork time. Where this doc and the README disagree, this doc wins
> (the README still advertises removed features — see §6).

---

## 1. Protocol & API contracts (source of truth)

### Recipes (Nostr)
- **Recipe:** `kind 30023` (NIP-23 long-form). Feed filter:
  `{ kinds: [30023], "#t": ["zapcooking", "nostrcooking"] }`
  (`zapcooking` = new, `nostrcooking` = legacy — support both).
- **Premium/gated recipe:** `kind 35000`, tag `zapcooking-premium`.
  Body gated on active membership.
- Wisp already renders 30023 (ArticleScreen) — branch it for the recipe
  layout, don't reinvent it.

### Backend endpoints (base `https://zap.cooking`)
AI (OpenAI-backed, all server-side — the app only calls them):
- `POST /api/extract-recipe` (+ `/public`) — recipe import
  (image/url/text → normalized recipe). `url` free + IP-rate-limited;
  `image`/`text` require active membership.
- `POST /api/zappy` (+ `/zappy/scan`) — **Cheffy.** Conversational
  assistant: `{ messages }` chat history, modes `chat` and `hungry`
  ("what can I make"), `scan` for image input.
- `POST /api/nourish` (+ `/nourish/scan`) — nutrition intelligence;
  member-gated; backed by a scoring engine.
- `POST /api/cookbook-intro` — AI intro copy for recipe books (rides
  with the cookbook/recipe-books commerce feature; later phase).

Membership:
- `GET /api/membership?pubkey=<hex>` — **public batch read** of status
  (no auth). Use for displaying a user's own/others' member state.
- `POST /api/membership/check-status` — **NIP-98 verified**
  (`verifyNip98`). This is the real auth round-trip.
- Status source: `pantry.zap.cooking/api/members/{pubkey}`.
- **Purchase is out of app.** A "Become a member" entry point opens the
  `zap.cooking` membership page in a Custom Tab. No in-app Lightning
  checkout, no Strike/Stripe in the binary. The app only READS status.

### NIP-98 (the linchpin)
- Add `nostr/Nip98.kt` (kind 27235): `u` tag (exact request URL), `method`
  tag, optional `payload` sha256 for POST bodies, fresh `created_at`,
  header `Authorization: Nostr <base64(event)>`.
- Reference client: frontend `$lib/nip98` `signNip98AuthHeader`.
  Verifier: frontend `src/lib/nip98.server.ts`. **Match the verifier's
  byte reconstruction exactly** — URL canonicalization (query included)
  and method casing are the known footguns.
- Sign via the `NostrSigner` abstraction. **This fork is LocalSigner
  only** — Amber/NIP-55 remote signing was removed (§6). `READ_ONLY`
  accounts have no key and cannot sign NIP-98; gate member-only AI
  features behind "account has a signing key."

### Relays (role-based — mirror the web; do NOT collapse to one)
- `default` (general): `nos.lol`, `relay.damus.io`, `relay.primal.net`
- `members`: `wss://pantry.zap.cooking` (The Pantry — members only)
- `discovery`: `nostr.wine`, `relay.primal.net`, `purplepag.es`
- `profiles`: `purplepag.es`
- `articles` (kind 30023 = **recipes**): `relay.primal.net`, `nos.lol`,
  `relay.damus.io`, `nostr.wine`, `eden.nostr.land`, `relay.noswhere.com`
- **Recipes live on the public article relays, not on Pantry.** Adding
  Pantry as the members relay is correct; replacing the aggregators is
  not — it breaks recipe loading. Leave `RelayProber.BOOTSTRAP` and the
  discovery hardcodes alone.

---

## 2. Distribution & flavors
Target **Zapstore** (primary) and **Google Play**.
- Gradle product flavors `zapstore` and `play`.
- `zapstore`: links out to the web membership page freely, always.
- `play`: linking out to web purchase is currently permitted for US
  users under the Epic v. Google injunction (in effect through Nov 2027,
  fees pending). Keep the membership entry point behind a flavor/remote
  flag so it can be geo-gated, hidden, or swapped to Play Billing without
  a rewrite.
- The app never processes payment in-app, so the entire store-policy
  blast radius is one button.
- `applicationId` `cooking.zap.app` is **permanent once on Play** —
  confirm final before it ships.

---

## 3. Phases (stop-gated; one concern per PR, surgical diffs)

### Phase 0 — Rebrand + foundation
Concern 0: this doc committed (system of record).
Concern 1: fix + rebrand CLAUDE.md (ObjectBox is used — the "no
database" claim is wrong; note Amber removed; point here).
Concern 2: `Nip98.kt` + `ZapCookingApi` (reuse `HttpClientFactory`,
`Dispatchers.IO`). Smoke test against `POST /api/membership/check-status`
(it verifies NIP-98), **not** the public GET.
Concern 3: package rename `com.wisp.app -> cooking.zap.app` (mechanical;
keep `wisp_*` storage strings, ObjectBox UIDs, and class names untouched
— see §5). Fold in a minimal `zapstore`/`play` flavor skeleton here.
Concern 4: branding (app name, icon, splash, M3 tokens, user-visible
strings, client tag, User-Agent; class-name rebrand optional).
Concern 5: relays — add Pantry as members, align `default` to the web
set, leave bootstrap/discovery aggregators in place.
**Gate:** builds/installs; a real NIP-98 round-trip the backend accepts;
no "Wisp" in UI/CLAUDE.md/README; package renamed; flavors build;
relays correct.

### Phase 1 — Recipes + foodstr feed
RecipeRepository (30023 + `#t` filter, naddr fetch, 35000 gating);
RecipeDetailScreen (branched from ArticleScreen); CookMode (screen-on,
timers, scaling); home = recipes + `#foodstr`. Recipe reads target the
`articles` relay set.

### Phase 2 — Recipe import + Cheffy
Native camera + share-target import → `extract-recipe`. Cheffy chat
screen → `zappy` (chat/hungry/scan). [Confirm: Cheffy chat in v1 or
fast-follow — pending decision.]

### Phase 3 — Membership + premium
`MembershipRepository` (public GET status + cache); flavor-gated
"Become a member" Custom Tab link; unlock 35000 + member AI on active
status. No in-app checkout.

### Phase 4 — Nourish + polish
NourishSheet → `nourish`; push notifications; saved-recipes (NIP-51);
cookbook-intro rides with recipe-books if/when that ships.

---

## 5. Package-rename rules (Concern 3)
- `git mv` the `com/wisp/app` tree (main + test) so history follows.
- Rewrite only package/import tokens across the 255 `.kt` files — not
  arbitrary "wisp" substrings.
- Change `namespace`, `baseApplicationId`, `rootProject.name`. Manifest
  relative class refs and `${applicationId}.fileprovider` auto-resolve.
- Do **not** touch: `wisp_*` (Encrypted)SharedPreferences name strings,
  ObjectBox entity UIDs, or class names. The `applicationId` change alone
  gives the app a new sandbox — renaming storage strings adds pure
  orphaning risk for zero benefit. Class-name rebrand is Concern 4.
- Verify: `./gradlew clean assembleDebug` + grep shows zero
  `com.wisp.app` in source and only intended id strings changed.

---

## 6. Known fork deltas from the README
- **Amber / NIP-55 removed.** `SigningMode` is `LOCAL`/`READ_ONLY` only;
  `KeyRepository.migrateRemoveRemoteSigner()` purges remote accounts on
  launch; `NostrSigner` has only `LocalSigner`. README's remote-signing
  copy is stale — fix in doc cleanup.
- **ObjectBox is in use** (`db/`, `objectbox-models/default.json`).
  CLAUDE.md's "no database" line is wrong; README §Performance is right.

---

## 7. Conventions
One concern per PR; investigate before coding; surgical diffs. New NIPs
are standalone `nostr/NipXX.kt` objects. Network off-main-thread;
`StateFlow` for UI, `SharedFlow` for relay events. Any backend-contract
change lands here before the PR merges. Keep this doc current.
