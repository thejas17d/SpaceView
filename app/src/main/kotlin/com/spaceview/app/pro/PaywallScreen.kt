package com.spaceview.app.pro

import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spaceview.app.R
import com.spaceview.app.billing.BillingPeriod
import com.spaceview.app.billing.FakeBillingGateway
import com.spaceview.app.billing.PeriodUnit
import com.spaceview.app.billing.PlanKind
import com.spaceview.app.billing.ProductOffer
import com.spaceview.app.common.InstrumentCard
import com.spaceview.app.ui.theme.SpaceViewTheme
import java.text.DateFormat
import java.util.Date

/**
 * The paywall, wired to its view-model. A non-AR screen: it never opens over the camera, never shows an
 * ad, and closing it (button or system back) always returns the user to where they were.
 */
@Composable
fun PaywallRoute(viewModel: PaywallViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    val uriHandler = LocalUriHandler.current
    val packageName = LocalContext.current.packageName
    PaywallScreen(
        state = state,
        onClose = onClose,
        onSelect = viewModel::select,
        onBuy = { activity?.let(viewModel::buy) },
        onRestore = viewModel::restore,
        onManage = { runCatching { uriHandler.openUri(ProLinks.manageSubscriptions(packageName)) } },
        onRetry = viewModel::load,
    )
}

/** Links into Google Play. */
object ProLinks {
    /** Play's subscription centre for SpaceView Pro (cancel / change plan). Works without a subscription too. */
    fun manageSubscriptions(packageName: String): String =
        "https://play.google.com/store/account/subscriptions?sku=${ProCatalog.SUBSCRIPTION_ID}&package=$packageName"
}

@Composable
fun PaywallScreen(
    state: PaywallUiState,
    onClose: () -> Unit,
    onSelect: (String) -> Unit,
    onBuy: () -> Unit,
    onRestore: () -> Unit,
    onManage: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
        // Outside the scrolling content: the dismiss stays visible at any font size (P1 review MINOR 7).
        Box(Modifier.padding(horizontal = 16.dp)) { CloseBar(onClose) }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.pro_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            val entitlement = state.entitlement
            if (entitlement is Entitlement.Pro) {
                ActiveCard(entitlement, onClose)
            } else {
                Text(reasonText(state.feature), style = MaterialTheme.typography.bodyLarge)
                if (entitlement is Entitlement.Pending) {
                    InfoCard(R.string.pro_pending_title, stringResource(R.string.pro_pending_body), Modifier.testTag("paywall_pending"))
                }
                Benefits()
                Offers(state, onSelect, onBuy, onRetry)
            }
            Message(state.message)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onRestore, enabled = !state.busy, modifier = Modifier.testTag("paywall_restore")) {
                    Text(stringResource(R.string.pro_restore))
                }
                TextButton(onClick = onManage, modifier = Modifier.testTag("paywall_manage")) {
                    Text(stringResource(R.string.pro_manage))
                }
            }
            Text(
                stringResource(R.string.pro_free_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.heightIn(min = 12.dp))
        }
        }
    }
}

@Composable
private fun CloseBar(onClose: () -> Unit) {
    val close = stringResource(R.string.pro_close)
    val ink = MaterialTheme.colorScheme.onBackground
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        // Always visible and first in focus order (Play policy: a clearly visible dismiss).
        IconButton(onClick = onClose, modifier = Modifier.testTag("paywall_close").semantics { contentDescription = close }) {
            Canvas(Modifier.size(16.dp)) {
                val s = 2.dp.toPx()
                drawLine(ink, Offset(0f, 0f), Offset(size.width, size.height), s, StrokeCap.Square)
                drawLine(ink, Offset(size.width, 0f), Offset(0f, size.height), s, StrokeCap.Square)
            }
        }
    }
}

@Composable
private fun reasonText(feature: ProFeature): String = when (feature) {
    ProFeature.NO_ADS -> stringResource(R.string.pro_reason_no_ads)
    ProFeature.GALLERY_UNLIMITED -> stringResource(R.string.pro_reason_gallery, ProCatalog.FREE_GALLERY_ITEMS)
    ProFeature.DEVICE_SCAN -> stringResource(R.string.pro_reason_device_scan, ProCatalog.FREE_DEVICE_SCANS)
    ProFeature.FURNITURE_TEMPLATES -> stringResource(R.string.pro_reason_templates)
    ProFeature.COMPARE -> stringResource(R.string.pro_reason_compare, ProCatalog.FREE_COMPARE_ITEMS)
    ProFeature.EXPORT_GLB -> stringResource(R.string.pro_reason_export_glb)
    ProFeature.EXPORT_PDF -> stringResource(R.string.pro_reason_export_pdf)
}

@StringRes
internal fun benefitRes(feature: ProFeature): Int = when (feature) {
    ProFeature.NO_ADS -> R.string.pro_benefit_no_ads
    ProFeature.GALLERY_UNLIMITED -> R.string.pro_benefit_gallery
    ProFeature.DEVICE_SCAN -> R.string.pro_benefit_device_scan
    ProFeature.FURNITURE_TEMPLATES -> R.string.pro_benefit_templates
    ProFeature.COMPARE -> R.string.pro_benefit_compare
    ProFeature.EXPORT_GLB -> R.string.pro_benefit_export_glb
    ProFeature.EXPORT_PDF -> R.string.pro_benefit_export_pdf
}

@Composable
private fun Benefits() {
    InstrumentCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pro_benefits_title), style = MaterialTheme.typography.titleMedium)
            ProCatalog.advertised.forEach { feature ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CheckMark()
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(benefitRes(feature)), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun CheckMark() {
    val ink = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(16.dp)) {
        val s = 2.dp.toPx()
        drawLine(ink, Offset(0f, size.height * 0.55f), Offset(size.width * 0.38f, size.height * 0.9f), s, StrokeCap.Square)
        drawLine(ink, Offset(size.width * 0.38f, size.height * 0.9f), Offset(size.width, size.height * 0.1f), s, StrokeCap.Square)
    }
}

@Composable
private fun Offers(state: PaywallUiState, onSelect: (String) -> Unit, onBuy: () -> Unit, onRetry: () -> Unit) {
    when (val offers = state.offers) {
        OffersUi.Loading -> Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("paywall_loading"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.pro_loading), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        is OffersUi.Failed -> InstrumentCard(Modifier.testTag("paywall_problem")) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(problemRes(offers.problem)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (offers.problem == PaywallProblem.OFFLINE || offers.problem == PaywallProblem.GENERIC) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.pro_retry)) }
                }
            }
        }
        is OffersUi.Ready -> {
            InstrumentCard(Modifier.testTag("paywall_plans")) {
                Column(Modifier.selectableGroup()) {
                    offers.offers.forEachIndexed { i, offer ->
                        if (i > 0) HorizontalDivider(color = SpaceViewTheme.colors.hairline)
                        PlanRow(offer, selected = offer.basePlanId == state.selectedBasePlan, onSelect = { onSelect(offer.basePlanId) })
                    }
                }
            }
            val selected = state.selectedOffer
            if (selected != null) {
                val pending = state.entitlement is Entitlement.Pending
                Button(
                    onClick = onBuy,
                    // A second purchase while one is pending could charge twice.
                    enabled = !state.busy && !pending,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("paywall_buy"),
                ) {
                    Text(ctaText(selected))
                }
                Text(
                    termsText(selected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("paywall_terms"),
                )
            }
        }
    }
}

@Composable
private fun PlanRow(offer: ProductOffer, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("paywall_plan_${offer.basePlanId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(planNameRes(offer)), style = MaterialTheme.typography.titleMedium)
            Text(priceText(offer), style = SpaceViewTheme.typography.spec)
            offer.freeTrial?.let {
                Text(
                    stringResource(R.string.pro_trial_badge, periodPhrase(it)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@StringRes
private fun planNameRes(offer: ProductOffer): Int = when {
    offer.kind == PlanKind.PREPAID -> R.string.pro_plan_pass
    offer.basePlanId == ProCatalog.BASE_PLAN_YEARLY -> R.string.pro_plan_yearly
    else -> R.string.pro_plan_monthly
}

@Composable
private fun priceText(offer: ProductOffer): String =
    if (offer.kind == PlanKind.PREPAID) {
        stringResource(R.string.pro_price_once, offer.formattedPrice, periodPhrase(offer.period))
    } else {
        stringResource(R.string.pro_price_recurring, offer.formattedPrice, perPhrase(offer.period))
    }

@Composable
private fun ctaText(offer: ProductOffer): String {
    val trial = offer.freeTrial
    return when {
        offer.kind == PlanKind.PREPAID -> stringResource(R.string.pro_cta_pass)
        trial != null -> stringResource(R.string.pro_cta_trial, periodPhrase(trial))
        else -> stringResource(R.string.pro_cta_subscribe)
    }
}

/** Price, period, renewal, trial end and how to cancel, in plain words (Play subscriptions policy). */
@Composable
private fun termsText(offer: ProductOffer): String {
    val trial = offer.freeTrial
    return when {
        offer.kind == PlanKind.PREPAID -> stringResource(R.string.pro_terms_prepaid, offer.formattedPrice, periodPhrase(offer.period))
        trial != null -> stringResource(R.string.pro_terms_trial, periodPhrase(trial), offer.formattedPrice, everyPhrase(offer.period))
        else -> stringResource(R.string.pro_terms_renewing, offer.formattedPrice, everyPhrase(offer.period))
    }
}

@PluralsRes
private fun periodsRes(unit: PeriodUnit): Int = when (unit) {
    PeriodUnit.DAY -> R.plurals.pro_periods_day
    PeriodUnit.WEEK -> R.plurals.pro_periods_week
    PeriodUnit.MONTH -> R.plurals.pro_periods_month
    PeriodUnit.YEAR -> R.plurals.pro_periods_year
}

/** "7 days", "1 month". */
@Composable
private fun periodPhrase(p: BillingPeriod): String = pluralStringResource(periodsRes(p.unit), p.count, p.count)

/** "year" for one, "3 months" otherwise (after a "/"). */
@Composable
private fun perPhrase(p: BillingPeriod): String = if (p.count != 1) {
    periodPhrase(p)
} else {
    stringResource(
        when (p.unit) {
            PeriodUnit.DAY -> R.string.pro_per_day
            PeriodUnit.WEEK -> R.string.pro_per_week
            PeriodUnit.MONTH -> R.string.pro_per_month
            PeriodUnit.YEAR -> R.string.pro_per_year
        },
    )
}

/** "every year", "every 3 months". */
@Composable
private fun everyPhrase(p: BillingPeriod): String = if (p.count != 1) {
    stringResource(R.string.pro_every_n, periodPhrase(p))
} else {
    stringResource(
        when (p.unit) {
            PeriodUnit.DAY -> R.string.pro_every_day
            PeriodUnit.WEEK -> R.string.pro_every_week
            PeriodUnit.MONTH -> R.string.pro_every_month
            PeriodUnit.YEAR -> R.string.pro_every_year
        },
    )
}

@StringRes
private fun problemRes(problem: PaywallProblem): Int = when (problem) {
    PaywallProblem.OFFLINE -> R.string.pro_problem_offline
    PaywallProblem.UNAVAILABLE -> R.string.pro_problem_unavailable
    PaywallProblem.NOT_ON_SALE -> R.string.pro_problem_not_on_sale
    PaywallProblem.GENERIC -> R.string.pro_problem_generic
}

@StringRes
internal fun paywallMessageRes(message: PaywallMessage): Int = when (message) {
    PaywallMessage.PURCHASE_PENDING -> R.string.pro_pending_body
    PaywallMessage.PURCHASE_FAILED -> R.string.pro_msg_purchase_failed
    PaywallMessage.PURCHASE_OFFLINE -> R.string.pro_problem_offline
    PaywallMessage.PURCHASE_UNAVAILABLE -> R.string.pro_problem_unavailable
    PaywallMessage.RESTORED -> R.string.pro_msg_restored
    PaywallMessage.NOTHING_TO_RESTORE -> R.string.pro_msg_nothing
    PaywallMessage.RESTORE_PENDING -> R.string.pro_msg_restore_pending
    PaywallMessage.RESTORE_FAILED -> R.string.pro_msg_restore_failed
}

@Composable
private fun Message(message: PaywallMessage?) {
    if (message == null) return
    Text(
        stringResource(paywallMessageRes(message)),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("paywall_message")
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun ActiveCard(pro: Entitlement.Pro, onDone: () -> Unit) {
    val body = buildString {
        append(stringResource(R.string.pro_active_body))
        if (pro.source == Entitlement.Pro.Source.CACHE_GRACE) {
            append(' ')
            append(stringResource(R.string.pro_active_offline, formatDate(pro.confirmedAtMillis)))
        }
    }
    InfoCard(R.string.pro_active_title, body, Modifier.testTag("paywall_active"))
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(stringResource(R.string.pro_done)) }
}

@Composable
private fun InfoCard(@StringRes title: Int, body: String, modifier: Modifier = Modifier) {
    InstrumentCard(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun formatDate(millis: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

@Preview(name = "Light", showBackground = true, heightDp = 1100)
@Preview(name = "Dark", showBackground = true, heightDp = 1100, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PaywallPreview() {
    val offers = OfferSelector.select(FakeBillingGateway.defaultOffers())
    SpaceViewTheme {
        PaywallScreen(
            state = PaywallUiState(
                feature = ProFeature.GALLERY_UNLIMITED,
                entitlement = Entitlement.Free,
                offers = OffersUi.Ready(offers),
                selectedBasePlan = ProCatalog.BASE_PLAN_YEARLY,
            ),
            onClose = {}, onSelect = {}, onBuy = {}, onRestore = {}, onManage = {}, onRetry = {},
        )
    }
}
