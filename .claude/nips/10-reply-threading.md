# NIP-10: Reply Threading

**Status in Wisp:** Implemented
**File:** `Nip10.kt`
**Depends on:** NIP-01

## Overview

Defines how kind 1 (text note) events reference other events to form
reply threads using marked `e` tags.

## Marked E-Tag Format (Preferred)

```json
["e", "<event-id>", "<relay-url>", "<marker>"]
```

### Markers

| Marker | Meaning | Count per event |
|--------|---------|-----------------|
| `"root"` | Original post that started the thread | Exactly 0 or 1 |
| `"reply"` | The event being directly replied to | Exactly 0 or 1 |
| `"mention"` | Referenced but not replied to | 0 or more |

## Reply Tag Construction

### Replying to a root post (no existing thread)

```json
{
  "tags": [
    ["e", "<root-event-id>", "<relay>", "root"],
    ["e", "<root-event-id>", "<relay>", "reply"],
    ["p", "<root-author-pubkey>"]
  ]
}
```

Note: root and reply point to the same event.

### Replying to a reply (threaded)

```json
{
  "tags": [
    ["e", "<original-root-id>", "<relay>", "root"],
    ["e", "<direct-parent-id>", "<relay>", "reply"],
    ["p", "<parent-author-pubkey>"]
  ]
}
```

### P-tags

Always include `p` tags for:
- The author of the event being replied to
- Optionally: authors of other events in the thread

## Q-Tags (Quotes)

When quoting (not replying to) an event:
```json
["q", "<quoted-event-id>", "<relay-url>"]
```
- Q-tags indicate the content references/quotes another event
- Different from reply: displayed inline, not as thread continuation

## Wisp Implementation

`Nip10.buildReplyTags(replyTo: NostrEvent)` handles:
1. Checks if `replyTo` has a root tag (already in a thread)
2. If yes: preserves root, adds reply pointing to `replyTo`
3. If no: uses `replyTo.id` as both root and reply
4. Always adds `p` tag for `replyTo.pubkey`

## Positional E-Tags (Legacy)

Older events use position instead of markers:
- First `e` tag = root
- Last `e` tag = reply (if more than one)
- Middle `e` tags = mentions

Clients should support reading both formats but write marked tags.

## Thread Reconstruction

To build a thread view:
1. Fetch the root event
2. Query: `{"kinds": [1], "#e": ["<root-event-id>"]}`
3. Build tree using root/reply markers
4. Sort siblings by `created_at`

## Common Pitfalls

- Events with markers and without markers should both be handled
- Root and reply can point to the same event (direct reply to root)
- Always include relay hints in e-tags when known
- P-tags should include all participants for proper notifications
- Don't confuse `e` tags (reply threading) with `q` tags (quoting)
