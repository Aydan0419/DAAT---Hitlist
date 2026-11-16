package com.daat.hitlist.platform

import androidx.compose.runtime.Composable
import java.util.TimeZone
import java.util.UUID

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun currentTimeZoneId(): String = TimeZone.getDefault().id

actual fun randomId(): String = UUID.randomUUID().toString().replace("-", "")

actual val platformName: String = "android"

actual val supportsAppleSignIn: Boolean = false

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled, onBack)
}
