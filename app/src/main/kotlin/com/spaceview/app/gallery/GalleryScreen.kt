package com.spaceview.app.gallery

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spaceview.app.R
import com.spaceview.app.ads.AdsManager
import com.spaceview.app.ads.BannerAd
import com.spaceview.app.common.AccuracyBadge
import com.spaceview.app.common.InstrumentCard
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.data.gallery.GalleryItem
import com.spaceview.data.gallery.ObjectKind

/** Name of an object kind ("Frame", "TV", …). */
@StringRes
fun ObjectKind.labelRes(): Int = when (this) {
    ObjectKind.FRAME -> R.string.gallery_kind_frame
    ObjectKind.TV -> R.string.gallery_kind_tv
    ObjectKind.APPLIANCE -> R.string.gallery_kind_appliance
    ObjectKind.BOX -> R.string.gallery_kind_box
    ObjectKind.OTHER -> R.string.gallery_kind_other
}

/** The item's label, or its kind when unnamed. */
@Composable
fun GalleryItem.title(): String = label ?: stringResource(kind.labelRes())

/**
 * AR Gallery grid (S8): thumbnail, name, W × H × D in context units, TV diagonal, badge. Banner in a fixed
 * anchored slot under the grid (never inside it: AdMob flags ads next to gallery images), separated from the
 * thumbnails by a divider and 24 dp.
 *
 * @param onScan optional "Scan an object" action for the empty state (null hides the button).
 */
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onOpen: (Long) -> Unit,
    onBack: () -> Unit,
    onScan: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_back)) }
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.gallery_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (state.failed) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.gallery_error_load),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            } else if (state.loaded && state.cards.isEmpty()) {
                EmptyGallery(onScan, Modifier.fillMaxSize())
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 156.dp),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.cards, key = { it.item.id }) { card -> GalleryCardView(card, onClick = { onOpen(card.item.id) }) }
                }
                // Fixed slot outside the grid: one AdView per screen, set apart from the thumbnails by a divider
                // and 24 dp so it never reads as one of the gallery images (AdMob policy).
                HorizontalDivider(color = SpaceViewTheme.colors.hairline)
                BannerAd(
                    adsManager = AdsManager.get(LocalContext.current),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                    anchored = true,
                )
            }
        }
    }
}

@Composable
private fun EmptyGallery(onScan: (() -> Unit)?, modifier: Modifier = Modifier) {
    Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.gallery_empty_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.gallery_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (onScan != null) {
                Button(
                    onClick = onScan,
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpaceViewTheme.colors.signal,
                        contentColor = SpaceViewTheme.colors.onSignal,
                    ),
                ) { Text(stringResource(R.string.gallery_scan_object)) }
            }
        }
    }
}

@Composable
private fun GalleryCardView(card: GalleryCard, onClick: () -> Unit) {
    val item = card.item
    val title = item.title()
    InstrumentCard(onClick = onClick) {
        GalleryImage(
            file = item.thumbnailFile,
            version = item.updatedAt,
            contentDescription = null,
            maxPx = 256,
            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
        )
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val spoken = GalleryFormatter.spokenDims(
                card.dimsText,
                about = stringResource(R.string.gallery_cd_about),
                by = stringResource(R.string.gallery_cd_by),
            )
            val dimsDescription = if (item.hasEstimates) stringResource(R.string.gallery_cd_estimated, spoken) else spoken
            Text(
                card.dimsText,
                style = SpaceViewTheme.typography.spec,
                maxLines = 2,
                modifier = Modifier.semantics { contentDescription = dimsDescription },
            )
            card.diagonalText?.let {
                Text(stringResource(R.string.gallery_diagonal_short, it), style = SpaceViewTheme.typography.spec)
            }
            if (item.allEstimated) {
                Text(
                    stringResource(if (item.hasTyped) R.string.gallery_edited_by_you else R.string.gallery_all_estimated),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AccuracyBadge(item.quality, tolerance = card.plusMinusText)
            }
        }
    }
}
