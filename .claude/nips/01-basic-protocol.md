# NIP-01: Basic Protocol

**Status in Wisp:** Implemented
**Files:** `Event.kt`, `Filter.kt`, `ClientMessage.kt`, `RelayMessage.kt`, `Keys.kt`

## Event Structure

```json
{
  "id": "<32-byte SHA-256 hex of serialized event>",
  "pubkey": "<32-byte x-only public key hex>",
  "created_at": "<unix timestamp seconds>",
  "kind": "<integer 0-65535>",
  "tags": [["e", "..."], ["p", "..."], ...],
  "content": "<arbitrary string>",
  "sig": "<64-byte Schnorr signature hex>"
}
```

## Event ID Computation

SHA-256 of the UTF-8 serialized JSON array:
```
[0, <pubkey>, <created_at>, <kind>, <tags>, <content>]
```
- Element 0 is always the integer `0`
- No whitespace in serialization
- Tags is array of arrays of strings

## Signature

- Schnorr signature (BIP-340) over the 32-byte event ID
- Uses secp256k1 curve with x-only public keys

## Client-to-Relay Messages

| Message | Format | Purpose |
|---------|--------|---------|
| EVENT | `["EVENT", <event>]` | Publish event |
| REQ | `["REQ", <sub_id>, <filter>, ...]` | Subscribe |
| CLOSE | `["CLOSE", <sub_id>]` | Unsubscribe |

## Relay-to-Client Messages

| Message | Format | Purpose |
|---------|--------|---------|
| EVENT | `["EVENT", <sub_id>, <event>]` | Matching event |
| EOSE | `["EOSE", <sub_id>]` | End of stored events |
| OK | `["OK", <event_id>, <bool>, <message>]` | Event acceptance |
| NOTICE | `["NOTICE", <message>]` | Human-readable error |

## Filter Object

```json
{
  "ids": ["<hex>", ...],
  "authors": ["<hex>", ...],
  "kinds": [<int>, ...],
  "#e": ["<hex>", ...],
  "#p": ["<hex>", ...],
  "#<single-letter>": ["<value>", ...],
  "since": <timestamp>,
  "until": <timestamp>,
  "limit": <int>
}
```
- All fields optional — empty filter matches everything
- Multiple filters in REQ = OR logic
- Fields within a filter = AND logic
- Tag filters use `#` prefix: `#e`, `#p`, `#t`, etc.

## Kind Ranges

| Range | Type | Storage |
|-------|------|---------|
| 0, 3, 10000-19999 | Replaceable | Keep only latest per pubkey (+ d-tag for parameterized) |
| 1-9999 (except above) | Regular | All stored |
| 20000-29999 | Ephemeral | Not stored, only forwarded |
| 30000-39999 | Parameterized replaceable | Latest per pubkey + d-tag combo |

## Standard Event Kinds

- **0** — Profile metadata (replaceable). Content is JSON: `{name, about, picture, nip05, banner, display_name, ...}`
- **1** — Short text note. Content is the post text.

## Common Pitfalls

- `created_at` is Unix seconds, not milliseconds
- Event ID must be computed from exact JSON serialization (no extra whitespace)
- Subscription IDs are arbitrary strings chosen by the client
- Relays may send events for a subscription even after EOSE (new live events)
- A REQ with the same subscription ID replaces the previous subscription
- `limit` applies to initial stored events; live events keep flowing
- Tag values in filters are exact matches (case-sensitive hex)
