package com.spaceview.app.billing

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.io.encoding.Base64

/**
 * Local check that a purchase really comes from Google Play (ADR-004 option B).
 *
 * Play signs each purchase's `originalJson` with the app's licence key (RSA, SHA1withRSA, Play's
 * scheme). A fake billing service cannot produce that signature, so its forged purchases are
 * rejected. It does **not** stop a patched APK (accepted in ADR-004); anything that costs money per
 * use is verified by a server instead.
 *
 * Fails closed: a blank key, an unparsable key or signature, or any crypto error means "not verified".
 * Uses only `java.security`; no custom crypto.
 */
object PurchaseVerifier {
    private const val KEY_ALGORITHM = "RSA"
    private const val SIGNATURE_ALGORITHM = "SHA1withRSA"

    /** Parses a base64 X.509 public key (Play Console → Monetisation setup → Licensing). Null if invalid. */
    fun publicKey(base64PublicKey: String): PublicKey? {
        val clean = base64PublicKey.filterNot(Char::isWhitespace)
        if (clean.isEmpty()) return null
        return try {
            KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(Base64.Default.decode(clean)))
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    /** True only if [signature] (base64) is a valid signature of [signedData] by [key]. */
    fun verify(key: PublicKey?, signedData: String, signature: String): Boolean {
        if (key == null || signedData.isEmpty() || signature.isBlank()) return false
        return try {
            val bytes = Base64.Default.decode(signature.trim())
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(key)
                update(signedData.toByteArray(Charsets.UTF_8))
                verify(bytes)
            }
        } catch (e: IllegalArgumentException) {
            false
        } catch (e: GeneralSecurityException) {
            false
        }
    }

    fun verify(base64PublicKey: String, signedData: String, signature: String): Boolean =
        verify(publicKey(base64PublicKey), signedData, signature)

    /**
     * A verifier for [OwnedPurchase]s: Play's signature with [key], **and** the purchase belongs to
     * [packageName] (P1 review MINOR 4: a genuine purchase of another app signed with the same key, e.g. a
     * debug/release twin, never grants). [key] is a lazy provider so the RSA key is parsed once, on first
     * use, after the first frame (MINOR 6).
     */
    fun forKey(key: () -> PublicKey?, packageName: String): (OwnedPurchase) -> Boolean = { purchase ->
        purchase.packageName == packageName && verify(key(), purchase.signedData, purchase.signature)
    }
}

/** SHA-256 of a purchase token, hex. Stored instead of the token, for idempotent grants. */
object TokenHash {
    fun of(purchaseToken: String): String =
        MessageDigest.getInstance("SHA-256").digest(purchaseToken.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
