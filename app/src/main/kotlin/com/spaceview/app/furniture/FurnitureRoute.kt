package com.spaceview.app.furniture

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spaceview.app.R
import com.spaceview.app.ads.AdsManager
import com.spaceview.app.ads.NonArScreenAdEffect
import com.spaceview.app.measure.ArGate
import com.spaceview.app.pro.ProFeature

/**
 * The furniture flow (T): picker → options → guided measure (AR) → 3D preview → review → saved, in one destination.
 * Only the guided measure has the camera on.
 *
 * Ads: as the scan flow (ui-ux §5.2, K2). Nothing is shown here; the view model reports each stored item once (it
 * counts towards the cap); [onDone] and [onClose] may let the next non-AR screen show a capped interstitial, but only
 * after a save in this visit; [onPlace] must never be followed by one. Share stays on Saved and arms nothing.
 *
 * @param proActive Pro is on: the picker shows no Pro badges.
 * @param onOpenPaywall contract K5 (the picker, before any AR; or Review when a gate refuses the save).
 */
@Composable
fun FurnitureRoute(
    viewModel: FurnitureViewModel,
    proActive: Boolean,
    onClose: () -> Unit,
    onDone: () -> Unit,
    onPlace: (Long) -> Unit,
    onOpenPaywall: (ProFeature) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val paywall by rememberUpdatedState(onOpenPaywall)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { e ->
            when (e) {
                is FurnitureEvent.OpenPaywall -> paywall(e.feature)
            }
        }
    }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    // Back to the picker drops the template's values: confirm when the user measured or named something.
    val requestPicker: () -> Unit = { if (state.hasWork) confirmLeave = true else viewModel.backToPicker() }

    when (state.step) {
        FurnitureStep.PICK -> {
            BackHandler(onBack = onClose)
            FurniturePicker(state.checking, proActive, onPick = viewModel::pick, onClose = onClose)
        }
        FurnitureStep.OPTIONS -> {
            BackHandler(onBack = requestPicker)
            FurnitureOptions(
                state,
                onBack = requestPicker,
                onPreset = viewModel::choosePreset,
                onOption = viewModel::setOption,
                onMeasure = viewModel::startMeasuring,
                onType = viewModel::typeInstead,
            )
        }
        FurnitureStep.MEASURE -> {
            val t = state.template ?: return
            val spec = state.currentSpec ?: return
            val inputs = state.inputs ?: return
            val firstWithoutOptions = state.measureIndex == 0 && !FurnitureFlow.hasOptions(t)
            val back: () -> Unit = { if (firstWithoutOptions) requestPicker() else viewModel.previousMeasure() }
            BackHandler(onBack = back)
            ArGate(onClose = viewModel::leaveMeasure) {
                FurnitureMeasureScreen(
                    step = GuidedStep(
                        template = t,
                        spec = spec,
                        stepText = stepText(state),
                        current = inputs.lengths[spec.id],
                        optional = spec.id !in FurnitureFlow.REQUIRED,
                        errorFor = { v -> FurnitureFlow.errorIfSet(t, inputs, spec.id, v) },
                    ),
                    settings = settings,
                    onBack = back,
                    onUse = { m, tier -> viewModel.useMeasured(spec.id, m, tier) },
                    onType = { editing = spec.id },
                    onUseTypical = {
                        viewModel.resetLength(spec.id)
                        viewModel.nextMeasure()
                    },
                    onNext = viewModel::nextMeasure,
                )
            }
        }
        FurnitureStep.PREVIEW -> {
            val glb by viewModel.previewGlb.collectAsStateWithLifecycle()
            BackHandler(onBack = viewModel::back)
            FurniturePreviewScreen(state, glb, settings.units, onBack = viewModel::back, onNext = viewModel::toReview)
        }
        FurnitureStep.REVIEW -> {
            BackHandler(enabled = !state.saving, onBack = viewModel::back)
            FurnitureReviewScreen(
                state,
                settings.units,
                onBack = viewModel::back,
                onEdit = { editing = it },
                onOption = viewModel::setOption,
                onPalette = viewModel::setPalette,
                onLabel = viewModel::setLabel,
                onPreview = viewModel::toPreview,
                onSave = viewModel::save,
                onUpgrade = viewModel::upgrade,
            )
        }
        FurnitureStep.SAVED -> {
            // Saved is a non-AR screen: preload here so an ad after Done is never late. The chance itself is only
            // armed when the user leaves (Done / Back), never for Place or Share.
            NonArScreenAdEffect(AdsManager.get(LocalContext.current))
            BackHandler(onBack = onClose)
            FurnitureSavedScreen(
                item = state.savedItem,
                units = settings.units,
                onPlace = { state.savedId?.let(onPlace) },
                onDone = onDone,
            )
        }
    }

    val editId = editing
    val t = state.template
    val inputs = state.inputs
    if (editId != null && t != null && inputs != null && state.step != FurnitureStep.SAVED) {
        val spec = t.spec(editId)
        if (spec == null) {
            editing = null
        } else {
            LengthDialog(
                t = t,
                spec = spec,
                current = FurnitureFlow.dimension(inputs, state.params, editId),
                hasValue = editId in inputs.lengths,
                units = settings.units,
                errorFor = { v -> FurnitureFlow.errorIfSet(t, inputs, editId, v) },
                onDismiss = { editing = null },
                onSet = { v ->
                    editing = null
                    viewModel.typeLength(editId, v)
                },
                onClear = {
                    editing = null
                    viewModel.resetLength(editId)
                },
            )
        }
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.furniture_discard_title)) },
            text = { Text(stringResource(R.string.furniture_discard_body)) },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; viewModel.backToPicker() }) { Text(stringResource(R.string.furniture_discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.gallery_cancel)) } },
        )
    }
}
