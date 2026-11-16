package com.spaceview.app.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.Signature
import kotlin.io.encoding.Base64

/** Vectors signed with a **test** RSA key (§4.10). */
class PurchaseVerifierTest {
    private val keys = FakeBillingGateway.sharedTestKeyPair
    private val publicKey = Base64.Default.encode(keys.public.encoded)
    private val data = """{"orderId":"GPA.1","packageName":"com.spaceview.app","productId":"pro","purchaseState":0}"""

    private fun sign(text: String, pair: java.security.KeyPair = keys): String =
        Base64.Default.encode(
            Signature.getInstance("SHA1withRSA").run {
                initSign(pair.private)
                update(text.toByteArray(Charsets.UTF_8))
                sign()
            },
        )

    @Test fun validSignatureVerifies() = assertTrue(PurchaseVerifier.verify(publicKey, data, sign(data)))

    @Test
    fun oneFlippedByteInTheSignatureFails() {
        val raw = Base64.Default.decode(sign(data))
        raw[10] = (raw[10].toInt() xor 0x01).toByte()
        assertFalse(PurchaseVerifier.verify(publicKey, data, Base64.Default.encode(raw)))
    }

    @Test fun tamperedDataFails() = assertFalse(PurchaseVerifier.verify(publicKey, data.replace("pro", "prx"), sign(data)))

    @Test
    fun wrongKeyFails() {
        val other = FakeBillingGateway.generateKeyPair(1024)
        assertFalse(PurchaseVerifier.verify(publicKey, data, sign(data, other)))
    }

    @Test fun emptySignatureFails() = assertFalse(PurchaseVerifier.verify(publicKey, data, ""))

    @Test fun nonBase64SignatureFails() = assertFalse(PurchaseVerifier.verify(publicKey, data, "not base64 !!!"))

    @Test fun blankKeyFailsClosed() = assertFalse(PurchaseVerifier.verify("", data, sign(data)))

    @Test fun garbageKeyFailsClosed() = assertFalse(PurchaseVerifier.verify("bm90IGEga2V5", data, sign(data)))

    @Test fun emptyDataFails() = assertFalse(PurchaseVerifier.verify(publicKey, "", sign("")))

    @Test
    fun keyWithLineBreaksIsAccepted() {
        val wrapped = publicKey.chunked(64).joinToString("\n")
        assertTrue(PurchaseVerifier.verify(wrapped, data, sign(data)))
    }

    @Test fun unparsableKeyIsNull() = assertNull(PurchaseVerifier.publicKey("%%%"))

    @Test
    fun forKeyChecksOwnedPurchases() {
        val check = PurchaseVerifier.forKey({ PurchaseVerifier.publicKey(publicKey) }, "com.spaceview.app")
        val good = OwnedPurchase(listOf("pro"), "t", PurchaseState.PURCHASED, true, true, false, data, sign(data), "com.spaceview.app")
        assertTrue(check(good))
        assertFalse(check(good.copy(signedData = data + " ")))
    }

    /** P1 review MINOR 4: a genuine, correctly signed purchase of another package never grants. */
    @Test
    fun otherPackageIsRejectedEvenWithAValidSignature() {
        val check = PurchaseVerifier.forKey({ PurchaseVerifier.publicKey(publicKey) }, "com.spaceview.app")
        val other = OwnedPurchase(listOf("pro"), "t", PurchaseState.PURCHASED, true, true, false, data, sign(data), "com.spaceview.app.debug")
        assertFalse(check(other))
        assertFalse("missing package", check(other.copy(packageName = "")))
    }

    @Test
    fun keyIsParsedLazilyOnce() {
        var parses = 0
        val key by lazy { parses++; PurchaseVerifier.publicKey(publicKey) }
        val check = PurchaseVerifier.forKey({ key }, "p")
        assertEquals("not parsed when the verifier is built", 0, parses)
        val p = OwnedPurchase(listOf("pro"), "t", PurchaseState.PURCHASED, true, true, false, data, sign(data), "p")
        assertTrue(check(p))
        assertTrue(check(p))
        assertEquals(1, parses)
    }

    @Test
    fun fakeGatewayPurchasesVerifyWithItsKeyOnly() {
        val fake = FakeBillingGateway()
        val p = fake.grant()
        val check = PurchaseVerifier.forKey({ PurchaseVerifier.publicKey(fake.publicKeyBase64) }, fake.packageName)
        assertTrue(check(p))
        assertFalse(check(fake.grant(validSignature = false)))
        assertFalse("another app's purchase", check(fake.grant(packageName = "com.other")))
    }

    @Test
    fun tokenHashIsSha256HexAndStable() {
        val h = TokenHash.of("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h)
    }
}
