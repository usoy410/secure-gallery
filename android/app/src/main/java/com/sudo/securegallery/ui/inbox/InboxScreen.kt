package com.sudo.securegallery.ui.inbox

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sudo.securegallery.R
import com.sudo.securegallery.data.DocumentClass
import com.sudo.securegallery.data.Item
import com.sudo.securegallery.data.ItemState

/** The inbox tab: the list, or the box editor while an item is open (#24). */
@Composable
fun InboxRoute(
    viewModel: InboxViewModel,
    modifier: Modifier = Modifier,
    unlocked: Boolean = false,
    unlockPrompt: @Composable (onDismiss: () -> Unit) -> Unit = {},
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val viewer by viewModel.viewer.collectAsStateWithLifecycle()
    val unlockRequested by viewModel.unlockRequested.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val state = editor
    val viewing = viewer
    if (state != null) {
        BackHandler(onBack = viewModel::close)
        BoxEditorScreen(
            state = state,
            actions = EditorActions(
                select = viewModel::select,
                move = viewModel::move,
                moveCorner = viewModel::moveCorner,
                endGesture = viewModel::endGesture,
                add = viewModel::add,
                delete = viewModel::deleteSelected,
                save = viewModel::save,
                cancel = viewModel::close,
            ),
            modifier = modifier,
        )
    } else if (viewing != null) {
        BackHandler(onBack = viewModel::closeViewer)
        ViewerScreen(
            state = viewing,
            unlocked = unlocked,
            actions = ViewerActions(
                toggle = viewModel::toggleOriginal,
                edit = { viewModel.openEditor(viewing.item) },
                close = viewModel::closeViewer,
            ),
            modifier = modifier,
        )
        if (unlockRequested && !unlocked) unlockPrompt(viewModel::cancelUnlock)
    } else {
        InboxScreen(modifier, items, viewModel::open, message, viewModel::dismissMessage)
    }
}

/**
 * The review inbox (§8.4): every processed item with its state, NEEDS_CHECK first. A row shows
 * the document class, the state and the number of boxes, never a field value or the image.
 * With no items it shows a quiet empty state.
 */
@Composable
fun InboxScreen(
    modifier: Modifier = Modifier,
    items: List<Item> = emptyList(),
    onOpen: (Item) -> Unit = {},
    message: InboxMessage? = null,
    onDismissMessage: () -> Unit = {},
) {
    val snackbar = remember { SnackbarHostState() }
    val text = message?.let { stringResource(it.text) }
    LaunchedEffect(message) {
        if (text != null) {
            snackbar.showSnackbar(text)
            onDismissMessage()
        }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            InboxTopBar()
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        if (items.isEmpty()) {
            EmptyInboxContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(innerPadding)) {
                items(items, key = { it.id }) { item -> InboxRow(item, onClick = { onOpen(item) }) }
            }
        }
    }
}

@Composable
private fun InboxRow(item: Item, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick).heightIn(min = 72.dp),
        headlineContent = { Text(stringResource(item.documentClass.label)) },
        supportingContent = {
            Text(pluralStringResource(R.plurals.inbox_item_summary, item.boxes.size, item.boxes.size))
        },
        trailingContent = {
            val needsCheck = item.state == ItemState.NEEDS_CHECK
            AssistChip(
                onClick = onClick,
                label = { Text(stringResource(item.state.label)) },
                leadingIcon = if (needsCheck) {
                    { Icon(Icons.Outlined.ErrorOutline, contentDescription = null, Modifier.size(18.dp)) }
                } else {
                    null
                },
                colors = if (needsCheck) {
                    AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        labelColor = MaterialTheme.colorScheme.onErrorContainer,
                        leadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    AssistChipDefaults.assistChipColors()
                },
            )
        },
    )
    HorizontalDivider()
}

internal val DocumentClass?.label: Int
    get() = when (this) {
        DocumentClass.PHILSYS -> R.string.inbox_class_philsys
        DocumentClass.LTO -> R.string.inbox_class_lto
        DocumentClass.PAYMENT_CARD -> R.string.inbox_class_payment_card
        null -> R.string.inbox_class_unknown
    }

private val ItemState.label: Int
    get() = when (this) {
        ItemState.NEEDS_CHECK -> R.string.inbox_state_needs_check
        ItemState.VAULTED -> R.string.inbox_state_vaulted
        ItemState.ORIGINAL_STILL_PUBLIC -> R.string.inbox_state_still_public
        else -> R.string.inbox_state_redacted
    }

private val InboxMessage.text: Int
    get() = when (this) {
        InboxMessage.VAULT_LOCKED -> R.string.inbox_unlock_first
        InboxMessage.NOT_AVAILABLE -> R.string.inbox_not_available
        InboxMessage.SAVED -> R.string.inbox_saved
        InboxMessage.SAVED_NEEDS_CHECK -> R.string.inbox_saved_needs_check
        InboxMessage.SAVE_FAILED -> R.string.inbox_save_failed
    }

/**
 * Top app bar for the review inbox screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxTopBar() {
    TopAppBar(
        title = {
            Text(
                text = stringResource(id = R.string.inbox_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}

/**
 * Empty state content rendered when there are no items needing manual review.
 *
 * @param modifier Layout modifier applied to the container.
 */
@Composable
fun EmptyInboxContent(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.CheckCircleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(id = R.string.inbox_empty_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(id = R.string.inbox_empty_description),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
