package com.spaceview.ar

import android.app.Activity
import android.content.Context
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Whether this device can run SpaceView's AR right now. */
enum class ArAvailability {
    /** Google Play Services for AR is installed and up to date. */
    READY,

    /** Supported, but Google Play Services for AR is missing or too old: offer the install. */
    NEEDS_INSTALL,

    /** The device is not ARCore-certified. */
    UNSUPPORTED,

    /** The check failed or timed out (often: no network on first check). Offer a retry. */
    UNKNOWN,
}

/** Outcome of [ArCoreSupport.requestInstall]. */
enum class InstallOutcome {
    /** Ready to create a session. */
    INSTALLED,

    /** The Play Store flow was launched; call again from the next onResume. */
    INSTALL_REQUESTED,

    /** The user declined. */
    DECLINED,

    /** Not an ARCore device after all. */
    UNSUPPORTED,

    /** Anything else (Play Store missing, …). */
    FAILED,
}

/**
 * ARCore availability and install helpers (AR Required still needs them: users can sideload or
 * have an outdated Google Play Services for AR, docs/research/android-performance.md §6.5).
 */
object ArCoreSupport {

    /** Asynchronous availability check, safe to call from the main thread (never blocks it). */
    suspend fun availability(context: Context): ArAvailability = suspendCancellableCoroutine { cont ->
        val app = context.applicationContext
        try {
            ArCoreApk.getInstance().checkAvailabilityAsync(app) { a -> if (cont.isActive) cont.resume(map(a)) }
        } catch (e: RuntimeException) {
            if (cont.isActive) cont.resume(ArAvailability.UNKNOWN)
        }
    }

    /**
     * Launches (or continues) the Google Play Services for AR install. Must be called from a
     * resumed activity. [userRequested] is true when the user tapped "Install".
     */
    fun requestInstall(activity: Activity, userRequested: Boolean): InstallOutcome = try {
        when (ArCoreApk.getInstance().requestInstall(activity, userRequested)) {
            ArCoreApk.InstallStatus.INSTALLED -> InstallOutcome.INSTALLED
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> InstallOutcome.INSTALL_REQUESTED
        }
    } catch (e: UnavailableUserDeclinedInstallationException) {
        InstallOutcome.DECLINED
    } catch (e: UnavailableDeviceNotCompatibleException) {
        InstallOutcome.UNSUPPORTED
    } catch (e: Exception) {
        InstallOutcome.FAILED
    }

    internal fun map(a: ArCoreApk.Availability?): ArAvailability = when (a) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArAvailability.READY
        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> ArAvailability.NEEDS_INSTALL
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> ArAvailability.UNSUPPORTED
        else -> ArAvailability.UNKNOWN
    }
}
