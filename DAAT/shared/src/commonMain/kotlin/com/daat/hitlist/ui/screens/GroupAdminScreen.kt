package com.daat.hitlist.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daat.hitlist.model.GroupSettings
import com.daat.hitlist.model.Member
import com.daat.hitlist.model.QuietHours
import com.daat.hitlist.model.SafeZone
import com.daat.hitlist.model.Snipe
import com.daat.hitlist.model.minuteLabel
import com.daat.hitlist.platform.LocationProvider
import com.daat.hitlist.platform.randomId
import com.daat.hitlist.ui.AppViewModel
import com.daat.hitlist.ui.components.Avatar
import com.daat.hitlist.ui.components.ConfirmDialog
import com.daat.hitlist.ui.components.Eyebrow
import com.daat.hitlist.ui.components.Panel
import com.daat.hitlist.ui.components.PrimaryButton
import com.daat.hitlist.ui.components.StoragePhoto
import com.daat.hitlist.ui.components.TextEntryDialog
import com.daat.hitlist.ui.theme.Gold
import kotlinx.coroutines.launch

@Composable
fun GroupAdminScreen(vm: AppViewModel, location: LocationProvider, onClose: () -> Unit) {
    val group by vm.group.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val disputes by vm.disputes.collectAsStateWithLifecycle()
    val me by vm.user.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val g = group

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }
    // Losing admin (or the group) while this screen is open sends you back.
    if (g == null || g.adminId != me?.id) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    // Settings are edited locally and sent in one go with Save.
    var draft by remember(g.id, g.settings) { mutableStateOf(g.settings) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var addingZone by remember { mutableStateOf(false) }
    var kicking by remember { mutableStateOf<Member?>(null) }
    var promoting by remember { mutableStateOf<Member?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(g.name) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = { TextButton(onClick = { renaming = true }) { Text("Rename") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "invite") {
                Panel {
                    Eyebrow("Invite code")
                    SelectionContainer {
                        Text(g.inviteCode, fontSize = 36.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp, color = Gold)
                    }
                    TextButton(onClick = { vm.regenerateInviteCode(g.id) }, enabled = !busy) { Text("Make a new code") }
                }
            }

            if (disputes.isNotEmpty()) {
                item(key = "disputes-title") { Eyebrow("Disputed snipes", color = Gold) }
                items(disputes, key = { "d-${it.id}" }) { DisputeCard(it, vm, busy) }
            }

            item(key = "members-title") { Eyebrow("Players (${members.size})") }
            items(members, key = { "m-${it.uid}" }) { member ->
                MemberRow(
                    member,
                    isMe = member.uid == me?.id,
                    onKick = { kicking = member },
                    onPromote = { promoting = member },
                    onToggleSound = { vm.setSoundMuted(g.id, member.uid, !member.soundMuted) },
                )
            }

            item(key = "settings") {
                Panel {
                    Eyebrow("Game rules")
                    Spacer(Modifier.height(8.dp))
                    Stepper("Daily reshuffle", minuteLabel(draft.reshuffleHour * 60), "Everyone gets a new target at this time") {
                        draft = draft.copy(reshuffleHour = (draft.reshuffleHour + it + 24) % 24)
                    }
                    Stepper("Snipes per day", draft.maxSnipesPerDay.toString(), "At 1, everyone has exactly one hunter. Higher gives a new target after each snipe, so someone can be hunted by two") {
                        draft = draft.copy(maxSnipesPerDay = (draft.maxSnipesPerDay + it).coerceIn(1, 20))
                    }
                    Stepper("Immunity after being sniped", "${draft.immunityMinutes} min", "0 turns it off") {
                        draft = draft.copy(immunityMinutes = (draft.immunityMinutes + it * 15).coerceIn(0, 1440))
                    }
                    Stepper("Penalty for being sniped", "${draft.snipedPenalty} pts", "Points the target loses") {
                        draft = draft.copy(snipedPenalty = (draft.snipedPenalty + it * 25).coerceIn(0, 500))
                    }
                    Stepper("Snipe range", "${draft.maxDistanceM.toInt()} m", "How close the hunter has to be") {
                        draft = draft.copy(maxDistanceM = (draft.maxDistanceM + it * 10).coerceIn(10.0, 100.0))
                    }
                    Toggle("Proximity hint", "Show hunters roughly how close their target is", draft.hintEnabled) {
                        draft = draft.copy(hintEnabled = it)
                    }
                    Toggle("Quiet hours", "No sniping anywhere during these hours", draft.quietHours != null) {
                        draft = draft.copy(quietHours = if (it) QuietHours(22 * 60, 7 * 60) else null)
                    }
                    draft.quietHours?.let { quiet ->
                        Stepper("Quiet from", minuteLabel(quiet.startMinute), null) {
                            draft = draft.copy(quietHours = quiet.copy(startMinute = (quiet.startMinute + it * 60 + 1440) % 1440))
                        }
                        Stepper("Quiet until", minuteLabel(quiet.endMinute), null) {
                            draft = draft.copy(quietHours = quiet.copy(endMinute = (quiet.endMinute + it * 60 + 1440) % 1440))
                        }
                    }
                }
            }

            item(key = "zones") {
                Panel {
                    Eyebrow("Safe zones")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Snipes don't count when the hunter or the target is inside a safe zone. Good for classrooms, workplaces and homes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    draft.safeZones.forEach { zone ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(zone.name, style = MaterialTheme.typography.titleMedium)
                                Text("${zone.radiusM.toInt()} m around where it was added", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { draft = draft.copy(safeZones = draft.safeZones - zone) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove ${zone.name}")
                            }
                        }
                    }
                    TextButton(onClick = { addingZone = true }, enabled = draft.safeZones.size < 20) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Add a safe zone where I'm standing")
                    }
                }
            }

            item(key = "save") {
                PrimaryButton(
                    if (draft == g.settings) "Rules saved" else "Save rules",
                    onClick = { vm.saveSettings(g.id, draft) },
                    enabled = !busy && draft != g.settings,
                )
            }
            item(key = "delete") {
                TextButton(onClick = { deleting = true }) { Text("Delete this group", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (renaming) {
        TextEntryDialog("Rename group", "Group name", "Save", onConfirm = { vm.renameGroup(g.id, it) }, onDismiss = { renaming = false }, initial = g.name, isValid = { it.trim().length >= 2 })
    }
    if (addingZone) {
        TextEntryDialog(
            title = "New safe zone",
            label = "Name, e.g. Library",
            confirm = "Add",
            onConfirm = { name ->
                scope.launch {
                    val fix = location.current()
                    if (fix == null) {
                        vm.showError("NO_LOCATION")
                    } else {
                        draft = draft.copy(safeZones = draft.safeZones + SafeZone(randomId(), name.trim(), fix.lat, fix.lon, 100.0))
                    }
                }
            },
            onDismiss = { addingZone = false },
        )
    }
    if (deleting) {
        ConfirmDialog("Delete ${g.name}?", "Every score and snipe photo in this group is deleted for all players. It can't be undone.", "Delete group", { vm.deleteGroup(g.id, onClose) }, { deleting = false }, destructive = true)
    }
    kicking?.let { m ->
        ConfirmDialog("Remove ${m.name}?", "They lose their score in this group. They can rejoin with the invite code unless you make a new one.", "Remove", { vm.kick(g.id, m.uid) }, { kicking = null }, destructive = true)
    }
    promoting?.let { m ->
        ConfirmDialog("Make ${m.name} the admin?", "You'll stop being the admin of this group.", "Transfer", { vm.transferAdmin(g.id, m.uid, onClose) }, { promoting = null })
    }
}

@Composable
private fun MemberRow(
    member: Member,
    isMe: Boolean,
    onKick: () -> Unit,
    onPromote: () -> Unit,
    onToggleSound: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Avatar(member.name, member.photoUrl, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(member.name + if (isMe) " (you)" else "", style = MaterialTheme.typography.titleMedium)
            Text("@${member.username}  ·  ${member.score} pts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!isMe) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${member.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Make admin") }, onClick = { menu = false; onPromote() })
                    DropdownMenuItem(
                        text = { Text(if (member.soundMuted) "Allow shot sound" else "Mute shot sound") },
                        onClick = { menu = false; onToggleSound() },
                    )
                    DropdownMenuItem(text = { Text("Remove from group") }, onClick = { menu = false; onKick() })
                }
            }
        }
    }
}

@Composable
private fun DisputeCard(snipe: Snipe, vm: AppViewModel, busy: Boolean) {
    Panel {
        Text("${snipe.hunterName} says they sniped ${snipe.targetName}", style = MaterialTheme.typography.titleMedium)
        Text("${snipe.targetName} disputes it. You decide.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        StoragePhoto(snipe.photoPath, vm::photoUrl, Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(14.dp)))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { vm.resolveDispute(snipe, approve = false) },
                enabled = !busy,
                modifier = Modifier.weight(1f).height(54.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Doesn't count") }
            PrimaryButton("It counts", onClick = { vm.resolveDispute(snipe, approve = true) }, Modifier.weight(1f), enabled = !busy)
        }
    }
}

/** A labelled value with minus and plus buttons. [onStep] receives -1 or +1. */
@Composable
private fun Stepper(label: String, value: String, hint: String?, onStep: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { onStep(-1) }) { Text("-", fontSize = 22.sp) }
        Text(value, fontWeight = FontWeight.Bold)
        TextButton(onClick = { onStep(1) }) { Text("+", fontSize = 22.sp) }
    }
}

@Composable
private fun Toggle(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
