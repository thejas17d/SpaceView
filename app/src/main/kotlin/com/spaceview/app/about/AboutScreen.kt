package com.spaceview.app.about

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spaceview.app.BuildConfig
import com.spaceview.app.R
import com.spaceview.app.common.InstrumentCard
import com.spaceview.app.help.BackTopBar
import com.spaceview.app.ui.theme.SpaceViewTheme

/**
 * About (M5): app version, the privacy policy (a link when one is configured, else the in-app privacy summary in
 * Help) and the open-source licences ([OssLicences], checked against the release dependency baseline). Offline.
 *
 * @param onOpenPrivacySummary opens Help, whose privacy section is the in-app summary.
 * @param privacyPolicyUrl [AppLinks.privacyPolicyUrl]; null while the user has not configured one.
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenPrivacySummary: () -> Unit,
    modifier: Modifier = Modifier,
    privacyPolicyUrl: String? = AppLinks.privacyPolicyUrl,
    versionName: String = BuildConfig.VERSION_NAME,
    versionCode: Int = BuildConfig.VERSION_CODE,
) {
    val uriHandler = LocalUriHandler.current
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackTopBar(R.string.about_title, onBack)
            InstrumentCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.about_version, versionName, versionCode),
                        style = SpaceViewTheme.typography.spec,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyMedium)
                }
            }
            InstrumentCard {
                if (privacyPolicyUrl != null) {
                    LinkRow(R.string.about_privacy_policy, R.string.about_privacy_policy_detail) {
                        runCatching { uriHandler.openUri(privacyPolicyUrl) }
                    }
                } else {
                    LinkRow(R.string.about_privacy_summary, R.string.about_privacy_summary_detail, onClick = onOpenPrivacySummary)
                }
            }
            Text(
                stringResource(R.string.about_licences_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp).semantics { heading() },
            )
            Text(stringResource(R.string.about_licences_intro), style = MaterialTheme.typography.bodyMedium)
            InstrumentCard {
                OssLicences.all.forEachIndexed { i, entry ->
                    if (i > 0) HorizontalDivider(color = SpaceViewTheme.colors.hairline)
                    // One TalkBack stop per library: "AndroidX…, Apache License 2.0".
                    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(entry.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(entry.licence),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Text(stringResource(R.string.about_apache_link), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun LinkRow(@StringRes title: Int, @StringRes detail: Int, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Preview(name = "Light", showBackground = true, heightDp = 1400)
@Preview(name = "Dark", showBackground = true, heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AboutPreview() {
    SpaceViewTheme { AboutScreen(onBack = {}, onOpenPrivacySummary = {}, privacyPolicyUrl = null, versionName = "0.1.0", versionCode = 1) }
}
