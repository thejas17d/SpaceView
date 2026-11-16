package com.spaceview.app.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.spaceview.app.common.InstrumentCard

/**
 * Debug builds only (shown when `BuildConfig.FAKE_BILLING`): drives [FakeBillingGateway] so purchase,
 * pending, refund and offline paths can be tried on a phone before Play Console is set up.
 * Deliberately not localised: it never ships in a release build.
 *
 * @param onChanged called after a change that Play would only report on the next query (runs a restore).
 */
@Composable
fun FakeBillingControls(fake: FakeBillingGateway, onChanged: () -> Unit) {
    // Re-read the fake's (non-Compose) state after each tap.
    var tick by remember { mutableIntStateOf(0) }
    val bump = { tick++ }
    InstrumentCard(Modifier.testTag("fake_billing")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Debug: fake billing", style = MaterialTheme.typography.titleSmall)
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
