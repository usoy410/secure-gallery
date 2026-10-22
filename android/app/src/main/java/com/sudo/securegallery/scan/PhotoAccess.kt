package com.sudo.securegallery.scan

import android.Manifest
import android.os.Build

/**
 * How much of the gallery the user let the app read.
 *
 * On Android 14+ the user can share selected photos only; MediaStore then returns just those,
 * and the scan covers just those.
 */
enum class PhotoAccess {
    NONE,
    SELECTED,
    FULL;

    companion object {
        /** The permissions to ask for on this Android version. */
        fun permissionsFor(sdkInt: Int): Array<String> = when {
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            sdkInt >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        fun from(sdkInt: Int, isGranted: (String) -> Boolean): PhotoAccess = when {
            sdkInt >= Build.VERSION_CODES.TIRAMISU && isGranted(Manifest.permission.READ_MEDIA_IMAGES) -> FULL
            sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> SELECTED
            sdkInt < Build.VERSION_CODES.TIRAMISU && isGranted(Manifest.permission.READ_EXTERNAL_STORAGE) -> FULL
            else -> NONE
        }
    }
}
