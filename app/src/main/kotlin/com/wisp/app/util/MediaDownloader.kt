package com.wisp.app.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

object MediaDownloader {

    private val mimeTypes = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "mp4" to "video/mp4",
        "mov" to "video/quicktime",
        "webm" to "video/webm"
    )

    private val httpClient
        get() = com.wisp.app.relay.HttpClientFactory.createHttpClient(
            connectTimeoutSeconds = 30,
            readTimeoutSeconds = 60
        )

    suspend fun downloadMedia(context: Context, url: String) {
        try {
            val bytes = withContext(Dispatchers.IO) {
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Download failed: ${response.code}")
                    response.body?.bytes() ?: error("Empty response")
                }
            }

            val fileName = url.substringAfterLast('/').substringBefore('?').ifEmpty { "download" }
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = mimeTypes[ext] ?: "application/octet-stream"

            withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(collection, values)
                        ?: error("Failed to create MediaStore entry")
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    val file = File(dir, fileName)
                    FileOutputStream(file).use { it.write(bytes) }
                }
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Saved to Downloads", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
