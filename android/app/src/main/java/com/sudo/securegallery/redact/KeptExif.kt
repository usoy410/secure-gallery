package com.sudo.securegallery.redact

import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The only metadata a redacted copy carries (§8.2): the original's capture date, so other
 * galleries file the copy on the right day, and a normal orientation, because the rotation is
 * already applied to the pixels. GPS, device, maker note and the embedded thumbnail are never
 * copied: the copy's EXIF block is built from these fields alone.
 *
 * @property dateTimeOriginal `YYYY:MM:DD HH:MM:SS`, or null if the original has none.
 * @property offsetTimeOriginal the time zone of that date, such as `+08:00`, or null.
 */
data class KeptExif(val dateTimeOriginal: String?, val offsetTimeOriginal: String?) {
    init {
        require(dateTimeOriginal == null || DATE.matches(dateTimeOriginal)) { "not an EXIF date" }
        require(offsetTimeOriginal == null || OFFSET.matches(offsetTimeOriginal)) { "not an EXIF offset" }
    }

    /** An APP1 segment holding only these fields and orientation 1 (normal). */
    fun app1Segment(): ByteArray {
        val strings = listOfNotNull(
            dateTimeOriginal?.let { TAG_DATE_TIME_ORIGINAL to it },
            offsetTimeOriginal?.let { TAG_OFFSET_TIME_ORIGINAL to it },
        )
        val tiff = ByteArrayOutputStream()
        fun short(v: Int) = tiff.write(byteArrayOf((v shr 8).toByte(), v.toByte()))
        fun int(v: Int) { short(v ushr 16); short(v and 0xFFFF) }

        // Big-endian TIFF header, IFD0 at offset 8.
        tiff.write("MM".toByteArray()); short(0x2A); int(8)
        val exifIfd = 8 + 2 + 2 * 12 + 4
        short(2)
        short(TAG_ORIENTATION); short(TYPE_SHORT); int(1); short(ExifInterface.ORIENTATION_NORMAL); short(0)
        short(TAG_EXIF_IFD); short(TYPE_LONG); int(1); int(exifIfd)
        int(0) // no IFD1, so no thumbnail
        var data = exifIfd + 2 + strings.size * 12 + 4
        short(strings.size)
        for ((tag, value) in strings) {
            short(tag); short(TYPE_ASCII); int(value.length + 1); int(data)
            data += value.length + 1
        }
        int(0)
        for ((_, value) in strings) { tiff.write(value.toByteArray(Charsets.US_ASCII)); tiff.write(0) }

        val body = EXIF_HEADER + tiff.toByteArray()
        val length = body.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + body
    }

    companion object {
        private val DATE = Regex("[0-9]{4}:[0-9]{2}:[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}")
        private val OFFSET = Regex("[+-][0-9]{2}:[0-9]{2}")
        private val EXIF_HEADER = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0, 0) // "Exif\0\0"
        private const val TAG_ORIENTATION = 0x0112
        private const val TAG_EXIF_IFD = 0x8769
        private const val TAG_DATE_TIME_ORIGINAL = 0x9003
        private const val TAG_OFFSET_TIME_ORIGINAL = 0x9011
        private const val TYPE_ASCII = 2
        private const val TYPE_SHORT = 3
        private const val TYPE_LONG = 4

        /** The fields to keep from an original's EXIF; malformed values are dropped, not copied. */
        fun read(original: InputStream): KeptExif {
            val exif = ExifInterface(original)
            return KeptExif(
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.takeIf(DATE::matches),
                exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)?.takeIf(OFFSET::matches),
            )
        }
    }
}
