package com.spaceview.app.scan

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.spaceview.app.R
import com.spaceview.app.common.AccuracyBadge
import com.spaceview.app.common.Checkerboard
import com.spaceview.app.common.InstrumentCard
import com.spaceview.app.common.hintRes
import com.spaceview.app.common.spokenLength
import com.spaceview.app.common.spokenText
import com.spaceview.app.common.spokenUnit
import com.spaceview.app.gallery.GalleryFormatter
import com.spaceview.app.pro.GalleryFullCard
import com.spaceview.app.gallery.labelRes
import com.spaceview.app.measure.messageRes
import com.spaceview.app.scan.imaging.CutoutStatus
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.data.gallery.Dimension
import com.spaceview.data.gallery.DimensionSource
import com.spaceview.data.gallery.ObjectKind
import com.spaceview.engine.MeasureContext
import com.spaceview.engine.Measurement
import com.spaceview.engine.Uncertainty
import com.spaceview.engine.UnitPreference
import com.spaceview.engine.Units

/**
 * S6 Review: W, H, D (each "± …" when measured, "estimate" when typed or preset), the diagonal for
 * frames and TVs, an edit per value (an edited value becomes an estimate), the name, the badge, one tip
 * when Fair or Rough, and **Save to Gallery**. What is shown here is exactly what is saved.
 */
@Composable
internal fun ScanReviewScreen(
    state: ScanUiState,
    units: UnitPreference,
    vm: ScanViewModel,
    onRemeasure: () -> Unit,
    onUpgrade: () -> Unit = {},
    onDiscard: () -> Unit = {},
) {
    val kind = state.kind ?: return
    val outline = state.outline
    val values = state.values
    val suggested = stringResource(kind.labelRes())
    var editing by rememberSaveable { mutableStateOf<ScanDim?>(null) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRemeasure, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.scan_measure_again))
                }
                Spacer(Modifier.weight(1f))
                Text(stepText(ScanStep.REVIEW), style = SpaceViewTheme.typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.scan_review_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })

            PhotoBlock(state, suggested, onUndoCutout = vm::setCutoutUndone)

            if (outline == null || values == null) return@Column
            InstrumentCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DimRow(R.string.gallery_width, values.width, kind, units, edited = ScanDim.WIDTH in state.edits) { editing = ScanDim.WIDTH }
                    DimRow(R.string.gallery_height, values.height, kind, units, edited = ScanDim.HEIGHT in state.edits) { editing = ScanDim.HEIGHT }
                    DimRow(R.string.gallery_depth, values.depth, kind, units, edited = ScanDim.DEPTH in state.edits) { editing = ScanDim.DEPTH }
                    if (outline.depth == null && ScanDim.DEPTH !in state.edits) {
                        Text(stringResource(R.string.scan_depth_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    values.diagonal?.let { diag ->
                        val ctx = if (kind == ObjectKind.TV) MeasureContext.TV else ScanDraftBuilder.contextFor(kind)
                        // A TV is outlined at its outer edge; it is sold by the (smaller) screen diagonal (m10).
                        val label = if (kind == ObjectKind.TV) R.string.scan_diagonal_outer else R.string.gallery_diagonal
                        DimRow(label, diag, kind, units, edited = false, context = ctx, onEdit = null)
                    }
                    if (!outline.geometryConsistent) {
                        Text(stringResource(R.string.calib_reject_skewed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    AccuracyBadge(values.quality)
                    if (state.edits.isNotEmpty()) {
                        Text(stringResource(R.string.gallery_badge_measured_only), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(stringResource(values.quality.hintRes()), style = MaterialTheme.typography.bodySmall)
                    ScanDraftBuilder.reviewTip(values.quality, state.tip)?.let { tip ->
                        Text(
                            "${stringResource(R.string.result_tip_title)}: ${stringResource(tip.messageRes())}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = state.label ?: suggested,
                onValueChange = vm::setLabel,
                label = { Text(stringResource(R.string.scan_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            // SM1b: a curated name for what the photo shows; applied only when the user taps it (never automatically).
            // The container is always there and polite, so TalkBack announces the chip when it appears up to 3 s after
            // the photo (SM1 review MINOR 6).
            Box(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                state.nameSuggestion?.let { s ->
                    val name = stringResource(s.nameRes)
                    if (!state.saving && state.label != name) {
                        SuggestionChip(
                            onClick = { vm.setLabel(name) },
                            label = { Text(stringResource(R.string.smart_name_chip, name)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
            if (state.saveFailed) Text(stringResource(R.string.scan_save_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.announced())
            // Free gallery cap (Builder B): the scan stays here; Upgrade, Share or Discard.
            if (state.galleryFull) {
                GalleryFullCard(
                    title = state.label ?: suggested,
                    dims = listOf(values.width, values.height, values.depth).map { d ->
                        Units.formatForContext(Measurement(d.meters, if (d.isEstimate) 0.0 else d.bound95 / Uncertainty.K95), ScanDraftBuilder.contextFor(kind), units).primary.text
                    },
                    onUpgrade = onUpgrade,
                    onDiscard = onDiscard,
                )
            }
            if (!values.isSavable) Text(stringResource(R.string.scan_invalid_size), color = MaterialTheme.colorScheme.error, modifier = Modifier.announced())
            Button(
                onClick = { vm.save(suggested) },
                enabled = values.isSavable && !state.saving && !state.photoBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = SpaceViewTheme.colors.signal, contentColor = SpaceViewTheme.colors.onSignal),
            ) { Text(stringResource(R.string.scan_save), style = MaterialTheme.typography.titleMedium) }
        }
    }

    val dim = editing
    if (dim != null && values != null && outline != null) {
        EditDialog(
            dim = dim,
            current = values[dim],
            kind = kind,
            units = units,
            canReset = dim in state.edits && (dim != ScanDim.DEPTH || outline.depth != null),
            onDismiss = { editing = null },
            onSet = { meters -> vm.edit(dim, meters); editing = null },
        )
    }
}

/**
 * The picture that will be saved and placed (PLAN v3 W2): on a checkerboard, so a removed background shows as
 * transparent, at the measured W:H (the model is exactly that size). Below it, what happened to the background and,
 * when a cutout exists, "Undo cutout" / "Remove background again".
 */
@Composable
private fun PhotoBlock(state: ScanUiState, title: String, onUndoCutout: (Boolean) -> Unit) {
    val photo = state.photo
    when {
        photo != null -> {
            val aspect = state.values?.let { v -> (v.width.meters / v.height.meters).toFloat() }
                ?.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.2f, 5f)
                ?: (photo.width.toFloat() / photo.height)
            Box(Modifier.fillMaxWidth().heightIn(max = 280.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f)) {
                    Checkerboard(Modifier.matchParentSize())
                    Image(
                        bitmap = photo,
                        contentDescription = stringResource(R.string.gallery_photo_of, state.label ?: title),
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
            CutoutNote(state, onUndoCutout)
        }
        state.photoBusy -> Text(stringResource(R.string.scan_photo_preparing), style = MaterialTheme.typography.bodyMedium)
        state.photoFailed -> Text(stringResource(R.string.scan_photo_failed), style = MaterialTheme.typography.bodyMedium)
        else -> Text(stringResource(R.string.gallery_no_photo), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What happened to the background, read out when it changes; the Undo / Redo cutout button. */
@Composable
private fun CutoutNote(state: ScanUiState, onUndoCutout: (Boolean) -> Unit) {
    val note = when {
        state.removingBackground -> R.string.scan_cutout_removing
        state.cutoutStatus == CutoutStatus.REMOVED -> if (state.cutoutUndone) R.string.scan_cutout_undone else R.string.scan_cutout_removed
        state.cutoutStatus == CutoutStatus.FLAT -> R.string.scan_cutout_flat
        state.cutoutStatus == CutoutStatus.NOT_AVAILABLE -> R.string.scan_cutout_not_available
        state.cutoutStatus == CutoutStatus.NOT_SURE -> R.string.scan_cutout_not_sure
        else -> null
    } ?: return
    val warn = state.cutoutStatus == CutoutStatus.NOT_AVAILABLE || state.cutoutStatus == CutoutStatus.NOT_SURE
    Text(
        stringResource(note),
        style = MaterialTheme.typography.bodySmall,
        color = if (warn) SpaceViewTheme.colors.accuracyFair else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    if (state.hasCutout && !state.removingBackground) {
        TextButton(
            onClick = { onUndoCutout(!state.cutoutUndone) },
            enabled = !state.saving,
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(stringResource(if (state.cutoutUndone) R.string.scan_cutout_redo else R.string.scan_cutout_undo)) }
    }
}

/** One size: "61.0 cm ± 0.5 (24 in)" when measured, "~3 cm · estimate" when typed or preset. */
@Composable
private fun DimRow(
    @StringRes label: Int,
    d: Dimension,
    kind: ObjectKind,
    units: UnitPreference,
    edited: Boolean,
    context: MeasureContext = ScanDraftBuilder.contextFor(kind),
    onEdit: (() -> Unit)?,
) {
    val f = Units.formatForContext(Measurement(d.meters, if (d.isEstimate) 0.0 else d.bound95 / Uncertainty.K95), context, units)
    val name = stringResource(label)
    val p = f.primary
    val estimateText = if (p.unit == com.spaceview.engine.LengthUnit.FEET_INCHES) p.value else "${p.value} ${p.unitSymbol}"
    val note = stringResource(if (edited || d.source == DimensionSource.TYPED) R.string.gallery_edited_by_you else R.string.scan_estimate)
    // M5 a11y: one node per size with units and "±" in words ("Width, 61.0 centimetres plus or minus 0.5 centimetres").
    val spoken = if (d.isEstimate) {
        "$name, ${spokenText("${GalleryFormatter.ESTIMATE_MARK}$estimateText")}, $note"
    } else {
        listOfNotNull(name, spokenLength(p), f.secondary?.let { spokenText(it) }).joinToString(", ")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(80.dp).clearAndSetSemantics { })
        Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken }) {
            if (d.isEstimate) {
                Text("${GalleryFormatter.ESTIMATE_MARK}$estimateText", style = MaterialTheme.typography.titleLarge)
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(f.primary.text, style = MaterialTheme.typography.titleLarge)
                f.secondary?.let { Text("($it)", style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (onEdit != null) {
            val editLabel = stringResource(R.string.scan_edit_title, stringResource(label))
            TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = editLabel }) {
                Text(stringResource(R.string.scan_edit))
            }
        }
    }
}

@Composable
private fun EditDialog(
    dim: ScanDim,
    current: Dimension,
    kind: ObjectKind,
    units: UnitPreference,
    canReset: Boolean,
    onDismiss: () -> Unit,
    onSet: (Double?) -> Unit,
) {
    var unit by remember { mutableStateOf(GalleryFormatter.editUnit(kind, units)) }
    var text by remember { mutableStateOf(GalleryFormatter.editText(current.meters, unit)) }
    val parsed = ScanDraftBuilder.parseEdit(text, unit)
    val name = stringResource(
        when (dim) {
            ScanDim.WIDTH -> R.string.gallery_width
            ScanDim.HEIGHT -> R.string.gallery_height
            ScanDim.DEPTH -> R.string.gallery_depth
        },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scan_edit_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.gallery_edit_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(16) },
                    // M5 a11y: the field says what it edits (and the unit, in words).
                    label = { Text(stringResource(R.string.smart_a11y_edit_field, name, spokenUnit(unit.symbol))) },
                    singleLine = true,
                    suffix = { Text(unit.symbol) },
                    isError = parsed == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                )
                if (parsed == null) {
                    Text(
                        stringResource(R.string.gallery_edit_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.announced(),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (u in GalleryFormatter.EditUnit.entries) {
                        val unitName = spokenUnit(u.symbol)
                        FilterChip(
                            modifier = Modifier.semantics { contentDescription = unitName },
                            selected = u == unit,
                            onClick = {
                                // Convert what is typed so switching units never changes the length.
                                val m = ScanDraftBuilder.parseEdit(text, unit)
                                unit = u
                                if (m != null) text = GalleryFormatter.editText(m, u)
                            },
                            // The chip is read as "centimetres" / "inches", never "c m" / "in".
                            label = { Text(u.symbol, modifier = Modifier.clearAndSetSemantics { }) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let { onSet(it) } }, enabled = parsed != null) { Text(stringResource(R.string.gallery_save)) }
        },
        dismissButton = {
            Row {
                if (canReset) TextButton(onClick = { onSet(null) }) { Text(stringResource(R.string.scan_use_measured)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.gallery_cancel)) }
            }
        },
    )
}

/** M5 a11y: an error or status line that TalkBack reads when it appears. */
private fun Modifier.announced(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }
