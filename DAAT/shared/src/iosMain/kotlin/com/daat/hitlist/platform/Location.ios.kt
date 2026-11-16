@file:OptIn(ExperimentalForeignApi::class)

package com.daat.hitlist.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.daat.hitlist.model.GeoFix
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreLocation.CLHeading
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.Foundation.NSError
import platform.darwin.NSObject
import kotlin.coroutines.resume

private class LocationDelegate : NSObject(), CLLocationManagerDelegateProtocol {
    var onFix: ((GeoFix?) -> Unit)? = null
    var onHeading: ((Double) -> Unit)? = null

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        val location = didUpdateLocations.lastOrNull() as? CLLocation ?: return
        val accuracy = location.horizontalAccuracy
        val fix = location.coordinate.useContents { GeoFix(latitude, longitude, if (accuracy > 0) accuracy else 20.0) }
        onFix?.invoke(fix)
        onFix = null
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        onFix?.invoke(null)
        onFix = null
    }

    override fun locationManager(manager: CLLocationManager, didUpdateHeading: CLHeading) {
        // trueHeading is negative when it isn't known yet; fall back to magnetic north.
        val heading = if (didUpdateHeading.trueHeading >= 0) didUpdateHeading.trueHeading else didUpdateHeading.magneticHeading
        onHeading?.invoke(heading)
    }
}

fun CLLocationManager.isAuthorized(): Boolean {
    val status = authorizationStatus
    return status == kCLAuthorizationStatusAuthorizedWhenInUse || status == kCLAuthorizationStatusAuthorizedAlways
}

/** Must be created and used on the main thread, as Core Location requires. */
class IosLocationProvider : LocationProvider {
    private val delegate = LocationDelegate()
    private val manager = CLLocationManager().also {
        it.delegate = delegate
        it.desiredAccuracy = kCLLocationAccuracyBest
    }

    /** One fix; Core Location powers the GPS down again afterwards. */
    override suspend fun current(): GeoFix? {
        if (!manager.isAuthorized()) return null
        return withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine { cont ->
                delegate.onFix = { fix -> if (cont.isActive) cont.resume(fix) }
                cont.invokeOnCancellation { delegate.onFix = null }
                manager.requestLocation()
            }
        }
    }

    override fun headings(): Flow<Double> = callbackFlow {
        delegate.onHeading = { trySend(it) }
        manager.startUpdatingHeading()
        awaitClose {
            manager.stopUpdatingHeading()
            delegate.onHeading = null
        }
    }.conflate()
}

@Composable
actual fun rememberLocationProvider(): LocationProvider = remember { IosLocationProvider() }
