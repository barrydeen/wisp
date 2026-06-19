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
- **1.5** ✅ Home foodstr feed — `FoodstrFeedViewModel` merges recipes
  (reusing `RecipeRepository` wholesale: `ARTICLES_RELAYS` union + coordinate
  dedup) with `#foodstr` kind:1 notes (lightweight hashtag sub on the search
  relay) into one time-sorted `FoodstrItem` stream (recipes by `publishedAt`,
  notes by `created_at`; pure merge unit-tested). `FoodstrFeedScreen` renders
  recipes as a lightweight `RecipeCard` (tap → recipe route) and notes via the
  shared `PostCard` with full inline `NoteActions`/zap. **Part A tap-rewiring:**
  `NavController.openArticleOrRecipe()` opens any 30023 as a recipe when
  `RecipeParser.isRecipe`, else the article route — with a **cache-miss guard**
  (evicted event → article fallback, never a recipe screen with no event);
  applied to all 6 `onArticleClick` sites, so recipes in the *existing* home
  feed now open the recipe screen too. Reachable via a "Recipes" drawer entry
  (`WispDrawerContent` → `FeedScreen.onRecipes` → `Routes.FOODSTR`).
  **Deferred:** the post-login default-landing swap (FEED→FOODSTR anchor
  repoint touches bottom-nav/back-stack that can't be verified without an
  emulator here — fast follow after a device smoke test). Conscious MVP
  asymmetry: notes zap inline, recipe cards zap one tap into detail.
  `FeedSubscriptionManager`/`FeedViewModel` core untouched. Suite 57/0/0/0,
  `assembleZapstoreDebug` clean.
  NOTE: 1.6 un-merged the notes out of this screen — see below.
- **1.6** ✅ OnlyFood 🍳 social feed + Recipes un-merge/rename. **Un-merge:**
  `FoodstrFeed*`/`Routes.FOODSTR` renamed → `RecipeFeed*`/`Routes.RECIPES`
  (`"recipes"`) and gutted to recipe cards ONLY (dropped the `#foodstr` note
  sub + `mergeFoodstrItems` + the merge test) — so a post never shows in two
  feeds. **OnlyFood (new, additive):** `nostr/FoodHashtags.kt` (the expanded
  ~85-tag set from web `FoodstrFeedOptimized.svelte`, deduped + unit-tested);
  `OnlyFoodFeedViewModel` — kind-1 feed with **GLOBAL/FOLLOWING** modes (v1;
  members + replies deferred). Global = all matching notes (search relay);
  Following = server-side `authors` filter from the kind-3 contact list,
  chunked at 500/REQ (distinct subIds under one prefix, since a same-subId
  REQ replaces). **Filtering is mute-only** (blocked author / muted word) —
  matches the proven mute-only `HashtagFeedViewModel` and the web (no NSpam).
  NSpam was dropped after the feed came back empty on device: a live relay
  probe proved `since`/relay were NOT the cause (search.nostrarchives.com
  returns ~78 food notes all <7d old, with or without `since`), leaving the
  one filter OnlyFood added over the working HashtagFeed — `score()` run
  inside the collector, which both over-filtered hashtag/link-heavy food
  posts at `>= 0.7` and risked an exception cancelling the stream. Initial
  load takes **no `since` floor** (newest-100, like HashtagFeed); `since`/
  `until` apply ONLY in `loadMore()` pagination (older windows via
  `until = oldest-1` on infinite scroll; global 7d / following 3d windows).
  ⏭️ FORWARD (not done): if Global is spammy in practice, re-add spam
  filtering CORRECTLY — `score()` in try/catch (keep-on-error so one bad note
  can't cancel the collector) + the `>= 0.7` threshold verified against real
  food posts first; and audit existing `score()`-inside-`collect{}` sites
  (e.g. notifications) for the same stream-cancellation latent bug.
  RELOAD FIX: subIds are process-wide unique (companion `AtomicLong SUB_SEQ`,
  not an instance counter) — an instance counter restarted at 0 per nav
  back-stack entry, so re-entering within the prior instance's ~14s teardown
  let its `closeAll` CLOSE `onlyfood-0` and kill the new sub on the shared
  ephemeral relay. TRACKED: `HashtagFeedViewModel.loadCounter` is the same
  instance-scoped pattern (no rapid re-entry there, so left as-is).
  THROTTLE FIX: blank-after-toggle was relay-side throttling (logcat: REQ
  sent, EOSE 0 events in ~305ms = relay refusing to search) from CLOSE-frame
  hammering, not capacity/cooldown. Three churn cuts: (1) all loads —
  initial/toggle/pagination — serialize through one `submit()` that
  `cancelAndJoin`s the previous job before the next REQ (no overlapping/
  orphaned subs); (2) `loadMore()` goes through that same path (was
  overwriting `activeJob` without cancelling); (3) teardown CLOSEs **only the
  subIds actually opened** (1 global / real chunk count) — the old
  `base-0..base-39` sweep × `closeOnAllRelays` fan-to-all-connections was
  ~450 stray CLOSE frames per teardown. NOTE (latent RelayPool bug, not
  fixed here): the ephemeral cooldown-failure path doesn't
  `subscriptionTracker.untrackRelay`, leaking tracked subs — separate concern.
  THROTTLE ROOT CAUSE (logcat-confirmed): `search.nostrarchives.com`
  rate-limits repeated queries PER CONNECTION — identical filter, same conn,
  99 events → 0 events ~12s later. Toggling re-queried each switch, so every
  toggle after the first was throttled to blank. FIX = **per-mode result
  cache, don't re-query on toggle**: each Mode keeps its own `ModeState`
  (`seen`/`loaded`/`endReached`/`emptyFollows`); `setMode` swaps `_notes` to
  the target's cache INSTANTLY with no relay query; a mode is queried once
  (`loaded=true` on completion regardless of event count, so a legit-0 mode
  isn't re-throttled). Correctness: `ModeState` captured at `submit()`
  call-time (mid-flight mode switch can't mis-route), collector touches
  `_notes` only while its mode is current. **Pull-to-refresh**
  (`PullToRefreshBox`; empty states live inside the LazyColumn so the gesture
  works when blank) is the ONLY path that re-queries a loaded mode.
  `OnlyFoodFeedScreen` =
  Global|Following segmented toggle + shared `PostCard` (full inline
  `NoteActions`/zap) + infinite scroll. Reachable via an "OnlyFood" drawer
  entry. Additive: general/Following feed + `FeedViewModel`/nav structure
  untouched. Keyword relevance scorer skipped (v1 = hashtag set only;
  `#beef`/`#steak` ambiguity caught by NSpam+mutes). Forward note: outbox
  routing is the following-mode completeness upgrade if the search relay is
  sparse. Suite 58/0/0/0, `assembleZapstoreDebug` clean.

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
