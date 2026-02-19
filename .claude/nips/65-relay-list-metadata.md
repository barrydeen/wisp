# NIP-65: Relay List Metadata

**Status in Wisp:** Not yet implemented
**Depends on:** NIP-01
**Kind:** 10002 (replaceable)

## Overview

Kind 10002 events advertise a user's preferred relays and whether they
use each relay for reading, writing, or both. This is the foundation
of the "outbox model" for efficient message routing.

## Event Structure

```json
{
  "kind": 10002,
  "tags": [
    ["r", "wss://relay1.example.com"],
    ["r", "wss://relay2.example.com", "read"],
    ["r", "wss://relay3.example.com", "write"]
  ],
  "content": ""
}
```

## R-Tag Format

```
["r", "<relay-url>"]           // Both read and write
["r", "<relay-url>", "read"]   // Read only
["r", "<relay-url>", "write"]  // Write only
```

- No marker = both read and write
- `read`: Client fetches events FROM this relay
- `write`: Client publishes events TO this relay

## The Outbox Model

### Writing (Publishing)

When user A publishes an event:
1. Fetch user A's kind 10002
2. Publish to all relays marked as `write` (or unmarked)

### Reading (Fetching)

When you want to see user B's posts:
1. Fetch user B's kind 10002
2. Subscribe on relays marked as `read` (or unmarked) in B's list

### Practical Example

```
Alice's relays (kind 10002):
  wss://relay-a.com  [write]
  wss://relay-b.com  [read + write]

Bob wants to see Alice's posts:
  -> Subscribe on wss://relay-a.com and wss://relay-b.com
  (where Alice writes)

Alice wants to see Bob's posts:
  -> Fetch Bob's kind 10002
  -> Subscribe on Bob's write relays
```

## Fetching a User's Relay List

```json
{"kinds": [10002], "authors": ["<pubkey>"], "limit": 1}
```

## Implementation Considerations

- Cache relay lists — they rarely change
- Limit relay connections (don't connect to 50 relays per user)
- Fallback: if no kind 10002, use default relays
- Recommended: 2-4 write relays, 4-8 read relays per user
- Update your own kind 10002 when the user changes relay settings

## Relationship to Other NIPs

- **NIP-02 (kind 3):** Some clients stored relay preferences in kind 3 content (legacy)
- **NIP-51 (kind 10050):** DM relays for NIP-17 private messages
- **NIP-11:** Use relay info to check capabilities before adding to list

## Read-Modify-Write

Kind 10002 is replaceable — same pattern as NIP-02:
1. Fetch current kind 10002
2. Parse r-tags
3. Add/remove/modify relays
4. Republish complete list

## Common Pitfalls

- Don't add too many relays — each costs a WebSocket connection
- Relay URLs should be normalized (lowercase, trailing slash consistency)
- An empty kind 10002 means the user has no preferred relays (use defaults)
- The outbox model requires fetching relay lists for every user you want to follow
- Batch relay list fetches to avoid N+1 query patterns
- Some relays may not store kind 10002 events
- Wisp currently uses hardcoded default relays — kind 10002 would make this dynamic
