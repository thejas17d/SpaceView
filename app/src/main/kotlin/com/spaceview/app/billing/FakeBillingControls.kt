package com.spaceview.app.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spaceview.app.common.InstrumentCard
import com.spaceview.app.pro.Entitlement
import com.spaceview.app.pro.ProCatalog

/**
 * Debug builds only (shown when `BuildConfig.FAKE_BILLING`): the Settings **Developer** section. Its first row is the
 * "Pro (test)" switch (PLAN v3 W7): on grants a signed fake Pro purchase, off refunds it, so every Pro path can be
 * tried on a phone without paying and before Play Console is set up. Below it, the finer fake-billing controls
 * (pending, refund, offline). Deliberately not localised: it never ships in a release build (`FAKE_BILLING` is a
 * constant false there, R8 removes this class, and CI fails if its name is found in the release dex).
 *
 * @param entitlement what the app currently believes (shown next to the switch).
 * @param onChanged called after a change that Play would only report on the next query (runs a restore).
 */
@Composable
fun FakeBillingControls(fake: FakeBillingGateway, entitlement: Entitlement, onChanged: () -> Unit) {
    // Re-read the fake's (non-Compose) state after each tap.
    var tick by remember { mutableIntStateOf(0) }
    var showMore by remember { mutableStateOf(false) }
    val bump = { tick++ }
    InstrumentCard(Modifier.testTag("fake_billing")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Developer (debug build only)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            key(tick) {
                val on = fake.testProOn
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("fake_pro_switch")
                        .toggleable(value = on, role = Role.Switch) { wanted ->
                            fake.setTestPro(wanted)
                            onChanged()
                            bump()
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Pro (test)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "No payment. App now: ${entitlement.debugName()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = on, onCheckedChange = null)
                }
            }
            TextButton(onClick = { showMore = !showMore }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(if (showMore) "Hide billing tests" else "More billing tests")
            }
            if (showMore) {
                key(tick) {
                    Text(
                        "Next purchase: ${fake.nextOutcome}. Owned: ${fake.purchases.joinToString { it.state.name }.ifEmpty { "none" }}. " +
                            "Play reachable: ${fake.queryFailure == null}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = {
                        val all = FakeBillingGateway.NextOutcome.entries
                        fake.nextOutcome = all[(fake.nextOutcome.ordinal + 1) % all.size]
                        bump()
                    }) { Text("Next outcome") }
                    OutlinedButton(onClick = {
                        fake.approvePending()
                        bump()
                    }) { Text("Approve pending") }
                    OutlinedButton(onClick = {
                        fake.revokeAll()
                        onChanged()
                        bump()
                    }) { Text("Refund / expire") }
                    OutlinedButton(onClick = {
                        fake.queryFailure = if (fake.queryFailure == null) BillingCode.NETWORK_ERROR else null
                        onChanged()
                        bump()
                    }) { Text("Toggle offline") }
                }
            }
        }
    }
}

/**
 * Debug builds only: the paywall's "Test: unlock Pro" button (PLAN v3 W7). Grants a fake Pro purchase without paying,
 * then [onChanged] asks the entitlement to re-check (the paywall turns into "You have Pro"). Not localised: never in
 * a release build.
 */
@Composable
fun FakeUnlockProButton(fake: FakeBillingGateway, onChanged: () -> Unit) {
    OutlinedButton(
        onClick = {
            fake.setTestPro(true)
            onChanged()
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fake_unlock_pro"),
    ) { Text("Test: unlock Pro (debug, no payment)") }
}

/** True while the fake owns a purchased, valid Pro subscription. */
val FakeBillingGateway.testProOn: Boolean
    get() = purchases.any { it.state == PurchaseState.PURCHASED && ProCatalog.SUBSCRIPTION_ID in it.productIds }

/** The "Pro (test)" switch: on grants one signed Pro purchase (if none is owned yet), off refunds everything. */
fun FakeBillingGateway.setTestPro(on: Boolean) {
    if (on) {
        if (!testProOn) grant()
    } else {
        revokeAll()
    }
}

private fun Entitlement.debugName(): String = when (this) {
    Entitlement.Unknown -> "checking…"
    Entitlement.Free -> "Free"
    is Entitlement.Pending -> "payment pending"
    is Entitlement.Pro -> "Pro"
}
