package cooking.zap.app.viewmodel

import android.app.Application
import android.util.Log
import android.widget.Toast
import cooking.zap.app.R
import cooking.zap.app.nostr.NostrEvent
import cooking.zap.app.nostr.SignerFailureAction
import cooking.zap.app.nostr.signerDeleteFailureAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared signing for the background draft-delete paths ([ComposeViewModel.deleteDraftOnPublish]
 * and [DraftsViewModel.deleteDraft]). Both previously swallowed every signer failure with
 * `catch (_: Exception)`, so on a RemoteSigner (Amber) a failed delete-sign vanished silently and
 * the "deleted" draft survived on relays — a resurrection vector.
 *
 * [signOrNull] surfaces the failure instead: a user-dismissed cancel logs and gives up (never
 * re-prompts — that's the popup we're eliminating); a rejection (possibly an automatic permissions
 * auto-reject) retries ONCE via the silent ContentResolver path, which never launches UI. It does
 * NOT toast — the caller decides, so a multi-event delete can abort remaining NORMAL signs after a
 * terminal failure (avoiding a second popup) and toast exactly once via [toastFailed].
 */
object DraftDeleteSigning {
    private const val TAG = "DraftDelete"

    /**
     * Sign one deletion event. Returns the signed event, or null if signing ultimately failed
     * (caller skips publishing it). [normal] uses the full signer (silent + intent fallback);
     * [silent] uses the silent-only path (no UI). On a [SignerFailureAction.RETRY_SILENT] failure
     * the silent path is tried once; a [SignerFailureAction.GIVE_UP] failure is not retried.
     */
    suspend fun signOrNull(
        label: String,
        normal: suspend () -> NostrEvent,
        silent: suspend () -> NostrEvent?
    ): NostrEvent? {
        return try {
            normal()
        } catch (e: Exception) {
            when (signerDeleteFailureAction(e)) {
                SignerFailureAction.RETRY_SILENT -> {
                    Log.w(TAG, "$label rejected; retrying via silent path", e)
                    val retried = try {
                        silent()
                    } catch (e2: Exception) {
                        Log.w(TAG, "$label silent retry threw", e2)
                        null
                    }
                    if (retried == null) Log.w(TAG, "$label failed after silent retry")
                    retried
                }
                SignerFailureAction.GIVE_UP -> {
                    Log.w(TAG, "$label failed (${e.javaClass.simpleName}); not retrying", e)
                    null
                }
            }
        }
    }

    /**
     * Non-blocking "Couldn't clear draft" toast, on the main dispatcher via the application
     * context — the composer may already be out of composition (the PR 2 discard path fires the
     * delete post-onDispose), so this must never depend on the screen still being mounted.
     */
    suspend fun toastFailed(app: Application) {
        withContext(Dispatchers.Main) {
            Toast.makeText(app, app.getString(R.string.error_draft_delete_failed), Toast.LENGTH_SHORT).show()
        }
    }
}
