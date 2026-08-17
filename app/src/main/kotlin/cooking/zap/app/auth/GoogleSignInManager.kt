package cooking.zap.app.auth

import android.app.PendingIntent
import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialOption
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Two-step Google sign-in:
 *   1. Credential Manager returns a GoogleIdTokenCredential — the one-tap sheet
 *      first, escalating to the Sign in with Google button flow when that has
 *      no credential to offer (see [shouldFallBackToButtonFlow]). We pull the
 *      `sub` claim out of its signed JWT — that's the stable Google account ID
 *      (`GoogleIdTokenCredential.id` is the email, which can change for
 *      workspace renames). Play Services has already validated the JWT, so we
 *      only decode it; we don't re-verify the signature.
 *   2. AuthorizationClient requests an OAuth access token with the
 *      drive.appdata scope. May return a token directly, or a PendingIntent
 *      requiring the user to grant consent the first time.
 */
class GoogleSignInManager(
    context: Context,
    private val webClientId: String
) {
    private val appContext = context.applicationContext
    private val credentialManager = CredentialManager.create(context)

    data class GoogleAuthResult(
        val sub: String,
        val accessToken: String
    )

    suspend fun signIn(activity: ComponentActivity): GoogleAuthResult {
        val sub = getGoogleSubFromCredentialManager(activity)
        val accessToken = getDriveAccessToken(activity)
        return GoogleAuthResult(sub = sub, accessToken = accessToken)
    }

    private suspend fun getGoogleSubFromCredentialManager(activity: ComponentActivity): String {
        val idOption = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()

        val response = try {
            requestCredential(activity, idOption)
        } catch (e: GetCredentialException) {
            if (!shouldFallBackToButtonFlow(e)) {
                throw GoogleSignInException("Google sign-in cancelled or unavailable: ${e.message}", e)
            }
            Log.i(TAG, "GetGoogleIdOption returned no credential; retrying with the Sign in with Google button flow", e)
            val buttonOption = GetSignInWithGoogleOption.Builder(webClientId).build()
            try {
                requestCredential(activity, buttonOption)
            } catch (fallback: GetCredentialException) {
                throw GoogleSignInException(
                    "Google sign-in cancelled or unavailable: ${fallback.message}",
                    fallback
                )
            }
        }

        val credential = response.credential
        if (credential !is CustomCredential || !isGoogleIdTokenCredentialType(credential.type)) {
            // The type string, not the class name: every custom credential is a
            // `CustomCredential`, so the class tells us nothing, and a provider
            // minting a type we don't know about is the one case this branch
            // exists to catch. `type` is declared on the base `Credential`, so
            // it reads on the `!is CustomCredential` arm too.
            throw GoogleSignInException("Unexpected credential type: ${credential.type}")
        }

        val parsed = try {
            GoogleIdTokenCredential.createFrom(credential.data)
        } catch (e: GoogleIdTokenParsingException) {
            throw GoogleSignInException("Failed to parse Google ID token", e)
        }
        return extractSubFromJwt(parsed.idToken)
    }

    private suspend fun requestCredential(
        activity: ComponentActivity,
        option: CredentialOption
    ): GetCredentialResponse {
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()
        return credentialManager.getCredential(activity, request)
    }

    private fun extractSubFromJwt(idToken: String): String {
        val parts = idToken.split('.')
        if (parts.size < 2) {
            throw GoogleSignInException("Malformed ID token: expected at least two JWT segments")
        }
        val payloadJson = try {
            String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        } catch (e: IllegalArgumentException) {
            throw GoogleSignInException("Malformed ID token payload encoding", e)
        }
        val sub = try {
            jsonParser.parseToJsonElement(payloadJson).jsonObject["sub"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            throw GoogleSignInException("ID token payload is not valid JSON", e)
        }
        if (sub.isNullOrBlank()) {
            throw GoogleSignInException("ID token missing sub claim")
        }
        return sub
    }

    /**
     * Clears the stale Drive access token from Play Services' local cache,
     * then re-runs the authorization flow. When the user has revoked
     * Wisp's consent in their Google account settings, the local cache may
     * still hold a previously-issued token that Drive now rejects with 401.
     * Clearing forces `authorize()` to contact Google, which returns a
     * resolution PendingIntent so the user can re-consent.
     *
     * Best-effort: if `clearToken` itself fails (network, Play Services
     * unavailable), we still re-call `authorize()` — Play Services may
     * surface the resolution anyway once the upstream auth state is checked.
     */
    suspend fun refreshDriveAccessToken(activity: ComponentActivity, staleToken: String): String {
        withContext(Dispatchers.IO) {
            try {
                GoogleAuthUtil.clearToken(appContext, staleToken)
            } catch (e: Exception) {
                Log.w(TAG, "GoogleAuthUtil.clearToken failed; continuing with authorize() anyway", e)
            }
        }
        return getDriveAccessToken(activity)
    }

    /**
     * The Drive-authorization half of [signIn], and the sole entry point to it
     * — [resolveAuthorization] is reached only from here.
     *
     * Everything that escapes is a [DriveAuthorizationException], including
     * whatever `authorize()` itself raises. That is the whole point of the
     * wrapper: this step runs under the same `SigningIn` state as the
     * credential step, so the exception type is the only thing that can tell
     * a caller the member's Google sign-in actually succeeded and it was the
     * Drive grant that failed.
     */
    private suspend fun getDriveAccessToken(activity: ComponentActivity): String =
        try {
            requestDriveAccessToken(activity)
        } catch (e: CancellationException) {
            // Never re-type a cancellation: the catch below would turn the
            // coroutine's own teardown into a member-visible failure.
            throw e
        } catch (e: DriveAuthorizationException) {
            throw e
        } catch (e: Exception) {
            throw DriveAuthorizationException("Google Drive authorization failed: ${e.message}", e)
        }

    private suspend fun requestDriveAccessToken(activity: ComponentActivity): String {
        val authClient = Identity.getAuthorizationClient(activity)
        val request = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()

        val authResult = authClient.authorize(request).await()

        if (authResult.hasResolution()) {
            val pendingIntent = authResult.pendingIntent
                ?: throw DriveAuthorizationException("Authorization required but no pending intent provided")
            return resolveAuthorization(activity, pendingIntent)
        }

        return authResult.accessToken
            ?: throw DriveAuthorizationException("No access token returned")
    }

    private suspend fun resolveAuthorization(
        activity: ComponentActivity,
        pendingIntent: PendingIntent
    ): String = suspendCoroutine { cont ->
        val key = "wisp_google_auth_${System.currentTimeMillis()}"
        var launcher: androidx.activity.result.ActivityResultLauncher<IntentSenderRequest>? = null
        launcher = activity.activityResultRegistry.register(
            key,
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            launcher?.unregister()
            try {
                val authResult = Identity.getAuthorizationClient(activity)
                    .getAuthorizationResultFromIntent(result.data)
                val token = authResult.accessToken
                    ?: throw DriveAuthorizationException("Authorization granted but no access token returned")
                cont.resume(token)
            } catch (e: Exception) {
                cont.resumeWithException(
                    if (e is DriveAuthorizationException) e
                    else DriveAuthorizationException("Authorization resolution failed: ${e.message}", e)
                )
            }
        }
        launcher.launch(
            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
        )
    }

    companion object {
        private const val TAG = "GoogleSignInManager"
        private const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        private val jsonParser = Json { ignoreUnknownKeys = true }
    }
}

open class GoogleSignInException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A failure in the **Drive-authorization** half of [GoogleSignInManager.signIn]
 * — the member signed in to Google successfully and then the `drive.appdata`
 * grant did not complete.
 *
 * It narrows [GoogleSignInException] rather than sitting beside it, so no
 * existing catch changes meaning; the point is that a caller can now tell the
 * two halves apart. They share one state ([`SigningIn`]) because they share one
 * `signIn()` call, so the phase alone cannot distinguish them.
 *
 * This is not a corner case. `authorize()` returns a resolution `PendingIntent`
 * the **first** time an account grants the scope, so the consent screen always
 * appears for a new member — and backing out of it is one gesture.
 */
class DriveAuthorizationException(message: String, cause: Throwable? = null) :
    GoogleSignInException(message, cause)

/**
 * Decides whether a failed `GetGoogleIdOption` request should escalate to
 * [GetSignInWithGoogleOption].
 *
 * `GetGoogleIdOption` drives Credential Manager's one-tap bottom sheet, which
 * reports [NoCredentialException] — surfaced to the member as "No credentials
 * available" — in cases that have nothing to do with whether the device holds a
 * Google account: the account has never authorised this app, or the sheet is
 * inside the suppression window that follows repeated dismissals.
 * [GetSignInWithGoogleOption] is the branded button flow. It always presents
 * the full account picker and is the option Google documents for an explicit
 * "Sign in with Google" tap, which is exactly our entry point (Continue with
 * Google, on the splash screen). It requests the same
 * `TYPE_GOOGLE_ID_TOKEN_CREDENTIAL` credential, so nothing downstream changes
 * (see [isGoogleIdTokenCredentialType] for the one caveat on what comes back).
 *
 * Only the no-credential case escalates. Every other [GetCredentialException]
 * — cancellation above all — is a real answer, and a second dialog on top of a
 * member who just declined one is worse than failing.
 */
internal fun shouldFallBackToButtonFlow(e: GetCredentialException): Boolean = e is NoCredentialException

/**
 * Whether a returned [CustomCredential] carries a Google ID token we can parse.
 *
 * Both request options — [GetGoogleIdOption] and [GetSignInWithGoogleOption] —
 * ask for `TYPE_GOOGLE_ID_TOKEN_CREDENTIAL` (identical string, read out of both
 * classes in `googleid` 1.1.1), so that is the type we expect on either path.
 * But the credential is minted by the Play Services APK on the device, not by
 * the client library, and `googleid` also declares a public
 * `TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL` that nothing in the library itself
 * references. We cannot rule out a provider build labelling the button-flow
 * credential with it, and an exact-match check would then fail *after* the
 * member has already picked an account — the worst place to fail.
 *
 * Accepting both is safe because the type is not what makes the credential
 * readable: `GoogleIdTokenCredential.createFrom` reads only the `BUNDLE_KEY_*`
 * entries and never looks at the type or the subtype key. A bundle without an
 * ID token throws [GoogleIdTokenParsingException] either way, which the caller
 * already handles.
 */
internal fun isGoogleIdTokenCredentialType(type: String): Boolean =
    type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
        type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL

/**
 * Whether a failure that reached [cooking.zap.app.viewmodel.GoogleAuthViewModel]'s
 * sign-in catches may be headlined "Google sign-in didn't go through".
 *
 * Two conditions, because two things can be false about that sentence:
 *
 *  - **Phase.** `beginSignIn` runs `signIn()` and then the Drive *listing*
 *    under one `try`. `signInPending` is the caller's answer to which one it
 *    was still in, read off the state (`CheckingDrive` means signed in).
 *  - **Half.** `signIn()` is itself two steps — credentials, then the Drive
 *    *grant* — and both run under `SigningIn`, so the phase cannot separate
 *    them. [DriveAuthorizationException] can, and it is the likely failure for
 *    a first-time member: the consent screen always appears the first time, and
 *    declining it is one gesture.
 *
 * Both point the same way when they are unsure: no headline, and the member
 * gets the card exactly as it renders today.
 */
internal fun claimsSignInFailed(signInPending: Boolean, error: Throwable): Boolean =
    signInPending && error !is DriveAuthorizationException
