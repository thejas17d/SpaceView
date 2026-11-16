package com.spaceview.app.pro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.spaceview.app.R
import com.spaceview.app.common.InstrumentCard
import com.spaceview.app.gallery.GalleryShare

/**
 * Shown on a non-AR Review screen when Save hit the Free gallery cap (P1 review MAJOR 3). The result is
 * never held hostage: the scan stays on screen, its sizes can be shared without saving, and it is only
 * dropped if the user confirms Discard.
 *
 * @param dims the sizes as shown on the screen (e.g. "60.0 cm"), joined as "W × H × D" for sharing.
 */
@Composable
fun GalleryFullCard(title: String, dims: List<String>, onUpgrade: () -> Unit, onDiscard: () -> Unit) {
    val context = LocalContext.current
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val shareText = stringResource(R.string.gallery_share_text, title, dims.joinToString(" × "))
    val chooser = stringResource(R.string.gallery_share)
    InstrumentCard(Modifier.testTag("gallery_full")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.pro_gallery_full_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Text(
                stringResource(R.string.pro_gallery_full_body, ProCatalog.FREE_GALLERY_ITEMS),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onUpgrade, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.pro_gallery_full_upgrade))
            }
            OutlinedButton(
                onClick = { GalleryShare.share(context, null, shareText, chooser) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.pro_gallery_full_share)) }
            TextButton(onClick = { confirmDiscard = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.pro_gallery_full_discard))
            }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.pro_gallery_full_discard_title)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDiscard() }) { Text(stringResource(R.string.pro_gallery_full_discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.pro_keep)) } },
        )
    }
}
