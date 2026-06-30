# Samsung IME Inset Issue — On-chain Send Screen

## Problem

The on-chain send amount screen (`OnchainSendAmountContent`) has a numeric `BasicTextField`
for the amount and action buttons ("Get Fee Quote" / "Continue") below it.
When the keyboard opens, the buttons were hidden behind it.

### What we tried that did NOT work

The app uses `enableEdgeToEdge()` + `windowSoftInputMode="adjustResize"` in the manifest.
On Android 11+ with `WindowCompat.setDecorFitsSystemWindows(window, false)` (called internally
by `enableEdgeToEdge()`), `adjustResize` is ignored — the window does not resize when the
keyboard appears. Content must be shifted using Compose's window inset APIs.

**Attempt 1 — `Modifier.imePadding()` on the outer column/Box**
Added `imePadding()` to a `fillMaxSize()` wrapper. On stock Android this should pin
content to just above the keyboard. On Samsung One UI (tested on SM-A546U, Android 13),
`WindowInsets.ime.bottom` is over-reported relative to the visual keyboard height.
This caused a large empty gap (~60–80 dp) between the button and the keyboard — the button
was pushed too far up.

**Attempt 2 — `navigationBarsPadding().imePadding()`**
The ModalBottomSheet pattern (used in `EditPresetsSheet`) applies nav-bar padding first so
`imePadding()` only adds the delta above the nav bar. On this Samsung, the gesture
navigation bar reports `navigationBars.bottom = 0` while the keyboard is open, so
`navigationBarsPadding()` contributed nothing and the gap remained identical.

**Attempt 3 — `Box` with `Alignment.BottomCenter`**
Pinned the button outside the scroll column using a `Box` and `Alignment.BottomCenter`,
so the button rides up with `imePadding()` on the outer Box. Same over-reporting issue;
gap still present. Without `imePadding()`, the box does not resize (confirming
`adjustResize` is inactive) and the button sits at the screen bottom behind the keyboard.

**Root cause summary**

`WindowInsets.ime` on Samsung One UI reports a larger bottom inset than the visual keyboard
height. The exact extra amount (~60–80 dp) could not be determined without device-specific
measurement. It is likely the gesture navigation area being double-counted or a
Samsung-specific IME reporting quirk. Neither `navigationBarsPadding()` nor any ordering of
inset modifiers corrected it.

## Solution

Sidestep the inset problem entirely by ensuring the keyboard is dismissed before the user
needs to interact with the action buttons:

1. **`ImeAction.Done` on the `BasicTextField`** — sets the keyboard action key to "Done"
   (visible on Samsung's numeric keypad). Tapping it triggers `onGetFeeQuote()`.

2. **Explicit keyboard dismissal** — `LocalSoftwareKeyboardController.hide()` is called in
   the `KeyboardActions.onDone` handler. `BasicTextField` (unlike `TextField`) does not
   auto-dismiss on Done; the call must be explicit.

3. **`LaunchedEffect(feeQuote)`** — when the fee quote arrives (either via Done or via the
   "Get Fee Quote" button tapped while keyboard is open), the keyboard is hidden
   automatically. This guarantees the "Continue" button is always fully visible when it
   appears, regardless of how the quote was triggered.

```kotlin
val keyboardController = LocalSoftwareKeyboardController.current

LaunchedEffect(feeQuote) {
    if (feeQuote != null) keyboardController?.hide()
}

BasicTextField(
    keyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Done
    ),
    keyboardActions = KeyboardActions(onDone = {
        keyboardController?.hide()
        onGetFeeQuote()
    }),
    ...
)
```

## Takeaway

On Samsung One UI, `Modifier.imePadding()` / `WindowInsets.ime` cannot be relied upon to
accurately represent the visual keyboard height. For screens where an action button must be
accessible while a keyboard is open, prefer dismissing the keyboard programmatically (via
`LocalSoftwareKeyboardController`) over trying to resize/reposition layout around it.
