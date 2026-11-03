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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spaceview.app.BuildConfig
import com.spaceview.app.R
import com.spaceview.app.ads.AdsManager
import com.spaceview.app.ads.BannerAd
import com.spaceview.app.common.InstrumentCard
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.engine.UnitPreference
import kotlinx.coroutines.launch

/**
 * Settings wired to the app-wide [AdsManager]: privacy options open the UMP form and a banner
 * sits at the end of the list. This is what navigation should host.
 */
@Composable
fun SettingsRoute(
    onRecalibrate: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val activity = LocalActivity.current
    val ads = AdsManager.get(LocalContext.current)
    val privacyRequired by ads.privacyOptionsRequired.collectAsStateWithLifecycle()
    SettingsScreen(
        onRecalibrate = onRecalibrate,
        onOpenPrivacyOptions = { activity?.let { ads.showPrivacyOptions(it) } },
        privacyOptionsRequired = privacyRequired,
        modifier = modifier,
        onBack = onBack,
        banner = { BannerAd(adsManager = ads) },
    )
}

/**
 * Settings screen (S14): units, sounds, recalibrate, privacy options, "Remove ads" (coming
 * soon), about and open-source licences. Settings persist in [SettingsRepository].
 *
 * @param onRecalibrate open the calibration flow.
 * @param onOpenPrivacyOptions show the UMP privacy-options form (see [AdsManager.showPrivacyOptions]).
 * @param privacyOptionsRequired from [AdsManager.privacyOptionsRequired]; when false the row is
 *   shown disabled with "No ad privacy choices are needed in your region".
 * @param onBack shows a back arrow when non-null.
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
    banner: @Composable () -> Unit = {},
) {
    val units by repository.unitPreference.collectAsStateWithLifecycle(UnitPreference.AUTO)
    val sounds by repository.soundsEnabled.collectAsStateWithLifecycle(true)
    val scope = rememberCoroutineScope()
    SettingsContent(
        units = units,
        onUnitsChange = { scope.launch { repository.setUnitPreference(it) } },
        sounds = sounds,
        onSoundsChange = { scope.launch { repository.setSoundsEnabled(it) } },
        onRecalibrate = onRecalibrate,
        onOpenPrivacyOptions = onOpenPrivacyOptions,
        privacyOptionsRequired = privacyOptionsRequired,
        onBack = onBack,
        banner = banner,
        modifier = modifier,
    )
}

@Composable
private fun SettingsContent(
    units: UnitPreference,
    onUnitsChange: (UnitPreference) -> Unit,
    sounds: Boolean,
    onSoundsChange: (Boolean) -> Unit,
    onRecalibrate: () -> Unit,
    onOpenPrivacyOptions: () -> Unit,
    privacyOptionsRequired: Boolean,
    onBack: (() -> Unit)?,
    banner: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showLicences by rememberSaveable { mutableStateOf(false) }
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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .toggleable(value = sounds, role = Role.Switch, onValueChange = onSoundsChange)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RowText(R.string.settings_sounds, R.string.settings_sounds_detail, Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = sounds, onCheckedChange = null)
                }
                Divider()
                ActionRow(R.string.settings_recalibrate, R.string.settings_recalibrate_detail, onClick = onRecalibrate)
            }

            Section(R.string.settings_section_privacy) {
                ActionRow(
                    title = R.string.settings_privacy_options,
                    detail = if (privacyOptionsRequired) R.string.settings_privacy_options_detail else R.string.settings_privacy_options_not_needed,
                    enabled = privacyOptionsRequired,
                    onClick = onOpenPrivacyOptions,
                )
                if (BuildConfig.PRIVACY_POLICY_URL.isNotBlank()) {
                    Divider()
                    ActionRow(R.string.settings_privacy_policy, null, onClick = {
                        runCatching { uriHandler.openUri(BuildConfig.PRIVACY_POLICY_URL) }
                    })
                }
            }

            Section(R.string.settings_section_ads) {
                ActionRow(R.string.settings_remove_ads, R.string.settings_coming_soon, enabled = false, onClick = {})
            }

            Section(R.string.settings_section_about) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.settings_version), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(BuildConfig.VERSION_NAME, style = SpaceViewTheme.typography.spec, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Divider()
                ActionRow(R.string.settings_accuracy_note_title, R.string.settings_accuracy_note, onClick = null)
                Divider()
                ActionRow(R.string.settings_licences, null, onClick = { showLicences = true })
                if (BuildConfig.SUPPORT_EMAIL.isNotBlank()) {
                    Divider()
                    ActionRow(R.string.settings_contact, null, onClick = {
                        runCatching { uriHandler.openUri("mailto:${BuildConfig.SUPPORT_EMAIL}") }
                    })
                }
            }

            Spacer(Modifier.height(4.dp))
            banner()
            Spacer(Modifier.height(12.dp))
        }
    }
    if (showLicences) LicencesDialog(onDismiss = { showLicences = false })
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
private fun RowText(@StringRes title: Int, @StringRes detail: Int?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val alpha = if (enabled) 1f else 0.5f
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
        if (detail != null) {
            Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
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

/** A tappable settings row; [onClick] null renders static text. */
@Composable
private fun ActionRow(@StringRes title: Int, @StringRes detail: Int?, enabled: Boolean = true, onClick: (() -> Unit)?) {
    val base = Modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
    val clickable = if (onClick != null) {
        base.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else {
        base
    }
    Row(clickable.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RowText(title, detail, Modifier.weight(1f), enabled = enabled)
    }
}

/** Libraries shipped in the APK and their licences. Verify against the final dependency list in M6. */
private val LICENCES = listOf(
    "AndroidX, Jetpack Compose" to "Apache License 2.0",
    "Kotlin, kotlinx.coroutines" to "Apache License 2.0",
    "SceneView / ARSceneView" to "Apache License 2.0",
    "Filament (Google)" to "Apache License 2.0",
    "ARCore SDK for Android (Google)" to "Google APIs Terms of Service",
    "Google Mobile Ads SDK, User Messaging Platform" to "Google APIs Terms of Service",
)

@Composable
private fun LicencesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
        title = { Text(stringResource(R.string.settings_licences)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LICENCES.forEach { (name, licence) ->
                    Column {
                        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(licence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.settings_licences_apache), style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

@Preview(name = "Light", showBackground = true, heightDp = 1200)
@Preview(name = "Dark", showBackground = true, heightDp = 1200, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SettingsPreview() {
    SpaceViewTheme {
        SettingsContent(
            units = UnitPreference.AUTO, onUnitsChange = {}, sounds = true, onSoundsChange = {},
            onRecalibrate = {}, onOpenPrivacyOptions = {}, privacyOptionsRequired = false, onBack = {}, banner = {},
        )
    }
}
