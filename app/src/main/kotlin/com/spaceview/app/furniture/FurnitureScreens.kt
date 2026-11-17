package com.spaceview.app.furniture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.spaceview.app.R
import com.spaceview.app.common.InstrumentCard
import com.spaceview.app.gallery.GalleryFormatter
import com.spaceview.app.gallery.GalleryShare
import com.spaceview.app.gallery.title
import com.spaceview.app.pro.ProCatalog
import com.spaceview.app.pro.ProFeature
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.data.gallery.Dimension
import com.spaceview.data.gallery.DimensionSource
import com.spaceview.data.gallery.GalleryItem
import com.spaceview.data.gallery.ObjectKind
import com.spaceview.engine.LengthUnit
import com.spaceview.engine.MeasureContext
import com.spaceview.engine.Measurement
import com.spaceview.engine.Uncertainty
import com.spaceview.engine.UnitPreference
import com.spaceview.engine.Units
import com.spaceview.engine.furniture.FurniturePalettes
import com.spaceview.engine.furniture.FurnitureTemplate
import com.spaceview.engine.furniture.ParamError
import com.spaceview.engine.furniture.ParamKind
import com.spaceview.engine.furniture.ParamSpec
import com.spaceview.engine.furniture.Templates

/** How furniture values read: "92.5 cm ± 1.2" when measured, "~45 cm" when typed or typical. */
internal object FurnitureFormat {
    fun dimension(d: Dimension, units: UnitPreference): String {
        val f = Units.formatForContext(Measurement(d.meters, if (d.isEstimate) 0.0 else d.bound95 / Uncertainty.K95), MeasureContext.OBJECT, units)
        if (!d.isEstimate) return f.primary.text
        val p = f.primary
        return GalleryFormatter.ESTIMATE_MARK + if (p.unit == LengthUnit.FEET_INCHES) p.value else "${p.value} ${p.unitSymbol}"
    }

    /** A bare length (a limit), without ±. */
    fun length(meters: Double, units: UnitPreference): String {
        val p = Units.formatForContext(Measurement(meters, 0.0), MeasureContext.OBJECT, units).primary
        return if (p.unit == LengthUnit.FEET_INCHES) p.value else "${p.value} ${p.unitSymbol}"
    }

    @Composable
    fun error(e: ParamError, label: String, units: UnitPreference, isLength: Boolean = true): String {
        fun limit(v: Double) = if (isLength) length(v, units) else v.toInt().toString()
        return when (e.reason) {
            ParamError.Reason.TOO_SMALL -> stringResource(R.string.furniture_too_small, label, limit(e.limit ?: 0.0))
            ParamError.Reason.TOO_LARGE -> stringResource(R.string.furniture_too_large, label, limit(e.limit ?: 0.0))
            else -> stringResource(R.string.furniture_invalid, label)
        }
    }
}

@Composable
private fun TopRow(onBack: () -> Unit, stepText: String?, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_back)) }
        Spacer(Modifier.weight(1f))
        if (stepText != null) Text(stepText, style = SpaceViewTheme.typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun stepText(state: FurnitureUiState): String = stringResource(R.string.furniture_step, state.stepNumber, state.stepTotal)

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = SpaceViewTheme.colors.signal, contentColor = SpaceViewTheme.colors.onSignal),
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

// ---- picker ------------------------------------------------------------------------------------------------

/**
 * The template picker: a grid of every template drawn from its own geometry, with a Pro badge on the templates
 * outside the 3 core ones (hidden once Pro is active). Picking runs the gates before any AR.
 */
@Composable
internal fun FurniturePicker(checking: String?, proActive: Boolean, onPick: (String) -> Unit, onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_back)) }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.furniture_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(R.string.furniture_picker_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(Templates.all, key = { it.id }) { t ->
                    TemplateCard(t, locked = !proActive && !ProCatalog.isCoreTemplate(t.id), checking = checking == t.id, enabled = checking == null) { onPick(t.id) }
                }
            }
        }
    }
}

@Composable
private fun TemplateCard(t: FurnitureTemplate, locked: Boolean, checking: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val name = stringResource(FurnitureLabels.name(t))
    val parts = remember(t) { t.build(emptyMap()) }
    InstrumentCard(Modifier.testTag("furniture_template_${t.id}"), onClick = if (enabled) onClick else null) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FurnitureSketch(parts, FurniturePalettes.of(0), stringResource(R.string.furniture_drawing_of, name), Modifier.fillMaxWidth().height(96.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Badge(if (locked) stringResource(R.string.furniture_badge_pro) else stringResource(R.string.furniture_badge_free), strong = locked)
            }
            Text(
                if (checking) stringResource(R.string.furniture_checking) else stringResource(FurnitureLabels.detail(t)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (checking) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
            )
        }
    }
}

@Composable
private fun Badge(text: String, strong: Boolean) {
    val bg = if (strong) SpaceViewTheme.colors.signal else Color.Transparent
    val fg = if (strong) SpaceViewTheme.colors.onSignal else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .background(bg, MaterialTheme.shapes.small)
            .border(1.dp, if (strong) bg else SpaceViewTheme.colors.hairline, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) { Text(text, style = SpaceViewTheme.typography.caption, color = fg) }
}

// ---- options -----------------------------------------------------------------------------------------------

/** Presets (typical starting sizes) and the template's options (seats, doors, side…), then Measure or Type. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FurnitureOptions(
    state: FurnitureUiState,
    onBack: () -> Unit,
    onPreset: (String) -> Unit,
    onOption: (String, Int) -> Unit,
    onMeasure: () -> Unit,
    onType: () -> Unit,
) {
    val t = state.template ?: return
    val inputs = state.inputs ?: return
    val params = state.params
    val name = stringResource(FurnitureLabels.name(t))
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TopRow(onBack, stepText(state))
            Text(name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            val parts = remember(t, params) { runCatching { t.build(params) }.getOrNull() }
            if (parts != null) {
                FurnitureSketch(parts, FurniturePalettes.of(inputs.paletteIndex), stringResource(R.string.furniture_drawing_of, name), Modifier.fillMaxWidth().height(140.dp))
            }
            if (t.presets.isNotEmpty()) {
                Text(stringResource(R.string.furniture_start_from), style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    t.presets.forEach { p ->
                        FilterChip(selected = inputs.presetId == p.id, onClick = { onPreset(p.id) }, label = { Text(stringResource(FurnitureLabels.preset(p.id))) })
                    }
                }
                Text(stringResource(R.string.furniture_preset_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OptionRows(t, params, onOption)
            Spacer(Modifier.height(8.dp))
            PrimaryButton(stringResource(R.string.furniture_measure_start), onMeasure, modifier = Modifier.testTag("furniture_measure"))
            OutlinedButton(onClick = onType, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
                Text(stringResource(R.string.furniture_type_instead))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionRows(t: FurnitureTemplate, params: Map<String, Double>, onOption: (String, Int) -> Unit) {
    for (spec in t.options) {
        if (spec.id !in params) continue
        val options = spec.options(params)
        if (options.size < 2) continue // nothing to choose
        val current = params[spec.id]?.toInt()
        Text(stringResource(FurnitureLabels.param(t, spec.id)), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { v ->
                val label = FurnitureLabels.option(spec.id, v)?.let { stringResource(it) } ?: v.toString()
                FilterChip(selected = current == v, onClick = { onOption(spec.id, v) }, label = { Text(label) })
            }
        }
    }
}

// ---- preview -----------------------------------------------------------------------------------------------

/** The generated model in 3D (turn and zoom), with the overall size beside it. */
@Composable
internal fun FurniturePreviewScreen(state: FurnitureUiState, glb: ByteArray?, units: UnitPreference, onBack: () -> Unit, onNext: () -> Unit) {
    val t = state.template ?: return
    val inputs = state.inputs ?: return
    val name = stringResource(FurnitureLabels.name(t))
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TopRow(onBack, stepText(state))
            Text(stringResource(R.string.furniture_preview_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    state.errors.isNotEmpty() -> Text(stringResource(R.string.furniture_preview_invalid), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    glb != null -> FurniturePreview3D(glb, stringResource(R.string.furniture_preview_desc, name), Modifier.fillMaxSize())
                    else -> Unit // building (a few ms)
                }
            }
            if (state.errors.isEmpty()) {
                Text(stringResource(R.string.furniture_preview_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OverallSummary(t, inputs, state.params, units)
            PrimaryButton(stringResource(R.string.furniture_next), onNext, modifier = Modifier.testTag("furniture_preview_next"))
        }
    }
}

@Composable
private fun OverallSummary(t: FurnitureTemplate, inputs: FurnitureInputs, params: Map<String, Double>, units: UnitPreference) {
    InstrumentCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (id in FurnitureFlow.REQUIRED) {
                val d = FurnitureFlow.dimension(inputs, params, id) ?: continue
                Row {
                    Text(stringResource(FurnitureLabels.param(t, id)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
                    Text(FurnitureFormat.dimension(d, units), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

// ---- review ------------------------------------------------------------------------------------------------

/**
 * Review: name, colours, the overall box (W × D × H, each with its ± or marked as an estimate), every other size and
 * option, and Save. Nothing is saved until W, D and H are measured or typed and every value fits the shape.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FurnitureReviewScreen(
    state: FurnitureUiState,
    units: UnitPreference,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onOption: (String, Int) -> Unit,
    onPalette: (Int) -> Unit,
    onLabel: (String) -> Unit,
    onPreview: () -> Unit,
    onSave: (String) -> Unit,
    onUpgrade: () -> Unit,
) {
    val t = state.template ?: return
    val inputs = state.inputs ?: return
    val params = state.params
    val name = stringResource(FurnitureLabels.name(t))
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TopRow(onBack, stepText(state), enabled = !state.saving)
            Text(stringResource(R.string.furniture_review_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            val parts = remember(t, params) { runCatching { t.build(params) }.getOrNull() }
            if (parts != null) {
                FurnitureSketch(parts, FurniturePalettes.of(inputs.paletteIndex), stringResource(R.string.furniture_drawing_of, name), Modifier.fillMaxWidth().height(140.dp))
                TextButton(onClick = onPreview, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.furniture_preview_open)) }
            }
            OutlinedTextField(
                value = state.label ?: name,
                onValueChange = onLabel,
                label = { Text(stringResource(R.string.furniture_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.furniture_colours), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0 until FurniturePalettes.count) {
                    val swatch = Color(0xFF000000.toInt() or FurniturePalettes.of(i).colorOf(com.spaceview.engine.PartMaterial.BODY))
                    val label = stringResource(FurnitureLabels.palette(i))
                    FilterChip(
                        selected = inputs.paletteIndex == i,
                        onClick = { onPalette(i) },
                        label = { Text(label) },
                        leadingIcon = { Box(Modifier.size(16.dp).background(swatch, CircleShape)) },
                        modifier = Modifier.semantics { selected = inputs.paletteIndex == i },
                    )
                }
            }
            InstrumentCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.furniture_overall), style = MaterialTheme.typography.titleMedium)
                    for (id in FurnitureFlow.REQUIRED) ParamRow(t, t.spec(id)!!, inputs, params, units, onEdit)
                    Text(stringResource(R.string.furniture_overall_box_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            InstrumentCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.furniture_details), style = MaterialTheme.typography.titleMedium)
                    OptionRows(t, params, onOption)
                    for (spec in t.params.drop(3)) {
                        if (spec.kind == ParamKind.LENGTH && spec.id in params) ParamRow(t, spec, inputs, params, units, onEdit)
                    }
                }
            }
            val missing = FurnitureFlow.missingRequired(inputs)
            if (missing.isNotEmpty()) {
                Text(
                    stringResource(R.string.furniture_still_needed, missing.map { stringResource(FurnitureLabels.param(t, it)) }.joinToString(", ")),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.saveFailed) Text(stringResource(R.string.furniture_save_failed), color = MaterialTheme.colorScheme.error)
            state.needsPro?.let { feature ->
                InstrumentCard(Modifier.testTag("furniture_needs_pro")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(if (feature == ProFeature.FURNITURE_TEMPLATES) R.string.furniture_needs_pro_template else R.string.furniture_needs_pro_gallery),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        PrimaryButton(stringResource(R.string.furniture_see_pro), onUpgrade)
                    }
                }
            }
            PrimaryButton(
                stringResource(R.string.furniture_save),
                onClick = { onSave(name) },
                enabled = state.canSave && !state.saving,
                modifier = Modifier.testTag("furniture_save"),
            )
        }
    }
}

/** One length: label, value ("± …" when measured; "~ · typical" / "~ · typed by you" otherwise), its error, Edit. */
@Composable
private fun ParamRow(t: FurnitureTemplate, spec: ParamSpec, inputs: FurnitureInputs, params: Map<String, Double>, units: UnitPreference, onEdit: (String) -> Unit) {
    val label = stringResource(FurnitureLabels.param(t, spec.id))
    val d = FurnitureFlow.dimension(inputs, params, spec.id) ?: return
    val error = FurnitureFlow.errorFor(t, params, spec.id)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(FurnitureFormat.dimension(d, units), style = MaterialTheme.typography.titleLarge)
            when (d.source) {
                DimensionSource.TYPED -> Text(stringResource(R.string.furniture_typed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DimensionSource.PRESET -> Text(stringResource(R.string.furniture_typical), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DimensionSource.MEASURED -> Unit
            }
            if (error != null) Text(FurnitureFormat.error(error, label, units), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        val editLabel = stringResource(R.string.furniture_edit_title, label)
        TextButton(onClick = { onEdit(spec.id) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = editLabel }) {
            Text(stringResource(R.string.furniture_type))
        }
    }
}

/**
 * Types a length (an estimate, no ±). [onClear] drops the user's value: a key extra goes back to its typical value,
 * W / D / H become "still needed".
 */
@Composable
internal fun LengthDialog(
    t: FurnitureTemplate,
    spec: ParamSpec,
    current: Dimension?,
    hasValue: Boolean,
    units: UnitPreference,
    errorFor: (Double) -> ParamError?,
    onDismiss: () -> Unit,
    onSet: (Double) -> Unit,
    onClear: () -> Unit,
) {
    var unit by remember { mutableStateOf(GalleryFormatter.editUnit(ObjectKind.FURNITURE, units)) }
    var text by remember { mutableStateOf(current?.let { GalleryFormatter.editText(it.meters, unit) }.orEmpty()) }
    val parsed = GalleryFormatter.parseLength(text, unit)
    val label = stringResource(FurnitureLabels.param(t, spec.id))
    val rangeError = parsed?.let(errorFor)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.furniture_edit_title, label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.furniture_edit_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(16) },
                    singleLine = true,
                    suffix = { Text(unit.symbol) },
                    isError = text.isNotEmpty() && (parsed == null || rangeError != null),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    modifier = Modifier.testTag("furniture_length_field"),
                )
                when {
                    text.isNotEmpty() && parsed == null ->
                        Text(stringResource(R.string.gallery_edit_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    rangeError != null ->
                        Text(FurnitureFormat.error(rangeError, label, units), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (u in GalleryFormatter.EditUnit.entries) {
                        FilterChip(
                            selected = u == unit,
                            onClick = {
                                // Convert what is typed so switching units never changes the length.
                                val m = GalleryFormatter.parseLength(text, unit)
                                unit = u
                                if (m != null) text = GalleryFormatter.editText(m, u)
                            },
                            label = { Text(u.symbol) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onSet) }, enabled = parsed != null && rangeError == null) { Text(stringResource(R.string.gallery_save)) }
        },
        dismissButton = {
            Row {
                if (hasValue) TextButton(onClick = onClear) { Text(stringResource(R.string.furniture_clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.gallery_cancel)) }
            }
        },
    )
}

// ---- saved -------------------------------------------------------------------------------------------------

/** Saved: Place in my room (primary), Share (sizes as text: a template has no photo) and Done. */
@Composable
internal fun FurnitureSavedScreen(item: GalleryItem?, units: UnitPreference, onPlace: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.furniture_saved_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            if (item != null) {
                val parts = remember(item.id) {
                    item.templateId?.let { id -> item.templateParams?.let { Templates.partsOrNull(id, it) } }
                }
                val title = item.title()
                if (parts != null) {
                    FurnitureSketch(
                        parts, FurniturePalettes.fromParams(item.templateParams.orEmpty()),
                        stringResource(R.string.furniture_drawing_of, title), Modifier.fillMaxWidth().height(160.dp),
                    )
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(GalleryFormatter.shareDims(item, units), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.heightIn(min = 8.dp))
            PrimaryButton(stringResource(R.string.gallery_place), onPlace, modifier = Modifier.testTag("furniture_place"))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (item != null) {
                    val shareText = stringResource(R.string.gallery_share_text, item.title(), GalleryFormatter.shareDims(item, units))
                    val chooserTitle = stringResource(R.string.gallery_share)
                    OutlinedButton(
                        onClick = { GalleryShare.share(context, null, shareText, chooserTitle) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) { Text(stringResource(R.string.gallery_share)) }
                }
                OutlinedButton(onClick = onDone, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
                    Text(stringResource(R.string.furniture_done))
                }
            }
        }
    }
}
