package com.sudo.securegallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sudo.securegallery.ui.inbox.InboxScreen
import com.sudo.securegallery.ui.theme.SecureGalleryTheme

/**
 * Main activity for Secure Gallery.
 *
 * Hosts the root Jetpack Compose hierarchy and launches directly into the Review Inbox.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SecureGalleryTheme {
                InboxScreen()
            }
        }
    }
}
