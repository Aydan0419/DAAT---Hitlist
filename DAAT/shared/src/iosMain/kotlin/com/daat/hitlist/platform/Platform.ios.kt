@file:OptIn(ExperimentalForeignApi::class)

package com.daat.hitlist.platform

import androidx.compose.runtime.Composable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970
import platform.posix.memcpy

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun currentTimeZoneId(): String = NSTimeZone.localTimeZone.name

actual fun randomId(): String = NSUUID().UUIDString().replace("-", "").lowercase()

actual val platformName: String = "ios"

actual val supportsAppleSignIn: Boolean = true

/** iOS has no system back button; screens close with their own on-screen controls. */
@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val bytes = ByteArray(size)
    if (size > 0) bytes.usePinned { memcpy(it.addressOf(0), this.bytes, length) }
    return bytes
}
