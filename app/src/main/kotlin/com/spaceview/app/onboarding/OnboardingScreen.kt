package com.spaceview.app.onboarding

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spaceview.app.R
import com.spaceview.app.settings.SettingsRepository
import com.spaceview.app.ui.theme.SpaceViewTheme
import kotlinx.coroutines.launch

private enum class OnboardingPage(@StringRes val title: Int, @StringRes val body: Int) {
    WHAT(R.string.onboarding_what_title, R.string.onboarding_what_body),
    HOW(R.string.onboarding_how_title, R.string.onboarding_how_body),
    ACCURACY(R.string.onboarding_accuracy_title, R.string.onboarding_accuracy_body),
}

/**
 * First-run onboarding: 3 short, skippable screens (what SpaceView does / how to measure
 * accurately / honest accuracy, "typically ±2%") ending in a "Calibrate now / Later" choice.
 *
 * Completion is saved to DataStore ([SettingsRepository.onboardingCompleted]) **before** either
 * callback runs, so the caller can navigate straight away. Back goes to the previous page; on the
 * first page it is left to the caller (the system default, i.e. leave the app).
 *
 * @param onDone "Later" (or Skip then Later): continue to the home screen.
 * @param onCalibrateNow "Calibrate now": open calibration. Defaults to [onDone] if the caller has
 *   no calibration route yet.
 */
@Composable
fun OnboardingScreen(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onCalibrateNow: () -> Unit = onDone,
    repository: SettingsRepository = SettingsRepository.get(LocalContext.current),
) {
    val pages = OnboardingPage.entries
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    var finishing by remember { mutableStateOf(false) }
    val isLast = pagerState.currentPage == pages.lastIndex

    fun finish(then: () -> Unit) {
        if (finishing) return
        finishing = true
        scope.launch {
            repository.setOnboardingCompleted()
            then()
        }
    }

    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (!isLast) {
                    TextButton(onClick = { scope.launch { pagerState.animateScrollToPage(pages.lastIndex) } }) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { index -> PageContent(pages[index]) }

            PageIndicator(current = pagerState.currentPage, count = pages.size)
            Spacer(Modifier.height(16.dp))
            val signalButton = ButtonDefaults.buttonColors(
                containerColor = SpaceViewTheme.colors.signal,
                contentColor = SpaceViewTheme.colors.onSignal,
            )
            if (isLast) {
                Button(
                    onClick = { finish(onCalibrateNow) },
                    enabled = !finishing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = signalButton,
                ) { Text(stringResource(R.string.onboarding_calibrate_now), style = MaterialTheme.typography.titleMedium) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { finish(onDone) },
                    enabled = !finishing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = MaterialTheme.shapes.medium,
                ) { Text(stringResource(R.string.onboarding_calibrate_later), style = MaterialTheme.typography.titleMedium) }
            } else {
                Button(
                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = signalButton,
                ) { Text(stringResource(R.string.onboarding_next), style = MaterialTheme.typography.titleMedium) }
                // Keeps the button block the same height on every page, so nothing jumps.
                Spacer(Modifier.height(64.dp))
            }
        }
    }
}

@Composable
private fun PageContent(page: OnboardingPage) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Illustration(
            page,
            Modifier
                .widthIn(max = 280.dp)
                .fillMaxWidth()
                .aspectRatio(1.4f),
        )
        Text(
            stringResource(page.title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(page.body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp),
        )
        if (page == OnboardingPage.ACCURACY) {
            Text(
                stringResource(R.string.onboarding_calibrate_prompt),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 480.dp),
            )
        }
    }
}

@Composable
private fun PageIndicator(current: Int, count: Int) {
    val description = stringResource(R.string.onboarding_step, current + 1, count)
    Row(
        Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .size(if (i == current) 10.dp else 8.dp)
                    .background(
                        if (i == current) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    ),
            )
        }
    }
}

/** Static drafting-style line drawings (no animation, so reduced-motion users see the same). */
@Composable
private fun Illustration(page: OnboardingPage, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val faint = SpaceViewTheme.colors.hairline
    val signal = SpaceViewTheme.colors.signal
    Canvas(modifier.clearAndSetSemantics { }) {
        when (page) {
            OnboardingPage.WHAT -> drawCrosshair(ink, signal, faint)
            OnboardingPage.HOW -> drawSweep(ink, signal, faint)
            OnboardingPage.ACCURACY -> drawTolerance(ink, signal, faint)
        }
    }
}

private fun DrawScope.drawCrosshair(ink: Color, signal: Color, faint: Color) {
    val s = 1.5.dp.toPx()
    val c = center
    val r = size.minDimension * 0.22f
    // Corner brackets around the target.
    val b = size.minDimension * 0.36f
    val arm = b * 0.35f
    for ((dx, dy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
        val corner = Offset(c.x + dx * b, c.y + dy * b)
        drawLine(faint, corner, Offset(corner.x - dx * arm, corner.y), s * 1.5f)
        drawLine(faint, corner, Offset(corner.x, corner.y - dy * arm), s * 1.5f)
    }
    drawCircle(signal, radius = r, center = c, style = Stroke(s * 2.5f))
    drawCircle(ink, radius = r + s * 1.5f, center = c, style = Stroke(s))
    drawLine(ink, Offset(c.x - r * 0.4f, c.y), Offset(c.x + r * 0.4f, c.y), s)
    drawLine(ink, Offset(c.x, c.y - r * 0.4f), Offset(c.x, c.y + r * 0.4f), s)
}

private fun DrawScope.drawSweep(ink: Color, signal: Color, faint: Color) {
    val s = 1.5.dp.toPx()
    // Feature dots on a surface.
    val rows = 4
    val cols = 9
    for (i in 0 until rows) for (j in 0 until cols) {
        val x = size.width * (0.1f + 0.8f * j / (cols - 1))
        val y = size.height * (0.62f + 0.08f * i)
        drawCircle(if ((i + j) % 3 == 0) signal else faint, radius = s * 1.6f, center = Offset(x, y))
    }
    // Phone outline.
    val w = size.width * 0.18f
    val h = w * 1.8f
    val top = Offset(center.x - w / 2, size.height * 0.08f)
    drawRoundRect(ink, topLeft = top, size = Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 4), style = Stroke(s))
    // Side-to-side arrows.
    val y = top.y + h / 2
    val gap = w * 0.9f
    val len = size.width * 0.16f
    for (dir in listOf(-1f, 1f)) {
        val start = Offset(center.x + dir * gap, y)
        val end = Offset(start.x + dir * len, y)
        drawLine(ink, start, end, s, StrokeCap.Round)
        drawLine(ink, end, Offset(end.x - dir * s * 5, y - s * 4), s, StrokeCap.Round)
        drawLine(ink, end, Offset(end.x - dir * s * 5, y + s * 4), s, StrokeCap.Round)
    }
}

private fun DrawScope.drawTolerance(ink: Color, signal: Color, faint: Color) {
    val s = 1.5.dp.toPx()
    val y = size.height * 0.5f
    val left = size.width * 0.1f
    val right = size.width * 0.9f
    val band = size.height * 0.08f
    // Tolerance sleeve at each end, then the dimension line with extension lines.
    drawRect(signal.copy(alpha = 0.35f), topLeft = Offset(right - band, y - band), size = Size(band * 2, band * 2))
    drawRect(signal.copy(alpha = 0.35f), topLeft = Offset(left - band, y - band), size = Size(band * 2, band * 2))
    drawLine(ink, Offset(left, y), Offset(right, y), s)
    drawLine(ink, Offset(left, y - band * 2), Offset(left, y + band * 2), s)
    drawLine(ink, Offset(right, y - band * 2), Offset(right, y + band * 2), s)
    // Ruler ticks underneath.
    val ticks = 40
    for (i in 0..ticks) {
        val x = left + (right - left) * i / ticks
        val t = if (i % 10 == 0) band * 1.4f else if (i % 5 == 0) band else band * 0.5f
        drawLine(faint, Offset(x, y + band * 3), Offset(x, y + band * 3 + t), s * 0.8f)
    }
    drawLine(faint, Offset(left, y - band * 3), Offset(right, y - band * 3), s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(s * 3, s * 3)))
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PagePreview() {
    SpaceViewTheme { Surface { PageContent(OnboardingPage.ACCURACY) } }
}
