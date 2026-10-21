package com.sudo.securegallery

import android.app.Application

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
    }
}
