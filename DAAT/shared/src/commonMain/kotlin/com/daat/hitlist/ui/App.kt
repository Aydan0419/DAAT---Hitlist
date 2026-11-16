package com.daat.hitlist.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.daat.hitlist.data.FirebaseGameRepository
import com.daat.hitlist.data.GameRepository
import com.daat.hitlist.model.Session
import com.daat.hitlist.platform.BackHandler
import com.daat.hitlist.platform.rememberHuntPermissions
import com.daat.hitlist.platform.rememberLocationProvider
import com.daat.hitlist.ui.components.Crosshair
import com.daat.hitlist.ui.screens.FeedScreen
import com.daat.hitlist.ui.screens.GroupAdminScreen
import com.daat.hitlist.ui.screens.HuntScreen
import com.daat.hitlist.ui.screens.LeaderboardScreen
import com.daat.hitlist.ui.screens.ProfileScreen
import com.daat.hitlist.ui.screens.RegistrationScreen
import com.daat.hitlist.ui.screens.SignInScreen
import com.daat.hitlist.ui.screens.SnipeFlow
import com.daat.hitlist.ui.theme.Cream
import com.daat.hitlist.ui.theme.HitlistTheme
import com.daat.hitlist.ui.theme.Ink
import com.daat.hitlist.ui.theme.Sun
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** The device's push token, set by the platform shell as soon as it has one. */
object PushTokens {
    val current = MutableStateFlow<String?>(null)
}

/** How often the app shares its location while it is open. */
private const val LOCATION_INTERVAL_MS = 30_000L

private enum class Tab(val label: String, val icon: ImageVector) {
    Hunt("Hunt", Icons.Filled.Home),
    Feed("Feed", Icons.Filled.List),
    Ranks("Ranks", Icons.Filled.Star),
    Profile("Profile", Icons.Filled.Person),
}

private enum class Overlay { None, Camera, Admin }

@Composable
fun App(repository: GameRepository? = null) {
    val vm: AppViewModel = viewModel { AppViewModel(repository ?: FirebaseGameRepository()) }
    HitlistTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val session by vm.session.collectAsStateWithLifecycle()
            when (val s = session) {
                Session.Loading -> Splash()
                Session.SignedOut -> SignInScreen(vm)
                is Session.NeedsRegistration -> RegistrationScreen(vm, s.suggestedName)
                is Session.SignedIn -> Main(vm)
            }
        }
    }
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Crosshair(Modifier.size(96.dp))
        CircularProgressIndicator(Modifier.size(150.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun Main(vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.Hunt) }
    var overlay by rememberSaveable { mutableStateOf(Overlay.None) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val pushToken by PushTokens.current.collectAsStateWithLifecycle()
    val permissions = rememberHuntPermissions()
    val location = rememberLocationProvider()

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }
    LaunchedEffect(pushToken) { pushToken?.let(vm::registerPushToken) }

    // Share location only while the app is on screen and the player is in a live game.
    val lifecycle = LocalLifecycleOwner.current
    val onHuntTab by rememberUpdatedState(tab == Tab.Hunt && overlay == Overlay.None)
    val inLiveGame = (group?.memberIds?.size ?: 0) >= 3
    LaunchedEffect(permissions.granted, inLiveGame, group?.id) {
        if (!permissions.granted || !inLiveGame) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                location.current()?.let { vm.onLocation(it, refreshHint = onHuntTab) }
                delay(LOCATION_INTERVAL_MS)
            }
        }
    }

    BackHandler(enabled = overlay != Overlay.None || tab != Tab.Hunt) {
        if (overlay != Overlay.None) {
            overlay = Overlay.None
            vm.clearSnipe()
        } else {
            tab = Tab.Hunt
        }
    }

    when (overlay) {
        Overlay.Camera -> SnipeFlow(vm, location, onClose = { overlay = Overlay.None; vm.clearSnipe() })
        Overlay.Admin -> GroupAdminScreen(vm, location, onClose = { overlay = Overlay.None })
        Overlay.None -> Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar(containerColor = Ink, contentColor = Cream) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Ink,
                                selectedTextColor = Sun,
                                indicatorColor = Sun,
                                unselectedIconColor = Cream.copy(alpha = 0.7f),
                                unselectedTextColor = Cream.copy(alpha = 0.7f),
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    Tab.Hunt -> HuntScreen(
                        vm,
                        onShoot = { if (permissions.granted) overlay = Overlay.Camera else permissions.request() },
                        onOpenProfile = { tab = Tab.Profile },
                    )
                    Tab.Feed -> FeedScreen(vm)
                    Tab.Ranks -> LeaderboardScreen(vm)
                    Tab.Profile -> ProfileScreen(vm, onManageGroup = { overlay = Overlay.Admin })
                }
            }
        }
    }
}
