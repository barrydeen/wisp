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

### Phase 2 — AI features (investigated; map below)
Mirror the web app, function AND branding. All four are **server-side**: the
app calls `zap.cooking` HTTPS endpoints; it never embeds model keys. Android
work = API client + UI + branding + (for Sous Chef "save") a recipe-create
pipeline.

⚠️ **ARCHITECTURAL DRIFT from the kickoff assumption:** the four AI endpoints
do **NOT** use NIP-98. They take **`pubkey` in the JSON body** and the server
calls `hasActiveMembership(pubkey)` (an insecure, client-supplied trust model
the web acknowledges — there's a planned `FOLLOWUPS-auth-migration` to NIP-98).
So Android needs a **plain `postJson`** helper (no NIP-98 signing) — NOT
`authedPost`. `getJson` (GET) + new `postJson` cover all four. NIP-98 stays
for `membership/check-status` only. When the server migrates, swap to NIP-98.

**Contracts (verified against `zapcooking/frontend`):**

1. **Sous Chef** — `POST /api/extract-recipe` (+ `/public`).
   Body `{ type:'url'|'image'|'text', url?|imageData?(base64)|textData?, pubkey? }`
   → `{ success, recipe: NormalizedRecipe }`. `/public` = `{ url }` only, no
   auth. **Gating: URL is FREE for everyone** (per-IP 8/hr·30/day);
   **image/text require active membership** (pubkey in body). Response is
   **structured fields, not markdown**: `NormalizedRecipe = { title, summary,
   chefsnotes, preptime, cooktime, servings, ingredients[], directions[],
   tags[], imageUrls[] }`. Web UX: landing hero POSTs `/public`, stashes the
   recipe in sessionStorage, navigates to `/souschef` editor → **view-only
   preview until sign-in**, then save.

2. **Cheffy** — `POST /api/zappy` (brand is "Cheffy"; endpoint stays `/zappy`
   for back-compat — confirmed) + `/api/zappy/scan`.
   Body `{ prompt, mode?:'prompt'|'chat'|'hungry'|'format', pubkey?, messages?:
   {role,content}[] }` → `{ ok, output }`. Scan: `{ image:(base64), pubkey? }`.
   **Member-gated** (Pro Kitchen) when `MEMBERSHIP_ENABLED`; fail-open on the
   membership-API outage for a pubkey-bearing caller. Output is markdown;
   when the user wants a recipe it emits the EXACT `# Title / ## Details(emoji)
   / ## Ingredients / ## Directions / ## Chef's notes` format `RecipeParser`
   reads. Branding is heaviest: bespoke `CheffyIcon` (expression states) +
   `CheffyAvatar` character, "kitchen companion", Lightning-native (own LN
   address), starter prompts, "show Cheffy your fridge" scan.

3. **Nourish** — **BOTH relay-read AND endpoint-compute** (the critical
   question, answered): the web resolver reads a 4-layer chain ending at the
   **pantry relay** (`wss://pantry.zap.cooking`, already `MEMBERS_RELAY`):
   **kind 30078**, author = a fixed `NOURISH_SERVICE_PUBKEY`, filter `#d` =
   recipe key; content is JSON scores. The resolver **never computes** — a
   `miss` means the member-gated `POST /api/nourish` computes AND the server
   **publishes the score back to pantry** for future reads. Compute body
   `{ pubkey, eventId, title, ingredients[], tags[], servings, recipePubkey?,
   recipeDTag?, contentHash? }` → `{ success, scores, improvements[],
   ingredient_signals[], promptVersion, createdAt }`, **member-gated
   fail-closed**. Scores = **8 dimensions** (gut, protein, realFood,
   antiInflammatory, bloodSugar, immuneSupportive, brainHealth, heartHealth)
   + weighted overall. `CACHE_VERSION 2.0`, `PROMPT_VERSION 3`. Plus a
   flag/report-a-score path (`flagSubmit`).

4. **Cookbook intro** — `POST /api/cookbook-intro`. Body `{ pubkey, packTitle,
   packDescription?, creatorName?, recipeCount, recipeTitles? }` →
   `{ success, introduction }`. Member-gated. Smallest endpoint, but **depends
   on a Recipe Packs / cookbook commerce feature that does NOT exist on
   Android** → can't ship standalone; deferred until cookbooks exist.

**Android infra EXISTS vs NET-NEW** (audit): EXISTS — `api/ZapCookingApi`
(`getJson`; `authedPost` is NIP-98, not used by these; **add `postJson`**),
`NostrEvent.create`/signing (kind-agnostic), `RelayPool.sendToWriteRelays`
(publish), `BlossomRepository.uploadMedia` (image upload, Blossom auth),
`ComposeViewModel` media pipeline, `RecipeParser` (read), membership read
(`getPublicMembership`/`checkMembershipStatus`). NET-NEW — **`RecipeSerializer`**
(inverse of `RecipeParser`: structured → `##`-section markdown + 30023 tags),
**kind-30023 publish path** (none today; only kind-1/68/71), a recipe-create
VM/screen, **Chrome Custom Tabs** (`androidx.browser`, absent) for the
membership link-out, `MembershipRepository` (Phase 3).

**Proposed breakdown + build order (one concern each):**
- **2.0** ✅ API plumbing — `ZapCookingApi.postJson` (unauth POST, surfaces the
  HTTP code for 400/429/403) + `extractRecipeFromUrl` + `ExtractRecipeResponse`/
  `NormalizedRecipe` models + `parseError`. Folded into the 2.1 PR (no
  standalone consumer).
- **2.1** ✅ Sous Chef — URL import → **read-only preview** (no posting). Drawer
  "Sous Chef" entry → `Routes.SOUS_CHEF` → `SousChefScreen` (URL field +
  paste + Import) → `SousChefViewModel.import` calls the free anon
  `/api/extract-recipe/public` → maps `NormalizedRecipe.toRecipePreview()` →
  renders via the **shared `recipeBody`** (extracted from `RecipeDetailScreen`,
  which is pixel-identical after — byline + Start-cooking via header slots,
  `ActionBar` still appended). Errors: 400→server message, 429→"try again",
  else generic. Empty `imageUrls` guarded (no hero). "Save coming soon"
  affordance (Save = 2.2). Golden-tested vs the live *Easy Meatloaf* response.
  Suite 63/0/0/0. ⚠️ PRE-SHIP: the drawer mark is a placeholder
  (`AutoAwesome`) — port the real Sous Chef SVG for symbol parity before
  release.
- **2.2** ✅ Recipe-create pipeline (one PR; the spine the manual-create modal
  reuses). `nostr/RecipeSerializer` — `RecipeParser.Recipe → 30023 content+tags`,
  mirrors web `createMarkdown` + `create/+page.svelte`: `d=slug(title)`
  (lowercase+spaces→hyphens ONLY; parens/slashes kept), `t:zapcooking` +
  `t:zapcooking-<slug>` + `t:zapcooking-<category>`, image tags, **no
  published_at**; **round-trip-tested** (serialize→parse→equals) vs the real
  Tuscan Peposo. `repo/RecipePublisher` — re-host the cover image via Blossom
  (fetch→`uploadMedia`, **fallback to source URL** so Save never blocks) →
  `signer.signEvent(30023)` → `cacheEvent` (optimistic) → `sendToWriteRelays`
  **+ broadcast to `ARTICLES_RELAYS`** (so it shows in the Recipes feed).
  Gated on a signing key (READ_ONLY can't). Sous Chef "Save" is now live:
  no-image/READ_ONLY block with an explicit reason; after Save, optimistic nav
  to the just-cached recipe (no relay round-trip). Suite 69/0/0/0.
- **2.2b** ✅ Standalone recipe compose ("Create recipe", one PR) — authors a
  recipe from scratch on the 2.2 spine. `ui/screen/RecipeComposeScreen` +
  `viewmodel/RecipeComposeViewModel`: a dedicated full-screen form (NOT an
  extension of the inherited kind-1 note modal) mirroring the web `/create`
  field order — title, categories (free-text chips), summary, chef's notes,
  prep/cook/servings, add/remove ingredient & direction rows, multi-image
  picker, additional resources. Images upload to Blossom **as picked**
  (per-image uploading/done/failed + remove); publish is **blocked while any
  upload is pending or failed** so a half-uploaded image can't be signed in.
  Validation mirrors web `canPublish` (title, ≥1 category, ≥1 image, ≥1
  ingredient, ≥1 direction) via `blockReason` — the button shows the reason,
  never a silent disable. `RecipePublisher` got an **additive** multi-image
  overload `publish(…, imageUrls: List<String>, …)` (already-hosted URLs →
  shared serialize/sign/broadcast core, **no re-host**); Sous Chef's original
  re-host `publish(…)` is untouched. Entry = a single "Create recipe" FAB on
  the Recipes feed, **hidden for READ_ONLY** (`onCreateRecipe == null`); the
  general-feed note FAB is unchanged. On success: optimistic nav to the
  just-cached recipe (same pattern as Sous Chef Save). v1 caveats (TODOs):
  **same title → same d-tag silently replaces** that author's existing recipe
  (web's "make your title unique" caption mirrored; collision warning deferred);
  **no draft autosave** (state survives rotation, not process death — web
  parity); edit-existing prefill + video upload out of scope. Suite 84/0/0/0.
- **2.2c** ✅ Recipe-format seam (PURE refactor — zero behavior change, one PR).
  A `RecipeFormat` abstraction so a future recipe NIP (e.g. `kind 333xx`) plugs
  in as a second implementation with NO rewrite of screens/feeds/compose or the
  `RecipeParser.Recipe` domain model. `nostr/RecipeFormat` (interface: `kind`,
  `formatRank`, `matches`, `parse`, `serialize→UnsignedRecipeEvent`, `slug`,
  `feedFilter`, `coordinateFilter`) + `Nip23RecipeFormat` (a **thin adapter**
  delegating verbatim to the retained `RecipeParser`/`RecipeSerializer` — that's
  what keeps the byte-output and parity tests unchanged) + `RecipeFormats`
  registry (`active`/`primary`/`forEvent`/`rankOf` — the one-line extension
  point) + `Nip333RecipeFormat` **stub** (compile-checked against the interface,
  `TODO()` bodies, **never in `active`/iterated** so they can't fire). Repos
  rewired through the registry: `RecipeRepository` feed (per-format filter
  union), `isRecipe`→`forEvent`, parse skip-on-null; **single-recipe fetch fans
  out per format too** (`requestRecipe` per-format `coordinateFilter`s +
  `findRecipeEvent` per-format cache dispatch — the detail screen's raw event no
  longer hardcodes 30023); `RecipePublisher` serializes via `primary`. Read-side
  cross-format dedup shaped now (not exercised): Stage 1 within-format
  newest-wins (`recipeCoordinate`), Stage 2 `dedupeAcrossFormats` keyed by
  `RecipeKey(author, slug)` (kind-independent) with `(formatRank desc,
  created_at desc, id asc)` canonical-pick — a **true pass-through while one
  format is active**. ⚠️ Dual-write caveat (flagged in `RecipeFormat` KDoc):
  rank-before-recency can mask a newer low-rank edit — keep events in lockstep
  or put recency ahead of rank when dual-write turns on. Parity tests
  (`RecipeSerializerTest`/`RecipeParserTest`) and `RecipeRepositoryTest` left
  **byte-untouched**; new `RecipeFormatTest` covers adapter identity, the
  pass-through, the cross-format pick, and the stub guard. Suite 92/0/0/0.
- **2.3** Cheffy chat — member-gated chat screen → `/api/zappy` (chat/hungry);
  scan (vision) as 2.3b. Heaviest branding (port `CheffyIcon`/`CheffyAvatar`).
  Recipe-format output can deep-link into 2.2 create.
- **2.4** Nourish (sub-phased): **2.4a READ** ✅ — `nostr/NourishParser` (pure;
  kind-30078 JSON → `NourishScore`, **trusts the stored `overall`**, legacy
  dims default 0; 6 unit tests on a synthetic spec-accurate fixture — real
  golden TBD on device). `repo/NourishRepository.fetchScore` —
  `autoApproveRelayAuth(pantry)` → warm-up REQ to open the conn + trigger
  silent NIP-42 AUTH → poll `isAuthenticated` (fast-path; `authCompleted` is
  non-replay) → REQ
  `{ kinds:[30078], authors:[service], "#d":["nourish:30023:author:dTag"] }` →
  parse → cache per recipe key. Null on miss/timeout/**no signing key**.
  RecipeDetail renders a Nourish section (overall+label, 8 dimension bars, top
  suggestions) **outside `recipeBody`** (Sous Chef preview stays score-free),
  **only when a score comes back** — READ_ONLY/miss = quiet absence, never an
  error. Wired via `RecipeDetailViewModel.load` (independent of recipe load).
  Suite 77/0/0/0. ⚠️ PRE-SHIP: Nourish visual is placeholder — port the web
  styling. OPEN (device-resolves): does a non-member signing account auth-read
  pantry, or is it member-gated?
  AUTH FIX (device-confirmed CLOSED auth-required + stale isAuthenticated):
  two bugs — (1) `authenticatedRelays` was never cleared on a transient
  disconnect, so `isAuthenticated(pantry)` stayed stale-true and the query
  fired onto an unauthed live socket; (2) `resyncSubscriptions` re-sent the
  tracked REQ on reconnect before the fresh socket's AUTH. Fix: RelayPool now
  `authenticatedRelays.remove(url)` on `connectionState=false` (auth is
  per-connection — also fixes latent DM/group stale-auth) + a `closedSignals`
  flow + a permanent pantry/auth CLOSED logcat line + `pinEphemeral` (exclude
  pinned URLs from LRU eviction). New reusable `relay/AuthedRelayReader`:
  send REQ → on `CLOSED auth-required` wait for auto-AUTH + re-send (bounded 3,
  tied to `reconnectGeneration`) → collect to EOSE; pins the relay for the
  read. `NourishRepository.fetchScore` now uses it (dropped the one-shot
  `ensureAuthenticated` short-circuit). READ_ONLY/no-key returns null without
  attempting auth. Nourish compute (2.4b) + future member reads reuse
  `AuthedRelayReader`. ⚠️ CORRECTION (live-confirmed):
  pantry requires **NIP-42 AUTH** on every read (`["AUTH",…]` →
  `["CLOSED","auth-required"]`, even kind 1), so this is **NOT "ungated"** — it
  needs a NIP-42-authed pantry connection and therefore a **signing key**
  (READ_ONLY can't auth). Infra exists (`RelayPool.setAuthSigner` wired in
  FeedViewModel; `autoApproveRelayAuth(url)` pre-approves a first-party relay
  for silent AUTH). OPEN QUESTION (device-test answers it): does a non-member
  signing account auth-and-read pantry (share-once-read-many implies yes), or
  does pantry restrict reads to active members (→ Nourish read becomes
  member-gated)? **2.4b COMPUTE** ✅ — `ZapCookingApi.computeNourish` →
  `POST /api/nourish` (pubkey-in-body, NOT NIP-98) on a long-timeout compute
  client (`getComputeClient`, 75s — LLM + awaited pantry publish exceed the
  general 15s). **Response-direct** (no pantry re-read; the server publishes to
  pantry for future viewers). Request carries `recipePubkey`/`recipeDTag`/
  `contentHash` (SHA-256 over raw event content, UTF-8, no trim — byte-exact
  via `Nip98.sha256Hex`). `NourishParser` refactored to a shared
  `parseScores(scoresJson, improvements)` both paths use (trusts stored
  `overall`; lenient — ignores audience_scores/promptVersion/createdAt).
  `RecipeDetailViewModel.NourishUi` state machine + `computeNourish`: signing-
  account-no-score → "Get Nourish score" → Computing → Scored | **MembersOnly
  (403, message-only)** | Error(retry). **READ_ONLY ⇒ always Hidden.**
  Optimistic + graceful-403 — no MembershipRepository (Phase 3). Suite 79/0/0/0.
  **2.4c flag** a score — deferred (separate endpoint + UI).
- **2.5** Cookbook intro — **DEFERRED** (blocked on a Recipe Packs feature
  not in scope).

**Recommended order & dependencies:** 2.0 → **2.1** (fastest demoable, free
URL path, preview-only) → **2.2** (enables Save; serializer is the create
pipeline) → **2.4a** (high-value read; needs pantry NIP-42 AUTH + a signing
key — not "no membership" as first thought) → **2.3** (member-gated, big
branding) → **2.4b/c** → 2.5 deferred. Cross-cutting: Phase 2
features gate on membership, but the endpoints enforce it server-side — Android
can be optimistic (send pubkey, **handle 403 gracefully** with a "membership
required" + link-out), so the full `MembershipRepository`/Custom-Tab is Phase 3;
a minimal status read can be pulled forward only if UI pre-gating is wanted.

**Sous Chef v1 decision (Part B): import → structured preview ONLY; posting
deferred to 2.2.** Mirrors the web's anon path (preview until sign-in); the
free URL endpoint makes it the fastest working feature reusing only
`ZapCookingApi`; decouples the small AI-import client from the bigger create
pipeline (serializer + upload + publish, all net-new); and the preview is
exactly what 2.2 "Save" consumes (clean prerequisite, not throwaway). The
imported `NormalizedRecipe` preview can reuse `RecipeDetailScreen`'s rendering.
Branding note: the bespoke icons (`CheffyIcon`, Sous Chef / Nourish marks) are
SVG components — port them per-feature during each build, against the live site.

**Phase 2 tracked follow-ups (don't lose these):**
- **NIP-98 auth-migration parity.** The AI endpoints trust a client-supplied
  `pubkey` (no signature). The web has a planned `FOLLOWUPS-auth-migration`
  to NIP-98. When the server moves, Android swaps `postJson(pubkey-in-body)`
  → `authedPost` (the NIP-98 spine already exists for `check-status`). Keep
  the request models auth-agnostic so this is a one-call swap per endpoint.
- **NIP-101n — consider later** (per Seth; tracked, not scoped). Evaluate
  its relevance when the auth-migration item above is picked up; out of scope
  for the current Phase 2 build order. Specifics TBD.
- **Build-order soft call** (revisit after 2.1): do **2.4a Nourish READ before
  2.2 create-pipeline** — it's ungated, relay-read-only (pantry, already
  connected), and puts health scores on every recipe (higher broad value,
  lower complexity than the net-new create pipeline). Both orders are fine.
- **Membership pre-gating** (when the first GATED feature lands — Cheffy /
  Nourish-compute): if optimistic try-then-403 feels rough, pull the cheap
  public read (`GET /api/membership?pubkey=`, already in `ZapCookingApi`)
  forward to pre-gate the UI. Optimistic stays the default (server enforces).

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
