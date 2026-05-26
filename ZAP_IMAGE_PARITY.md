# Zap Message Rich Content — Cross-Platform Parity

Companion to `WALLET_PARITY.md` and `LIGHT_MODE_COLOR_PARITY.md`. This
doc captures the contract for rendering rich zap-receipt messages
(body text, links, hashtags, inline images) so iOS and Android look
the same.

Android lands this in `feat/zap-with-image`. iOS should mirror.

---

## 1. Motivation

Zap messages are a free-text field and people use them for everything
from "thanks!" to full promotional payloads with body text, links, and
attached images ("zapvertising"). Rendering them as single-line
truncated strings — the original behavior — destroyed the content:
the body got chopped to `... tes…`, links were unclickable, and
attached images were only sometimes detected.

Spec: the engagement-drawer zap row now renders the message body
through the same rich-content pipeline as a regular post body. The
post-card top-zap banner stays a one-line preview with image URLs
collapsed to `[image]`.

---

## 2. Detection contract (top-zap banner only)

The post-card top-zap banner needs a single-line preview. To keep the
sats amount visible, image URLs inside the message are replaced with
the literal token `[image]`. This is the **only** surface that
performs URL detection — the drawer uses the full rich-content
renderer instead.

A URL is treated as an inline image if and only if its path ends in
one of:

```
jpg, jpeg, png, gif, webp, heic, heif, bmp, svg
```

(case-insensitive, query/fragment stripped before extension check).

Same extension set the rest of the app already uses for inline images
in regular post content — keep it identical so behavior is consistent.
Only **the first** image URL is collapsed; surrounding text is
preserved.

---

## 3. Surfaces

There are exactly two places where zap messages render today, and each
gets a specific treatment:

### 3.1 Top-zap banner (above the post action bar)

Single-line preview. Trade: room is tight, the sats amount must stay
visible. So when the message contains an image URL, replace the URL
with the literal token `[image]` and keep the surrounding text.

Examples:

| Message | Banner shows |
| --- | --- |
| `https://i.nostr.build/abc.gif` | `[image]` |
| `nice post https://i.nostr.build/abc.gif` | `nice post [image]` |
| `https://i.nostr.build/abc.gif love it` | `[image] love it` |
| `no image here, just text` | `no image here, just text` (unchanged) |

No image is loaded on the banner — it's a one-line preview.

### 3.2 Engagement drawer zap row

Renders the zap as a mini-post. Layout, top to bottom:

- **Header row** — avatar (30dp, top-aligned) on the left, then a
  weighted column on the right:
  - Display-name line (semibold labelMedium, left), private-zap icon
    (if applicable), bolt + sats amount (orange, right-aligned).
- **Body** (only when `message.isNotBlank()`) — full post-content
  pipeline. Same renderer used for regular post bodies (Android:
  `RichContent`; iOS: equivalent `PostBodyRenderer` / whatever you
  use for kind-1 content). Handles inline text, hashtags, profile/
  note mentions, inline images / videos / embeds, **and OG-style
  social preview cards** for plain links (the renderer fetches
  og:image / og:title / og:siteName / og:description on demand and
  renders a tappable card). Caching applies the same way as for
  links inside post bodies, so a URL pasted in a zap message and the
  same URL in a regular post share preview data.

The body inherits the post-body callbacks the caller already plumbs
(`onProfileClick`, `onNoteClick`, `onHashtagClick`, `eventRepo` for
profile resolution), so a hashtag in a zap message is just as
clickable as one in the post above it. No special-case URL detection
inside the drawer — the rich-content renderer already detects images
and renders them inline at the right position in the body, which is
exactly what zapvertising payloads need.

Long messages are NOT truncated. The drawer grows vertically. Trade-
off: occasionally tall zap rows when someone pastes a 500-char
manifesto. Worth it — truncation destroys the value of the message.

---

## 4. Android implementation references

| Concern | File | Symbol |
| --- | --- | --- |
| Banner detection + preview-text helpers | `app/src/main/kotlin/com/wisp/app/ui/util/ZapMessageImage.kt` | `firstImageUrl`, `previewText` |
| Top-zap banner integration | `app/src/main/kotlin/com/wisp/app/ui/component/PostCard.kt` | `TopZapperBanner` (message line) |
| Engagement-drawer row integration | `app/src/main/kotlin/com/wisp/app/ui/component/ReactionDetailsSection.kt` | `ZapRow` |
| Shared post-body renderer used by ZapRow | `app/src/main/kotlin/com/wisp/app/ui/component/RichContent.kt` | `RichContent` |

`ZapMessageImage.kt` is ~30 lines of plain Kotlin — only used by the
banner now. The drawer delegates to `RichContent`, which is the same
component a normal feed post renders its body through, so the iOS
port is mostly "pass the message string through your existing post-
body renderer" rather than writing detection from scratch.

---

## 5. iOS port checklist

- [ ] Add `ZapMessage` helper (or extension) exposing
      `firstImageURL(in:)` and `previewText(for:)` with the extension
      set from §2. Only used by the banner.
- [ ] In the top-zap banner view, swap the raw message string for
      `ZapMessage.previewText(for: message)`.
- [ ] **Rewrite the engagement-drawer zap row as a mini-post:**
      - Header row: 30pt avatar (top-aligned) + display name (semi-
        bold labelMedium) on the left, optional private icon + bolt
        icon + sats amount (orange) on the right.
      - Body: pass `message` to your existing post-body renderer (the
        same component that renders kind-1 content in the feed).
        Inherits its hashtag / mention / link / inline-image handling.
      - **Do not** truncate the body. Let it wrap vertically.
- [ ] Plumb the existing post-body callbacks
      (`onProfileClick`, `onNoteClick`, `onHashtagClick`, eventRepo
      equivalent) into the zap row so links in zap messages are as
      interactive as links in a regular post.

### 5.1 Visual test

1. Zap any post with `"check out https://my-site.com #bitcoin"` — the
   engagement drawer renders the message with an OG preview card for
   the URL (image / title / domain) and the `#bitcoin` hashtag styled
   like in a post body.
2. Zap with a message containing only an image URL — the drawer
   renders the inline image (no extra text), banner shows `[image]`.
3. Zap with a long body (e.g. 400 chars) — the drawer row grows
   vertically, nothing is truncated.
4. Zap with a body that includes a `nostr:nevent1…` reference — the
   referenced note renders as a quoted card inside the zap row (or
   whatever your post-body renderer does for note refs).
5. Banner check: top-zap banner stays single-line with the sats
   amount visible regardless of message length.
6. Tap a social-preview card in the drawer → opens the URL in a
   browser (same handler as link previews in regular posts).

---

## 6. Locked decisions

| Decision | Choice | Why |
| --- | --- | --- |
| Drawer renderer | **Full post-body renderer** | Zapvertising payloads (body + links + media) deserve the same rendering as a regular post. Custom detection lost too much. |
| Drawer body truncation | **None** — let it wrap | Truncation killed the value of the message. Drawer height is acceptable trade. |
| Image-detection extensions (banner only) | Same set as in-post images | Behavior parity with the rest of the app. |
| Single-line banner preview | `[image]` token | Sats amount must stay readable on the top-zap banner. |
| Image detection scope | Banner only | Drawer's post-body renderer already handles inline images at the right position. |
| Multiple image URLs in the banner | Only the first collapses to `[image]` | Edge case; secondary URLs stay as text. |
| Animated GIFs | Loaded as-is via the post-body renderer | Coil on Android already animates GIFs; iOS post-body renderer should too. |
| Loading / failed states | Default platform behavior | Don't bake a placeholder — let the renderer fall through. |
