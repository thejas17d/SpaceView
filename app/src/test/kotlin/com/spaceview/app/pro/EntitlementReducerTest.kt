package com.spaceview.app.pro

import com.spaceview.app.billing.BillingCode
import com.spaceview.app.billing.OwnedPurchase
import com.spaceview.app.billing.PurchaseState
import com.spaceview.app.billing.QueryResult
import com.spaceview.app.billing.TokenHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ARCHITECTURE-v2 §4.4, every row, plus the grace boundaries (§4.10). */
class EntitlementReducerTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val valid: (OwnedPurchase) -> Boolean = { it.signature == "good" }

    private fun purchase(
        state: PurchaseState = PurchaseState.PURCHASED,
        token: String = "t1",
        acknowledged: Boolean = true,
        autoRenewing: Boolean = true,
        suspended: Boolean = false,
        signature: String = "good",
        product: String = ProCatalog.SUBSCRIPTION_ID,
    ) = OwnedPurchase(listOf(product), token, state, acknowledged, autoRenewing, suspended, "{}", signature)

    private fun cached(confirmedAt: Long, product: String = ProCatalog.SUBSCRIPTION_ID) =
        CachedEntitlement(product, TokenHash.of("t1"), confirmedAt, autoRenewing = true, acknowledged = true, catalogVersion = 1)

    @Test
    fun `active verified subscription grants live Pro and caches it`() {
        val r = EntitlementReducer.onQuery(null, QueryResult.Ok(listOf(purchase())), now, valid)
        assertEquals(Entitlement.Pro("pro", true, now, Entitlement.Pro.Source.PLAY_LIVE), r.live)
        assertEquals(now, r.cached!!.confirmedAtMillis)
        assertEquals(TokenHash.of("t1"), r.cached!!.tokenHash)
        assertTrue(r.toAcknowledge.isEmpty())
    }

    @Test
    fun `cache never stores the raw token`() {
        val r = EntitlementReducer.onQuery(null, QueryResult.Ok(listOf(purchase(token = "secret-token"))), now, valid)
        assertFalse(r.cached.toString().contains("secret-token"))
        assertFalse(purchase(token = "secret-token").toString().contains("secret-token"))
    }

    @Test
    fun `unacknowledged purchase is granted and listed for acknowledgement`() {
        val p = purchase(acknowledged = false)
        val r = EntitlementReducer.onQuery(null, QueryResult.Ok(listOf(p)), now, valid)
        assertTrue(r.live is Entitlement.Pro)
        assertEquals(listOf(p), r.toAcknowledge)
        assertFalse(r.cached!!.acknowledged)
    }

    @Test
    fun `pending purchase gives Pending with no Pro and no cache`() {
        val r = EntitlementReducer.onQuery(null, QueryResult.Ok(listOf(purchase(PurchaseState.PENDING))), now, valid)
        assertEquals(Entitlement.Pending("pro"), r.live)
        assertNull(r.cached)
        assertTrue(r.toAcknowledge.isEmpty())
    }

    @Test
    fun `pending then purchased becomes Pro`() {
        val pending = EntitlementReducer.onQuery(null, QueryResult.Ok(listOf(purchase(PurchaseState.PENDING, acknowledged = false))), now, valid)
        val done = EntitlementReducer.onQuery(pending.cached, QueryResult.Ok(listOf(purchase(acknowledged = false))), now + 1000, valid)
        assertTrue(done.live is Entitlement.Pro)
        assertEquals(1, done.toAcknowledge.size)
    }

    @Test
    fun `refunded revoked or expired (absent from the query) is Free at once, even with a fresh cache`() {
        val r = EntitlementReducer.onQuery(cached(now - 1000), QueryResult.Ok(emptyList()), now, valid)
        assertEquals(Entitlement.Free, r.live)
        assertNull(r.cached)
    }

    @Test
    fun `account hold (suspended) never grants`() {
        val r = EntitlementReducer.onQuery(cached(now), QueryResult.Ok(listOf(purchase(suspended = true))), now, valid)
        assertEquals(Entitlement.Free, r.live)
        assertNull(r.cached)
    }

    @Test
    fun `invalid signature never grants and is counted`() {
        val r = EntitlementReducer.onQuery(null, QueryResult.Ok(listOf(purchase(signature = "forged"))), now, valid)
        assertEquals(Entitlement.Free, r.live)
        assertNull(r.cached)
        assertEquals(1, r.rejectedSignatures)
        assertTrue("a forged purchase is never acknowledged", r.toAcknowledge.isEmpty())
    }

    @Test
    fun `forged pending purchase does not show Pending`() {
        val r = EntitlementReducer.onQuery(null, QueryResult.Ok(listOf(purchase(PurchaseState.PENDING, signature = "x"))), now, valid)
        assertEquals(Entitlement.Free, r.live)
    }

    @Test
    fun `other products never grant Pro`() {
        val r = EntitlementReducer.onQuery(null, QueryResult.Ok(listOf(purchase(product = "something_else"))), now, valid)
        assertEquals(Entitlement.Free, r.live)
    }

    @Test
    fun `a renewing subscription is preferred over a prepaid pass`() {
        val r = EntitlementReducer.onQuery(
            null,
            QueryResult.Ok(listOf(purchase(token = "pass", autoRenewing = false), purchase(token = "sub", autoRenewing = true))),
            now,
            valid,
        )
        assertEquals(TokenHash.of("sub"), r.cached!!.tokenHash)
        assertTrue((r.live as Entitlement.Pro).autoRenewing)
    }

    @Test
    fun `failed query keeps the cache and applies grace`() {
        val prev = cached(now - 2 * day)
        val r = EntitlementReducer.onQuery(prev, QueryResult.Failed(BillingCode.NETWORK_ERROR), now, valid)
        assertEquals("kept, with the clock high-water mark moved to now", prev.copy(lastSeenMillis = now), r.cached)
        assertEquals(Entitlement.Pro.Source.CACHE_GRACE, (r.live as Entitlement.Pro).source)
    }

    @Test
    fun `failed query without a cache is Free`() {
        val r = EntitlementReducer.onQuery(null, QueryResult.Failed(BillingCode.BILLING_UNAVAILABLE), now, valid)
        assertEquals(Entitlement.Free, r.live)
    }

    @Test
    fun `grace boundary - 6 d 23 h 59 m is Pro, exactly 7 d is Pro, 7 d plus 1 ms is Free`() {
        val minute = 60_000L
        assertTrue(EntitlementReducer.effective(cached(now - (7 * day - minute)), now) is Entitlement.Pro)
        assertTrue(EntitlementReducer.effective(cached(now - 7 * day), now) is Entitlement.Pro)
        assertEquals(Entitlement.Free, EntitlementReducer.effective(cached(now - 7 * day - 1), now))
    }

    @Test
    fun `clock moved back past the confirmation never extends grace`() {
        // Confirmed "now"; the user winds the clock back a month: the cache is not trusted.
        assertEquals(Entitlement.Free, EntitlementReducer.effective(cached(now), now - 30 * day))
        // Even far beyond the grace window, a rolled-back clock can't revive an expired cache.
        assertEquals(Entitlement.Free, EntitlementReducer.effective(cached(now - 8 * day), now - 30 * day))
    }

    @Test
    fun `small backwards NTP correction keeps Pro`() {
        assertTrue(EntitlementReducer.effective(cached(now), now - 60_000) is Entitlement.Pro)
    }

    @Test
    fun `forward clock jump only shortens grace`() {
        assertEquals(Entitlement.Free, EntitlementReducer.effective(cached(now), now + 8 * day))
    }

    @Test
    fun `cache for an unknown product is ignored`() {
        assertEquals(Entitlement.Free, EntitlementReducer.effective(cached(now, product = "old_product"), now))
    }

    @Test
    fun `purchase update grants Pro`() {
        val r = EntitlementReducer.onPurchaseUpdate(null, Entitlement.Free, listOf(purchase(acknowledged = false)), now, valid)
        assertTrue(r.live is Entitlement.Pro)
        assertEquals(1, r.toAcknowledge.size)
    }

    @Test
    fun `purchase update never takes Pro away`() {
        val current = Entitlement.Pro("pro", true, now, Entitlement.Pro.Source.PLAY_LIVE)
        val pendingTopUp = EntitlementReducer.onPurchaseUpdate(cached(now), current, listOf(purchase(PurchaseState.PENDING, token = "t2")), now, valid)
        assertEquals(current, pendingTopUp.live)
        val forged = EntitlementReducer.onPurchaseUpdate(cached(now), current, listOf(purchase(signature = "bad", token = "t3")), now, valid)
        assertEquals(current, forged.live)
        assertEquals(1, forged.rejectedSignatures)
    }

    @Test
    fun `pending purchase update on Free shows Pending`() {
        val r = EntitlementReducer.onPurchaseUpdate(null, Entitlement.Free, listOf(purchase(PurchaseState.PENDING)), now, valid)
        assertEquals(Entitlement.Pending("pro"), r.live)
    }

    @Test
    fun `same token re-confirmed keeps a known acknowledgement`() {
        val prev = cached(now - day).copy(acknowledged = true)
        val r = EntitlementReducer.onQuery(prev, QueryResult.Ok(listOf(purchase(acknowledged = false))), now, valid)
        assertTrue(r.cached!!.acknowledged)
        assertEquals(now, r.cached!!.confirmedAtMillis)
    }
    // ---- P1 review MAJOR 2: the clock high-water mark ----

    @Test
    fun `rollback inside the grace window cannot extend it`() {
        val confirmed = now
        // Offline: the app saw day 6 (high-water mark moves forward).
        val seenDay6 = EntitlementReducer.onQuery(cached(confirmed), QueryResult.Failed(BillingCode.BILLING_UNAVAILABLE), confirmed + 6 * day, valid)
        assertEquals(confirmed + 6 * day, seenDay6.cached!!.lastSeenMillis)
        assertTrue(seenDay6.live is Entitlement.Pro)
        // The user winds the clock back to day 1, still inside the 7-day window: not trusted.
        assertEquals(Entitlement.Free, EntitlementReducer.effective(seenDay6.cached, confirmed + day))
        // Nor does the high-water mark move back.
        val after = EntitlementReducer.onQuery(seenDay6.cached, QueryResult.Failed(BillingCode.BILLING_UNAVAILABLE), confirmed + day, valid)
        assertEquals(confirmed + 6 * day, after.cached!!.lastSeenMillis)
        assertEquals(Entitlement.Free, after.live)
    }

    @Test
    fun `repeated rollbacks never get past 7 days of observed time`() {
        var c = cached(now)
        var clock = now
        // Use 2 days, roll back 1, use 2, roll back 1, ...: the observed time still grows by a day each round.
        repeat(8) {
            clock += 2 * day
            c = EntitlementReducer.onQuery(c, QueryResult.Failed(BillingCode.NETWORK_ERROR), clock, valid).cached!!
            clock -= day
        }
        assertEquals(now + 9 * day, c.lastSeenMillis)
        assertEquals("past 7 days of observed time", Entitlement.Free, EntitlementReducer.effective(c, c.lastSeenMillis))
        assertEquals("a rolled-back clock below the mark", Entitlement.Free, EntitlementReducer.effective(c, clock))
    }

    @Test
    fun `small NTP correction below the high-water mark keeps Pro`() {
        val c = cached(now).copy(lastSeenMillis = now + 3 * day)
        assertTrue(EntitlementReducer.effective(c, now + 3 * day - 60_000) is Entitlement.Pro)
    }

    @Test
    fun `grace is measured from the high-water mark, not the current clock`() {
        val c = cached(now).copy(lastSeenMillis = now + 7 * day - 1000)
        // A clock slightly behind the mark (within tolerance) doesn't reset the age.
        assertTrue(EntitlementReducer.effective(c, now + 7 * day - 60_000) is Entitlement.Pro)
        assertEquals(Entitlement.Free, EntitlementReducer.effective(c.copy(lastSeenMillis = now + 7 * day + 1), now + 7 * day - 60_000))
    }

    @Test
    fun `a live Play confirmation restarts the mark from the current clock`() {
        val prev = cached(now - 3 * day).copy(lastSeenMillis = now + 30 * day) // a clock once wrongly ahead
        val r = EntitlementReducer.onQuery(prev, QueryResult.Ok(listOf(purchase())), now, valid)
        assertEquals(now, r.cached!!.lastSeenMillis)
        assertTrue(EntitlementReducer.effective(r.cached, now + day) is Entitlement.Pro)
    }

    // ---- P1 review MINOR 9 ----

    @Test
    fun `UNSPECIFIED state never grants`() {
        val r = EntitlementReducer.onQuery(null, QueryResult.Ok(listOf(purchase(PurchaseState.UNSPECIFIED))), now, valid)
        assertEquals(Entitlement.Free, r.live)
        assertNull(r.cached)
        assertTrue(r.toAcknowledge.isEmpty())
    }

    @Test
    fun `purchase update for another product never grants`() {
        val r = EntitlementReducer.onPurchaseUpdate(null, Entitlement.Free, listOf(purchase(product = "credits_3", acknowledged = false)), now, valid)
        assertEquals(Entitlement.Free, r.live)
        assertNull(r.cached)
        assertTrue(r.toAcknowledge.isEmpty())
    }
}
