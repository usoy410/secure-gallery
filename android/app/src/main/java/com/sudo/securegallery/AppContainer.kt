package com.sudo.securegallery

import android.content.Context
import androidx.work.WorkManager
import com.sudo.securegallery.data.AppDatabase
import com.sudo.securegallery.scan.GalleryScanner
import com.sudo.securegallery.scan.ImageSource
import com.sudo.securegallery.scan.MediaStoreImageSource
import com.sudo.securegallery.vault.KeystoreDeviceSecret
import com.sudo.securegallery.vault.Vault
import com.sudo.securegallery.vault.VaultStore
import java.io.File

/**
 * AppContainer provides manual dependency injection across the application without Hilt.
 *
 * It serves as the single source of truth for application-scoped dependencies,
 * adhering to the offline architecture requirement specified in §8.8 of SECURE_GALLERY.md.
 */
interface AppContainer {
    /** Application context available across the app lifecycle. */
    val context: Context

    /** Local item index (§8.4). */
    val database: AppDatabase

    /** Public gallery images the scan reads. */
    val imageSource: ImageSource

    val galleryScanner: GalleryScanner

    val workManager: WorkManager

    /** Encrypted originals in app-private storage (§8.3). */
    val vault: Vault
}

/**
 * Default production implementation of [AppContainer].
 *
 * Singletons are created on first use, so building the container stays cheap at startup.
 *
 * @property context The application-level [Context].
 */
class DefaultAppContainer(
    override val context: Context
) : AppContainer {
    override val database: AppDatabase by lazy { AppDatabase.create(context) }

    override val imageSource: ImageSource by lazy {
        MediaStoreImageSource(context.contentResolver, context.packageName)
    }

    override val galleryScanner: GalleryScanner by lazy {
        GalleryScanner(imageSource, database.itemDao())
    }

    override val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    override val vault: Vault by lazy {
        Vault(VaultStore(File(context.filesDir, "vault")), KeystoreDeviceSecret())
    }
}
