package com.sudo.securegallery.ui.scan

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sudo.securegallery.R
import com.sudo.securegallery.scan.PhotoAccess

/**
 * Scan screen with its state: asks for photo access, starts the first scan once access is
 * granted, and shows how many images were found and processed.
 */
@Composable
fun ScanRoute(viewModel: ScanViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sdk = Build.VERSION.SDK_INT
    fun currentAccess() = PhotoAccess.from(sdk) {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var access by remember { mutableStateOf(currentAccess()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = currentAccess()
        if (access != PhotoAccess.NONE) viewModel.scanNow()
    }
    // Scan once per app start when access is already granted.
    LaunchedEffect(Unit) {
        if (access != PhotoAccess.NONE) viewModel.scanNow()
    }
    // The user can change access in Settings while the app is in the background.
    LifecycleResumeEffect(Unit) {
        val before = access
        access = currentAccess()
        if (access != before && access != PhotoAccess.NONE) viewModel.scanNow()
        onPauseOrDispose { }
    }
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    ScanScreen(
        access = access,
        progress = progress,
        onRequestAccess = { launcher.launch(PhotoAccess.permissionsFor(sdk)) },
        onScanNow = viewModel::scanNow,
        modifier = modifier,
    )
}

/** Stateless scan screen: no access yet, scanning, or done. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    access: PhotoAccess,
    progress: ScanProgress,
    onRequestAccess: () -> Unit,
    onScanNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp),
        ) {
            if (access == PhotoAccess.SELECTED) {
                SelectedPhotosNote(onSelectMore = onRequestAccess)
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    access == PhotoAccess.NONE -> NoAccess(onRequestAccess)
                    progress.running -> Scanning(progress)
                    else -> Done(progress, onScanNow)
                }
            }
        }
    }
}

@Composable
private fun Message(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
    action: @Composable () -> Unit = {},
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        icon()
        Spacer(Modifier.height(24.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        action()
    }
}

@Composable
private fun NoAccess(onRequestAccess: () -> Unit) = Message(
    icon = { BigIcon(Icons.Outlined.PhotoLibrary) },
    title = stringResource(R.string.scan_access_title),
    body = stringResource(R.string.scan_access_body),
    action = { Button(onClick = onRequestAccess) { Text(stringResource(R.string.scan_access_button)) } },
)

@Composable
private fun Scanning(progress: ScanProgress) = Message(
    icon = { BigIcon(Icons.Outlined.PhotoLibrary) },
    title = stringResource(R.string.scan_running_title),
    body = pluralStringResource(R.plurals.scan_running_body, progress.found, progress.processed, progress.found),
    action = {
        LinearProgressIndicator(
            progress = { if (progress.found == 0) 0f else progress.processed.toFloat() / progress.found },
            modifier = Modifier.fillMaxWidth(),
        )
    },
)

@Composable
private fun Done(progress: ScanProgress, onScanNow: () -> Unit) = Message(
    icon = { BigIcon(Icons.Outlined.CheckCircleOutline) },
    title = stringResource(R.string.scan_done_title),
    body = pluralStringResource(R.plurals.scan_done_body, progress.processed, progress.processed),
    action = { OutlinedButton(onClick = onScanNow) { Text(stringResource(R.string.scan_now_button)) } },
)

@Composable
private fun SelectedPhotosNote(onSelectMore: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.scan_selected_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSelectMore) { Text(stringResource(R.string.scan_selected_button)) }
        }
    }
}

@Composable
private fun BigIcon(image: androidx.compose.ui.graphics.vector.ImageVector) = Icon(
    imageVector = image,
    contentDescription = null,
    tint = MaterialTheme.colorScheme.primary,
    modifier = Modifier.size(64.dp),
)
