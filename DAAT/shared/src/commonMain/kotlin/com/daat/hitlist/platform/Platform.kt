package com.daat.hitlist.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.daat.hitlist.model.GeoFix
import kotlinx.coroutines.flow.Flow

/** Milliseconds since the Unix epoch. */
expect fun nowMillis(): Long

/** IANA id of the phone's timezone, e.g. "America/Chicago". */
expect fun currentTimeZoneId(): String

/** A random id safe to use as a Firestore document id and a file name. */
expect fun randomId(): String

/** "android" or "ios", stored with push tokens. */
expect val platformName: String

/** Whether to offer Sign in with Apple (iOS only; the App Store requires it there). */
expect val supportsAppleSignIn: Boolean

interface LocationProvider {
    /** A fresh high-accuracy fix, or null if location is off or permission is missing. */
    suspend fun current(): GeoFix?

    /** Compass heading in degrees (0 = north, clockwise) while collected. */
    fun headings(): Flow<Double>
}

/** Starts a provider's own sign-in screen and signs in to Firebase with the result. */
interface SignInProvider {
    /** Throws [com.daat.hitlist.model.GameException] with SIGN_IN_CANCELLED or SIGN_IN_FAILED. */
    suspend fun signInWithGoogle()
    suspend fun signInWithApple()
}

/** A photo taken with the in-app camera: a compressed JPEG and when the shutter fired. */
class CapturedPhoto(val jpeg: ByteArray, val capturedAt: Long, val headingDeg: Double)

@Composable
expect fun rememberLocationProvider(): LocationProvider

@Composable
expect fun rememberSignInProvider(): SignInProvider

/** Camera, location and notification permission state. */
class PermissionState(val granted: Boolean, val request: () -> Unit)

/** Camera plus precise location: both are needed to snipe. */
@Composable
expect fun rememberHuntPermissions(): PermissionState

/** Asks once for notifications and, where the OS allows, background location. */
@Composable
expect fun rememberBackgroundPermissions(): PermissionState

/**
 * The snipe camera. Shows the camera full-screen and calls [onCaptured] with a
 * JPEG no larger than about 1080 px on the long edge. [heading] is the current
 * compass heading, recorded with the photo at the moment the shutter fires.
 */
@Composable
expect fun SnipeCamera(
    heading: Double,
    onCaptured: (CapturedPhoto) -> Unit,
    onError: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
)

/** Closes the screen on the system back gesture or button. */
@Composable
expect fun BackHandler(enabled: Boolean, onBack: () -> Unit)
