package com.daat.hitlist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daat.hitlist.model.PointsBreakdown
import com.daat.hitlist.model.SnipeStatus
import com.daat.hitlist.platform.LocationProvider
import com.daat.hitlist.platform.SnipeCamera
import com.daat.hitlist.ui.AppViewModel
import com.daat.hitlist.ui.SnipeUi
import com.daat.hitlist.ui.components.Crosshair
import com.daat.hitlist.ui.components.Eyebrow
import com.daat.hitlist.ui.components.Panel
import com.daat.hitlist.ui.components.PrimaryButton
import com.daat.hitlist.ui.theme.Gold
import com.daat.hitlist.ui.theme.Green
import kotlinx.coroutines.launch

/** Camera, then "checking", then the result. */
@Composable
fun SnipeFlow(vm: AppViewModel, location: LocationProvider, onClose: () -> Unit) {
    val state by vm.snipe.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val headings = remember(location) { location.headings() }
    val heading by headings.collectAsState(0.0)

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (val s = state) {
            SnipeUi.Idle -> SnipeCamera(
                heading = heading,
                onCaptured = { photo -> scope.launch { vm.submitSnipe(photo, location.current()) } },
                onError = vm::snipeFailed,
                onClose = onClose,
                modifier = Modifier.fillMaxSize(),
            )
            SnipeUi.Sending -> Centered {
                CircularProgressIndicator(Modifier.size(56.dp))
                Spacer(Modifier.height(24.dp))
                Text("Checking your shot", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Confirming you were close enough and pointed the right way. This can take up to 20 seconds.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            is SnipeUi.Done -> Centered {
                if (s.result.status == SnipeStatus.VERIFIED) {
                    Crosshair(Modifier.size(96.dp), color = Green)
                    Spacer(Modifier.height(24.dp))
                    Text("Target sniped", style = MaterialTheme.typography.headlineMedium)
                    Text("+${s.result.points} points", style = MaterialTheme.typography.displaySmall, color = Green)
                    s.result.breakdown?.let {
                        Spacer(Modifier.height(20.dp))
                        Breakdown(it)
                    }
                } else {
                    Crosshair(Modifier.size(96.dp), color = Gold)
                    Spacer(Modifier.height(24.dp))
                    Text("Waiting on ${s.targetName}", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "We couldn't get a fresh location for ${s.targetName}, so they've been asked to confirm. " +
                            "You'll get your points when they do, or after a day if they don't answer.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(32.dp))
                PrimaryButton("Done", onClick = onClose)
            }
            is SnipeUi.Failed -> Centered {
                Crosshair(Modifier.size(96.dp), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(24.dp))
                Text("Missed", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))
                Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                PrimaryButton("Try again", onClick = vm::clearSnipe)
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun Breakdown(b: PointsBreakdown) {
    Panel {
        Eyebrow("How you scored")
        Spacer(Modifier.height(8.dp))
        Line("Snipe", b.base)
        if (b.streak > 0) Line("Streak bonus", b.streak)
        if (b.closeQuarters > 0) Line("Close quarters (under 5 m)", b.closeQuarters)
        if (b.speed > 0) Line("Speed (within 2 hours)", b.speed)
    }
}

@Composable
private fun Line(label: String, points: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text("+$points", color = Green)
    }
}
