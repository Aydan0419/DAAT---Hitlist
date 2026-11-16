package com.daat.hitlist.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daat.hitlist.model.Group
import com.daat.hitlist.model.ProximityBand
import com.daat.hitlist.model.Snipe
import com.daat.hitlist.model.agoLabel
import com.daat.hitlist.model.bandDetail
import com.daat.hitlist.model.bandLabel
import com.daat.hitlist.model.directionLabel
import com.daat.hitlist.model.durationLabel
import com.daat.hitlist.platform.nowMillis
import com.daat.hitlist.ui.AppViewModel
import com.daat.hitlist.ui.HuntState
import com.daat.hitlist.ui.components.Avatar
import com.daat.hitlist.ui.components.EmptyState
import com.daat.hitlist.ui.components.Eyebrow
import com.daat.hitlist.ui.components.Panel
import com.daat.hitlist.ui.components.PrimaryButton
import com.daat.hitlist.ui.components.Stat
import com.daat.hitlist.ui.components.StoragePhoto
import com.daat.hitlist.ui.theme.Gold
import com.daat.hitlist.ui.theme.Green
import kotlinx.coroutines.delay

/** A clock that ticks every 15 seconds: enough for "12 min left" style timers. */
@Composable
fun rememberNow(): Long {
    val now by produceState(nowMillis()) {
        while (true) {
            delay(15_000)
            value = nowMillis()
        }
    }
    return now
}

@Composable
fun GroupSwitcher(groups: List<Group>, selectedId: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    if (groups.size < 2) return
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        items(groups, key = { it.id }) { g ->
            FilterChip(selected = g.id == selectedId, onClick = { onSelect(g.id) }, label = { Text(g.name) })
        }
    }
}

@Composable
fun HuntScreen(vm: AppViewModel, onShoot: () -> Unit, onOpenProfile: () -> Unit) {
    val state by vm.hunt.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val now = rememberNow()
    val group = state.group

    if (group == null) {
        EmptyState(
            title = "Join the hunt",
            body = "Hitlist is played in groups of friends. Create a group, or join one with an invite code.",
            modifier = Modifier.fillMaxSize(),
            action = { PrimaryButton("Create or join a group", onClick = onOpenProfile) },
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "header") {
            Column {
                GroupSwitcher(groups, group.id, vm::selectGroup)
                if (groups.size < 2) {
                    Text(group.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
        items(state.awaitingMyAnswer, key = { "await-${it.id}" }) { snipe ->
            AnswerCard(snipe, vm, Modifier.padding(horizontal = 16.dp))
        }
        if (group.memberIds.size < 3) {
            item(key = "waiting") { WaitingForPlayers(group, Modifier.padding(horizontal = 16.dp)) }
        } else {
            item(key = "target") { TargetCard(state, now, onShoot, Modifier.padding(horizontal = 16.dp)) }
            item(key = "me") { MyStatus(state, now, Modifier.padding(horizontal = 16.dp)) }
        }
    }
}

@Composable
private fun WaitingForPlayers(group: Group, modifier: Modifier = Modifier) {
    Panel(modifier) {
        Eyebrow("Waiting for players")
        Spacer(Modifier.height(8.dp))
        Text(
            "The game starts when 3 players have joined. You have ${group.memberIds.size}.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(18.dp))
        Eyebrow("Invite code")
        SelectionContainer {
            Text(group.inviteCode, fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = 6.sp, color = Gold)
        }
        Text(
            "Share this code. Friends enter it under Profile, then Join a group.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TargetCard(state: HuntState, now: Long, onShoot: () -> Unit, modifier: Modifier = Modifier) {
    val group = state.group ?: return
    val assignment = state.assignment
    val target = state.target
    Panel(modifier) {
        Eyebrow("Your target", color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(14.dp))

        if (assignment == null || target == null) {
            val limitHit = (state.me?.let { if (it.snipeDayId == group.dayId) it.snipesToday else 0 } ?: 0) >=
                group.settings.maxSnipesPerDay
            Text(
                if (limitHit) "You've hit today's limit" else "No target right now",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(6.dp))
            val wait = group.nextReshuffleAt - now
            Text(
                if (wait > 0) "New targets in ${durationLabel(wait)}." else "New targets are on the way.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Panel
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(target.name, target.photoUrl, size = 84.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(target.name, style = MaterialTheme.typography.headlineMedium)
                Text("@${target.username}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Assigned ${agoLabel(now, assignment.assignedAt)}" +
                        if (target.streak > 0) "  ·  ${target.streak} streak" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val immuneUntil = maxOf(target.immuneUntil, state.hint?.targetImmuneUntil ?: 0)
        val immune = immuneUntil > now
        val pending = assignment.pendingSnipeId != null

        if (group.settings.hintEnabled) {
            Spacer(Modifier.height(18.dp))
            val band = state.hint?.band ?: ProximityBand.UNKNOWN
            val hot = band == ProximityBand.CLOSE || band == ProximityBand.VERY_CLOSE
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("Proximity")
                    Text(
                        if (state.hint == null) "Locating..." else bandLabel(band),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (hot) Green else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (state.hint == null) "Checking how close you are" else bandDetail(band),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                directionLabel(state.hint?.direction)?.let {
                    Column(horizontalAlignment = Alignment.End) {
                        Eyebrow("Direction")
                        Text(it, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        when {
            pending -> Note("Waiting for ${target.name} to confirm your snipe. If they don't answer within a day, it counts.")
            immune -> Note("${target.name} was just sniped and is immune for ${durationLabel(immuneUntil - now)}.")
            else -> PrimaryButton("Take the shot", onClick = onShoot)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Gold)
}

@Composable
private fun MyStatus(state: HuntState, now: Long, modifier: Modifier = Modifier) {
    val me = state.me ?: return
    val group = state.group ?: return
    Panel(modifier) {
        val immuneLeft = me.immuneUntil - now
        if (immuneLeft > 0) {
            Eyebrow("You're immune", color = Green)
            Text("Nobody can snipe you for ${durationLabel(immuneLeft)}.", style = MaterialTheme.typography.bodyLarge)
        } else {
            Eyebrow("You're being hunted", color = MaterialTheme.colorScheme.primary)
            Text("Someone in ${group.name} has you as their target.", style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val today = if (me.snipeDayId == group.dayId) me.snipesToday else 0
            Stat("Score", me.score.toString())
            Stat("Streak", me.streak.toString())
            Stat("Today", "$today/${group.settings.maxSnipesPerDay}")
            Stat("Sniped", me.timesSniped.toString())
        }
    }
}

/** A snipe the server couldn't check by location: the target says whether it really happened. */
@Composable
private fun AnswerCard(snipe: Snipe, vm: AppViewModel, modifier: Modifier = Modifier) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    Panel(modifier) {
        Eyebrow("Did this happen?", color = Gold)
        Spacer(Modifier.height(6.dp))
        Text("${snipe.hunterName} says they sniped you.", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        StoragePhoto(
            snipe.photoPath,
            vm::photoUrl,
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(14.dp)),
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { vm.respondToSnipe(snipe, accept = false) },
                enabled = !busy,
                modifier = Modifier.weight(1f).height(54.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text("Dispute") }
            PrimaryButton("They got me", onClick = { vm.respondToSnipe(snipe, accept = true) }, Modifier.weight(1f), enabled = !busy)
        }
    }
}
