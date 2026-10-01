# NIP-22: Comment

**Status in Wisp:** Implemented (consume + publish — replies to a 1111 are 1111)
**File:** `Nip22.kt`
**Depends on:** NIP-01, NIP-10

## Overview

Kind 1111 is a generic "comment" event that targets any other event
(any kind) via NIP-10 reply tags. Many clients are migrating replies
(long-form article comments, thread replies) from kind 1 to kind 1111.
Wisp ingests 1111 as a reply everywhere and mirrors the target's
namespace when publishing: a reply to a 1111 is itself published as a
1111 (`Nip22.buildCommentTags`), a reply to kind 1 stays kind 1.

## Event Format

```json
{
  "kind": 1111,
  "content": "comment text",
  "tags": [
    ["e", "<root-id>", "<relay>", "root"],
    ["e", "<parent-id>", "<relay>", "reply"],
    ["p", "<parent-author-pubkey>"],
    ["k", "<parent-kind>"]
  ]
}
```

- Threading uses the same NIP-10 marked e-tags as kind 1 — Nip10 helpers
  (`getReplyTarget`, `getRootId`, `isReply`) work unchanged.
- `k` tag carries the kind of the event being replied to (informational).
- The target can be any kind: 1, 30023 (article), even another 1111.

## Client Rules

- Comments MUST NOT appear in regular feeds — only in the thread/article
  view of the targeted event.
- A kind 1 note replying to a 1111 comment is a stray main-feed note, not a
  comment reply: thread/article views ignore it (`Nip22.isStrayKind1OnComment`)
  and it does not bump the comment's reply count. Private gift-wrapped
  replies are exempt (private comment publishing deferred, rumors are kind 1).
- Comments count toward the targeted event's reply count.
- A comment with a p-tag to me (or an e-tag to my event) is a reply
  notification.
- Reactions (25) and zaps (9735) can target a 1111 like any event.

## Wisp Implementation

- Fetched alongside kind 1 in thread/article/engagement/notification REQs
  (`ThreadViewModel`, `ArticleViewModel`, `OutboxRouter`,
  `MetadataFetcher`, `FeedSubscriptionManager`, `StartupCoordinator`).
- `EventRouter` treats kind 1111 identically to kind 1 in reply-count and
  notification arms; `NotificationRepository` merges it into
  `NotificationType.REPLY`.
- `EventRepository.addEvent` caches 1111 but never inserts it into feeds.
- Persisted in ObjectBox so thread replies/notifications survive restarts.

## Common Pitfalls

- Do not render 1111 as a standalone feed post.
- Do not require a `k` tag when consuming — many senders omit it.
- Reply counts must dedup by reply event id (same as kind 1).
- Nested replies (`e` = parent comment) reference the thread root ONLY via
  uppercase `E` — they match `#E`, not `#e`, and must be admitted/validated
  via `Nip22.referencesRoot`, not a lowercase-`e` check. Thread REQs need an
  ORed second filter (`kinds 1111 + #E`); one filter object would AND `#e`
  and `#E` and match almost nothing.
