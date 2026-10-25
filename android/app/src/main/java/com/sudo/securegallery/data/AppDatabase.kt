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
    version = 3,
    exportSchema = true,
    // 1 -> 2: card_corners column for the detector (#16).
    // 2 -> 3: redacted_media_store_id links an original to its redacted copy (#18).
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
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
