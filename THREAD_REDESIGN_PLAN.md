# Thread navigation architecture

This document describes the shipped thread-view pattern. Both Wisp iOS and
Wisp Android implement it identically. If you're touching the thread UI on
either client, read this first to understand the invariants.

## Pattern

Each thread screen renders **one focal post + its direct replies**. Tapping a
reply pushes a new thread screen with that reply as the new focal. Ancestors
(root → focal-1) render compactly above the focal so the user always sees how
they got there. A back-stack chain tracker enables smart-pop — tapping an
ancestor that's already on the stack pops to it instead of pushing a duplicate.

This is the established social media app convention for threaded views.
Familiar, trivially scalable to deep threads, and a smaller code surface than
single-screen collapse trees.

**Do not name specific competing apps** in commits, PR text, source comments,
or this doc. The pattern is generic.

## Three sections per screen

In stack order, top to bottom:

1. **Ancestors** — `root → focal-1`, rendered with the post composable in a
   compact variant: small avatar, no nip-05 badge, no engagement bar, no
   inner profile click region (the outer row tap owns navigation), and the
   body **does not cap height** — capping would force aspect-fit images to
   shrink and lose their rounded corners. Tapping any ancestor pushes a
   thread screen for that ancestor's id (or smart-pops if it's already in
   the chain).
2. **Focal** — the post composable in its full variant, with a subtle
   `surfaceVariant @ ~0.15-0.25 alpha` background to visually distinguish it,
   and an **absolute timestamp** ("Mar 5, 2026 · 5:39 PM") instead of the
   relative offset elsewhere. Below the focal, a small "N replies" meta
   line. Not tappable.
3. **Replies** — direct children of focal, sorted oldest first. Each is a
   tappable row that pushes a new thread screen. When the row has known
   children (`childCounts[id] > 0` or engagement repo reports replies > 0),
   append a small "View N replies" hint in the accent color.

## State slices (view model)

The thread view model exposes three derived state slices, replacing the
former DFS + per-branch collapse machinery:

- `ancestors: [Event]` — root → focal-1, in order (empty if focal is root)
- `focal: Event?` — events keyed by `focalEventId` (which is the inner
  kind-1 id when the seed was a kind-6 repost; the model unwraps that
  during seeding so engagement / reply queries route to the original)
- `replies: [Event]` — direct children of focal, sorted oldest first

Plus `childCounts: Map<String, Int>` — direct-child count per event id, used
to drive the "View N replies" hint.

`fetchAncestorChain()` walks `Nip10.replyTarget(of:)` upward from the focal,
firing one-shot fetches against indexer relays + author inbox relays for any
missing parent. Bounded at ~30 hops. Without this, a focal opened from a
notification would render with no parent context until the broad replies
stream eventually filled it in.

## Smart-pop chain tracker

A side-channel `List<String>` per `NavController` mirrors the eventIds of
every thread route on the back stack, in stack order.

- **On thread screen enter**: append `seedEventId` to the chain if not already
  at the tail (DisposableEffect with `key = eventId` on Android,
  `task` / `onAppear` on iOS).
- **On thread screen disposal**: if `chain.last() == seedEventId`, remove
  it. This handles natural back, swipe-back, and the cascading disposes
  that follow a smart-pop.
- **On tap (reply or ancestor)**: if the target id is already in the chain
  at index `idx < chain.size - 1`, pop `(chain.size - idx - 1)` levels.
  Otherwise push normally. Tapping the current focal is a no-op.

## Composer

The reply composer at any level posts a reply to **that level's focal**, not
the original root. Each pushed thread screen owns its own composer state — no
leakage between levels.

## Scroll target

On first composition after both `focal` and `ancestors` resolve, scroll the
focal row to the top once. Guard with a `didScrollToFocal` flag so the user
can scroll up freely afterward.

## Where the code lives

### iOS (`/Users/daniel/GitHub/wisp-ios`)

| Concern | Path |
|---|---|
| State slices, ancestor walk, focal unwrap | `ThreadViewModel.swift` |
| Three-section layout, focal row, ancestor + reply rows | `wisp/ThreadView.swift` |
| Compact ancestor rendering flag | `PostCardView.swift` (`ancestorCompact: Bool`) |
| Smart-pop chain tracker | `MainView.swift` (`feedThreadChain` etc., one chain per tab path) |
| Reply target helpers | `Nip10.swift` (`rootId(of:)`, `replyTarget(of:)`) |

### Android (`/Users/daniel/GitHub/wisp`)

| Concern | Path |
|---|---|
| State slices, ancestor walk | `app/src/main/kotlin/com/wisp/app/viewmodel/ThreadViewModel.kt` |
| Three-section layout, focal row, ancestor + reply rows | `app/src/main/kotlin/com/wisp/app/ui/screen/ThreadScreen.kt` |
| Compact ancestor rendering flag | the post row composable in that screen file (`ancestorCompact`) |
| Smart-pop chain tracker | `app/src/main/kotlin/com/wisp/app/Navigation.kt` (`threadChain`) |
| Reply target helpers | wherever `Nip10` lives in the Android module |

## Invariants — keep in sync across clients

When you touch the thread layer, preserve these (they're tested by users
expecting consistent behavior across iOS and Android):

- Three-section structure (ancestors → focal → replies)
- Focal background uses a subtle surface-variant tint (~0.15 – 0.25 alpha)
- Focal timestamp is absolute, not relative
- "N replies" meta line below focal
- "View N replies" hint on reply rows that have children
- Ancestors render compactly: smaller avatar, no engagement bar, no profile
  click region, no body height cap
- Smart-pop on tap into an in-chain ancestor; push otherwise
- Composer replies to the screen's focal, not the root
- Scroll-to-focal once on first resolved composition

## Known v1 trade-offs (acceptable, do not "fix")

- Each pushed thread screen re-fetches the whole thread from its new seed.
  The ancestor-walk path-of-fetches happens fresh per push. Optimize later
  if perf becomes a concern.
- Tapping an ancestor pushes a new screen even when smart-pop *could*
  semantically apply but the chain was broken (e.g., user navigated through
  a Profile route between threads). The back stack can grow with redundant
  entries in those cases. Acceptable.
