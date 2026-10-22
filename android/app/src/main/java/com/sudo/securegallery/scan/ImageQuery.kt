package com.sudo.securegallery.scan

import android.provider.MediaStore

/**
 * The MediaStore query the scan runs (§8.1, §8.2).
 *
 * It keeps still images only: MediaStore.Images already leaves out videos, and GIFs are
 * excluded here. It skips files the app wrote itself, either by owner package or by the
 * [REDACTED_NAME_MARKER] in the file name, which survives a reinstall (the owner package
 * does not). With Android 14 "selected photos" access, MediaStore returns only the photos the
 * user picked, so the same query scans just those.
 */
object ImageQuery {
    /** Part of every redacted copy's display name, e.g. `IMG_0001.sg-redacted.jpg` (#18). */
    const val REDACTED_NAME_MARKER = ".sg-redacted."

    const val EXCLUDED_MIME_TYPE = "image/gif"

    val projection: Array<String> = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.SIZE,
    )

    val selection: String = listOf(
        "${MediaStore.Images.Media.MIME_TYPE} != ?",
        "${MediaStore.Images.Media.IS_PENDING} = 0",
        "(${MediaStore.Images.Media.OWNER_PACKAGE_NAME} IS NULL" +
            " OR ${MediaStore.Images.Media.OWNER_PACKAGE_NAME} != ?)",
        "${MediaStore.Images.Media.DISPLAY_NAME} NOT LIKE ?",
    ).joinToString(" AND ")

    fun selectionArgs(ownPackage: String): Array<String> =
        arrayOf(EXCLUDED_MIME_TYPE, ownPackage, "%$REDACTED_NAME_MARKER%")

    const val SORT_ORDER = "${MediaStore.Images.Media._ID} ASC"
}
