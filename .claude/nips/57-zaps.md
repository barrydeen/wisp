# NIP-57: Zaps (Lightning Payments)

**Status in Wisp:** Not yet implemented
**Depends on:** NIP-01, NIP-05 (for LNURL discovery)
**Kinds:** 9734 (zap request), 9735 (zap receipt)

## Overview

Zaps are Lightning Network payments attached to Nostr events or profiles.
The flow involves the client, the recipient's LNURL server, and a Lightning node.

## Zap Flow

```
1. Client discovers recipient's LNURL (from kind 0 profile)
2. Client creates kind 9734 zap request event
3. Client sends zap request to LNURL server
4. LNURL server returns a Lightning invoice
5. Client pays the invoice via Lightning wallet
6. LNURL server sees payment, creates kind 9735 zap receipt
7. LNURL server publishes zap receipt to relays
```

## LNURL Discovery

From the recipient's kind 0 profile metadata:
```json
{
  "lud06": "lnurl1...",       // LNURL-pay encoded URL
  "lud16": "user@domain.com"   // Lightning Address (preferred)
}
```

Lightning Address resolution:
`user@domain.com` -> `GET https://domain.com/.well-known/lnurlp/user`

## Zap Request (Kind 9734)

Created by the sender, sent to the LNURL server (NOT published to relays).

```json
{
  "kind": 9734,
  "tags": [
    ["p", "<recipient-pubkey>"],
    ["e", "<zapped-event-id>"],
    ["relays", "wss://relay1...", "wss://relay2..."],
    ["amount", "21000"],
    ["lnurl", "lnurl1..."]
  ],
  "content": "Great post!"
}
```

| Tag | Required | Purpose |
|-----|----------|---------|
| `p` | Yes | Recipient pubkey |
| `e` | No | Event being zapped (omit for profile zap) |
| `relays` | Yes | Where to publish the receipt |
| `amount` | Yes | Millisatoshis |
| `lnurl` | Yes | Recipient's LNURL |

- The content is an optional public message
- The event is signed by the sender

## LNURL Server Request

```
GET <callback_url>?amount=<millisats>&nostr=<url-encoded-kind-9734-json>&lnurl=<lnurl>
```

Response:
```json
{"pr": "lnbc210n1...", "routes": []}
```

`pr` is the Lightning invoice (BOLT11) to pay.

## Zap Receipt (Kind 9735)

Created by the LNURL server after payment confirmation.

```json
{
  "kind": 9735,
  "pubkey": "<lnurl-server-pubkey>",
  "tags": [
    ["p", "<recipient-pubkey>"],
    ["e", "<zapped-event-id>"],
    ["P", "<sender-pubkey>"],
    ["bolt11", "lnbc210n1..."],
    ["description", "<kind-9734-json>"]
  ],
  "content": ""
}
```

- Signed by the LNURL server's key (not sender or recipient)
- `description` tag contains the original kind 9734 zap request JSON
- `bolt11` tag contains the paid invoice

## Querying Zaps

```json
// Zap receipts for an event
{"kinds": [9735], "#e": ["<event-id>"]}

// Zap receipts for a user
{"kinds": [9735], "#p": ["<pubkey>"]}
```

## Validating Zap Receipts

1. Parse the kind 9735 event
2. Extract the `description` tag -> parse as kind 9734
3. Verify kind 9734 signature
4. Verify `p` tags match between 9734 and 9735
5. Verify `e` tags match (if present)
6. Verify the bolt11 invoice amount matches the `amount` tag in 9734

## Common Pitfalls

- Kind 9734 is NOT published to relays — it's sent to the LNURL server
- Kind 9735 is published by the LNURL server, not the sender
- Amount is in **millisatoshis** (1 sat = 1000 msats)
- Must validate zap receipts — anyone could publish a fake kind 9735
- The sender's pubkey is in the 9734 (inside `description`), not in the 9735 pubkey field
- LNURL servers may have minimum/maximum payment amounts
- Not all profiles have Lightning addresses
- Anonymous zaps: if the 9734 is signed by a random key, the zap is anonymous
