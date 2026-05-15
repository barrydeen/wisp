package com.wisp.app.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException

object NostrCredentialSaver {
    private const val TAG = "NostrCredentialSaver"

    suspend fun saveNsec(context: Context, npub: String, nsec: String): Boolean {
        return try {
            val cm = CredentialManager.create(context)
            val request = CreatePasswordRequest(id = npub, password = nsec)
            cm.createCredential(context, request)
            true
        } catch (e: CreateCredentialCancellationException) {
            Log.d(TAG, "User dismissed the save-credential prompt")
            false
        } catch (e: CreateCredentialException) {
            Log.w(TAG, "Credential Manager could not save the nsec: ${e.type} ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error saving nsec to password manager", e)
            false
        }
    }

    /**
     * Asks Credential Manager for a saved password (nsec). Shows the system
     * picker if any saved credentials match this app, otherwise returns null
     * silently. The returned string is the password field of the chosen
     * PasswordCredential — for accounts created through Wisp this is the nsec.
     */
    suspend fun loadSavedNsec(context: Context): String? {
        return try {
            val cm = CredentialManager.create(context)
            val request = GetCredentialRequest(listOf(GetPasswordOption()))
            val response = cm.getCredential(context, request)
            (response.credential as? PasswordCredential)?.password
        } catch (e: GetCredentialCancellationException) {
            Log.d(TAG, "User dismissed the credential picker")
            null
        } catch (e: NoCredentialException) {
            Log.d(TAG, "No saved credentials available")
            null
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Credential Manager could not load credentials: ${e.type} ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error loading credentials", e)
            null
        }
    }
}
