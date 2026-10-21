package com.sudo.securegallery

import android.content.Context

/**
 * AppContainer provides manual dependency injection across the application without Hilt.
 *
 * It serves as the single source of truth for application-scoped dependencies,
 * adhering to the offline architecture requirement specified in §8.8 of SECURE_GALLERY.md.
 */
interface AppContainer {
    /** Application context available across the app lifecycle. */
    val context: Context
}

/**
 * Default production implementation of [AppContainer].
 *
 * Singletons and service providers (database, vault, detector, etc.) will be instantiated
 * and held here as modular pipeline features are implemented.
 *
 * @property context The application-level [Context].
 */
class DefaultAppContainer(
    override val context: Context
) : AppContainer
