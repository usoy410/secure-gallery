package com.sudo.securegallery

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sudo.securegallery.ui.inbox.InboxRoute
import com.sudo.securegallery.ui.inbox.InboxViewModel
import com.sudo.securegallery.ui.scan.ScanRoute
import com.sudo.securegallery.ui.setup.SetupRoute
import com.sudo.securegallery.ui.setup.SetupViewModel
import com.sudo.securegallery.ui.scan.ScanViewModel
import com.sudo.securegallery.ui.theme.SecureGalleryTheme
import com.sudo.securegallery.ui.vault.VaultRoute
import com.sudo.securegallery.ui.vault.VaultViewModel
import com.sudo.securegallery.ui.vault.hasStrongBiometric

/**
 * Main activity for Secure Gallery.
 *
 * Hosts the root Jetpack Compose hierarchy: the gallery scan, the review inbox and the vault.
 * A [FragmentActivity] because `BiometricPrompt` needs one.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SecureGalleryApplication).container

        setContent {
            SecureGalleryTheme {
                // First-run setup (#28) comes before the tabs until it is finished.
                var setupDone by remember { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(Unit) { setupDone = container.setupProgress.isDone() }
                when (setupDone) {
                    null -> return@SecureGalleryTheme
                    false -> {
                        SetupRoute(
                            viewModel(factory = SetupViewModel.factory(container, hasStrongBiometric(this@MainActivity))),
                            onFinished = { setupDone = true },
                        )
                        return@SecureGalleryTheme
                    }
                    true -> Unit
                }
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Column(Modifier.fillMaxSize()) {
                    when (tab) {
                        0 -> ScanRoute(viewModel(factory = ScanViewModel.factory(container)), Modifier.weight(1f))
                        1 -> {
                            val unlocked by container.vaultSession.unlocked.collectAsStateWithLifecycle()
                            InboxRoute(
                                viewModel(factory = InboxViewModel.factory(container)),
                                Modifier.weight(1f),
                                unlocked = unlocked,
                                // The eye toggle's unlock prompt is the vault's own lock screen (#23).
                                unlockPrompt = { onDismiss ->
                                    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
                                        VaultRoute(
                                            viewModel(factory = VaultViewModel.factory(container, hasStrongBiometric(this@MainActivity))),
                                            Modifier.fillMaxSize(),
                                        )
                                    }
                                },
                            )
                        }
                        else -> VaultRoute(
                            viewModel(factory = VaultViewModel.factory(container, hasStrongBiometric(this@MainActivity))),
                            Modifier.weight(1f),
                        )
                    }
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_scan)) },
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Icon(Icons.Outlined.Inbox, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_inbox)) },
                        )
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                            label = { Text(stringResource(R.string.nav_vault)) },
                        )
                    }
                }
            }
        }
    }
}
