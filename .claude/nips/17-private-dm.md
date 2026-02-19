# NIP-17: Private Direct Messages

**Status in Wisp:** Not yet implemented
**Depends on:** NIP-01, NIP-44 (encryption)
**Kinds:** 14 (chat message), 13 (seal), 1059 (gift wrap)

## Overview

Three-layer encryption scheme for private messaging that hides both
content AND metadata (sender, recipient, timestamps) from relays.

## Three-Layer Architecture

```
Layer 3 (outer):  Kind 1059 — Gift Wrap     [random pubkey, visible to relay]
Layer 2 (middle): Kind 13   — Seal          [sender's pubkey, encrypted]
Layer 1 (inner):  Kind 14   — Chat Message  [actual content, encrypted]
```

### Layer 1: Chat Message (Kind 14) — "Rumor"

The actual message. Created but **NOT signed** (no `sig`, no `id`).

```json
{
  "kind": 14,
  "pubkey": "<sender-pubkey>",
  "created_at": "<actual-timestamp>",
  "tags": [
    ["p", "<recipient-pubkey>"],
    ["e", "<replied-event-id>", "<relay>", "reply"],
    ["subject", "conversation topic"]
  ],
  "content": "Hello!"
}
```

- `p` tags: all conversation participants
- Optional `e` tags for replies within DM conversation
- Optional `subject` tag for conversation threading

### Layer 2: Seal (Kind 13)

Encrypts the rumor with NIP-44, signed by the sender.

```json
{
  "kind": 13,
  "pubkey": "<sender-real-pubkey>",
  "created_at": "<randomized-timestamp>",
  "tags": [],
  "content": "<nip44-encrypted-rumor-json>"
}
```

- `created_at` is randomized (within +-2 days) to hide timing
- No tags — sender info is hidden from relay
- Content = NIP-44 encrypt(rumor JSON, sender_privkey, recipient_pubkey)

### Layer 3: Gift Wrap (Kind 1059)

Wraps the seal with a random throwaway key.

```json
{
  "kind": 1059,
  "pubkey": "<random-throwaway-pubkey>",
  "created_at": "<randomized-timestamp>",
  "tags": [["p", "<recipient-pubkey>"]],
  "content": "<nip44-encrypted-seal-json>"
}
```

- `pubkey` is a fresh random key (not the sender!)
- `p` tag has recipient so relay can deliver
- Content = NIP-44 encrypt(seal JSON, throwaway_privkey, recipient_pubkey)

## Sending Flow

1. Create unsigned kind 14 rumor (the actual message)
2. JSON-serialize the rumor
3. NIP-44 encrypt rumor with sender's privkey + recipient's pubkey
4. Create kind 13 seal, sign with sender's real key, randomize timestamp
5. Generate throwaway keypair
6. NIP-44 encrypt seal with throwaway privkey + recipient's pubkey
7. Create kind 1059 gift wrap, sign with throwaway key, randomize timestamp
8. Send to recipient's relays

## Receiving Flow

1. Receive kind 1059 event
2. NIP-44 decrypt content with my privkey + gift wrap pubkey -> seal JSON
3. Parse seal (kind 13), verify signature
4. NIP-44 decrypt seal content with my privkey + seal pubkey -> rumor JSON
5. Parse rumor (kind 14), extract message

## Subscription Filter

```json
{"kinds": [1059], "#p": ["<my-pubkey>"]}
```

Only need to subscribe to kind 1059 — the rest is encrypted inside.

## Group DMs

- Include multiple `p` tags in the rumor (kind 14)
- Send separate gift-wrapped copies to each participant
- Each copy encrypted to that specific recipient

## Common Pitfalls

- The rumor (kind 14) must NOT be signed — it has no `id` or `sig`
- Gift wrap pubkey is throwaway — don't try to look up the sender from it
- Timestamps on seal and gift wrap are intentionally randomized
- Must send a separate gift wrap to each recipient (can't broadcast)
- Relays only see: "someone sent an encrypted blob to recipient X"
- NIP-44 encryption is required (not NIP-04)
- Store decrypted conversations locally — you can't re-derive them without your privkey
