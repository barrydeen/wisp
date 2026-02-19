# NIP-25: Reactions

**Status in Wisp:** Not yet implemented
**Depends on:** NIP-01, NIP-10 (for e-tag format)
**Kind:** 7 (regular)

## Overview

Kind 7 events express reactions to other events. The `content` field
determines the reaction type.

## Event Structure

```json
{
  "kind": 7,
  "tags": [
    ["e", "<reacted-event-id>", "<relay-url>"],
    ["p", "<reacted-event-author-pubkey>"],
    ["k", "<reacted-event-kind>"]
  ],
  "content": "+"
}
```

## Content Values

| Content | Meaning |
|---------|---------|
| `+` | Like |
| `-` | Dislike |
| Any emoji | Custom emoji reaction (e.g., `🤙`, `❤️`) |
| `:shortcode:` | Custom emoji (with `emoji` tag) |
| Empty `""` | Treated as `+` (like) |

## Custom Emoji Reactions

```json
{
  "kind": 7,
  "content": ":soapbox:",
  "tags": [
    ["e", "<event-id>"],
    ["p", "<author>"],
    ["emoji", "soapbox", "https://example.com/soapbox.png"]
  ]
}
```

## Tag Requirements

- `e` tag: MUST reference the reacted-to event (last `e` tag = target)
- `p` tag: MUST reference the author of the reacted-to event
- `k` tag: SHOULD include the kind of the reacted-to event

## Threading

The `e` tag follows NIP-10 conventions:
- The **last** `e` tag is the event being reacted to
- Earlier `e` tags are for threading context (optional)

## Querying Reactions

```json
// Reactions to a specific event
{"kinds": [7], "#e": ["<event-id>"]}

// Reactions by a specific user
{"kinds": [7], "authors": ["<pubkey>"]}
```

## Counting Reactions

Group by `content` value to get counts per reaction type:
- Count of `+` and `""` = total likes
- Count of `-` = total dislikes
- Count per emoji = emoji reaction counts

## Implementation Notes

```kotlin
// Create a like reaction
val tags = listOf(
    listOf("e", targetEvent.id),
    listOf("p", targetEvent.pubkey),
    listOf("k", targetEvent.kind.toString())
)
val reaction = NostrEvent.create(
    privkey = keypair.privkey,
    pubkey = keypair.pubkey,
    kind = 7,
    content = "+",
    tags = tags
)
```

## Common Pitfalls

- One user can react multiple times to the same event (no built-in uniqueness)
- Clients should deduplicate reactions per user per event in the UI
- The `k` tag is important for relays to validate deletions
- Empty content should be treated as a like (`+`)
- Dislike (`-`) support varies — some clients ignore it
