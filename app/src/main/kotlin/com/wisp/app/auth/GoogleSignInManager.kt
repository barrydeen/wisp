package com.wisp.app.auth

import android.app.PendingIntent
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Two-step Google sign-in:
 *   1. Credential Manager returns a GoogleIdTokenCredential whose `id` field is
 *      stable per (Google account, OAuth client). We treat it as the `sub` and
 *      derive the backup encryption key from it.
 *   2. AuthorizationClient requests an OAuth access token with the
 *      drive.appdata scope. May return a token directly, or a PendingIntent
 *      requiring the user to grant consent the first time.
 */
class GoogleSignInManager(
    context: Context,
    private val webClientId: String
) {
    private val credentialManager = CredentialManager.create(context)

    data class GoogleAuthResult(
        val sub: String,
        val accessToken: String,
        val email: String?
    )

    suspend fun signIn(activity: ComponentActivity): GoogleAuthResult {
        val sub = getGoogleSubFromCredentialManager(activity)
        val accessToken = getDriveAccessToken(activity)
        return GoogleAuthResult(
            sub = sub,
            accessToken = accessToken,
            email = sub.takeIf { it.contains("@") }
        )
    }

    private suspend fun getGoogleSubFromCredentialManager(activity: ComponentActivity): String {
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        val response = try {
            credentialManager.getCredential(activity, request)
        } catch (e: GetCredentialException) {
            throw GoogleSignInException("Google sign-in cancelled or unavailable: ${e.message}", e)
        }

        val credential = response.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw GoogleSignInException("Unexpected credential type: ${credential.javaClass.simpleName}")
        }

        val parsed = try {
            GoogleIdTokenCredential.createFrom(credential.data)
        } catch (e: GoogleIdTokenParsingException) {
            throw GoogleSignInException("Failed to parse Google ID token", e)
        }
        return parsed.id
    }

    private suspend fun getDriveAccessToken(activity: ComponentActivity): String {
        val authClient = Identity.getAuthorizationClient(activity)
        val request = AuthorizationRequest.Builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()

        val authResult = authClient.authorize(request).await()

        if (authResult.hasResolution()) {
            val pendingIntent = authResult.pendingIntent
                ?: throw GoogleSignInException("Authorization required but no pending intent provided")
            return resolveAuthorization(activity, pendingIntent)
        }

        return authResult.accessToken
            ?: throw GoogleSignInException("No access token returned")
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
                    ?: throw GoogleSignInException("Authorization granted but no access token returned")
                cont.resume(token)
            } catch (e: Exception) {
                cont.resumeWithException(
                    if (e is GoogleSignInException) e
                    else GoogleSignInException("Authorization resolution failed: ${e.message}", e)
                )
            }
        }
        launcher.launch(
            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
        )
    }

    companion object {
        private const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}

class GoogleSignInException(message: String, cause: Throwable? = null) : Exception(message, cause)
