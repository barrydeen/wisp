package cooking.zap.app.ui.component

import android.app.Activity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference

private val NSEC_REGEX = Regex("nsec1[a-z0-9]{58}")

object NsecPasteGuard {
    /**
     * Set to true while a screen that intentionally accepts nsec input (e.g. login/import)
     * is visible, so key entry continues to work normally.
     */
    var nsecPasteAllowed: Boolean = false

    var warningVisible by mutableStateOf(false)
        private set

    private var activityRef: WeakReference<Activity>? = null

    fun setActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun containsNsec(text: String): Boolean = NSEC_REGEX.containsMatchIn(text)

    /**
     * Returns true if the paste was blocked (caller should NOT apply the new value).
     * Only triggers when an nsec appears in newText that wasn't in currentText.
     */
    fun blockIfNsec(currentText: String, newText: String): Boolean {
        if (nsecPasteAllowed) return false
        if (containsNsec(newText) && !containsNsec(currentText)) {
            warningVisible = true
            hideKeyboard()
            return true
        }
        return false
    }

    fun dismissWarning() {
        warningVisible = false
    }

    fun hideKeyboard() {
        val activity = activityRef?.get() ?: return
        val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        // Try the currently focused window first (works when a dialog has focus),
        // fall back to the decor view token.
        val token = activity.currentFocus?.windowToken
            ?: activity.window.decorView.windowToken
        imm.hideSoftInputFromWindow(token, 0)
    }

    /** InputTransformation for BasicTextField(state = TextFieldState) fields. */
    val inputTransformation = InputTransformation {
        if (!nsecPasteAllowed && containsNsec(asCharSequence().toString())) {
            revertAllChanges()
            warningVisible = true
            hideKeyboard()
        }
    }
}

/**
 * Red warning pill that floats above ALL content including dialogs, by rendering in a
 * non-touchable, non-focusable Dialog window. Place once at the root of the composition.
 */
@Composable
fun NsecPasteWarningOverlay(modifier: Modifier = Modifier) {
    var popupOpen by remember { mutableStateOf(false) }
    var showPill by remember { mutableStateOf(false) }

    // Use snapshotFlow + collectLatest so that dismissWarning() setting warningVisible=false
    // doesn't cancel this coroutine (it's filtered out), allowing popupOpen = false to run.
    LaunchedEffect(Unit) {
        snapshotFlow { NsecPasteGuard.warningVisible }
            .filter { it }
            .collectLatest {
                popupOpen = true
                showPill = true
                delay(3500)
                showPill = false
                NsecPasteGuard.dismissWarning()
                delay(250)
                popupOpen = false
            }
    }

    if (popupOpen) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            // Inside a Compose Dialog, LocalView.current IS the DialogWindowProvider.
            val dialogView = LocalView.current
            val keyboardController = LocalSoftwareKeyboardController.current

            SideEffect {
                val window = (dialogView as? DialogWindowProvider)?.window
                window?.addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )
                window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }

            // Hide keyboard from within the overlay window's context — this uses the
            // applicationWindowToken which works regardless of which window has IME focus.
            LaunchedEffect(Unit) {
                keyboardController?.hide()
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                AnimatedVisibility(
                    visible = showPill,
                    enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut()
                ) {
                    Surface(
                        color = Color(0xFFB71C1C),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .padding(horizontal = 20.dp)
                            // Enough clearance for nav bar + compose toolbar / bottom bars
                            .padding(bottom = 100.dp)
                            .fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Text(
                                text = "Paste blocked — your nsec is your private key and must never be shared.",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
