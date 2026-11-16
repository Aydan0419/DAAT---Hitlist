package com.daat.hitlist.platform

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect

@Composable
private fun rememberPermissions(first: List<String>, then: List<String> = emptyList()): PermissionState {
    val context = LocalContext.current
    val all = first + then
    var granted by remember { mutableStateOf(all.all(context::hasPermission)) }
    // Re-check on return from the system settings screen.
    LifecycleResumeEffect(Unit) {
        granted = all.all(context::hasPermission)
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = all.all(context::hasPermission)
    }
    return PermissionState(granted) {
        // Android only allows asking for background location after foreground location is granted.
        val missingFirst = first.filterNot(context::hasPermission)
        launcher.launch((missingFirst.ifEmpty { then.filterNot(context::hasPermission) }).toTypedArray())
    }
}

@Composable
actual fun rememberHuntPermissions(): PermissionState = rememberPermissions(
    listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION),
)

@Composable
actual fun rememberBackgroundPermissions(): PermissionState = rememberPermissions(
    first = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    },
    then = if (Build.VERSION.SDK_INT >= 29) listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else emptyList(),
)

@Composable
actual fun rememberMicPermission(): PermissionState = rememberPermissions(listOf(Manifest.permission.RECORD_AUDIO))
