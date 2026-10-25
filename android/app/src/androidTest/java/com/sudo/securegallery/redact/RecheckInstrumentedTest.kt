package com.sudo.securegallery.redact

import android.content.ContentUris
import android.content.ContentValues
import android.provider.MediaStore
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sudo.securegallery.data.CardCorners
import com.sudo.securegallery.data.DocumentClass
import com.sudo.securegallery.data.FieldType
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.data.RedactionBox
import com.sudo.securegallery.localize.FieldRules
import com.sudo.securegallery.localize.MlKitOcrEngine
import com.sudo.securegallery.scan.MediaStoreImageSource
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The recheck with real ML Kit on the emulator (issue #19): a box that covers only the left
 * half of a synthetic card's PCN leaves readable digits, so the recheck fails, the box grows,
 * and the item ends with a passing check or in NEEDS_CHECK. Logs counts only.
 */
@RunWith(AndroidJUnit4::class)
class RecheckInstrumentedTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val resolver = app.contentResolver

    @Test
    fun anUndersizedBoxIsCaughtAndRetried() = runBlocking {
        val name = "r01_philsys-card"
        val label = JSONObject(testAssets.open("redaction/$name.json").bufferedReader().use { it.readText() })
        // Coordinates only: the label's field values are never read here.
        val pcn = (0 until label.getJSONArray("fields").length()).map { label.getJSONArray("fields").getJSONObject(it) }
            .first { it.getString("type") == "pcn" }.getJSONArray("polygon")
        val p = (0 until 4).map { pcn.getJSONArray(it).let { xy -> xy.getDouble(0).toFloat() to xy.getDouble(1).toFloat() } }
        // Left half of the PCN only.
        val half = listOf(p[0], mid(p[0], p[1]), mid(p[3], p[2]), p[3]).flatMap { listOf(it.first, it.second) }
        val corners = label.getJSONArray("card_corners").let { c -> (0 until 4).flatMap { listOf(c.getJSONArray(it).getDouble(0).toFloat(), c.getJSONArray(it).getDouble(1).toFloat()) } }

        val uri = resolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name-recheck.jpg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SG recheck test/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            },
        )!!
        resolver.openOutputStream(uri)!!.use { out -> testAssets.open("redaction/$name.jpg").use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        val originalId = ContentUris.parseId(uri)
        var copyId: Long? = null
        try {
            val item = Item(
                mediaStoreId = originalId, dateModified = 1, sizeBytes = 1, contentHash = "h",
                state = ItemState.REDACTED, documentClass = DocumentClass.PHILSYS, documentConfidence = 0.9f,
                boxes = listOf(RedactionBox(FieldType.PCN, half, 0.9f)), cardCorners = CardCorners(corners),
            )
            val ocr = MlKitOcrEngine()
            val source = MediaStoreImageSource(resolver, app.packageName)
            val done = Redactor(source, MediaStoreCopyStore(resolver), Recheck(FieldRules.load(app.assets), ocr)).redact(item)
            ocr.close()
            copyId = done.redactedMediaStoreId
            Log.i(TAG, "retries ${done.retryCount}, passed ${done.recheckPassed}, state ${done.state}")
            assertNotNull(copyId)
            assertTrue("the undersized box was not caught", done.retryCount >= 1)
            assertTrue(
                "ended neither passing nor in NEEDS_CHECK",
                (done.recheckPassed && done.retryCount <= Redactor.MAX_RECHECK_RETRIES) || done.state == ItemState.NEEDS_CHECK,
            )
        } finally {
            resolver.delete(uri, null, null)
            copyId?.let { resolver.delete(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, it), null, null) }
        }
    }

    private fun mid(a: Pair<Float, Float>, b: Pair<Float, Float>) = (a.first + b.first) / 2 to (a.second + b.second) / 2

    private companion object {
        const val TAG = "RecheckTest"
    }
}
