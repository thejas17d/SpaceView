package com.spaceview.data

import java.io.File

/**
 * Where SpaceView keeps user content: app-private storage only, so no storage permissions are
 * needed (docs/research/android-performance.md, section 3.1). The Room database (measurements,
 * gallery items) and DataStore preferences arrive in later milestones.
 *
 * Constructing this class does no I/O; directories are created on first use, off the main thread.
 */
class StorageLayout(private val filesDir: File) {
    /** Root for saved gallery items (GLB model, JPEG texture, WebP thumbnail per item). */
    val galleryDir: File get() = File(filesDir, GALLERY_DIR)

    /** Directory for a single gallery item, keyed by its stable id. */
    fun itemDir(itemId: String): File {
        require(itemId.isNotBlank() && itemId.none { it == '/' || it == '\\' } && itemId != "." && itemId != "..") {
            "Invalid gallery item id: '$itemId'"
        }
        return File(galleryDir, itemId)
    }

    private companion object {
        const val GALLERY_DIR = "gallery"
    }
}
