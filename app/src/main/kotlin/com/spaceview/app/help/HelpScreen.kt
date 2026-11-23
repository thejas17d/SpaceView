package com.spaceview.app.help

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spaceview.app.R
import com.spaceview.app.about.AppLinks
import com.spaceview.app.common.InstrumentCard
import com.spaceview.app.pro.ProCatalog
import com.spaceview.app.pro.benefitRes
import com.spaceview.app.ui.theme.SpaceViewTheme

/**
 * Help / FAQ (M5): static, offline, EN + HI. Every section title is a TalkBack heading, so a screen-reader user can
 * jump between questions. The Pro list is [ProCatalog.advertised], the same list the paywall shows, so the two can
 * never disagree.
 *
 * @param supportEmail the configured support address ([AppLinks.supportEmail]); null shows the Play listing instead.
 */
@Composable
fun HelpScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    supportEmail: String? = AppLinks.supportEmail,
) {
    val uriHandler = LocalUriHandler.current
    val packageName = LocalContext.current.packageName
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackTopBar(R.string.help_title, onBack)
            Text(
                stringResource(R.string.help_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HelpSection(R.string.help_accuracy_title) {
                Paragraph(R.string.help_accuracy_distance)
                Paragraph(R.string.help_accuracy_light)
                Paragraph(R.string.help_accuracy_move)
                Paragraph(R.string.help_accuracy_calibrate)
            }
            HelpSection(R.string.help_handsfree_title) {
                Paragraph(R.string.help_handsfree_body)
                Paragraph(R.string.help_handsfree_off)
                Paragraph(R.string.help_handsfree_undo)
            }
            HelpSection(R.string.help_pm_title, spokenTitle = R.string.a11y_plus_or_minus) {
                Paragraph(R.string.help_pm_body, spoken = R.string.help_pm_spoken)
            }
            HelpSection(R.string.help_wall_title) {
                Paragraph(R.string.help_wall_body)
                Paragraph(R.string.help_wall_fallback)
                Paragraph(R.string.help_wall_tips)
            }
            HelpSection(R.string.help_templates_title) {
                Paragraph(R.string.help_templates_body)
            }
            HelpSection(R.string.help_pro_title) {
                ProCatalog.advertised.forEach { feature ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("•", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.clearAndSetSemantics {})
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(benefitRes(feature)), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Paragraph(R.string.help_pro_free)
            }
            HelpSection(R.string.help_restore_title) {
                Paragraph(R.string.help_restore_body)
            }
            HelpSection(R.string.help_privacy_title) {
                Paragraph(R.string.help_privacy_body)
            }
            HelpSection(R.string.help_contact_title) {
                if (supportEmail != null) {
                    Paragraph(text = stringResource(R.string.help_contact_email, supportEmail))
                    ActionButton(R.string.help_contact_email_action) { runCatching { uriHandler.openUri(AppLinks.mailto(supportEmail)) } }
                } else {
                    Paragraph(R.string.help_contact_play)
                    ActionButton(R.string.help_contact_play_action) { runCatching { uriHandler.openUri(AppLinks.playListing(packageName)) } }
                }
            }
            Spacer(Modifier.heightIn(min = 12.dp))
        }
    }
}

/**
 * One FAQ answer: a heading and its paragraphs in an [InstrumentCard].
 *
 * @param spokenTitle when the visible title has a symbol ("±"), TalkBack reads the title with this word instead.
 */
@Composable
private fun HelpSection(@StringRes title: Int, @StringRes spokenTitle: Int? = null, content: @Composable () -> Unit) {
    InstrumentCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val titleText = stringResource(title)
            val spoken = spokenTitle?.let { titleText.replace("±", stringResource(it)) } ?: titleText
            Text(
                titleText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics {
                    heading()
                    contentDescription = spoken
                },
            )
            content()
        }
    }
}

@Composable
private fun Paragraph(@StringRes text: Int, @StringRes spoken: Int? = null) {
    val spokenText = spoken?.let { stringResource(it) }
    Paragraph(stringResource(text), spokenText)
}

@Composable
private fun Paragraph(text: String, spoken: String? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier,
    )
}

@Composable
private fun ActionButton(@StringRes label: Int, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(label))
    }
}

/** Title row with a 48 dp back button, shared by Help and About. */
@Composable
internal fun BackTopBar(@StringRes title: Int, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Text(
            stringResource(title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
    }
}

@Preview(name = "Light", showBackground = true, heightDp = 1800)
@Preview(name = "Dark", showBackground = true, heightDp = 1800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HelpPreview() {
    SpaceViewTheme { HelpScreen(onBack = {}, supportEmail = null) }
}
