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
  Body gated on active membership. **All 35000 handling is deferred to
  Phase 3** (it's meaningless without the membership check). ⚠️ Live probe
  (Step 0, Phase 1) found bare `kind 35000` is **squatted by an unrelated
  app** (events carry `sender`/`status` tags + JSON order payloads) and
  **zero** real `zapcooking-premium` events exist on the public relays or
  Pantry. When Phase 3 builds this, the filter MUST be tag-qualified
  (`#t: zapcooking-premium`), never bare `kinds: [35000]`. Phase 1 feed
  filters stay `kinds: [30023]` only — premium simply doesn't surface,
  which is correct.
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
  (`verifyNip98`). This is the real auth round-trip. Body `{ pubkey }`;
  the signing pubkey must equal the body pubkey. Success signal is
  `owner: true` in the response — an absent/invalid/mismatched signature
  **silently degrades to the public shape** (it does NOT 4xx). Route is
  also gated by the server flag `MEMBERSHIP_ENABLED`; a `403 Forbidden`
  there means the flag is off, not a bad signature.
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
  byte reconstruction exactly** — URL canonicalization and method casing
  are the known footguns. Correction to an earlier assumption: the `u`
  tag is `origin + pathname` ONLY — `normalizeUrl` **drops the query
  string and fragment** and strips a trailing slash on non-root paths
  (both sides normalize identically, so including the query buys
  nothing). The canonical auth-event JSON is key-ordered
  `id,pubkey,created_at,kind,tags,content,sig` with `created_at` as a
  number, base64-encoded behind the `Nostr ` prefix.
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
Concern 0: ✅ this doc committed (system of record).
Concern 1: ✅ fix + rebrand CLAUDE.md (ObjectBox is used — the "no
database" claim is wrong; note Amber removed; point here). README
remote-signing copy fixed; full README product-rebrand deferred to
Concern 4.
Concern 2: ✅ (code) `Nip98.kt` + `ZapCookingApi` added, reusing
`HttpClientFactory` on `Dispatchers.IO`; byte-for-byte unit test passes
against frontend goldens. **Remaining:** on-device NIP-98 round-trip
against `POST /api/membership/check-status` (assert `owner: true`) — the
JVM env here has no device/emulator.
Concern 3: ✅ package rename `com.wisp.app -> cooking.zap.app` (mechanical
git-mv + package/import token rewrite across 259 .kt; namespace +
baseApplicationId + rootProject.name; `wisp_*` storage strings, ObjectBox
UIDs/model, and class names left untouched — see §5). Folded in the
config-free `zapstore`(default)/`play` flavor skeleton. Verified: clean
`assembleZapstoreDebug` builds (applicationId `cooking.zap.app(.debug)`,
fileprovider authorities auto-resolved), unit suite 8/0/0/0, zero
`com.wisp.app` in source. NOTE: flavors make `testDebugUnitTest` ambiguous
— use `testZapstoreDebugUnitTest`.
Concern 4: ✅ branding — app name "Zap Cooking"; "zapcooking" M3 preset
(default) wired from web src/app.css (primary #ec4700/#ff5722, brand
surfaces/text, danger #dc2626/#ef4444); user-visible strings + 11 locales;
client tag "Zap Cooking" (matches web), User-Agent ZapCooking/1.0;
onboarding card + seed + crash recipient -> Zap Cooking account
(319ad3e7…); launcher icon (orange bg + pan mark) + splash + in-app logo
from frontend assets; README rebranded with MIT/Barry-Deen attribution
preserved. Left (optional): WispApp/Theme.Wisp identifiers, dead
WispLogo.kt, unused <API26 legacy mipmaps. Verified assembleZapstoreDebug.
Concern 5: ✅ relays — `RelayConfig.DEFAULTS` aligned to the web `default`
set (nos.lol, relay.damus.io, relay.primal.net); added
`RelayConfig.MEMBERS_RELAY = wss://pantry.zap.cooking` (not auto-added to
non-members; consumed in Phase 3). `DEFAULT_INDEXER_RELAYS`,
`RelayProber.BOOTSTRAP`, and DM relays left intact.
Concern 6: ✅ relay sovereignty (off Wisp infra; before Phase 1) —
`Nip29.DEFAULT_GROUP_RELAYS` → `wss://pantry.zap.cooking`; onboarding
no longer injects `relay.wisp.talk` into new accounts' NIP-65 (new users
get `DEFAULTS` + Pantry as write set); `relay.wisp.talk` dropped from
onboarding `ACTIVE_RELAYS`; DM invite placeholder → `pantry.zap.cooking`.
Zero `*.wisp.talk` remain in code. OPEN: `CREATOR_PUBKEYS` is fiatjaf +
the Zap Cooking account (de-Wisped, but not a curated food-creator set) —
pending a product decision on the seed follows.
**Gate:** builds/installs; a real NIP-98 round-trip the backend accepts;
no "Wisp" in UI/CLAUDE.md/README; package renamed; flavors build;
relays correct; off Wisp relay infrastructure.

### Phase 1 — Recipes + foodstr feed
RecipeRepository (30023 + `#t` filter, naddr fetch); RecipeDetailScreen
(branched from ArticleScreen); CookMode (screen-on, timers, scaling);
home = recipes + `#foodstr`. Recipe reads target the `articles` relay set.
35000/premium gating is **deferred to Phase 3** (see §1).

Step 0 (MCP/live probe, no code) is ✅: fetched 5 real `#t zapcooking`
recipes + legacy `nostrcooking` + `#foodstr` notes off the live relays and
confirmed the canonical `parseMarkdownForEditing` shape. Drift found and
now baked into the parser contract: `published_at` is **optional** (absent
on all 5 new `zapcooking` events; present on legacy `nostrcooking`) → fall
back to `created_at`; `## Details` prep/cook/servings are **all optional +
free-text** (no normalized units, e.g. "10", "30min") → tolerate missing,
never assume parseable; category t-tags follow `<root>-<category>`
(`zapcooking-italian`, …) plus a per-recipe `<root>-<slug>`; `articles`
relay coverage is uneven (`nostr.wine` returned 0 — treat the set as a
union); premium 35000 is squatted + has zero live events (see §1, deferred).

Sub-concern breakdown (one PR each, off main, no stacking):
- **1.1** ✅ (PR #7, merged) RecipeParser (`nostr/RecipeParser.kt`, mirrors
  frontend `parseMarkdownForEditing`) + `RelayConfig.ARTICLES_RELAYS`. No UI.
  Golden unit test vs the real *Tuscan Peposo* event incl. missing-
  `published_at`/`servings` and the live U+FE0F emoji bytes. 14 tests.
- **1.2** ✅ `repo/RecipeRepository.kt` — `{kinds:[30023],
  #t:[zapcooking,nostrcooking]}` reads fanned out to the `ARTICLES_RELAYS`
  **union** (not DEFAULTS), deduped by addressable coordinate
  (`kind:author:dTag`) newest-wins with the NIP-01 equal-`created_at`
  lower-id tiebreaker; `requestRecipe(author,dTag)` resolves a single recipe
  cache-first then via the same union (NOT `EventRepository.request-
  AddressableEvent`, which routes to general relays). Repo OWNS the recipe
  flow (one shared dedup for 1.3 detail + 1.5 home). Load-then-close sub
  lifecycle (closed on all relays in `finally`). Wired into `FeedViewModel`
  (service-locator). No 35000. Pure merge funcs unit-tested vs the real
  multi-relay-duplicate + older/newer-revision cases. 7 tests; suite 40/0/0/0.
- **1.3** ✅ `RecipeDetailScreen` (branched from ArticleScreen) +
  `RecipeDetailViewModel` (resolves via `RecipeRepository.requestRecipe`) +
  `nostr/IngredientScaler.kt`. Hero, summary, prep/cook/servings chips,
  chef's notes, ingredients, numbered directions. **Engagement bar
  (`ActionBar`) reused — zap/react/repost kept; only the comment THREAD is
  deferred.** Serving scaler = multiplier chips (½×/1×/2×/3×) scaling the
  **leading numeric token only** (secondary alt-measures stay unscaled);
  understands integers/decimals/unicode+mixed+ascii fractions/ranges and
  returns lines **verbatim on unparseable** (never crashes). Servings chip
  scales with the multiplier; prep/cook (free-text) don't. Route
  **`recipe/{author}/{dTag}`** (kind const 30023; `naddr` only at a future
  share/deep-link boundary) via `Routes.recipe()` which URL-encodes the
  d-tag (real d-tags carry `(`,`)`,`/`). Cook-mode hook (`onStartCooking`)
  is null in 1.3 → no dead button ships; 1.4 flips it on. The route is
  registered but not yet the destination for recipe taps — that rewiring
  rides with 1.5 (home feed). Pure `IngredientScaler` + `Routes.recipe`
  unit-tested vs the real Tuscan + Milk Bread lines and the encode cases;
  suite 54/0/0/0, `assembleZapstoreDebug` clean.
- **1.4** CookMode — keep-screen-on, step paging, inline timers, scaling.
- **1.5** Home foodstr feed — recipes (30023 `#zapcooking`) + `#foodstr`
  notes via the existing FeedScreen/HashtagFeed infra.

### Phase 1.5 — Onboarding foodstr cleanup (DEFERRED)
Tracked here so it isn't lost; **do not start until Phase 1 lands.** Closes
the Concern 6 OPEN item (`CREATOR_PUBKEYS` is fiatjaf + the Zap Cooking
account, not a curated food set) and aligns onboarding with the food-first
product:
- **Creator starter-pack seed** — replace the inherited `CREATOR_PUBKEYS`
  with a curated set of active food/`#foodstr` creators (the recipe authors
  surfaced in Phase 1 are live candidates), pending a product decision on
  who's in the pack.
- **Food-framed onboarding copy** — onboarding card/seed/empty-states speak
  food, not generic Nostr.
- **`#foodstr` discovery** — surface the foodstr/recipe feed as a first-run
  discovery affordance so new accounts have something to follow/see.
Deferred because it's product-curation + copy, not feed plumbing; Phase 1
ships the feeds it depends on.

### Phase 2 — Recipe import + Cheffy
Native camera + share-target import → `extract-recipe`. Cheffy chat
screen → `zappy` (chat/hungry/scan). [Confirm: Cheffy chat in v1 or
fast-follow — pending decision.]

### Phase 3 — Membership + premium
`MembershipRepository` (public GET status + cache); flavor-gated
"Become a member" Custom Tab link; unlock 35000 + member AI on active
status. No in-app checkout. **Premium recipes (35000) land here, not
Phase 1** — filter tag-qualified (`#t: zapcooking-premium`), never bare
kind (the kind is squatted; see §1). Verify real premium events exist on
Pantry before building the unlock path (Step 0 found none).

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
