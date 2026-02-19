# NIP-05: DNS-Based Verification

**Status in Wisp:** Not yet implemented
**Depends on:** NIP-01

## Overview

Maps a user's pubkey to an internet identifier like `user@domain.com`.
The domain owner places a JSON file that proves the mapping.

## Identifier Format

```
<local-part>@<domain>
```
Example: `bob@example.com`

## Verification Flow

1. Client sees `"nip05": "bob@example.com"` in a kind 0 profile event
2. Client fetches: `GET https://example.com/.well-known/nostr.json?name=bob`
3. Response must be JSON:
```json
{
  "names": {
    "bob": "<pubkey-hex>"
  },
  "relays": {
    "<pubkey-hex>": ["wss://relay1.example.com", "wss://relay2.example.com"]
  }
}
```
4. Client verifies `names.bob == event.pubkey`
5. Optionally use `relays` to discover user's preferred relays

## Profile Event (Kind 0) Content

```json
{
  "name": "bob",
  "nip05": "bob@example.com",
  "picture": "https://...",
  ...
}
```

## Implementation Notes

- HTTP request must use HTTPS (no plain HTTP)
- The `name` query parameter must be URL-encoded
- CORS headers needed on server: `Access-Control-Allow-Origin: *`
- Cache results — don't re-verify on every profile view
- `_@domain.com` is the convention for domain-level identifiers (the `_` local part)
- The `relays` field is optional in the response

## Display

- If verified: show as `bob@example.com` (often with a checkmark)
- If `_@domain.com`: display as just `domain.com`
- Verification can fail (DNS changes, server down) — degrade gracefully

## Common Pitfalls

- Must match the pubkey in the kind 0 event, not just any pubkey
- The local-part is case-insensitive for lookup but the server response key must match
- Don't block UI on verification — do it async
- Rate limit verification requests per domain
- The `.well-known/nostr.json` path is fixed (no customization)
