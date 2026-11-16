package com.spaceview.app.billing

import android.content.Context

/**
 * Keeps the debug build's fake purchases across process restarts, so restore, offline grace and process
 * death can be tried on a phone before Play Console is set up. Debug builds only ([FakeBillingGateway]).
 * `shared_prefs/` is outside the backup include list, so it is never backed up.
 */
class FakeBillingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun attach(fake: FakeBillingGateway) {
        fake.restore(load())
        fake.onChanged = ::save
    }

    private fun load(): List<OwnedPurchase> =
        prefs.getString(KEY, null).orEmpty().lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val f = line.split('|')
            if (f.size != 5) return@mapNotNull null
            val state = PurchaseState.entries.firstOrNull { it.name == f[1] } ?: return@mapNotNull null
            OwnedPurchase(listOf(f[4]), f[0], state, f[2].toBoolean(), f[3].toBoolean(), false, "", "")
        }.toList()

    private fun save(purchases: List<OwnedPurchase>) {
        val text = purchases.joinToString("\n") { "${it.purchaseToken}|${it.state.name}|${it.acknowledged}|${it.autoRenewing}|${it.productIds.first()}" }
        prefs.edit().putString(KEY, text).apply()
    }

    private companion object {
        const val FILE = "spaceview_fake_billing"
        const val KEY = "purchases"
    }
}
