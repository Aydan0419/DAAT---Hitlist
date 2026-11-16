package com.daat.hitlist.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daat.hitlist.model.Member
import com.daat.hitlist.ui.AppViewModel
import com.daat.hitlist.ui.components.Avatar
import com.daat.hitlist.ui.components.EmptyState
import com.daat.hitlist.ui.theme.Gold

@Composable
fun LeaderboardScreen(vm: AppViewModel) {
    var weekly by rememberSaveable { mutableStateOf(false) }
    val allTime by vm.members.collectAsStateWithLifecycle()
    val thisWeek by vm.weeklyMembers.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val me by vm.user.collectAsStateWithLifecycle()
    val rows = if (weekly) thisWeek else allTime
    val myRank = rows.indexOfFirst { it.uid == me?.id }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(16.dp))
        GroupSwitcher(groups, group?.id, vm::selectGroup)
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !weekly, onClick = { weekly = false }, label = { Text("All time") })
            FilterChip(selected = weekly, onClick = { weekly = true }, label = { Text("This week") })
            Spacer(Modifier.weight(1f))
            if (myRank >= 0) {
                Text(
                    "You're #${myRank + 1}",
                    modifier = Modifier.align(Alignment.CenterVertically),
                    color = Gold,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (rows.isEmpty()) {
            EmptyState(
                title = if (weekly) "No points this week yet" else "No players yet",
                body = if (weekly) "The weekly board resets every Monday. First snipe takes the lead." else "Join or create a group to see its leaderboard.",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(rows, key = { _, m -> m.uid }, contentType = { _, _ -> "row" }) { index, member ->
                RankRow(index + 1, member, if (weekly) member.weeklyScore else member.score, isMe = member.uid == me?.id)
            }
        }
    }
}

@Composable
private fun RankRow(rank: Int, member: Member, score: Int, isMe: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (isMe) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            rank.toString(),
            modifier = Modifier.width(32.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
            color = if (rank <= 3) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Avatar(member.name, member.photoUrl, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(member.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${member.snipes} snipes" + if (member.streak > 0) "  ·  ${member.streak} streak" else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (isMe) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(score.toString(), style = MaterialTheme.typography.titleLarge)
    }
}
