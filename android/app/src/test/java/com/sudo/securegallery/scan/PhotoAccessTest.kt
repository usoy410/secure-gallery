package com.sudo.securegallery.scan

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.READ_MEDIA_IMAGES
import android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which permission each Android version needs, and what a grant means (issue #15). */
class PhotoAccessTest {

    private fun access(sdk: Int, vararg granted: String) = PhotoAccess.from(sdk) { it in granted }

    @Test
    fun asksForTheRightPermissionPerVersion() {
        assertArrayEquals(arrayOf(READ_EXTERNAL_STORAGE), PhotoAccess.permissionsFor(30))
        assertArrayEquals(arrayOf(READ_EXTERNAL_STORAGE), PhotoAccess.permissionsFor(32))
        assertArrayEquals(arrayOf(READ_MEDIA_IMAGES), PhotoAccess.permissionsFor(33))
        assertArrayEquals(arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED), PhotoAccess.permissionsFor(34))
    }

    @Test
    fun grantsMapToAccessLevels() {
        assertEquals(PhotoAccess.FULL, access(30, READ_EXTERNAL_STORAGE))
        assertEquals(PhotoAccess.NONE, access(30))
        assertEquals(PhotoAccess.FULL, access(33, READ_MEDIA_IMAGES))
        // Below 33 the media permissions do not exist, so they grant nothing.
        assertEquals(PhotoAccess.NONE, access(32, READ_MEDIA_IMAGES))
        assertEquals(PhotoAccess.FULL, access(34, READ_MEDIA_IMAGES, READ_MEDIA_VISUAL_USER_SELECTED))
        assertEquals(PhotoAccess.SELECTED, access(34, READ_MEDIA_VISUAL_USER_SELECTED))
        assertEquals(PhotoAccess.NONE, access(34))
    }
}
