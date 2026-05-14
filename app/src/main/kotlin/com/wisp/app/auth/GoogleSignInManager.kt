package com.wisp.app.auth

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException

/**
 * Returns the user's stable Google identifier (the `sub`-shaped `id` field
 * from the Google ID token credential). That's the only thing we need — no
 * OAuth scopes, no Drive access, no consent dialog beyond Credential
 * Manager's account picker.
 */
class GoogleSignInManager(
    context: Context,
    private val webClientId: String
) {
    private val credentialManager = CredentialManager.create(context)

    suspend fun signIn(activity: ComponentActivity): String {
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
}

class GoogleSignInException(message: String, cause: Throwable? = null) : Exception(message, cause)
