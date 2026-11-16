package com.daat.hitlist.platform

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.daat.hitlist.model.GameException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.GoogleAuthProvider
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.CancellationException

/** Google sign-in through Credential Manager. [context] must be an Activity so the sheet can show. */
class AndroidSignInProvider(private val context: Context) : SignInProvider {

    /** The OAuth web client id that the google-services plugin generates from google-services.json. */
    private fun webClientId(): String {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (id == 0) throw GameException("SIGN_IN_FAILED")
        return context.getString(id)
    }

    override suspend fun signInWithGoogle() {
        try {
            val manager = CredentialManager.create(context)
            // Forget the last pick so the account chooser always shows.
            runCatching { manager.clearCredentialState(ClearCredentialStateRequest()) }
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId()).build())
                .build()
            val credential = manager.getCredential(context, request).credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) throw GameException("SIGN_IN_FAILED")
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            Firebase.auth.signInWithCredential(GoogleAuthProvider.credential(idToken, null))
        } catch (e: CancellationException) {
            throw e
        } catch (e: GameException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            throw GameException("SIGN_IN_CANCELLED", e)
        } catch (e: Exception) {
            throw GameException("SIGN_IN_FAILED", e)
        }
    }

    override suspend fun signInWithApple(): Unit = throw GameException("SIGN_IN_FAILED")
}

@Composable
actual fun rememberSignInProvider(): SignInProvider {
    val context = LocalContext.current
    return remember(context) { AndroidSignInProvider(context) }
}
