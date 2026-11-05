package com.spaceview.app.scan

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spaceview.app.R
import com.spaceview.app.ads.AdsManager
import com.spaceview.app.ads.NonArScreenAdEffect
import com.spaceview.app.common.InstrumentCard
import com.spaceview.app.gallery.GalleryFormatter
import com.spaceview.app.gallery.GalleryImage
import com.spaceview.app.gallery.GalleryShare
import com.spaceview.app.gallery.title
import com.spaceview.app.measure.ArGate
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.data.gallery.GalleryItem
import com.spaceview.data.gallery.ObjectKind
import com.spaceview.engine.UnitPreference
import kotlinx.coroutines.launch

/**
 * Scan flow (S4 → S6 → S7): type → outline in AR → front photo in AR → review → saved. One navigation
 * destination; the AR steps share one ARCore session and are the only parts with the camera on.
 *
 * Ads (ui-ux §5.2): nothing is ever shown here. The view model reports each stored scan exactly once
 * (it counts towards the cap); [onDone] (Done → Gallery) and [onClose] (Back, or any exit) may let the next non-AR screen
 * show a capped interstitial, but only after a save in this visit; [onPlace] must never be followed by one.
 * Share stays on the Saved screen and never arms anything.
 */
@Composable
fun ScanRoute(
    viewModel: ScanViewModel,
    onClose: () -> Unit,
    onDone: () -> Unit,
    onPlace: (Long) -> Unit,
) {
    val shareCopy: suspend (Long, String) -> java.io.File? = viewModel::shareCopy
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // Review → Measure again / Back: confirm before throwing away edits, a name or a photo (m4).
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val requestRemeasure: () -> Unit = {
        when {
            state.saving -> Unit // never while the save runs: it would jump to Saved from the camera
            state.hasReviewWork -> confirmDiscard = true
            else -> viewModel.remeasure()
        }
    }

    when (state.step) {
        ScanStep.TYPE -> {
            BackHandler(onBack = onClose)
            TypePicker(onPick = viewModel::chooseKind, onClose = onClose)
        }
        // One call site for both AR steps, so their MeasureSession (and its anchors) survives the step change.
        ScanStep.OUTLINE, ScanStep.PHOTO -> {
            val kind = state.kind ?: return
            BackHandler {
                if (state.step == ScanStep.PHOTO) viewModel.backToOutline() else viewModel.backToType()
            }
            ArGate(onClose = viewModel::backToType) {
                ScanArScreen(
                    kind = kind,
                    step = state.step,
                    outline = state.outline,
                    settings = settings,
                    onBack = { if (state.step == ScanStep.PHOTO) viewModel.backToOutline() else viewModel.backToType() },
                    onOutline = viewModel::onOutline,
                    onPhoto = viewModel::onFrontPhoto,
                    onSkipPhoto = viewModel::skipPhoto,
                    onRemeasure = viewModel::remeasure,
                )
            }
        }
        ScanStep.REVIEW -> {
            BackHandler(onBack = requestRemeasure)
            ScanReviewScreen(state, settings.units, viewModel, onRemeasure = requestRemeasure)
            if (confirmDiscard) {
                AlertDialog(
                    onDismissRequest = { confirmDiscard = false },
                    title = { Text(stringResource(R.string.scan_discard_title)) },
                    text = { Text(stringResource(R.string.scan_discard_body)) },
                    confirmButton = {
                        TextButton(onClick = { confirmDiscard = false; viewModel.remeasure() }) { Text(stringResource(R.string.scan_measure_again)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.gallery_cancel)) }
                    },
                )
            }
        }
        ScanStep.SAVED -> {
            // Saved is a non-AR screen: preload here so an ad after Done is never late. The chance itself
            // is only armed when the user leaves (Done / Back), never for Place or Share.
            NonArScreenAdEffect(AdsManager.get(LocalContext.current))
            // System Back leaves like Done (an ad slot), back to where the scan started.
            BackHandler(onBack = onClose)
            SavedScreen(
                item = state.savedItem,
                units = settings.units,
                shareCopy = shareCopy,
                onPlace = { state.savedId?.let(onPlace) },
                onDone = onDone,
            )
        }
    }
}

/** "Step 2 of 4". */
@Composable
internal fun stepText(step: ScanStep): String = stringResource(R.string.scan_step, step.number, ScanStep.COUNT)

// ---- step 1: type --------------------------------------------------------------------------------

@Composable
private fun TypePicker(onPick: (ObjectKind) -> Unit, onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_back)) }
                Spacer(Modifier.weight(1f))
                Text(stepText(ScanStep.TYPE), style = SpaceViewTheme.typography.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                stringResource(R.string.scan_type_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            TypeCard(R.string.scan_type_flat, R.string.scan_type_flat_detail) {
                ChoiceButton(R.string.scan_type_frame) { onPick(ObjectKind.FRAME) }
                ChoiceButton(R.string.scan_type_tv) { onPick(ObjectKind.TV) }
            }
            TypeCard(R.string.scan_type_box, R.string.scan_type_box_detail) {
                ChoiceButton(R.string.scan_type_appliance) { onPick(ObjectKind.APPLIANCE) }
                ChoiceButton(R.string.scan_type_carton) { onPick(ObjectKind.BOX) }
            }
            TypeCard(R.string.scan_type_other, R.string.scan_type_other_detail) {
                ChoiceButton(R.string.scan_type_choose) { onPick(ObjectKind.OTHER) }
            }
        }
    }
}

@Composable
private fun TypeCard(@StringRes title: Int, @StringRes detail: Int, choices: @Composable () -> Unit) {
    InstrumentCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            choices()
        }
    }
}

@Composable
private fun ChoiceButton(@StringRes label: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
        Text(stringResource(label))
    }
}

// ---- saved ---------------------------------------------------------------------------------------

/**
 * S7: confirmation with **Place in my room** (primary), **Share** and **Done**. Share uses the gallery
 * item's own share (photo + sizes); it is hidden until the stored item is loaded.
 */
@Composable
private fun SavedScreen(
    item: GalleryItem?,
    units: UnitPreference,
    shareCopy: suspend (Long, String) -> java.io.File?,
    onPlace: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.scan_saved_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            if (item != null) {
                val title = item.title()
                GalleryImage(
                    file = item.photoFile,
                    version = item.updatedAt,
                    contentDescription = stringResource(R.string.gallery_photo_of, title),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 280.dp),
                )
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(GalleryFormatter.shareDims(item, units), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.heightIn(min = 8.dp))
            Button(
                onClick = onPlace,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = SpaceViewTheme.colors.signal, contentColor = SpaceViewTheme.colors.onSignal),
            ) { Text(stringResource(R.string.gallery_place), style = MaterialTheme.typography.titleMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (item != null) {
                    val title = item.title()
                    val shareText = stringResource(R.string.gallery_share_text, title, GalleryFormatter.shareDims(item, units)) +
                        (GalleryFormatter.diagonalShort(item, units)?.let { " · " + stringResource(R.string.gallery_diagonal_short, it) } ?: "")
                    val chooserTitle = stringResource(R.string.gallery_share)
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val copy = if (item.photoFile != null) shareCopy(item.id, GalleryShare.fileName(title)) else null
                                GalleryShare.share(context, copy, shareText, chooserTitle)
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.medium,
                    ) { Text(stringResource(R.string.gallery_share)) }
                }
                OutlinedButton(
                    onClick = onDone,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.medium,
                ) { Text(stringResource(R.string.scan_done)) }
            }
            Spacer(Modifier.width(1.dp))
        }
    }
}
