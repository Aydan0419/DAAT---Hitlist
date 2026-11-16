package com.daat.hitlist.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.requestRecordPermission
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways

/** Kept alive for the app's lifetime: a permission prompt disappears if its manager is released. */
private val permissionManager by lazy { CLLocationManager() }

private fun cameraGranted(): Boolean =
    AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) == AVAuthorizationStatusAuthorized

/**
 * iOS answers permission prompts asynchronously and without a callback we can
 * easily share, so after asking we simply re-check for the next 30 seconds.
 */
@Composable
private fun rememberPermission(check: () -> Boolean, ask: () -> Unit): PermissionState {
    var tick by remember { mutableIntStateOf(0) }
    val scope: CoroutineScope = rememberCoroutineScope()
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val granted = remember(tick) { check() }
    return PermissionState(granted) {
        ask()
        scope.launch {
            repeat(60) {
                delay(500)
                tick++
            }
        }
    }
}

@Composable
actual fun rememberHuntPermissions(): PermissionState = rememberPermission(
    check = { cameraGranted() && permissionManager.isAuthorized() },
    ask = {
        if (!cameraGranted()) AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { }
        permissionManager.requestWhenInUseAuthorization()
    },
)

@Composable
actual fun rememberBackgroundPermissions(): PermissionState = rememberPermission(
    check = { permissionManager.authorizationStatus == kCLAuthorizationStatusAuthorizedAlways },
    ask = {
        IosBridge.native?.requestNotifications()
        // iOS shows the "Always" prompt only after "While Using" has been granted.
        if (permissionManager.isAuthorized()) permissionManager.requestAlwaysAuthorization()
        else permissionManager.requestWhenInUseAuthorization()
    },
)

@Composable
actual fun rememberMicPermission(): PermissionState = rememberPermission(
    check = { micGranted() },
    ask = { AVAudioSession.sharedInstance().requestRecordPermission { } },
)
