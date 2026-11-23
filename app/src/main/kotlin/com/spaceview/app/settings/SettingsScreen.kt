package com.spaceview.app.settings

import android.content.res.Configuration
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
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
import com.spaceview.app.BuildConfig
import com.spaceview.app.R
import com.spaceview.app.about.AppLinks
import com.spaceview.app.ads.AdsManager
import com.spaceview.app.ads.BannerAd
import com.spaceview.app.appContainer
import com.spaceview.app.billing.FakeBillingControls
import com.spaceview.app.billing.FakeBillingGateway
import com.spaceview.app.common.InstrumentCard
import com.spaceview.app.pro.Entitlement
import com.spaceview.app.pro.PaywallMessage
import com.spaceview.app.pro.PaywallViewModel
import com.spaceview.app.pro.ProFeature
import com.spaceview.app.pro.ProLinks
import com.spaceview.app.pro.RefreshReason
import com.spaceview.app.pro.formatDate
import com.spaceview.app.pro.paywallMessageRes
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.data.settings.MeasureSettings
import com.spaceview.data.settings.MeasureSettingsRepository
import com.spaceview.engine.UnitPreference
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Settings wired to the app-wide [AdsManager] and entitlement: privacy options open the UMP form (only
 * while ads are active), the Pro section shows the status with Restore purchases and Manage
 * subscription, and a banner sits at the end of the list (hidden for Pro by [BannerAd]). This is what
 * navigation hosts.
 *
 * @param onOpenPaywall contract K5; tapping the Free-plan row opens the paywall. Null: no upgrade action.
 */
@Composable
fun SettingsRoute(
    onRecalibrate: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onOpenPaywall: ((ProFeature) -> Unit)? = null,
    onOpenHelp: (() -> Unit)? = null,
    onOpenAbout: (() -> Unit)? = null,
) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val container = context.appContainer
    val ads = container.ads
    val entitlements = container.entitlements
    val privacyRequired by ads.privacyOptionsRequired.collectAsStateWithLifecycle()
    val entitlement by entitlements.entitlement.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf(false) }
    var restoreMessage by remember { mutableStateOf<PaywallMessage?>(null) }
    val restore: () -> Unit = {
        if (!restoring) {
            restoring = true
            restoreMessage = null
            scope.launch {
                restoreMessage = PaywallViewModel.restoreMessage(entitlements.refresh(RefreshReason.RESTORE_BUTTON))
                restoring = false
            }
        }
    }
    val uriHandler = LocalUriHandler.current
    // Delete my data (M5): runs to the end even if Settings is left meanwhile (UserDataEraser is NonCancellable).
    var erasing by remember { mutableStateOf(false) }
    var eraseComplete by remember { mutableStateOf<Boolean?>(null) }
    val erase: () -> Unit = {
        if (!erasing) {
            erasing = true
            eraseComplete = null
            scope.launch {
                eraseComplete = container.userDataEraser.eraseAll().complete
                erasing = false
            }
        }
    }
    SettingsScreen(
        onRecalibrate = onRecalibrate,
        onOpenPrivacyOptions = { activity?.let { ads.showPrivacyOptions(it) } },
        privacyOptionsRequired = privacyRequired,
        modifier = modifier,
        onBack = onBack,
        pro = ProSettingsUi(
            entitlement = entitlement,
            restoring = restoring,
            message = restoreMessage,
            onOpenPaywall = onOpenPaywall?.let { open -> { open(ProFeature.NO_ADS) } },
            onRestore = restore,
            onManage = { runCatching { uriHandler.openUri(ProLinks.manageSubscriptions(context.packageName)) } },
        ),
        debugPanel = {
            // Debug builds with fake billing only (BuildConfig.FAKE_BILLING is a constant false in release).
            if (BuildConfig.FAKE_BILLING) {
                (container.billing as? FakeBillingGateway)?.let { fake -> FakeBillingControls(fake, onChanged = restore) }
            }
            // The 3D Scan Lab lives in the debug source set only (ARCHITECTURE-v2 §7.1.10 spike); BuildConfig.DEBUG is
            // a constant false in release, so R8 drops this branch and nothing here names a lab class.
            if (BuildConfig.DEBUG) Scan3dLabLink()
        },
        banner = { BannerAd(adsManager = ads) },
        onOpenHelp = onOpenHelp,
        onOpenAbout = onOpenAbout,
        deleteData = DeleteDataUi(running = erasing, complete = eraseComplete, onDelete = erase),
    )
}

/** What the Settings "Delete my data" row shows. [complete]: null before the first run, else whether everything went. */
data class DeleteDataUi(
    val running: Boolean = false,
    val complete: Boolean? = null,
    val onDelete: () -> Unit = {},
)

/** What the Settings "SpaceView Pro" section shows. */
data class ProSettingsUi(
    val entitlement: Entitlement = Entitlement.Unknown,
    val restoring: Boolean = false,
    val message: PaywallMessage? = null,
    val onOpenPaywall: (() -> Unit)? = null,
    val onRestore: () -> Unit = {},
    val onManage: () -> Unit = {},
)

/**
 * Settings screen (S14): units, sounds, depth, calibration (status, recalibrate, clear), privacy
 * options, "Remove ads" (coming soon), about and open-source licences. App settings persist in
 * [SettingsRepository]; depth and calibration in [MeasureSettingsRepository].
 *
 * @param onRecalibrate open the calibration flow.
 * @param onOpenPrivacyOptions show the UMP privacy-options form (see [AdsManager.showPrivacyOptions]).
 * @param privacyOptionsRequired from [AdsManager.privacyOptionsRequired]; when false the row is
 *   shown disabled with "No ad privacy choices are needed in your region".
 * @param onBack shows a back arrow when non-null.
 * @param pro the "SpaceView Pro" section (status, Restore purchases, Manage subscription). The ad
 *   privacy row is shown only while ads are active, i.e. not for Pro.
 * @param debugPanel debug-only controls (fake billing); empty by default.
 * @param banner slot at the end of the list for a [BannerAd]; empty by default.
 */
@Composable
fun SettingsScreen(
    onRecalibrate: () -> Unit,
    onOpenPrivacyOptions: () -> Unit,
    modifier: Modifier = Modifier,
    privacyOptionsRequired: Boolean = true,
    onBack: (() -> Unit)? = null,
    repository: SettingsRepository = SettingsRepository.get(LocalContext.current),
    measureRepository: MeasureSettingsRepository = LocalContext.current.appContainer.measureSettings,
    clock: () -> Long = LocalContext.current.appContainer.clock,
    pro: ProSettingsUi = ProSettingsUi(),
    debugPanel: @Composable () -> Unit = {},
    banner: @Composable () -> Unit = {},
    onOpenHelp: (() -> Unit)? = null,
    onOpenAbout: (() -> Unit)? = null,
    deleteData: DeleteDataUi = DeleteDataUi(),
) {
    val units by repository.unitPreference.collectAsStateWithLifecycle(UnitPreference.AUTO)
    val sounds by repository.soundsEnabled.collectAsStateWithLifecycle(true)
    val measure by measureRepository.settings.collectAsStateWithLifecycle(MeasureSettings())
    val scope = rememberCoroutineScope()
    val calibratedAt = measure.calibration?.calibratedAtMillis
    SettingsContent(
        units = units,
        onUnitsChange = { scope.launch { repository.setUnitPreference(it) } },
        sounds = sounds,
        onSoundsChange = { scope.launch { repository.setSoundsEnabled(it) } },
        depth = measure.depthEnabled,
        // A disk error must not crash Settings; the switch then simply snaps back.
        onDepthChange = { enabled -> scope.launch { runCatching { measureRepository.setDepthEnabled(enabled) } } },
        // SM1a (Builder A): hands-free point drop, persisted next to depth in the measuring store.
        handsFree = measure.handsFreeEnabled,
        onHandsFreeChange = { enabled -> scope.launch { runCatching { measureRepository.setHandsFreeEnabled(enabled) } } },
        calibratedAtMillis = calibratedAt,
        // CalibrationRecord.isStale: older than 30 days (MAX_AGE_MILLIS) or dated in the future.
        calibrationStale = measure.calibration?.isStale(clock()) == true,
        onClearCalibration = { scope.launch { runCatching { measureRepository.clearCalibration() } } },
        onRecalibrate = onRecalibrate,
        onOpenPrivacyOptions = onOpenPrivacyOptions,
        privacyOptionsRequired = privacyOptionsRequired,
        onBack = onBack,
        pro = pro,
        debugPanel = debugPanel,
        banner = banner,
        onOpenHelp = onOpenHelp,
        onOpenAbout = onOpenAbout,
        deleteData = deleteData,
        modifier = modifier,
    )
}

@Composable
private fun SettingsContent(
    units: UnitPreference,
    onUnitsChange: (UnitPreference) -> Unit,
    sounds: Boolean,
    onSoundsChange: (Boolean) -> Unit,
    depth: Boolean,
    onDepthChange: (Boolean) -> Unit,
    calibratedAtMillis: Long?,
    calibrationStale: Boolean,
    onClearCalibration: () -> Unit,
    onRecalibrate: () -> Unit,
    onOpenPrivacyOptions: () -> Unit,
    privacyOptionsRequired: Boolean,
    onBack: (() -> Unit)?,
    pro: ProSettingsUi,
    debugPanel: @Composable () -> Unit,
    banner: @Composable () -> Unit,
    onOpenHelp: (() -> Unit)?,
    onOpenAbout: (() -> Unit)?,
    deleteData: DeleteDataUi,
    modifier: Modifier = Modifier,
    handsFree: Boolean = true,
    onHandsFreeChange: (Boolean) -> Unit = {},
) {
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TopBar(onBack)

            Section(R.string.settings_section_units) {
                Column(Modifier.selectableGroup()) {
                    UnitOption(UnitPreference.AUTO, R.string.settings_units_auto, R.string.settings_units_auto_detail, units, onUnitsChange)
                    Divider()
                    UnitOption(UnitPreference.METRIC, R.string.settings_units_metric, R.string.settings_units_metric_detail, units, onUnitsChange)
                    Divider()
                    UnitOption(UnitPreference.IMPERIAL, R.string.settings_units_imperial, R.string.settings_units_imperial_detail, units, onUnitsChange)
                }
            }

            Section(R.string.settings_section_measuring) {
                SwitchRow(R.string.settings_sounds, R.string.settings_sounds_detail, sounds, onSoundsChange)
                Divider()
                SwitchRow(R.string.settings_depth, R.string.settings_depth_detail, depth, onDepthChange)
                Divider()
                SwitchRow(R.string.smart_settings_hands_free, R.string.smart_settings_hands_free_detail, handsFree, onHandsFreeChange)
                Divider()
                val calibrationStatus = if (calibratedAtMillis == null) {
                    stringResource(R.string.settings_recalibrate_detail)
                } else {
                    val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(calibratedAtMillis))
                    stringResource(
                        if (calibrationStale) R.string.settings_calibration_stale else R.string.settings_calibration_on,
                        date,
                    )
                }
                ActionRow(
                    title = R.string.settings_recalibrate,
                    detail = null,
                    detailText = calibrationStatus,
                    detailColor = if (calibrationStale) MaterialTheme.colorScheme.error else null,
                    onClick = onRecalibrate,
                )
                Divider()
                ActionRow(
                    title = R.string.settings_clear_calibration,
                    detail = if (calibratedAtMillis == null) R.string.settings_calibration_none else R.string.settings_clear_calibration_detail,
                    enabled = calibratedAtMillis != null,
                    onClick = { confirmClear = true },
                )
            }

            // Ad privacy choices only matter while ads can run (never for Pro, ARCHITECTURE-v2 §4.6). The row is
            // enabled exactly when UMP says a privacy-options entry point is required (e.g. EEA/UK).
            val adsActive = pro.entitlement !is Entitlement.Pro
            val policyUrl = AppLinks.privacyPolicyUrl
            Section(R.string.settings_section_privacy) {
                if (adsActive) {
                    ActionRow(
                        title = R.string.settings_privacy_options,
                        detail = if (privacyOptionsRequired) R.string.settings_privacy_options_detail else R.string.settings_privacy_options_not_needed,
                        enabled = privacyOptionsRequired,
                        onClick = onOpenPrivacyOptions,
                    )
                    Divider()
                }
                if (policyUrl != null) {
                    ActionRow(R.string.settings_privacy_policy, null, onClick = { runCatching { uriHandler.openUri(policyUrl) } })
                    Divider()
                }
                ActionRow(
                    title = R.string.settings_delete_data,
                    detail = null,
                    detailText = when {
                        deleteData.running -> stringResource(R.string.settings_delete_data_running)
                        deleteData.complete == true -> stringResource(R.string.settings_delete_data_done)
                        deleteData.complete == false -> stringResource(R.string.settings_delete_data_partial)
                        else -> stringResource(R.string.settings_delete_data_detail)
                    },
                    detailColor = if (deleteData.complete == false) MaterialTheme.colorScheme.error else null,
                    liveDetail = deleteData.running || deleteData.complete != null,
                    enabled = !deleteData.running,
                    onClick = { confirmDelete = true },
                )
            }

            Section(R.string.pro_settings_section) {
                ProRows(pro)
            }
            debugPanel()

            Section(R.string.settings_section_about) {
                ActionRow(
                    title = R.string.settings_help,
                    detail = R.string.settings_help_detail,
                    detailSpoken = stringResource(R.string.settings_help_detail_spoken),
                    onClick = onOpenHelp,
                )
                Divider()
                ActionRow(R.string.settings_about, R.string.settings_about_detail, onClick = onOpenAbout)
                Divider()
                ActionRow(
                    R.string.settings_accuracy_note_title,
                    R.string.settings_accuracy_note,
                    detailSpoken = stringResource(R.string.settings_accuracy_note_spoken),
                    onClick = null,
                )
            }

            Spacer(Modifier.height(4.dp))
            banner()
            Spacer(Modifier.height(12.dp))
        }
    }
    if (confirmDelete) {
        DeleteDataDialog(
            onConfirm = {
                confirmDelete = false
                deleteData.onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
    if (confirmClear) {
        ClearCalibrationDialog(
            onConfirm = {
                confirmClear = false
                onClearCalibration()
            },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun ProRows(pro: ProSettingsUi) {
    val e = pro.entitlement
    val title: Int
    val detail: String?
    when (e) {
        is Entitlement.Pro -> {
            title = R.string.pro_status_pro
            detail = when {
                e.source == Entitlement.Pro.Source.CACHE_GRACE -> stringResource(R.string.pro_status_grace, formatDate(e.confirmedAtMillis))
                e.autoRenewing -> stringResource(R.string.pro_status_renewing)
                else -> stringResource(R.string.pro_status_not_renewing)
            }
        }
        is Entitlement.Pending -> {
            title = R.string.pro_status_pending
            detail = stringResource(R.string.pro_status_pending_detail)
        }
        Entitlement.Free -> {
            title = R.string.pro_status_free
            detail = stringResource(R.string.pro_status_free_detail)
        }
        Entitlement.Unknown -> {
            title = R.string.pro_status_checking
            detail = null
        }
    }
    val upgrade = pro.onOpenPaywall.takeIf { e is Entitlement.Free }
    ActionRow(title = title, detail = null, detailText = detail, onClick = upgrade)
    Divider()
    ActionRow(
        title = R.string.pro_restore,
        detail = R.string.pro_restore_detail,
        detailText = pro.message?.let { stringResource(paywallMessageRes(it)) },
        liveDetail = pro.message != null,
        enabled = !pro.restoring,
        onClick = pro.onRestore,
    )
    Divider()
    ActionRow(R.string.pro_manage, R.string.pro_manage_detail, onClick = pro.onManage)
}

/** Delete my data confirmation: says what goes, that it cannot be undone, and that the Play subscription stays. */
@Composable
private fun DeleteDataDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.settings_delete_data_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_cancel)) }
        },
        title = { Text(stringResource(R.string.settings_delete_data_title)) },
        text = { Text(stringResource(R.string.settings_delete_data_body), modifier = Modifier.verticalScroll(rememberScrollState())) },
    )
}

@Composable
private fun ClearCalibrationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_clear_calibration_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
        title = { Text(stringResource(R.string.settings_clear_calibration_title)) },
        text = { Text(stringResource(R.string.settings_clear_calibration_body)) },
    )
}

@Composable
private fun SwitchRow(@StringRes title: Int, @StringRes detail: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, detail, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun TopBar(onBack: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            val back = stringResource(R.string.common_back)
            val ink = MaterialTheme.colorScheme.onBackground
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = back }) {
                Canvas(Modifier.size(18.dp)) {
                    val s = 2.dp.toPx()
                    val y = size.height / 2
                    drawLine(ink, Offset(size.width, y), Offset(0f, y), s, StrokeCap.Square)
                    drawLine(ink, Offset(0f, y), Offset(size.width * 0.45f, y - size.height * 0.4f), s, StrokeCap.Square)
                    drawLine(ink, Offset(0f, y), Offset(size.width * 0.45f, y + size.height * 0.4f), s, StrokeCap.Square)
                }
            }
            Spacer(Modifier.width(4.dp))
        }
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun Section(@StringRes title: Int, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(title).uppercase(),
            style = SpaceViewTheme.typography.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = 4.dp, top = 8.dp)
                .semantics { heading() },
        )
        InstrumentCard { content() }
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = SpaceViewTheme.colors.hairline)

@Composable
private fun RowText(
    @StringRes title: Int,
    @StringRes detail: Int?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    detailText: String? = null,
    detailColor: Color? = null,
    detailSpoken: String? = null,
    liveDetail: Boolean = false,
) {
    val alpha = if (enabled) 1f else 0.5f
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        val text = detailText ?: detail?.let { stringResource(it) }
        if (text != null) {
            val color = detailColor ?: MaterialTheme.colorScheme.onSurfaceVariant
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = color.copy(alpha = alpha),
                // M5 a11y: results that appear after a tap (restore, delete) are announced; symbols are spoken in words.
                modifier = Modifier.semantics {
                    if (liveDetail) liveRegion = LiveRegionMode.Polite
                    if (detailSpoken != null) contentDescription = detailSpoken
                },
            )
        }
    }
}

@Composable
private fun UnitOption(
    value: UnitPreference,
    @StringRes title: Int,
    @StringRes detail: Int,
    selected: UnitPreference,
    onSelect: (UnitPreference) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = value == selected, role = Role.RadioButton, onClick = { onSelect(value) })
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = value == selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        RowText(title, detail, Modifier.weight(1f))
    }
}

/** A tappable settings row; [onClick] null renders static text. [detailText] overrides [detail]. */
@Composable
private fun ActionRow(
    @StringRes title: Int,
    @StringRes detail: Int?,
    enabled: Boolean = true,
    detailText: String? = null,
    detailColor: Color? = null,
    detailSpoken: String? = null,
    liveDetail: Boolean = false,
    onClick: (() -> Unit)?,
) {
    val base = Modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
    val clickable = if (onClick != null) {
        base.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else {
        base
    }
    Row(clickable.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RowText(
            title, detail, Modifier.weight(1f), enabled = enabled, detailText = detailText, detailColor = detailColor,
            detailSpoken = detailSpoken, liveDetail = liveDetail,
        )
    }
}

@Preview(name = "Light", showBackground = true, heightDp = 1200)
@Preview(name = "Dark", showBackground = true, heightDp = 1200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsPreview() {
    SpaceViewTheme {
        SettingsContent(
            units = UnitPreference.AUTO, onUnitsChange = {}, sounds = true, onSoundsChange = {},
            depth = false, onDepthChange = {}, calibratedAtMillis = 1_760_000_000_000L, calibrationStale = true,
            onClearCalibration = {}, onRecalibrate = {}, onOpenPrivacyOptions = {}, privacyOptionsRequired = false, onBack = {},
            pro = ProSettingsUi(entitlement = Entitlement.Free, onOpenPaywall = {}), debugPanel = {}, banner = {},
            onOpenHelp = {}, onOpenAbout = {}, deleteData = DeleteDataUi(complete = true),
        )
    }
}

/** Debug builds only: opens the 3D Scan Lab activity (declared in `src/debug/AndroidManifest.xml`). */
@Composable
private fun Scan3dLabLink() {
    val context = LocalContext.current
    androidx.compose.material3.OutlinedButton(
        onClick = {
            runCatching { context.startActivity(android.content.Intent().setClassName(context, SCAN3D_LAB_ACTIVITY)) }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(stringResource(R.string.settings_scan3d_lab)) }
}

private const val SCAN3D_LAB_ACTIVITY = "com.spaceview.app.scan3d.Scan3dLabActivity"
