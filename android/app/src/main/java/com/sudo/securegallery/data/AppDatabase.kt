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
    version = 2,
    exportSchema = true,
    // 1 -> 2: card_corners column for the detector (#16).
    autoMigrations = [AutoMigration(from = 1, to = 2)],
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
