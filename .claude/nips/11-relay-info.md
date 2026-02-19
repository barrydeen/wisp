# NIP-11: Relay Information Document

**Status in Wisp:** Not yet implemented
**Depends on:** NIP-01

## Overview

Relays expose metadata about themselves via an HTTP(S) endpoint at the
same URL as the WebSocket connection.

## How to Fetch

```
GET https://relay.example.com/
Accept: application/nostr+json
```

- Use the relay's WebSocket URL but with `https://` instead of `wss://`
- Must include the `Accept: application/nostr+json` header
- Without this header, the relay may serve a webpage instead

## Response Format

```json
{
  "name": "relay.example.com",
  "description": "A Nostr relay",
  "pubkey": "<operator-pubkey-hex>",
  "contact": "operator@example.com",
  "supported_nips": [1, 2, 9, 11, 12, 15, 16, 20, 22, 33, 40],
  "software": "https://github.com/...",
  "version": "1.0.0",
  "limitation": {
    "max_message_length": 524288,
    "max_subscriptions": 20,
    "max_filters": 10,
    "max_limit": 5000,
    "max_subid_length": 100,
    "max_event_tags": 100,
    "max_content_length": 8196,
    "min_pow_difficulty": 0,
    "auth_required": false,
    "payment_required": false,
    "created_at_lower_limit": 94608000,
    "created_at_upper_limit": 94608000
  },
  "relay_countries": ["US"],
  "language_tags": ["en"],
  "tags": ["community"],
  "posting_policy": "https://relay.example.com/policy"
}
```

All fields are optional.

## Key Fields for Clients

| Field | Use |
|-------|-----|
| `supported_nips` | Feature detection — check before using advanced features |
| `limitation.auth_required` | Whether NIP-42 auth is needed |
| `limitation.max_message_length` | Max WebSocket message size |
| `limitation.max_subscriptions` | How many concurrent REQs allowed |
| `limitation.payment_required` | Whether relay requires payment |

## Implementation Notes

- Cache relay info — it rarely changes (cache for hours/days)
- Use this to adapt client behavior per relay
- Check `supported_nips` before sending NIP-specific messages
- The `pubkey` field identifies the relay operator (for contacting about issues)

## Common Pitfalls

- Not all relays implement NIP-11
- The `Accept` header is required — omitting it gets HTML
- URL conversion: `wss://relay.example.com` -> `https://relay.example.com`
- Some relays behind reverse proxies may not handle the header correctly
- `supported_nips` is self-reported and may not be accurate
