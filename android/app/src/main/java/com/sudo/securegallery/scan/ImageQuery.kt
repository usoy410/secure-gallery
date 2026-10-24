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
 *
 * The query has no selection: on some phones MediaProvider returned no rows at all to the app
 * for a selection on `OWNER_PACKAGE_NAME`, while the same SQL worked for the shell user (#65).
 * Every filter runs in [keep] instead, and a value MediaStore leaves null never drops a photo.
 */
object ImageQuery {
    /** Part of every redacted copy's display name, e.g. `IMG_0001.sg-redacted.jpg` (#18). */
    const val REDACTED_NAME_MARKER = ".sg-redacted."

    const val EXCLUDED_MIME_TYPE = "image/gif"

    val projection: Array<String> = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.OWNER_PACKAGE_NAME,
        MediaStore.Images.Media.IS_PENDING,
    )

    /** True if the scan should index this row. Null columns count as "keep". */
    fun keep(mimeType: String?, displayName: String?, owner: String?, pending: Int?, ownPackage: String): Boolean =
        mimeType != EXCLUDED_MIME_TYPE &&
            owner != ownPackage &&
            displayName?.contains(REDACTED_NAME_MARKER) != true &&
            pending != 1

    const val SORT_ORDER = "${MediaStore.Images.Media._ID} ASC"
}
