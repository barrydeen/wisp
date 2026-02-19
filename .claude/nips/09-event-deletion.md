# NIP-09: Event Deletion

**Status in Wisp:** Not yet implemented
**Depends on:** NIP-01
**Kind:** 5 (regular)

## Overview

Kind 5 events request deletion of previously published events.
Relays SHOULD delete the referenced events but are not required to.

## Event Structure

```json
{
  "kind": 5,
  "tags": [
    ["e", "<event-id-to-delete>"],
    ["e", "<another-event-id>"],
    ["a", "<kind>:<pubkey>:<d-tag>"]
  ],
  "content": "<optional reason>"
}
```

## Tag Types for Deletion

- `e` tag: Delete a specific event by ID
- `a` tag: Delete a replaceable/parameterized-replaceable event by coordinates
- `k` tag: Specify which kinds are being deleted (recommended)

## Rules

1. **Authorization:** Only the event author can delete their own events
   (deletion event `pubkey` must match the target event `pubkey`)
2. **Relays SHOULD:** Stop serving the deleted events
3. **Relays SHOULD:** Serve the kind 5 deletion event itself
4. **Clients SHOULD:** Hide deleted events from the UI
5. **No guarantee:** Some relays may ignore deletion requests

## Deletion with Kind Filter

```json
{
  "kind": 5,
  "tags": [
    ["e", "<event-id>"],
    ["k", "1"]
  ],
  "content": "posted by mistake"
}
```

The `k` tag helps relays process deletions without fetching the original event.

## Implementation

```kotlin
// Delete a single event
val tags = listOf(
    listOf("e", eventIdToDelete),
    listOf("k", originalEvent.kind.toString())
)
val deleteEvent = NostrEvent.create(
    privkey = keypair.privkey,
    pubkey = keypair.pubkey,
    kind = 5,
    content = "",
    tags = tags
)
```

## Checking for Deletions

```json
{"kinds": [5], "authors": ["<pubkey>"], "#e": ["<event-id>"]}
```

## Common Pitfalls

- Deletion is a request, not a command — events may persist on some relays
- A user cannot delete someone else's events
- Deleting a replaceable event by `a` tag deletes all versions
- Clients should check for kind 5 events when displaying content
- Don't rely on deletion for privacy — treat published events as potentially permanent
