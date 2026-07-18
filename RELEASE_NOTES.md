
# Changelog

##[1.3.4]

🍳 My Kitchen — Your personal hub in the Recipes tab: Saved, Published, Grocery, Planner, and Nourish in one place.

🛒 Grocery lists — Create encrypted lists, add ingredients from any recipe, and shop by category. Syncs with zap.cooking.

📅 Meal planner — Plan your week from saved or published recipes (or plain text), then generate a grocery list from the whole week. Syncs with the web.

🔒 Private by default — Meal plans and grocery lists are NIP-44 encrypted to your own keys. Relays only ever see ciphertext.

Plus: back-navigation returns to the My Kitchen section you left; recipe name chips show on grocery lists; first-save protection so a cold session can't overwrite your recipe collections.

##[1.3.3]

🍳 Sous Chef publish flow — Publish, Edit in composer, and Discard now do exactly what they say. Save to My Recipes publishes and bookmarks in one honest step, with a clear heads-up that it posts publicly.

📛 Cookbook is now My Recipes — same tab, clearer name. The authored sub-tab is now "Published."

👤 Fixed account switcher sometimes showing a bare npub instead of your name/photo after importing a key.

##[1.3.2]

🥦 Nourish Explore — Browse pantry-analyzed recipes ranked by Nourish score. Filter by high protein, under 600 kcal, low carb, no seed oils, no added sugar, or no red meat — and stack filters to find exactly what you're after.

📊 Nutrition estimates on Explore cards — Calories and protein per serving when available; rough estimates are labeled honestly.

Plus: Intelligence → Nourish opens Explore directly (no placeholder hub).

##[1.3.1]

## Update Post Modal for Cheffy Recipe 

- fix(cheffy): bound Note Review draft field so actions stay reachable

## [1.3.0]

### Cheffy Note Review

- Ask Cheffy about any dish photo on the feed: open a food note's menu and
  choose "Ask Cheffy about this dish" to get a drafted reply — a short,
  warm comment or a reverse-engineered recipe guess. You always edit
  before anything is posted, and the reply is signed by you.
- Free for Pro Kitchen members. Not a member? Buy a single draft for
  21 sats, paid straight from the built-in wallet (Spark or NWC) or any
  Lightning wallet via QR.
- Credits are tied to your Nostr key, not your device — drafts bought on
  zap.cooking work in the app, and drafts bought in the app work on the
  web.
- Optional "⚡🍳 via Cheffy" note on posted replies (on by default for
  recipe guesses, off for comments — your choice is remembered per mode).
- Notes with several photos get a picker so Cheffy looks at the right one.

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
