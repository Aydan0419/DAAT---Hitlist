package com.daat.hitlist.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.daat.hitlist.data.FirebaseGameRepository
import com.daat.hitlist.model.GameException
import com.daat.hitlist.ui.PushTokens
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Things that are simpler to do in Swift with Apple's and Google's own SDKs.
 * The iOS app implements this and registers it at launch.
 * Each completion receives null on success, "CANCELLED" if the person backed out,
 * or any other text on failure.
 */
interface IosNativeBridge {
    fun signInWithGoogle(completion: (String?) -> Unit)
    fun signInWithApple(completion: (String?) -> Unit)
    /** Asks for notification permission and registers for remote notifications. */
    fun requestNotifications()
}

/** The Swift side's entry points into shared code. Call everything here on the main thread. */
object IosBridge {
    var native: IosNativeBridge? = null

    private val scope = MainScope()
    private val location by lazy { IosLocationProvider() }

    fun onPushToken(token: String) {
        PushTokens.current.value = token
    }

    /** Answers the server's silent "where are you?" push with one location fix. */
    fun reportLocation(completion: () -> Unit) {
        scope.launch {
            withTimeoutOrNull(15_000) {
                location.current()?.let { FirebaseGameRepository().reportLocation(it) }
            }
            completion()
        }
    }
}

private class IosSignInProvider : SignInProvider {
    private suspend fun run(start: (IosNativeBridge, (String?) -> Unit) -> Unit) {
        val bridge = IosBridge.native ?: throw GameException("SIGN_IN_FAILED")
        val error = suspendCancellableCoroutine<String?> { cont ->
            start(bridge) { result -> if (cont.isActive) cont.resume(result) }
        }
        when (error) {
            null -> Unit
            "CANCELLED" -> throw GameException("SIGN_IN_CANCELLED")
            else -> throw GameException("SIGN_IN_FAILED")
        }
    }

    override suspend fun signInWithGoogle() = run { bridge, done -> bridge.signInWithGoogle(done) }
    override suspend fun signInWithApple() = run { bridge, done -> bridge.signInWithApple(done) }
}

@Composable
actual fun rememberSignInProvider(): SignInProvider = remember { IosSignInProvider() }
