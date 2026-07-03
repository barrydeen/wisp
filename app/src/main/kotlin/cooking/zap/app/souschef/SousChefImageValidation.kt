package cooking.zap.app.souschef

/** Web parity: the unified input rejects files where `file.size > 20 * 1024 * 1024`. */
const val SOUS_CHEF_MAX_IMAGE_BYTES: Long = 20L * 1024 * 1024

/**
 * Validate an image picked for the Sous Chef unified input. Pure — callers
 * query [android.content.ContentResolver] for the MIME type and size and pass
 * the results in.
 *
 * Returns `null` when the pick is acceptable, else the user-facing error:
 * - non-image (or unknown) MIME type is rejected;
 * - size strictly greater than [SOUS_CHEF_MAX_IMAGE_BYTES] is rejected
 *   (exactly 20MB passes, matching the web's `>` check);
 * - unknown size (`null`) passes — the provider reported nothing to check.
 */
fun validateStagedImage(mimeType: String?, sizeBytes: Long?): String? {
    if (mimeType == null || !mimeType.startsWith("image/")) {
        return "Please choose an image file."
    }
    if (sizeBytes != null && sizeBytes > SOUS_CHEF_MAX_IMAGE_BYTES) {
        return "Image file is too large. Please use an image under 20MB."
    }
    return null
}
