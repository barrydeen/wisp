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
 * Minimal Drive REST v3 client targeted at the user's appDataFolder.
 *
 * One backup file per Nostr account. Filenames follow `wisp_nsec_<npub>.bin`
 * so we can list every account on the user's Drive and surface the npub
 * without downloading each file. Legacy single-file backups (`wisp_nsec.bin`,
 * from before multi-account support) are also surfaced in the list.
 */
class DriveBackupService(
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class BackupFile(val fileId: String, val name: String) {
        /** `npub1…` parsed from the filename, or null for the legacy unnamed backup. */
        val npubFromName: String?
            get() = when {
                name.startsWith(BACKUP_PREFIX) && name.endsWith(BACKUP_SUFFIX) -> {
                    name.removePrefix(BACKUP_PREFIX).removeSuffix(BACKUP_SUFFIX)
                        .takeIf { it.isNotEmpty() && it.startsWith("npub1") }
                }
                else -> null
            }
    }

    suspend fun listBackups(accessToken: String): List<BackupFile> = withContext(Dispatchers.IO) {
        val nameQuery = "name = '$LEGACY_FILENAME' or name contains '$BACKUP_PREFIX'"
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
                if (!response.isSuccessful) {
                    throw IOException("Drive download failed: ${response.code} ${response.message}")
                }
                response.body?.string() ?: throw IOException("Empty download body")
            }
        }

    /**
     * Creates a new backup file in appDataFolder. If a backup for the same npub
     * already exists, deletes it first so we keep one file per account rather
     * than accumulating duplicate revisions.
     */
    suspend fun uploadBackup(accessToken: String, npub: String, payload: String) =
        withContext(Dispatchers.IO) {
            require(npub.startsWith("npub1")) { "npub must be bech32-encoded" }
            val filename = "$BACKUP_PREFIX$npub$BACKUP_SUFFIX"

            // Remove any existing file with the same name to avoid duplicates.
            listBackups(accessToken).filter { it.name == filename }.forEach { existing ->
                deleteBackup(accessToken, existing.fileId)
            }

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
        private const val BACKUP_PREFIX = "wisp_nsec_"
        private const val BACKUP_SUFFIX = ".bin"
        private const val LEGACY_FILENAME = "wisp_nsec.bin"
    }
}
