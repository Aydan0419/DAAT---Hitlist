package com.daat.hitlist.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.daat.hitlist.model.GeoFix
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

class AndroidLocationProvider(context: Context) : LocationProvider {
    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    override suspend fun current(): GeoFix? = current(Priority.PRIORITY_HIGH_ACCURACY, 10_000)

    /**
     * One fix, then the radio goes quiet again. A fix up to 10 seconds old is
     * reused, so back-to-back requests don't each wake the GPS.
     */
    @SuppressLint("MissingPermission")
    suspend fun current(priority: Int, timeoutMs: Long): GeoFix? {
        if (!appContext.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !appContext.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) return null
        val request = CurrentLocationRequest.Builder()
            .setPriority(priority)
            .setMaxUpdateAgeMillis(10_000)
            .setDurationMillis(timeoutMs)
            .build()
        val cancel = CancellationTokenSource()
        return try {
            withTimeoutOrNull(timeoutMs + 2_000) { client.getCurrentLocation(request, cancel.token).await() }
                ?.let { GeoFix(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy.toDouble() else 20.0) }
        } catch (e: SecurityException) {
            null
        } finally {
            cancel.cancel()
        }
    }

    /**
     * The compass direction the back camera is pointing. The rotation matrix is
     * remapped for a phone held upright, which is how you hold it to take a photo;
     * without that the reading is only right when the phone lies flat.
     */
    override fun headings(): Flow<Double> = callbackFlow {
        val manager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        val rotation = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
                SensorManager.getOrientation(remapped, orientation)
                trySend((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { manager.unregisterListener(listener) }
    }.conflate()
}

@Composable
actual fun rememberLocationProvider(): LocationProvider {
    val context = LocalContext.current
    return remember { AndroidLocationProvider(context) }
}
