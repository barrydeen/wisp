# NIP-51: Lists

**Status in Wisp:** Not yet implemented
**Depends on:** NIP-01

## Overview

Standardized list events for organizing content. Lists can be public
(in tags) or private (encrypted in content).

## Standard List Kinds

### Replaceable Lists (10000-10050)

| Kind | Name | Purpose |
|------|------|---------|
| 10000 | Mute list | Muted pubkeys/events/words/threads |
| 10001 | Pin list | Pinned events |
| 10002 | Relay list | See NIP-65 |
| 10003 | Bookmark list | Bookmarked events |
| 10004 | Communities list | Followed communities |
| 10005 | Public chats list | Followed public chat channels |
| 10006 | Blocked relays | Relays the user avoids |
| 10007 | Search relays | Relays for search queries |
| 10009 | User groups | Followed user groups |
| 10015 | Interests list | Topics/hashtags of interest |
| 10030 | Emoji list | Custom emoji collections |
| 10050 | DM relays | Preferred relays for DMs (NIP-17) |

### Parameterized Replaceable Lists (30000-30030)

| Kind | Name | Purpose |
|------|------|---------|
| 30000 | Follow set | Named subset of follows |
| 30001 | Generic list | User-defined categorized list |
| 30002 | Relay set | Named relay group |
| 30003 | Bookmark set | Categorized bookmarks |
| 30004 | Curation set | Content curation |
| 30007 | User group set | Named user groups |
| 30015 | Interest set | Named interest categories |
| 30030 | Emoji set | Named emoji pack |

Parameterized lists use a `d` tag for the list name/identifier.

## Event Structure

```json
{
  "kind": 10000,
  "tags": [
    ["p", "<pubkey>"],
    ["e", "<event-id>"],
    ["word", "bitcoin"],
    ["t", "<hashtag>"]
  ],
  "content": "<optional-nip44-encrypted-private-items>"
}
```

## Public vs Private Items

- **Public items:** In `tags` array — visible to everyone
- **Private items:** NIP-44 encrypted in `content` field
  - Encrypted content is a JSON array of tags: `[["p","abc..."],["e","def..."]]`
  - Encrypted to self (own pubkey)

## Common Tag Types in Lists

| Tag | References |
|-----|-----------|
| `p` | Pubkey |
| `e` | Event ID |
| `a` | Replaceable event coordinate |
| `t` | Hashtag/topic |
| `word` | Keyword/phrase (mute lists) |
| `emoji` | Custom emoji |
| `r` | URL reference |

## Mute List (Kind 10000) Example

```json
{
  "kind": 10000,
  "tags": [
    ["p", "<annoying-pubkey>"],
    ["t", "politics"],
    ["word", "spam"],
    ["e", "<thread-to-mute>"]
  ],
  "content": "<nip44-encrypted-private-mutes>"
}
```

## Read-Modify-Write

Like NIP-02, all list kinds are replaceable:
1. Fetch current list
2. Parse existing tags
3. Add/remove items
4. Republish with complete updated list

## Common Pitfalls

- Lists are replaceable — publishing replaces the entire list
- Always read-modify-write to avoid data loss
- Private items require NIP-44 encryption
- Parameterized lists (30000+) need a `d` tag — don't forget it
- Some list kinds have specific tag type expectations
- Large lists may hit relay tag limits
- Kind 10002 (relay list) has its own spec in NIP-65
