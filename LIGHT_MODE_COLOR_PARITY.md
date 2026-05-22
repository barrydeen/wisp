# Light-Mode Color Parity

Companion to `WALLET_PARITY.md`. This doc captures the light-mode color-palette
decisions landed on Android in `fix/light-mode-and-icon` so the iOS agent can
mirror them and keep the two apps visually identical.

iOS is the long-running reference, but light-mode polish was an Android-first
pass — the Android code is the source of truth for the contract below until
iOS lands matching changes.

---

## 1. Why two adjustments instead of one

iOS and Android both ship the same per-theme `primary` / `zapColor` /
`repostColor` / etc. swatches. Two specific problems showed up only in light
mode and have separate fixes:

1. **Dark-mode primary reads as too bright on near-white surfaces.** The
   default Spark orange `#FF9800` doesn't have enough contrast against the
   light-mode background, so buttons look washed out. **Fix:** light mode
   uses a deeper, hand-tuned `customLightPrimary` (default `#D9730D`).
2. **The plain zap color reads as muddy when used for the celebratory
   in-flight bolt animation.** The HSL lightness drops too low after the
   primary darkening. **Fix:** the animation uses a separate
   `zapAnimationColor` derived from `zapColor` with floored lightness
   (`vividZapColor()`).

Everywhere *other* than the in-flight animation, the zap color and the
Material primary should be the **same** light-mode hue (`#D9730D` for the
default theme), so the post-zap icon, count text, top zap indicators, wallet
accents, and buttons all read as one consistent orange.

---

## 2. The contract (locked values)

### 2.1 `primary` — the throughout-the-theme accent

| Theme | Dark | Light |
| --- | --- | --- |
| default (`custom`) | `#FF9800` | **`#D9730D`** |
| user-picked custom accent | user value (`accentColor`) | `darkenColor(accentColor, fraction = 0.18f)` |

`darkenColor` is `hsl[2] *= (1 - fraction)` then `HSLToColor(hsl)`. Floor
caps optional (Android doesn't currently floor here; iOS can match).

### 2.2 `zapColor` — every zap surface *except* the in-flight animation

**Matches `primary` exactly in light mode.** Used by: post-zap icon tint
when `hasUserZapped`, post-zap count text, reaction count text after
react, top zap indicators in `NotificationsScreen`, wallet accent
(`WalletScreen`), Lightning QR icon, `PostCard` zap-bar tint, etc.

| Theme | Dark | Light |
| --- | --- | --- |
| default (`custom`) | `#FF9800` | **`#D9730D`** (same as `primary.light`) |
| user-picked custom accent | user value | `customLightPrimary` (same as `primary.light`) |

### 2.3 `zapAnimationColor` — *only* the celebratory in-flight bolt

Derived from `zapColor` via:

```
val hsl = colorToHSL(zapColor)
hsl[1] = (hsl[1] * 1.15f).coerceIn(0f, 1f)   // bump saturation 15%
hsl[2] = hsl[2].coerceAtLeast(0.5f)          // floor lightness at 0.5
return HSLToColor(hsl)
```

Used only by `LightningAnimation` (the three-shadow halo on the post action
bar's bolt during a zap in flight). Everywhere else uses `zapColor`.

### 2.4 Default theme other tokens (light)

| Token | Light value | Notes |
| --- | --- | --- |
| `repostColor` | `#2E7D32` | Dark green for contrast |
| `bookmarkColor` | `#D9730D` | Tracks `primary.light` |
| `paidColor` | `#C9A000` | Slightly muted gold |
| `secondary` | `#FFB74D` | Lighter accent |
| `background` | `#D8D8D8` | |
| `surface` | `#E8E8E8` | |
| `surfaceVariant` | `#CDCDCD` | |
| `onBackground` / `onSurface` | `#1C1B1F` | |
| `onSurfaceVariant` | `#333333` | |
| `outline` | `#999999` | |

---

## 3. Android implementation references

| Concern | File | Symbol |
| --- | --- | --- |
| `primary` darkening for custom accents | `app/src/main/kotlin/com/wisp/app/ui/theme/Theme.kt` | `customLightPrimary` |
| `darkenColor()` / `lightenColor()` helpers | `app/src/main/kotlin/com/wisp/app/ui/theme/Theme.kt` | `darkenColor`, `lightenColor` |
| `vividZapColor()` for the animation | `app/src/main/kotlin/com/wisp/app/ui/theme/Theme.kt` | `vividZapColor` |
| `zapAnimationColor` accessor | `app/src/main/kotlin/com/wisp/app/ui/theme/Theme.kt` | `WispThemeColors.zapAnimationColor` |
| `zapColor` aligned to `customLightPrimary` | `app/src/main/kotlin/com/wisp/app/ui/theme/Theme.kt` | `WispColors` custom-light branch |
| Default theme light swatches | `app/src/main/kotlin/com/wisp/app/ui/theme/Themes.kt` | `Themes.themes[0].light` |
| In-flight bolt consumes `zapAnimationColor` | `app/src/main/kotlin/com/wisp/app/ui/component/ActionBar.kt` | `LightningAnimation` |
| In-flight bolt overlay also uses it | `app/src/main/kotlin/com/wisp/app/ui/component/LightningOverlay.kt` | top-level `zapColor` lookup |

Search hint on Android: `git grep -n "WispThemeColors.zap"` lists every
consumer. Every site uses `zapColor` *except* the two animation files
which use `zapAnimationColor`.

---

## 4. iOS port checklist

Mirror the contract above. Specific items the iOS agent should action:

- [ ] Default-theme `light.primary` is `#D9730D` (was `#CC7000` on the
      original light branch — bump it to match).
- [ ] Custom-accent light variant darkens by 18% in HSL space
      (`darkenColor(accent, 0.18)` equivalent).
- [ ] Add a `zapAnimationColor` computed property derived from `zapColor`
      with `saturation *= 1.15` (cap 1.0) and `lightness ≥ 0.5`.
- [ ] In-flight bolt animations (the white-core glow pulse, the trailing
      shadow halos) consume `zapAnimationColor`.
- [ ] **Every other zap-tinted UI element consumes plain `zapColor`** —
      post-zap icon, post-zap count, reaction count after react, top zap
      indicators, wallet zap accents, etc. Don't accidentally use
      `zapAnimationColor` anywhere except the celebratory animation.
- [ ] Light-mode `zapColor` and `bookmarkColor` track `primary.light` for
      both the default theme and custom-accent themes (so the throughout-
      the-theme orange is one consistent hue).
- [ ] Non-default themes (Nord, Dracula, Gruvbox, etc.) keep their own
      curated `light.zapColor` — those weren't part of the mismatch and
      are intentionally different from `primary` for hue contrast.

### 4.1 Quick visual test

In light mode, with the default theme:

1. Open a post you've already zapped. The bolt icon + count should be
   the same orange as the **Save** / **Connect** / **Confirm** buttons
   elsewhere in the app.
2. Open the Notifications tab and find a zap row. The bolt + sat count
   should be that same orange.
3. Open Wallet → main dashboard. The Send/Receive button rings should be
   the same orange.
4. Trigger a fresh zap. The in-flight bolt animation should pulse a
   **noticeably brighter** orange (the `zapAnimationColor` vivid variant
   — never muddy or dark).

If any of those 4 surfaces reads as a different shade, the contract is
violated — go back to §2 and audit which property the site is reading.

---

## 5. App icon

Separate change shipped in the same PR but unrelated to the color contract.
Android replaced the Android-Studio default launcher icon with the iOS
artwork (orange wisp on black square) as an adaptive icon. iOS already
has this artwork — no action needed on the iOS side.

Reference: `app/src/main/res/drawable/ic_launcher_foreground.xml`
(vector), `app/src/main/res/drawable/ic_launcher_background.xml`
(solid black).

---

## 6. Locked decisions

| Decision | Choice | Why |
| --- | --- | --- |
| Light-mode `primary` for default theme | **`#D9730D`** (deeper than dark `#FF9800`) | Better contrast on near-white surfaces. |
| Light-mode custom-accent derivation | `darkenColor(accent, 0.18)` | Generic darken for any user-picked color. |
| `zapColor` vs `primary` in light mode | **Same value** | Throughout-the-theme orange should be one hue. |
| `zapAnimationColor` is separate | **Yes — vivid variant of `zapColor`** | Plain zap color reads muddy at animation time on light surfaces. |
| Scope of `zapAnimationColor` | **Animation only** | Static UI elements stay on `zapColor` to match `primary`. |
| Non-default themes | Their own curated palettes | Each preset has hue-contrast reasoning; don't blanket-align across all themes. |
