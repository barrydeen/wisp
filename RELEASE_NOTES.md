
# Changelog

## [1.2.1]

### Google Sign-In & Backup Fix

* Fixed Google sign-in for Zap Cooking Android.
* Restored Google Drive backup support for keys and app data.
* Moved Android OAuth configuration from the inherited Wisp project to Zap Cooking’s own Google Cloud project.
* Registered the Zap Cooking release signing certificate with Google so sign-in works with the published Android app.
* Added build documentation to help prevent future Google OAuth or signing-certificate mismatches.


## [1.2.0]

### What’s New

- Inline YouTube embeds now play directly in notes, replies, threads, and quoted posts.
- Improved quote previews, media handling, and note actions.
- Added quick actions for copying note text and npubs.
- More reliable link previews for modern websites.
- Fixed drafts that could reappear after deletion, logout, relay sync, or use on another device.
- Improved notification navigation back to the feed.
- Quoted notes from muted or unavailable accounts now fail gracefully instead of loading indefinitely.
- Cooking Utilities now remember the last-used tool and converter settings.
- Added a Clear action for converter inputs.
- General polish across feeds, recipes, drafts, quoted content, and utilities.


## [1.1.1] - 2026-07-03

### Zap Cooking Android Beta

- First native Android release of Zap Cooking
- Discover, publish, and save recipes on Nostr
- Browse the OnlyFood feed
- Create recipe posts and share what you are cooking
- Send Lightning zaps to cooks
- Built-in Spark wallet and NWC wallet support
- Group chats and encrypted direct messages
- Cheffy, your kitchen companion
