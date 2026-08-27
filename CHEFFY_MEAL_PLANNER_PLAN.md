# Cheffy Meal Planner — Android Port Plan

Port of the web frontend's **Plan with Cheffy** (My Kitchen → Planner: discover
real Nostr recipes → Cheffy fills a structured week → preview → one bulk write)
to `zapcooking/zap_cooking_android`. Follows house rules: stop-gated phases,
one concern per PR, surgical diffs, investigation before implementation.

**Status (2026-08-26): Phases 0–4 merged (#232–#235); Phase 5 (sheet) in
review on #236. The flag stays off until the device checklist on that PR
passes.**

**Source of truth:** `zapcooking/frontend` PR #644
(`feat/cheffy-meal-planner`, merged `8caef33`, 2026-08-23) — 14 files,
+3513/−7.

**Backend-as-API rule holds: zero server work.** `POST /api/zappy/meal-plan`
is live, client-agnostic (NIP-98 identity, no cookies), and unchanged by this
port. Android consumes it exactly as web does.

---

## 0. Baseline drift warning — read before scoping

`main` moved three times within a day of #644 and every one of those PRs
touched meal-plan files:

| PR | Branch | Meal-plan impact |
|---|---|---|
| #645 | `feat/plan-with-my-pantry` | new `pantryService.ts` / `pantryStore.ts`; candidates gain an optional `pantry` block; request gains `prioritizePantry`, `pantryIngredients` |
| #646 | `feat/smart-grocery-planner` | grocery derived from the meal plan; `groceryGeneration.ts` +70 |
| #647 | `feat/cheffy-planning-modes` | new `planningModes.ts` (655 lines); request gains `planningPreferences`, `familiarCoordinates`; `generation.ts` +190; endpoint +52 |

**Every one of those request fields is optional and additive.** A client built
against the #644 contract still parses and validates server-side today. So:

- **In scope for this plan:** #644 only — the core discover → plan → preview →
  apply loop.
- **Out of scope, tracked separately:** planning modes, My Pantry, and
  meal-plan-derived grocery. Port them as a second wave once the core ships,
  not by re-cutting the contract mid-port.

Re-diff `src/lib/mealplan/` against the frontend commit named in each phase
before starting that phase. Do not work from this document's snapshot of the
web code alone.

---

## 1. The web contract (source of truth)

### Endpoint (base `https://zap.cooking`)

| Endpoint | Auth | Success | Typed failures |
|---|---|---|---|
| `POST /api/zappy/meal-plan` | NIP-98, body-hash bound | `{ ok:true, plan: { meals: [...] } }` | 400 + `code` (request parse), 401 (auth), 403 `NOT_MEMBER`, 429 `RATE_LIMITED`, 422 + `code` (model output rejected), 500 generic |

**Request body** (`MealPlanGenerationRequest`):

```
weekId              "YYYY-Www", validated by isValidWeekId
days                MealPlanDayKey[]   mon…sun, deduped, ≥1
mealSlots           MealSlotKey[]      breakfast|lunch|dinner|snack, deduped, ≥1
strategy            "fill-empty" | "replace-selected"
candidates          RecipeCandidate[]  1..48
preferences         { styles[], maxMinutes?, servings?, excludeIngredients?[], notes? }
occupiedSlots?      { day, slot }[]    required for fill-empty enforcement
fillSlots?          { day, slot }[]    explicit targets (swap uses this)
excludeCoordinates? string[]           coordinates already used in the preview
```

`RecipeCandidate` = `{ a, title, tags[], ingredients[], prepTime?, cookTime?,
servings? }`.

**Response meal** = `{ day, slot, a, title, reason? }`. The client attaches
`image` locally for the preview; it is never sent and never written to the
plan.

### Hard caps (server rejects, does not truncate)

| Cap | Value | Failure |
|---|---|---|
| candidates | 48 (`MAX_CANDIDATES`) | 400 `too-many-candidates` |
| ingredients per candidate | 12 | silently sliced by sanitizer |
| tags per candidate | 8 | silently sliced |
| title | 120 chars | sliced |
| reason | 160 chars | sliced |
| notes | 500 chars | sliced |
| excludeIngredients | 20 | sliced |
| maxMinutes | ≤ 1440 | clamped |
| servings | ≤ 24 | clamped |
| coordinate | must start `30023:` and split into exactly 3 non-empty parts | candidate dropped |

### Server-side facts that shape the client

- **Fails OPEN on membership-service outage** (opposite of Note Review's
  fail-closed). A pantry/Caddy/secret-mismatch outage does *not* produce an
  upsell here — the request proceeds. Do not add a client-side
  `MEMBERSHIP_UNAVAILABLE` state for this endpoint.
- **Rate limit is per-IP, not per-pubkey** — `checkPerIpRateLimit` on
  `getClientAddress()`, scope `zappy-meal-plan`, **10/hour, 30/day**. Note
  Review is per-pubkey; this one is not. Two consequences: mobile users behind
  carrier CGNAT can consume each other's budget, and the 429 copy must not
  imply the user personally exhausted anything.
- **Cheffy cannot invent recipes.** The OpenAI call uses a strict
  `json_schema` whose `a` field is an **enum of the submitted coordinates**
  (falling back to `json_object` if the schema is rejected). Server then
  re-validates every meal against the request.
- **Breakfast/snack eligibility is enforced server-side**, after the model
  answers. An ineligible assignment is a hard 422 `ineligible-slot`, not a
  silent drop.
- Candidates are restricted to the requested slots *before* the model call. If
  nothing survives, the server returns **400 `no-candidates`** with slot-aware
  copy — this is the expected outcome of "plan 7 breakfasts from a dinner
  library", not an error state.
- Model: `gpt-4.1-mini`, `temperature 0.4`, `max_tokens 1200` (#644; raised to
  1800 by #647).

### Client invariants to preserve (decisions, not preferences)

1. **I1 — mandatory preview before write.** Nothing reaches the planner until
   the user approves. `Preview` is a distinct phase with View / Swap / Remove.
2. **I2 — one mutation, one save.** Approval applies the whole plan in a single
   plan transform and a single scheduled save. (See §4 — this is the highest-
   risk deviation on Android.)
3. **I3 — `fill-empty` never overwrites.** Enforced three times: when
   resolving target slots, by the server on model output, and again at apply
   time against live occupancy. Keep all three; the plan can change while the
   modal is open.
4. **I4 — client re-validates the server's plan.** Web runs
   `validateGeneratedMealPlan` on the response even though the server already
   did. Trust nothing that will be written into a signed, encrypted event.
5. **I5 — eligibility is fixture-locked, not hand-copied.** Breakfast and
   snack are hard filters driven by tag needles then title phrases. If Android
   drifts from the server's lists, the user gets a 422 they cannot act on.
6. **I6 — preferences are ephemeral.** `preferences` lives on the request only.
   It is never written into the meal-plan payload — that schema is a frozen
   cross-repo contract (`docs/mealplan-contract.md`, schemaVersion 1).
7. **I7 — the app sells nothing.** The Cook+ gate links out through the
   existing `MEMBERSHIP_LINKOUT_ENABLED` handling. No new purchase path, no
   flavor-specific branch.
8. **I8 — branch on `code`, then status.** Every typed failure carries a `code`
   in the body. Read it first; use the HTTP status only as a fallback. This is
   the direct lesson from the extract-recipe status coupling
   (`SousChefViewModel.kt` hard-branching on `400`) — do not repeat it.

---

## 2. What Android already has (reuse, don't rebuild)

- **`Nip98.kt` + `Nip98HeaderCache`** — body-hash-bound POST headers, already
  used by Note Review. The meal-plan POST is one more method on this spine.
- **`ZapCookingApi.authedPost` / `authedRaw`** and the
  `mapNoteReviewResponse`-style code→sealed-type mappers with unit tests off
  canned bodies. Direct precedent for Phase 2.
- **Membership**: `getPublicMembership` (batch, unauthenticated) and
  `checkMembershipStatus` (NIP-98). Server 403 stays authoritative; client
  status only pre-gates UI.
- **`mealplan/` package**: `Schema.kt` (JsonObject-level, unknown-field
  preserving), `PlannerMutations.kt` (`setSlot`, `withDay`, `stampForPublish`),
  `PlannerLogic.kt`, `Week.kt`, `RecipePickerLogic.kt`, `IngredientParser.kt`.
- **`PlannerViewModel`** — debounced per-week saves, dirty tracking,
  `flushPendingSaves`, read-only handling.
- **`PlannerSection.kt`** — `PlannerHeader`, `EmptyBanner`, `DayCard`,
  `SlotRow`, `RecipeSlotContent`; `PlannerDialogs.kt` for the modal idiom.
- **`RecipeRepository`** — feed with relay-union reads, `loadAuthoredRecipes` /
  `loadProfileAuthoredRecipes`, `requestRecipe(author, dTag)`,
  `requestRecipeEventByCoordinate`, `searchCachedRecipes`, `preloadCatalog`,
  newest-wins dedupe, `HiddenRecipes` filtering.
- **`RecipeBookmarkRepository`** — cookbook lists (`CookbookList.coordinates`),
  already flattened/deduped by `RecipePickerLogic.uniqueCoordinates`.
- **`RecipeParser`** — `RecipeDetails(prepTime, cookTime, servings)`,
  `parseIngredients`, tags, image.
- **`Cheffy.kt`** line pools, `CheffyIcon.kt`, `CheffyViewModel`/`CheffyScreen`
  conventions and copy voice.
- **`ContractFixtureChecksumTest` + `mealplan-schema.vectors.json`** — the
  house pattern for cross-repo parity fixtures. Phase 1 reuses it verbatim.
- **`FeatureFlags.kt`** — the dark-ship pattern for Phases 1–4.

## 3. Gaps (net-new Android work)

1. **`SlotEligibility.kt`** — breakfast/snack tag needles + title phrases +
   the `restrictCandidatesToRequestedSlots` rules. Parity-critical (I5).
2. **`MealPlanGeneration.kt`** — candidate model, `filterRecipeCandidates`
   (time/exclusions/vegetarian/style ranking/cap), `resolveTargetSlots`,
   `occupiedSlotsFromPlan`, `validateGeneratedPlan`, duration parsing.
3. **`ZapCookingApi.requestMealPlan`** + sealed result type.
4. **Candidate discovery** over `RecipeRepository` + `RecipeBookmarkRepository`
   producing web-shaped candidates.
5. **`PlannerMutations.applyGeneratedPlan`** — a pure *multi-slot* transform
   (Android has only single-slot `setSlot` today).
6. **The sheet itself** — Compose `ModalBottomSheet` with the phase machine.

---

## Phase 0 — Investigation (STOP GATE, no code)

Deliverable: findings comment on the tracking issue. No implementation PR
starts until each is answered.

- **0.1 Candidate-shape parity.** Web's `candidateFromEvent` reads the `title`
  tag, `t` tags minus the two recipe meta-prefixes, `ingredient` tags (index 3
  then 1) falling back to markdown parsing, and `prep_time`/`cook_time`/
  `servings` tags falling back to `extractRecipeDetails`. It also strips a
  leading quantity from each ingredient (`2 lbs chicken` → `chicken`) so Cheffy
  reasons about foods, not amounts. Confirm what `RecipeParser` +
  `IngredientParser` already give us and what has to be added. Candidate shape
  drives prompt quality, not correctness — a mismatch degrades plans silently.
- **0.2 Explore-source equivalence.** Web fetches `kind:30023 + #t
  RECIPE_TAGS`, `limit 150`, 8s timeout, and gates each event on
  `validateMarkdownTemplate` so only parseable Zap Cooking recipes become
  candidates. Android's analog is `RecipeRepository`'s feed +
  `RecipeFormats.forEvent(...)?.parse(...)`. Decide: reuse the live feed
  StateFlow, or a dedicated bounded query? Confirm the format gate is at least
  as strict as web's markdown gate.
- **0.3 Recipe-kind ceiling.** The server hardcodes `RECIPE_KIND_PREFIX =
  '30023:'`. `RecipeFormats.active` is `[Nip23RecipeFormat]` today, so we are
  aligned — but `Nip333RecipeFormat` exists as a typed placeholder. Record
  this as a known ceiling: **if a dedicated recipe kind is ever activated,
  those recipes silently cannot be planned by Cheffy until the server's prefix
  check changes.** File it as a frontend issue now rather than discovering it
  at adoption time.
- **0.4 Saved-source cost.** Web resolves saved coordinates from an offline
  cache first, then fetches misses in parallel with an 8s per-event timeout.
  Android has `requestRecipe` / `requestRecipeEventByCoordinate` — measure the
  cost for a user with a large cookbook and decide the cap and timeout before
  UI work.
- **0.5 Apply-time occupancy.** Confirm `PlannerViewModel` exposes live week
  state to the sheet so I3's third check runs against current occupancy, not
  the snapshot taken when the sheet opened.
- **0.6 Sheet vs. dialog.** `PlannerDialogs.kt` uses dialogs; the web UI is a
  full-screen-on-mobile modal with three phases and a scrollable preview.
  Decide `ModalBottomSheet` (Note Review precedent) vs. a full screen route,
  and confirm the form fits without the header jumping between phases.
- **0.7 Read-only / no-signer states.** schemaVersion > 1 weeks are read-only;
  read-only accounts cannot produce NIP-98. Match whatever Sous Chef does for
  the sign-in nudge (`LocalCanSign` precedent) and confirm the entry point is
  hidden, not merely disabled, on read-only weeks.

---

## Phase 1 — Pure logic + parity fixtures (PR 1, no UI)

`mealplan/SlotEligibility.kt` and `mealplan/MealPlanGeneration.kt`. No
networking, no Compose.

- `SlotEligibility`: `isRecipeEligibleForSlot`, `eligibleSlotsForRecipe`,
  `restrictCandidatesToRequestedSlots`, `insufficientSlotCoverageMessage`,
  `noEligibleRecipesMessage`. Normalization must match web exactly —
  lowercase, `_` and `/` → space, `-` → space, collapse whitespace; tag match
  is equality-or-contains; title match is phrase-with-leading-space, plus a
  whole-token list (`eggs`, `oats`, `hash`).
- `MealPlanGeneration`: `RecipeCandidate`, `MealPlanPreferences`,
  `MealSlotRef`, `MealPlanGenerationRequest`, `GeneratedMeal`;
  `isRecipeCoordinate`, `cartesianSlots`, `occupiedSlotSet`,
  `resolveTargetSlots`, `occupiedSlotsFromPlan` (reads `Schema.MealPlan`),
  `parseDurationMinutes`, `totalActiveMinutes`, `filterRecipeCandidates`,
  `validateGeneratedPlan`, `parseExcludeIngredientsInput`, plus the caps and
  the vegetarian blocklist.
- **Ship `app/src/test/resources/fixtures/mealplan-eligibility.vectors.json`**,
  generated from the web lists, and extend `ContractFixtureChecksumTest` to
  cover it. Vectors: every breakfast phrase, every token word, every snack
  phrase, tag-vs-title precedence, the four normalization cases, and negative
  cases (dinner entrees, `unbreakfast`-style substring traps).
- Unit tests additionally for: cap-48 slicing, coordinate rejection
  (wrong kind, 2 parts, empty part), `fill-empty` target resolution against
  occupied slots, duration parsing (`"1 hr 15 min"`, `"45"`, `"about an
  hour"` → null), vegetarian and exclude-ingredient drops, style ranking
  (matched-only when ≥ 8 matches, else matched-then-unmatched), and
  `validateGeneratedPlan` rejecting unknown coordinate / unknown slot /
  duplicate slot / occupied overwrite / ineligible slot.

**Gate:** full unit suite green; fixture checksum test passing; a written
diff-check that every phrase and needle in `SlotEligibility.kt` matches
`slotEligibility.ts` at the named frontend commit.

## Phase 2 — API layer (PR 2, no UI)

- `ZapCookingApi.requestMealPlan(request, signer)` on the existing NIP-98 POST
  spine, serializing the Phase 1 request type. Long-timeout compute client —
  the model call plus a `json_schema`→`json_object` retry can exceed the
  general read timeout.
- Sealed `MealPlanResult`: `Ok(meals)`, `SignInRequired`, `MembersOnly`,
  `RateLimited(retryAfter?)`, `NoCandidates(message)`,
  `InvalidRequest(code, message)`, `Rejected(code, message)` (422),
  `Failed(message)`.
- Mapper reads `code` first, status second (I8). `no-candidates` arrives as a
  **400 with a body code**, not a distinct status — a status-only mapper
  renders it as a generic failure and loses slot-aware copy.
- Client-side `validateGeneratedPlan` on the response before returning `Ok`
  (I4).
- Unit tests: code→type mapping off canned bodies for each failure, body-hash
  identity between signed bytes and sent bytes, response re-validation
  rejecting a tampered coordinate.

**Gate:** unit suite green; one manual authenticated call against production
from a Cook+ pubkey and one from a non-member pubkey, with the raw bodies
recorded on the issue.

## Phase 3 — Candidate discovery (PR 3, no UI)

- `mealplan/MealPlanCandidates.kt` (or `repo/MealPlanCandidateSource.kt` per
  house layering): `my-recipes | saved | explore | all`, merged first-seen by
  coordinate, honoring `HiddenRecipes`.
- `candidateFromEvent(event)` per the 0.1 findings, including quantity
  stripping and the tag/markdown fallbacks. Keep `image` on an Android-only
  wrapper type — it must not be serializable onto the wire candidate.
- `all` runs the three sources concurrently and degrades on partial failure
  (web logs and returns empty per source rather than failing the whole run).
- Unit tests on pure parts: coordinate building, tag filtering, ingredient
  quantity stripping, merge/dedupe order, hidden-coordinate exclusion.

**Gate:** unit suite green; an instrumented or manual count of candidates
produced per source on a real account, confirming the post-filter set lands
under 48 for a typical library.

## Phase 4 — Apply path (PR 4, no UI)

- `PlannerMutations.applyGeneratedPlan(plan, meals, strategy)` — a pure
  transform writing every applicable slot in **one** rebuilt `MealPlan`,
  preserving unknown fields at plan/day/slot level (contract rule 8).
- `PlannerViewModel.applyGeneratedPlan(weekId, meals, strategy)` wrapping a
  **single** `mutatePlan` call.

  > **Release-blocking, do not shortcut (I2).** Android's `mutatePlan` calls
  > `scheduleSave` on every invocation. Looping `setSlot` over 7–21 meals would
  > schedule that many debounced saves and republish the week that many times.
  > Because planner d-tags are deterministic per week, same-second republishes
  > share a `created_at`, and NIP-01's lowest-id tie-break can keep the *older*
  > version — the exact failure `stampForPublish` was written to prevent.

- `fill-empty` filters against live week state at apply time (I3), returning
  false when nothing is applicable or the week is read-only.
- Unit tests: 7 meals → one resulting plan and exactly one scheduled save;
  fill-empty skips occupied; replace-selected overwrites; read-only returns
  false without mutating; unknown fields survive.

**Gate:** unit suite green; a harness run (`PlannerStopGateHarness` precedent)
showing a single publish for a full-week apply.

## Phase 5 — UI (PR 5, flag off → on)

`ui/component/CheffyPlanSheet.kt` + `viewmodel/CheffyPlanViewModel.kt`.

- Phases: `Form → Working → Preview`, plus `SignIn`, `Upsell`, `ReadOnly`.
- **Form**: meal slots (default dinner), days (default all 7), style chips
  (the eight `PREFERENCE_STYLES` ids), max minutes, servings, exclude
  ingredients, notes, source selector, and fill-empty vs. replace. At least one
  slot and one day must stay selected.
- **Working**: Cheffy avatar + rotating `Cheffy.kt` lines. Discovery and the
  API call are one continuous phase from the user's view.
- **Preview**: per-meal card with day/slot label, title, thumbnail, reason, and
  View / Swap / Remove. Coverage note above the list when fewer meals came back
  than slots requested (`insufficientSlotCoverageMessage`) — a partial week is
  a **success** state, not an error.
- **Upsell**: reuse the existing membership link-out handling (I7). No
  `MEMBERSHIP_UNAVAILABLE` state — this endpoint fails open.
- **Entry points**: a "Plan with Cheffy" action in `PlannerHeader` and a
  primary button in `EmptyBanner`, both hidden when read-only, decrypt-failed,
  or unsigned.
- Ship behind `FeatureFlags.CHEFFY_MEAL_PLAN_ENABLED`, flipped on in the same
  PR only if the device checklist below has passed; otherwise flip in a
  follow-up.

## Phase 6 — Swap / regenerate (PR 6, optional)

Same endpoint with `fillSlots = [the one slot]` and `excludeCoordinates` = the
rest of the preview plus the rejected coordinate. Regenerate re-runs the whole
request with the existing discovery set. Both reuse the Phase 2 client
unchanged.

---

## Device checklist (gates the flag flip)

Run on the A14 against production, signed in as a Cook+ member:

- [ ] Plan dinner-only across 7 empty days → 7 meals, all real recipes,
      thumbnails render, one planner write.
- [ ] Plan breakfast-only from a mixed library → only breakfast/brunch
      recipes appear; no dinner entrees.
- [ ] Request 7 breakfasts with ~3 eligible recipes → partial week plus the
      "broaden your preferences" copy, empty slots left empty.
- [ ] Max time, excluded ingredients, and vegetarian all still apply on top of
      breakfast eligibility.
- [ ] `fill-empty` on a partly-planned week → existing meals untouched.
- [ ] `replace-selected` → selected slots overwritten, others untouched.
- [ ] Approve → **exactly one** relay publish for the week (relay log or
      diagnostic logger), and the week survives a cold restart.
- [ ] Grocery generation still builds from the applied recipes' `a` tags.
- [ ] Non-member pubkey → upsell, no crash, no partial write.
- [ ] Airplane mode mid-generation → clean error, form state preserved.
- [ ] Read-only week → entry point absent.
- [ ] Signed-out → sign-in nudge, no NIP-98 attempt.

## Open decisions for Seth

1. **Wave 2 scope.** Planning modes (#647), My Pantry (#645), and meal-plan
   grocery (#646) — port after the core, or fold #647 into Phase 5 since it is
   mostly prompt-side and optional on the wire?
2. **Explore corpus size.** Web pulls 150 events for `explore`. Confirm the
   Android limit against relay-union read cost on mobile data.
3. **Rate-limit copy.** Per-IP means a CGNAT collision can 429 a user who has
   done nothing. Do we want copy that reflects that, or the same line as web?
4. **Play sequencing.** No build upload while a Play submission is in review —
   confirm the queue is clear before Phase 5 lands.
5. **Kind-333 ceiling (0.3).** File the frontend issue now, or note and move
   on?
