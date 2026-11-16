package com.daat.hitlist.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daat.hitlist.model.Group
import com.daat.hitlist.platform.VoiceShots
import com.daat.hitlist.platform.rememberBackgroundPermissions
import com.daat.hitlist.platform.rememberMicPermission
import com.daat.hitlist.platform.rememberVoiceShots
import com.daat.hitlist.ui.AppViewModel
import com.daat.hitlist.ui.components.Avatar
import com.daat.hitlist.ui.components.ConfirmDialog
import com.daat.hitlist.ui.components.Eyebrow
import com.daat.hitlist.ui.components.Panel
import com.daat.hitlist.ui.components.Stat
import com.daat.hitlist.ui.components.TextEntryDialog
import com.daat.hitlist.ui.theme.Gold
import kotlinx.coroutines.delay

private enum class ProfileDialog { None, EditName, Create, Join, SignOut, Delete }

@Composable
fun ProfileScreen(vm: AppViewModel, onManageGroup: () -> Unit) {
    val user by vm.user.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val selected by vm.group.collectAsStateWithLifecycle()
    val hunt by vm.hunt.collectAsStateWithLifecycle()
    val blocked by vm.blockedUids.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val background = rememberBackgroundPermissions()
    var dialog by remember { mutableStateOf(ProfileDialog.None) }
    var leaving by remember { mutableStateOf<Group?>(null) }
    val me = user ?: return

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "me") {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(me.name, me.photoUrl, size = 64.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(me.name, style = MaterialTheme.typography.titleLarge)
                        Text("@${me.username}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { dialog = ProfileDialog.EditName }) { Text("Edit") }
                }
                hunt.me?.let { stats ->
                    Spacer(Modifier.height(18.dp))
                    Eyebrow("In ${selected?.name.orEmpty()}")
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("Score", stats.score.toString())
                        Stat("Snipes", stats.snipes.toString())
                        Stat("Best streak", stats.bestStreak.toString())
                        Stat("Sniped", stats.timesSniped.toString())
                    }
                }
            }
        }

        item(key = "groups-title") { Eyebrow("Your groups", Modifier.padding(top = 6.dp, start = 4.dp)) }
        items(groups, key = { it.id }) { group ->
            GroupRow(
                group = group,
                selected = group.id == selected?.id,
                isAdmin = group.adminId == me.id,
                onSelect = { vm.selectGroup(group.id) },
                onManage = { vm.selectGroup(group.id); onManageGroup() },
                onLeave = { leaving = group },
            )
        }
        item(key = "group-actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { dialog = ProfileDialog.Join },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Join a group") }
                OutlinedButton(
                    onClick = { dialog = ProfileDialog.Create },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Create a group") }
            }
        }

        item(key = "shot-sound") { ShotSoundPanel() }

        if (!background.granted) {
            item(key = "background") {
                Panel {
                    Eyebrow("Fair play", color = Gold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Turn on notifications and background location so the game can confirm snipes on you " +
                            "when Hitlist is closed. Without it, you'll be asked to confirm each one yourself.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Your location is checked only when someone snipes you or is hunting you. Players never see it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = background.request) { Text("Turn on") }
                }
            }
        }

        if (blocked.isNotEmpty()) {
            item(key = "blocked") {
                Panel {
                    Eyebrow("Blocked players")
                    blocked.forEach { uid ->
                        val name = members.firstOrNull { it.uid == uid }?.name ?: "A player in another group"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, Modifier.weight(1f))
                            TextButton(onClick = { vm.setBlocked(uid, false) }) { Text("Unblock") }
                        }
                    }
                }
            }
        }

        item(key = "account") {
            Column {
                TextButton(onClick = { dialog = ProfileDialog.SignOut }) { Text("Sign out") }
                TextButton(onClick = { dialog = ProfileDialog.Delete }) {
                    Text("Delete my account", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    val close = { dialog = ProfileDialog.None }
    when (dialog) {
        ProfileDialog.None -> Unit
        ProfileDialog.EditName -> TextEntryDialog("Change your name", "Name", "Save", onConfirm = vm::updateName, onDismiss = close, initial = me.name)
        ProfileDialog.Create -> TextEntryDialog(
            "Create a group", "Group name", "Create",
            onConfirm = { vm.createGroup(it) }, onDismiss = close, isValid = { it.trim().length >= 2 },
        )
        ProfileDialog.Join -> TextEntryDialog(
            "Join a group", "6-letter invite code", "Join",
            onConfirm = { vm.joinGroup(it) }, onDismiss = close, maxLength = 6, allCaps = true, isValid = { it.length == 6 },
        )
        ProfileDialog.SignOut -> ConfirmDialog("Sign out?", "You'll stop sharing your location until you sign back in.", "Sign out", vm::signOut, close)
        ProfileDialog.Delete -> ConfirmDialog(
            "Delete your account?",
            "This permanently deletes your profile, scores, and every snipe photo you're in, and removes you from all groups. It can't be undone.",
            "Delete account", vm::deleteAccount, close, destructive = true,
        )
    }
    leaving?.let { group ->
        ConfirmDialog(
            title = "Leave ${group.name}?",
            body = "Your score in this group is deleted. " +
                if (group.adminId == me.id) "Admin passes to the longest-standing member." else "You can rejoin with the invite code.",
            confirm = "Leave",
            destructive = true,
            onConfirm = { vm.leaveGroup(group.id) },
            onDismiss = { leaving = null },
        )
    }
}

@Composable
private fun GroupRow(
    group: Group,
    selected: Boolean,
    isAdmin: Boolean,
    onSelect: () -> Unit,
    onManage: () -> Unit,
    onLeave: () -> Unit,
) {
    Panel(Modifier.clickable(onClick = onSelect)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${group.memberIds.size} players" + (if (isAdmin) "  ·  you're the admin" else "") + (if (selected) "  ·  showing" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SelectionContainer {
                Text(group.inviteCode, fontWeight = FontWeight.Bold, color = Gold, letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing)
            }
        }
        Row {
            if (isAdmin) TextButton(onClick = onManage) { Text("Manage") }
            TextButton(onClick = onLeave) { Text("Leave", color = MaterialTheme.colorScheme.error) }
        }
    }
}


/** Record up to three short sounds and pick the one that plays when you snipe someone. */
@Composable
private fun ShotSoundPanel() {
    val voice = rememberVoiceShots()
    val mic = rememberMicPermission()
    // The clips live in files, so bump this to re-read them after every change.
    var version by remember { mutableIntStateOf(0) }
    var recording by remember { mutableIntStateOf(-1) }
    var failed by remember { mutableStateOf(false) }

    // Recording stops by itself at the time limit.
    LaunchedEffect(recording) {
        if (recording >= 0) {
            delay(VoiceShots.MAX_MS)
            voice.stopRecording()
            recording = -1
            version++
        }
    }

    Panel {
        Eyebrow("Shot sound", color = Gold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Record up to 3 seconds: say \"pew\", anything. It plays when you take a shot, and your group hears it on your snipes in the feed.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (VoiceShots.FREE_FOR_TESTING) {
            Text(
                "Free while we test. This will be a one-time unlock.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))

        key(version) {
            val active = voice.activeSlot
            for (slot in 0 until VoiceShots.SLOTS) {
                val has = voice.hasClip(slot)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = has && active == slot,
                        enabled = has,
                        onClick = { voice.activeSlot = slot; version++ },
                    )
                    Text(
                        when {
                            recording == slot -> "Recording…"
                            has -> "Sound ${slot + 1}"
                            else -> "Empty"
                        },
                        modifier = Modifier.weight(1f),
                        color = if (has || recording == slot) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (recording == slot) {
                        TextButton(onClick = { voice.stopRecording(); recording = -1; version++ }) { Text("Stop") }
                    } else {
                        if (has) {
                            TextButton(onClick = { voice.play(slot) }, enabled = recording < 0) { Text("Play") }
                            TextButton(onClick = { voice.delete(slot); version++ }, enabled = recording < 0) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        TextButton(
                            enabled = recording < 0,
                            onClick = {
                                if (!mic.granted) {
                                    mic.request()
                                } else if (voice.startRecording(slot)) {
                                    failed = false
                                    recording = slot
                                } else {
                                    failed = true
                                }
                            },
                        ) { Text(if (has) "Redo" else "Record") }
                    }
                }
            }
            if (failed) {
                Text(
                    "Couldn't start recording. Check that Hitlist is allowed to use the microphone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Play out loud when I shoot", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = voice.playOnShoot, onCheckedChange = { voice.playOnShoot = it; version++ })
            }
        }
    }
}
