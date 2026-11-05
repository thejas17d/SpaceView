package com.spaceview.app.gallery

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spaceview.app.R
import com.spaceview.app.common.AccuracyBadge
import com.spaceview.app.common.InstrumentCard
import com.spaceview.app.common.hintRes
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.data.gallery.GalleryItem
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Item detail (S9): front photo, spec block (W / H / D with ±, TV diagonal, "estimated" markers), badge,
 * Place in my room, Share, Edit dimensions, Rename, Delete. No banner here: the actions sit close together.
 *
 * @param onPlace "Place in my room" (M4).
 * @param onDeleted called after a successful delete (navigate back).
 */
@Composable
fun GalleryItemScreen(
    viewModel: GalleryItemViewModel,
    onPlace: (Long) -> Unit,
    onBack: () -> Unit,
    onDeleted: () -> Unit = onBack,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val problem by viewModel.problem.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val problemText = when (problem) {
        GalleryItemViewModel.Problem.SAVE_FAILED -> stringResource(R.string.gallery_error_save)
        GalleryItemViewModel.Problem.DELETE_FAILED -> stringResource(R.string.gallery_error_delete)
        GalleryItemViewModel.Problem.SHARE_FAILED -> stringResource(R.string.gallery_share_failed)
        null -> null
    }
    LaunchedEffect(problemText) {
        if (problemText != null) {
            snackbar.showSnackbar(problemText)
            viewModel.problemShown()
        }
    }

    var dialog by rememberSaveable { mutableStateOf(ItemDialog.NONE) }
    // A delete that finishes after the user already pressed Back must not pop a second screen.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val deleted = { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onDeleted() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val item = state.item
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_back)) }
                Spacer(Modifier.width(8.dp))
                Text(
                    item?.title() ?: stringResource(R.string.gallery_title),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
            }
            when {
                !state.loaded -> Unit
                item == null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.gallery_not_found), style = MaterialTheme.typography.bodyLarge)
                }
                else -> ItemContent(
                    state = state,
                    item = item,
                    onPlace = { onPlace(item.id) },
                    onEdit = { dialog = ItemDialog.EDIT },
                    onRename = { dialog = ItemDialog.RENAME },
                    onDelete = { dialog = ItemDialog.DELETE },
                    shareCopy = viewModel::shareCopy,
                    onShareFailed = viewModel::reportShareFailed,
                )
            }
        }
    }

    val item = state.item ?: return
    when (dialog) {
        ItemDialog.NONE -> Unit
        ItemDialog.RENAME -> RenameDialog(
            initial = item.label.orEmpty(),
            onDismiss = { dialog = ItemDialog.NONE },
            onConfirm = { viewModel.rename(it); dialog = ItemDialog.NONE },
        )
        ItemDialog.DELETE -> AlertDialog(
            onDismissRequest = { dialog = ItemDialog.NONE },
            title = { Text(stringResource(R.string.gallery_delete_title)) },
            text = { Text(stringResource(R.string.gallery_delete_body)) },
            confirmButton = {
                TextButton(onClick = { dialog = ItemDialog.NONE; viewModel.delete(deleted) }) {
                    Text(stringResource(R.string.gallery_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { dialog = ItemDialog.NONE }) { Text(stringResource(R.string.gallery_cancel)) } },
        )
        ItemDialog.EDIT -> EditDimensionsDialog(
            item = item,
            initialUnit = GalleryFormatter.editUnit(item.kind, state.units),
            onDismiss = { dialog = ItemDialog.NONE },
            onConfirm = { w, h, d -> viewModel.updateDimensions(w, h, d); dialog = ItemDialog.NONE },
        )
    }
}

private enum class ItemDialog { NONE, RENAME, DELETE, EDIT }

@Composable
private fun ItemContent(
    state: GalleryItemUiState,
    item: GalleryItem,
    onPlace: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    shareCopy: suspend (String) -> File?,
    onShareFailed: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val title = item.title()
    val shareText = stringResource(R.string.gallery_share_text, title, state.shareDims) +
        (GalleryFormatter.diagonalShort(item, state.units)?.let { " · " + stringResource(R.string.gallery_diagonal_short, it) } ?: "")
    val chooserTitle = stringResource(R.string.gallery_share)
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GalleryImage(
            file = item.photoFile,
            version = item.updatedAt,
            contentDescription = stringResource(R.string.gallery_photo_of, title),
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp),
        )
        SpecBlock(state, item)
        Button(
            onClick = onPlace,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(containerColor = SpaceViewTheme.colors.signal, contentColor = SpaceViewTheme.colors.onSignal),
        ) { Text(stringResource(R.string.gallery_place), style = MaterialTheme.typography.titleMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val copy = if (item.photoFile != null) shareCopy(GalleryShare.fileName(title)) else null
                        if (!GalleryShare.share(context, copy, shareText, chooserTitle)) onShareFailed()
                    }
                },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text(stringResource(R.string.gallery_share)) }
            OutlinedButton(
                onClick = onEdit,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.medium,
            ) { Text(stringResource(R.string.gallery_edit_dimensions), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        HorizontalDivider(color = SpaceViewTheme.colors.hairline)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRename, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.gallery_rename)) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.gallery_delete), color = MaterialTheme.colorScheme.error)
            }
        }
        val saved = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.createdAt))
        Text(
            stringResource(R.string.gallery_meta, saved, Formatter.formatShortFileSize(context, item.bytes)),
            style = SpaceViewTheme.typography.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.heightIn(min = 8.dp))
    }
}

@Composable
private fun SpecBlock(state: GalleryItemUiState, item: GalleryItem) {
    InstrumentCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in state.rows) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        stringResource(
                            when (row.which) {
                                DimensionRow.Which.WIDTH -> R.string.gallery_width
                                DimensionRow.Which.HEIGHT -> R.string.gallery_height
                                DimensionRow.Which.DEPTH -> R.string.gallery_depth
                                DimensionRow.Which.DIAGONAL -> R.string.gallery_diagonal
                            },
                        ),
                        style = SpaceViewTheme.typography.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(88.dp).padding(top = 3.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(row.formatted.primary.text, style = SpaceViewTheme.typography.spec, fontWeight = FontWeight.Medium)
                        row.formatted.secondary?.let {
                            Text(it, style = SpaceViewTheme.typography.spec, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (row.isEstimate) {
                            Text(
                                stringResource(if (row.isTyped) R.string.gallery_typed else R.string.gallery_estimated),
                                style = MaterialTheme.typography.labelMedium,
                                color = SpaceViewTheme.colors.accuracyFair,
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = SpaceViewTheme.colors.hairline)
            if (item.allEstimated) {
                Text(
                    stringResource(if (item.hasTyped) R.string.gallery_edited_by_you else R.string.gallery_all_estimated),
                    style = MaterialTheme.typography.labelLarge,
                )
            } else {
                AccuracyBadge(item.quality, tolerance = state.plusMinusText)
                Text(
                    stringResource(item.quality.hintRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.hasEstimates) {
                    Text(
                        stringResource(R.string.gallery_badge_measured_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (item.calibrated) {
                Text(stringResource(R.string.history_calibrated), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gallery_rename)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(com.spaceview.data.gallery.GalleryRepository.MAX_LABEL) },
                label = { Text(stringResource(R.string.gallery_rename_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.gallery_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.gallery_cancel)) } },
    )
}

/**
 * Edits W / H / D in cm or inches. Only fields the user actually changed are saved (and become estimates);
 * untouched fields keep their measured value and ±.
 */
@Composable
private fun EditDimensionsDialog(
    item: GalleryItem,
    initialUnit: GalleryFormatter.EditUnit,
    onDismiss: () -> Unit,
    onConfirm: (Double?, Double?, Double?) -> Unit,
) {
    var unit by rememberSaveable { mutableStateOf(initialUnit) }
    val originals = listOf(item.width.meters, item.height.meters, item.depth.meters)
    // Per field: typed text, or null while untouched (then it follows the unit switch). Saved across recreation.
    var typed by rememberSaveable(stateSaver = TypedFieldsSaver) { mutableStateOf(listOf<String?>(null, null, null)) }
    fun textOf(i: Int) = typed[i] ?: GalleryFormatter.editText(originals[i], unit)
    fun parsed(i: Int): Double? = typed[i]?.let { GalleryFormatter.parseLength(it, unit) }
    val allValid = (0 until 3).all { typed[it] == null || parsed(it) != null }
    val labels = listOf(R.string.gallery_width, R.string.gallery_height, R.string.gallery_depth)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gallery_edit_dimensions)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (u in GalleryFormatter.EditUnit.entries) {
                        FilterChip(
                            selected = unit == u,
                            onClick = {
                                if (unit != u) {
                                    // Keep typed values: convert them to the new unit.
                                    typed = GalleryFormatter.convertTyped(typed, unit, u)
                                    unit = u
                                }
                            },
                            label = { Text(u.symbol) },
                        )
                    }
                }
                for (i in 0 until 3) {
                    val invalid = typed[i] != null && parsed(i) == null
                    OutlinedTextField(
                        value = textOf(i),
                        onValueChange = { v -> typed = typed.toMutableList().also { it[i] = v } },
                        label = { Text(stringResource(labels[i]) + " (" + unit.symbol + ")") },
                        singleLine = true,
                        isError = invalid,
                        supportingText = if (invalid) {
                            { Text(stringResource(R.string.gallery_edit_invalid)) }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    )
                }
                Text(
                    stringResource(R.string.gallery_edit_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = allValid,
                onClick = {
                    // A field counts as edited only if its text differs from the pre-filled value.
                    fun edited(i: Int): Double? = GalleryFormatter.editedValue(typed[i], originals[i], unit)
                    onConfirm(edited(0), edited(1), edited(2))
                },
            ) { Text(stringResource(R.string.gallery_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.gallery_cancel)) } },
    )
}

/** Saves the edit dialog's per-field texts (null = untouched) in the saved-instance state. */
private val TypedFieldsSaver = listSaver<List<String?>, String>(
    save = { fields -> fields.map { it ?: UNTOUCHED } },
    restore = { saved -> saved.map { it.takeIf { s -> s != UNTOUCHED } } },
)

/** Marks an untouched field in [TypedFieldsSaver] (cannot be typed: it is a control character). */
private const val UNTOUCHED = "\u0000"
