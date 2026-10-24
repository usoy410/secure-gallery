package com.sudo.securegallery.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/** Local item index (§8.4). Lives in app-private storage and holds no OCR text. */
@Database(
    entities = [Item::class],
    version = 5,
    exportSchema = true,
    // 1 -> 2: card_corners column for the detector (#16).
    // 2 -> 3: redacted_media_store_id links an original to its redacted copy (#18).
    // 3 -> 4: recheck_passed marks copies that passed the on-device recheck (#19).
    // 4 -> 5: corrections counts user corrections for the correction-rate metric (#25).
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao

    companion object {
        const val NAME = "secure_gallery.db"

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME).build()
    }
}
