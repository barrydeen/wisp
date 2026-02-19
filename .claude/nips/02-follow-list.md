# NIP-02: Follow List

**Status in Wisp:** Not yet implemented
**Depends on:** NIP-01
**Kind:** 3 (replaceable)

## Overview

Kind 3 event stores a user's contact/follow list. It is a **replaceable event** —
only the latest kind 3 per pubkey is kept by relays.

## Event Structure

```json
{
  "kind": 3,
  "tags": [
    ["p", "<pubkey-hex>", "<relay-url>", "<petname>"],
    ["p", "<pubkey-hex>", "<relay-url>", "<petname>"],
    ...
  ],
  "content": ""
}
```

- Each `p` tag = one followed user
- `relay-url` (optional): preferred relay for that user
- `petname` (optional): local alias (rarely used)
- `content` is typically empty (some clients store relay preferences here as JSON — legacy behavior)

## Read-Modify-Write Pattern

**Critical:** Because kind 3 is replaceable, you must:
1. Fetch the user's current kind 3 event
2. Parse existing `p` tags
3. Add/remove the desired follow
4. Publish a new kind 3 with the complete updated tag list

If you skip step 1 and publish with only the new follow, you **overwrite** the
entire follow list.

## Filter to Fetch Follow List

```json
{"kinds": [3], "authors": ["<user-pubkey>"], "limit": 1}
```

## Following a User

```kotlin
// Pseudocode
val currentFollows = fetchKind3(userPubkey)
val tags = currentFollows?.tags?.toMutableList() ?: mutableListOf()
tags.add(listOf("p", targetPubkey))
publishEvent(kind = 3, tags = tags, content = "")
```

## Unfollowing a User

Remove the matching `p` tag and republish.

## Checking if Following

```kotlin
val isFollowing = kind3Event.tags.any {
    it.size >= 2 && it[0] == "p" && it[1] == targetPubkey
}
```

## Common Pitfalls

- **Race condition:** If two clients update the follow list at the same time, the
  last-published one wins and the other's changes are lost
- Always fetch the latest kind 3 before modifying
- The follow list can be large (thousands of p-tags)
- Some relays reject events with too many tags
- Content field is sometimes used for relay list JSON (legacy, non-standard)
