package com.daat.hitlist.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.daat.hitlist.data.FakeGameRepository
import com.daat.hitlist.ui.App
import com.daat.hitlist.ui.PushTokens
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Light screens: dark status-bar icons. The system picks a readable navigation bar.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEMO) {
            val demo = FakeGameRepository(now = { System.currentTimeMillis() })
            setContent { App(repository = demo) }
            return
        }

        FirebaseMessaging.getInstance().token.addOnSuccessListener { PushTokens.current.value = it }

        setContent { App() }
    }
}
