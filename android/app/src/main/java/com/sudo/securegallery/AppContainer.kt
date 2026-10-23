package com.sudo.securegallery

import android.content.Context
import androidx.work.WorkManager
import com.sudo.securegallery.data.AppDatabase
import com.sudo.securegallery.detect.DetectorConfig
import com.sudo.securegallery.detect.LiteRtDetector
import com.sudo.securegallery.localize.CardTemplate
import com.sudo.securegallery.localize.FieldLocalizer
import com.sudo.securegallery.localize.FieldRules
import com.sudo.securegallery.localize.HybridLocalizer
import com.sudo.securegallery.localize.MlKitOcrEngine
import com.sudo.securegallery.scan.GalleryScanner
import com.sudo.securegallery.scan.ImageSource
import com.sudo.securegallery.scan.MediaStoreImageSource
import com.sudo.securegallery.setup.SetupProgressStore
import com.sudo.securegallery.vault.KeystoreBiometricKey
import com.sudo.securegallery.vault.KeystoreDeviceSecret
import com.sudo.securegallery.vault.Vault
import com.sudo.securegallery.vault.VaultPrefs
import com.sudo.securegallery.vault.VaultSession
import com.sudo.securegallery.vault.VaultStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /** The bundled document detector, or null if the build has no model (#16). */
    val detector: LiteRtDetector?

    /** Hybrid field localization with the bundled templates and ML Kit (#17). */
    val fieldLocalizer: FieldLocalizer

    val workManager: WorkManager

    /** Encrypted originals in app-private storage (§8.3). */
    val vault: Vault

    /** The current vault unlock; locks on background and after 2 idle minutes. */
    val vaultSession: VaultSession

    /** How far first-run setup got (#28). */
    val setupProgress: SetupProgressStore

    /** Vault settings that are not secrets (#26). */
    val vaultPrefs: VaultPrefs
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

    override val detector: LiteRtDetector? by lazy { LiteRtDetector.open(context.assets) }

    override val fieldLocalizer: FieldLocalizer by lazy {
        val config = detector?.config ?: DetectorConfig()
        HybridLocalizer(CardTemplate.loadAll(context.assets), FieldRules.load(context.assets), MlKitOcrEngine(), config.highConfidence)
    }

    override val galleryScanner: GalleryScanner by lazy {
        val detector = detector
        GalleryScanner(imageSource, database.itemDao(), detector, detector?.config ?: DetectorConfig(), fieldLocalizer)
    }

    override val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    override val vault: Vault by lazy {
        Vault(VaultStore(File(context.filesDir, "vault")), KeystoreDeviceSecret(), KeystoreBiometricKey())
    }

    override val vaultSession: VaultSession by lazy {
        VaultSession(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }

    override val setupProgress: SetupProgressStore by lazy { SetupProgressStore(context) }

    override val vaultPrefs: VaultPrefs by lazy { VaultPrefs(context) }
}
