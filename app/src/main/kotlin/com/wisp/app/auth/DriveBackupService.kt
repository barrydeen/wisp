package com.wisp.app.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/**
 * Thrown when Drive returns 401 on a request authenticated with the supplied
 * access token. Caller must clear the token from the Play Services cache (via
 * `GoogleAuthUtil.clearToken`) and obtain a fresh one — most commonly because
 * the user revoked Wisp's authorization from their Google account settings.
 *
 * The stale token is exposed so the caller can pass it to `clearToken`.
 */
class DriveAuthorizationExpiredException(
    val staleToken: String,
    message: String
) : IOException(message)

/**
 * Minimal Drive REST v3 client targeted at the user's appDataFolder.
 *
 * Filenames are opaque (`wisp_bk_<uuid>.bin`) so Drive cannot see the user's
 * npub — that link would otherwise let anyone with Google account access tie
 * the Nostr identity to the Google identity. The npub is recovered by
 * decrypting the file with the user's PIN-derived key.
 */
class DriveBackupService(
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun throwIfExpired(accessToken: String, response: okhttp3.Response, op: String) {
        if (response.code == 401) {
            throw DriveAuthorizationExpiredException(
                staleToken = accessToken,
                message = "Drive $op failed: 401 (authorization expired or revoked)"
            )
        }
    }

    data class BackupFile(val fileId: String, val name: String)

    suspend fun listBackups(accessToken: String): List<BackupFile> = withContext(Dispatchers.IO) {
        val nameQuery = "name contains '$BACKUP_PREFIX'"
        val url = "https://www.googleapis.com/drive/v3/files" +
            "?spaces=appDataFolder" +
            "&q=" + java.net.URLEncoder.encode(nameQuery, "UTF-8") +
            "&fields=files(id,name,modifiedTime)" +
            "&pageSize=100"

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        httpClient.newCall(req).execute().use { response ->
            throwIfExpired(accessToken, response, "list")
            if (!response.isSuccessful) {
                throw IOException("Drive list failed: ${response.code} ${response.message}")
            }
            val body = response.body?.string() ?: return@withContext emptyList()
            val root = json.parseToJsonElement(body) as? JsonObject ?: return@withContext emptyList()
            val files = root["files"]?.jsonArray ?: return@withContext emptyList()
            files.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (!name.startsWith(BACKUP_PREFIX) || !name.endsWith(BACKUP_SUFFIX)) return@mapNotNull null
                BackupFile(id, name)
            }
        }
    }

    suspend fun downloadBackup(accessToken: String, fileId: String): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            httpClient.newCall(req).execute().use { response ->
                throwIfExpired(accessToken, response, "download")
                if (!response.isSuccessful) {
                    throw IOException("Drive download failed: ${response.code} ${response.message}")
                }
                response.body?.string() ?: throw IOException("Empty download body")
            }
        }

    /**
     * Creates a new backup file with a fresh random UUID filename. Each call
     * creates a distinct file — there is no replace path, which sidesteps the
     * delete-then-upload race that an in-place update would introduce.
     */
    suspend fun uploadBackup(accessToken: String, payload: String) =
        withContext(Dispatchers.IO) {
            val filename = "$BACKUP_PREFIX${UUID.randomUUID()}$BACKUP_SUFFIX"

            val metadata = """{"name":"$filename","parents":["$APP_DATA_FOLDER"]}"""
            val boundary = "wisp-${UUID.randomUUID()}"
            val crlf = "\r\n"
            val body = buildString {
                append("--").append(boundary).append(crlf)
                append("Content-Type: application/json; charset=UTF-8").append(crlf).append(crlf)
                append(metadata).append(crlf)
                append("--").append(boundary).append(crlf)
                append("Content-Type: application/octet-stream").append(crlf).append(crlf)
                append(payload).append(crlf)
                append("--").append(boundary).append("--").append(crlf)
            }

            val requestBody: RequestBody = body.toRequestBody(
                "multipart/related; boundary=$boundary".toMediaType()
            )

            val req = Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                .header("Authorization", "Bearer $accessToken")
                .post(requestBody)
                .build()

            httpClient.newCall(req).execute().use { response ->
                throwIfExpired(accessToken, response, "upload")
                if (!response.isSuccessful) {
                    throw IOException("Drive upload failed: ${response.code} ${response.message}")
                }
            }
        }

    suspend fun deleteBackup(accessToken: String, fileId: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId")
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()
        httpClient.newCall(req).execute().close()
    }

    companion object {
        private const val APP_DATA_FOLDER = "appDataFolder"
        private const val BACKUP_PREFIX = "wisp_bk_"
        private const val BACKUP_SUFFIX = ".bin"
    }
}
