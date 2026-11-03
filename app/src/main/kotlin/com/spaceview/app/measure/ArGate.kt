package com.spaceview.app.measure

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.spaceview.app.R
import com.spaceview.app.ui.theme.SpaceViewTheme
import com.spaceview.ar.ArAvailability
import com.spaceview.ar.ArCoreSupport
import com.spaceview.ar.InstallOutcome

private enum class GateState { CHECKING, NEED_PERMISSION, PERMISSION_BLOCKED, CHECKING_AR, NEEDS_INSTALL, INSTALLING, DECLINED, UNSUPPORTED, UNKNOWN, READY }

/**
 * Everything that must be true before an AR session is created: camera permission (asked in
 * context with a reason, with a Settings path once it is blocked) and Google Play Services for AR
 * (checked asynchronously, installed or updated through ARCore's own flow). Shows [content] only
 * when both are in place, so SceneView never has to ask for anything itself.
 */
@Composable
fun ArGate(onClose: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var state by rememberSaveable { mutableStateOf(GateState.CHECKING) }
    var attempt by rememberSaveable { mutableIntStateOf(0) }
    var installRequested by rememberSaveable { mutableStateOf(false) }

    fun hasCamera() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        state = when {
            granted -> GateState.CHECKING_AR
            activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA) -> GateState.PERMISSION_BLOCKED
            else -> GateState.NEED_PERMISSION
        }
    }

    // Re-evaluate on every resume: the user may come back from Settings or from the Play Store.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!hasCamera()) {
            if (state != GateState.PERMISSION_BLOCKED) state = GateState.NEED_PERMISSION
        } else if (state == GateState.INSTALLING && installRequested && activity != null) {
            state = when (ArCoreSupport.requestInstall(activity, userRequested = false)) {
                InstallOutcome.INSTALLED -> GateState.READY
                InstallOutcome.INSTALL_REQUESTED -> GateState.INSTALLING
                InstallOutcome.DECLINED -> GateState.DECLINED
                InstallOutcome.UNSUPPORTED -> GateState.UNSUPPORTED
                InstallOutcome.FAILED -> GateState.UNKNOWN
            }
            if (state != GateState.INSTALLING) installRequested = false
        } else if (state == GateState.CHECKING || state == GateState.NEED_PERMISSION || state == GateState.PERMISSION_BLOCKED) {
            state = GateState.CHECKING_AR
        }
    }

    LaunchedEffect(state, attempt) {
        if (state == GateState.CHECKING_AR) {
            state = when (ArCoreSupport.availability(context)) {
                ArAvailability.READY -> GateState.READY
                ArAvailability.NEEDS_INSTALL -> GateState.NEEDS_INSTALL
                ArAvailability.UNSUPPORTED -> GateState.UNSUPPORTED
                ArAvailability.UNKNOWN -> GateState.UNKNOWN
            }
        }
    }

    if (state == GateState.READY) {
        content()
        return
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp), contentAlignment = Alignment.Center) {
            when (state) {
                GateState.CHECKING, GateState.CHECKING_AR, GateState.INSTALLING -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(color = SpaceViewTheme.colors.signalText)
                    Text(stringResource(R.string.arcore_checking), style = MaterialTheme.typography.bodyLarge)
                }
                GateState.NEED_PERMISSION -> GateCard(
                    title = R.string.perm_title, body = R.string.perm_body,
                    action = R.string.perm_allow, onAction = { launcher.launch(Manifest.permission.CAMERA) },
                    onClose = onClose,
                )
                GateState.PERMISSION_BLOCKED -> GateCard(
                    title = R.string.perm_title, body = R.string.perm_denied_body,
                    action = R.string.perm_open_settings, onAction = { openAppSettings(activity) },
                    onClose = onClose,
                )
                GateState.NEEDS_INSTALL -> GateCard(
                    title = R.string.arcore_install_title, body = R.string.arcore_install_body,
                    action = R.string.arcore_install,
                    onAction = {
                        val a = activity ?: return@GateCard
                        state = when (ArCoreSupport.requestInstall(a, userRequested = true)) {
                            InstallOutcome.INSTALLED -> GateState.READY
                            InstallOutcome.INSTALL_REQUESTED -> { installRequested = true; GateState.INSTALLING }
                            InstallOutcome.DECLINED -> GateState.DECLINED
                            InstallOutcome.UNSUPPORTED -> GateState.UNSUPPORTED
                            InstallOutcome.FAILED -> GateState.UNKNOWN
                        }
                    },
                    onClose = onClose,
                )
                GateState.DECLINED -> GateCard(
                    title = R.string.arcore_install_title, body = R.string.arcore_declined,
                    action = R.string.arcore_install, onAction = { state = GateState.NEEDS_INSTALL }, onClose = onClose,
                )
                GateState.UNSUPPORTED -> GateCard(
                    title = null, body = R.string.arcore_unsupported, action = null, onAction = {}, onClose = onClose,
                )
                GateState.UNKNOWN -> GateCard(
                    title = null, body = R.string.arcore_unknown, action = R.string.common_try_again,
                    onAction = { state = GateState.CHECKING_AR; attempt++ }, onClose = onClose,
                )
                GateState.READY -> Unit
            }
        }
    }
}

@Composable
private fun GateCard(
    @StringRes title: Int?,
    @StringRes body: Int,
    @StringRes action: Int?,
    onAction: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (title != null) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Button(
                onClick = onAction,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = SpaceViewTheme.colors.signal, contentColor = SpaceViewTheme.colors.onSignal),
            ) { Text(stringResource(action), style = MaterialTheme.typography.titleMedium) }
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.common_back))
        }
    }
}

private fun openAppSettings(activity: Activity?) {
    activity ?: return
    runCatching {
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)),
        )
    }
}
