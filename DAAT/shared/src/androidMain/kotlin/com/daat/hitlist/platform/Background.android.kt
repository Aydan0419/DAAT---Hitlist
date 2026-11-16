package com.daat.hitlist.platform

import android.content.Context
import com.daat.hitlist.data.FirebaseGameRepository
import com.google.android.gms.location.Priority
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Answers the server's "where are you?" push. Takes one balanced-power fix
 * and uploads it; nothing keeps running afterwards.
 */
object BackgroundLocation {
    suspend fun reportOnce(context: Context) {
        withTimeoutOrNull(15_000) {
            val fix = AndroidLocationProvider(context).current(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 8_000)
                ?: return@withTimeoutOrNull
            FirebaseGameRepository().reportLocation(fix)
        }
    }
}
