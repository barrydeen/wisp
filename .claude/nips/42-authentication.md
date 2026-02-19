# NIP-42: Authentication

**Status in Wisp:** Not yet implemented
**Depends on:** NIP-01
**Kind:** 22242 (regular, ephemeral-like)

## Overview

Challenge-response authentication between client and relay. Used when
relays require identity verification before serving or accepting events.

## Flow

```
Relay  -->  ["AUTH", "<challenge-string>"]     (1. Challenge)
Client -->  ["AUTH", <signed-auth-event>]       (2. Response)
Relay  -->  ["OK", "<event-id>", true, ""]     (3. Acceptance)
```

## When Authentication Happens

Relay sends an AUTH challenge when:
1. On initial connection (if `auth_required` in NIP-11)
2. In response to a REQ that requires authentication
3. In response to an EVENT that requires authentication
4. Relay returns `OK` with `auth-required:` prefix message

## Auth Event Structure (Kind 22242)

```json
{
  "kind": 22242,
  "tags": [
    ["relay", "wss://relay.example.com"],
    ["challenge", "<challenge-string-from-relay>"]
  ],
  "content": "",
  "created_at": "<current-timestamp>"
}
```

## Implementation

```kotlin
// 1. Parse AUTH challenge from relay
// RelayMessage needs a new subtype:
data class Auth(val challenge: String) : RelayMessage()

// 2. When receiving ["AUTH", challenge]:
fun handleAuthChallenge(challenge: String, relayUrl: String) {
    val tags = listOf(
        listOf("relay", relayUrl),
        listOf("challenge", challenge)
    )
    val authEvent = NostrEvent.create(
        privkey = keypair.privkey,
        pubkey = keypair.pubkey,
        kind = 22242,
        content = "",
        tags = tags
    )
    // 3. Send: ["AUTH", <authEvent>]
    relay.send("""["AUTH",${authEvent.toJson()}]""")
}
```

## Relay Message Extension

Need to handle the AUTH message type in `RelayMessage.parse()`:
```
["AUTH", "<challenge>"]  -->  RelayMessage.Auth(challenge)
```

## Client Message Extension

Need a new client message format:
```
["AUTH", <event-json>]
```

## Validation (Relay Side)

- Kind must be 22242
- `created_at` must be recent (within a few minutes)
- `relay` tag must match the relay's own URL
- `challenge` tag must match the issued challenge
- Signature must be valid

## Common Pitfalls

- The `relay` tag must exactly match the relay URL (including trailing slash or not)
- Auth events should have recent timestamps — old ones will be rejected
- AUTH is the same message type name for both challenge (relay->client) and response (client->relay)
- Some relays require auth for reading, others only for writing
- Store the challenge and respond promptly — challenges may expire
- Don't confuse with HTTP auth — this is WebSocket-level Nostr auth
