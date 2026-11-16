package com.daat.hitlist.android

import com.daat.hitlist.platform.BackgroundLocation
import com.daat.hitlist.ui.PushTokens
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

/**
 * Receives pushes from the game server. Visible notifications ("You got sniped")
 * are shown by the system; the silent "locate" push is answered here with one
 * location fix so a snipe on this player can be verified while the app is closed.
 */
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        PushTokens.current.value = token
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "locate") {
            // This runs on a background thread with a short time budget, so block until the fix is sent.
            runBlocking { BackgroundLocation.reportOnce(applicationContext) }
        }
    }
}
