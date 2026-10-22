package com.sudo.securegallery

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * Base Application class for Secure Gallery.
 *
 * Instantiates the manual dependency injection [AppContainer] on application startup.
 * Avoids reflection or annotation processors for fast cold start and full architectural transparency.
 */
class SecureGalleryApplication : Application() {

    /** Global manual dependency injection container. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(applicationContext)
        // §8.3: the vault locks as soon as the app leaves the screen.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = container.vaultSession.lock()
        })
    }
}
