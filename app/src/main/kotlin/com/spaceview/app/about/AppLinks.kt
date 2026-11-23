package com.spaceview.app.about

import com.spaceview.app.BuildConfig

/**
 * Outward links shown in Help and About (M5).
 *
 * The support address and the privacy-policy URL come from the build (`SUPPORT_EMAIL` / `PRIVACY_POLICY_URL`
 * gradle properties or CI secrets; store builds with `REQUIRE_RELEASE_CONFIG=true` refuse to build without them,
 * app/build.gradle.kts). Until the user provides them, the placeholder constants below are used: they are blank on
 * purpose, so no fake address or URL can ever be shown. With a blank address Help falls back to the Play listing;
 * with a blank URL About shows the in-app privacy summary instead of a link.
 */
object AppLinks {
    // TODO(user): set the real support address (or pass -PSUPPORT_EMAIL / the SUPPORT_EMAIL CI secret).
    const val SUPPORT_EMAIL_PLACEHOLDER = ""

    // TODO(user): set the published privacy-policy URL (or pass -PPRIVACY_POLICY_URL / the CI secret).
    const val PRIVACY_POLICY_URL_PLACEHOLDER = ""

    /** The support address, or null while none is configured. */
    val supportEmail: String?
        get() = resolve(BuildConfig.SUPPORT_EMAIL, SUPPORT_EMAIL_PLACEHOLDER)?.takeIf { isPlausibleEmail(it) }

    /** The privacy-policy URL (https only), or null while none is configured. */
    val privacyPolicyUrl: String?
        get() = resolve(BuildConfig.PRIVACY_POLICY_URL, PRIVACY_POLICY_URL_PLACEHOLDER)?.takeIf { isValidPolicyUrl(it) }

    /** SpaceView's Play Store page (works for contact while no support address is set). */
    fun playListing(packageName: String): String = "https://play.google.com/store/apps/details?id=$packageName"

    /** `mailto:` link for [email]. */
    fun mailto(email: String): String = "mailto:$email"

    internal fun resolve(configured: String, placeholder: String): String? =
        configured.trim().ifEmpty { placeholder.trim() }.ifEmpty { null }

    // These two rules are repeated in app/build.gradle.kts, which fails any build given a value that breaks them
    // (SM1-M5 review MINOR 13). Change both together.
    internal fun isPlausibleEmail(s: String): Boolean =
        s.length in 6..254 && s.count { it == '@' } == 1 && s.substringAfter('@').contains('.') &&
            s.none { it.isWhitespace() || it == '"' }

    internal fun isValidPolicyUrl(s: String): Boolean =
        s.startsWith("https://") && s.length > "https://".length + 3 && s.none { it.isWhitespace() || it == '"' }
}
