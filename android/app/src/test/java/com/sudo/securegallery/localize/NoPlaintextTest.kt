package com.sudo.securegallery.localize

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudo.securegallery.data.AppDatabase
import com.sudo.securegallery.data.DocumentClass
import com.sudo.securegallery.data.ItemState
import com.sudo.securegallery.detect.Corner
import com.sudo.securegallery.detect.Detection
import com.sudo.securegallery.detect.DetectorImage
import com.sudo.securegallery.detect.DocumentDetector
import com.sudo.securegallery.scan.GalleryImage
import com.sudo.securegallery.scan.GalleryScanner
import com.sudo.securegallery.scan.ImageSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.io.InputStream

/**
 * OCR text stays in memory only (issue #17, §8.4): after a full scan with field localization,
 * neither the item, the database files on disk nor the log hold any of the recognized text.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NoPlaintextTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Synthetic values the fake OCR "reads"; each one matches a field rule. */
    private val secrets = listOf("1234-5678-9012-3456", "DELA CRUZ, JUAN", "1990/01/02", "12 MABINI ST")

    @Test
    fun recognizedTextIsNeverStoredOrLogged() = runTest {
        ShadowLog.clear()
        val name = "no-plaintext.db"
        context.deleteDatabase(name)
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).allowMainThreadQueries().build()
        val ocr = OcrEngine { bitmap ->
            secrets.mapIndexed { i, text ->
                val top = bitmap.height * (0.2f + 0.2f * i)
                OcrLine(i, 0, text.split(" ").mapIndexed { j, w -> OcrWord(w, 100f + 200f * j, top, 280f + 200f * j, top + 30f) })
            }
        }
        val assets = context.assets
        val localizer = HybridLocalizer(CardTemplate.loadAll(assets), FieldRules.load(assets), ocr)
        val corners = listOf(Corner(100f, 100f), Corner(1500f, 100f), Corner(1500f, 980f), Corner(100f, 980f))
        val detector = DocumentDetector { listOf(Detection(DocumentClass.PHILSYS, 0.9f, floatArrayOf(100f, 100f, 1500f, 980f), corners)) }

        GalleryScanner(OneCard, db.itemDao(), detector, localizer = localizer).scan()

        val item = db.itemDao().byMediaStoreId(1)!!
        assertTrue(item.state == ItemState.REDACTED || item.state == ItemState.NEEDS_CHECK)
        assertTrue("fields were found", item.boxes.size > 7)
        db.close()

        val stored = item.toString() + listOf("", "-wal", "-shm", "-journal")
            .map { File(context.getDatabasePath(name).path + it) }
            .filter(File::exists)
            .joinToString { String(it.readBytes(), Charsets.ISO_8859_1) }
        val logged = ShadowLog.getLogs().joinToString { "${it.tag} ${it.msg} ${it.throwable}" }
        for (secret in secrets) {
            // Whole values and their words; short pieces like "12" could match a box coordinate.
            for (piece in secret.split(" ", ", ").filter { it.length >= 4 } + secret) {
                assertFalse("the database holds OCR text", piece in stored)
                assertFalse("the log holds OCR text", piece in logged)
            }
        }
    }

    /** One synthetic photo, 1600 x 1200, that decodes. */
    private object OneCard : ImageSource {
        override suspend fun listImages() = listOf(GalleryImage(1, 1, 100))

        override fun open(mediaStoreId: Long): InputStream = "card".byteInputStream()

        override fun loadForDetection(mediaStoreId: Long, maxSide: Int) = DetectorImage(IntArray(640 * 480), 640, 480, 1600, 1200)

        override fun loadForOcr(mediaStoreId: Long, maxSide: Int) =
            OcrImage(Bitmap.createBitmap(1600, 1200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }, 1600, 1200)
    }
}
