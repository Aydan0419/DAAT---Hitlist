package com.daat.hitlist.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daat.hitlist.platform.rememberSignInProvider
import com.daat.hitlist.platform.supportsAppleSignIn
import com.daat.hitlist.ui.AppViewModel
import com.daat.hitlist.ui.components.Crosshair
import com.daat.hitlist.ui.components.PrimaryButton
import com.daat.hitlist.ui.theme.AutumnBrush

@Composable
fun SignInScreen(vm: AppViewModel) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val signIn = rememberSignInProvider()

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Crosshair(Modifier.size(112.dp))
        Spacer(Modifier.height(24.dp))
        Text("HITLIST", style = MaterialTheme.typography.displaySmall.copy(brush = AutumnBrush))
        Spacer(Modifier.height(8.dp))
        Text(
            "Everyone gets a target.\nEveryone is hunted.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(56.dp))

        if (busy) {
            CircularProgressIndicator()
        } else {
            PrimaryButton("Continue with Google", onClick = { vm.signInWith(signIn::signInWithGoogle) })
            if (supportsAppleSignIn) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { vm.signInWith(signIn::signInWithApple) },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text("Continue with Apple", style = MaterialTheme.typography.titleMedium) }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = vm::signInAsGuest) { Text("Try it as a guest") }
        }
        message?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

private val UsernameRule = Regex("^[a-z0-9_]{3,20}$")

@Composable
fun RegistrationScreen(vm: AppViewModel, suggestedName: String) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(suggestedName) }
    var username by rememberSaveable { mutableStateOf("") }
    var oldEnough by rememberSaveable { mutableStateOf(false) }
    val usernameOk = UsernameRule.matches(username)

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Set up your profile", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "This is how other players will see you on the leaderboard and when you're their target.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(20) },
            label = { Text("Username") },
            prefix = { Text("@") },
            singleLine = true,
            isError = username.isNotEmpty() && !usernameOk,
            supportingText = { Text("3-20 letters, numbers or underscores") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = oldEnough, onCheckedChange = { oldEnough = it })
            Text("I'm 13 or older")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Hitlist shares your location with the game server so snipes can be checked. " +
                "Other players never see where you are, only roughly how close you are to them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton(
            if (busy) "Saving..." else "Start playing",
            onClick = { vm.messageShown(); vm.completeRegistration(name, username) },
            enabled = !busy && name.isNotBlank() && usernameOk && oldEnough,
        )
        TextButton(onClick = vm::signOut, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Use a different account")
        }
    }
}
