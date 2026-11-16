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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daat.hitlist.model.Snipe
import com.daat.hitlist.model.agoLabel
import com.daat.hitlist.platform.rememberVoiceShots
import com.daat.hitlist.ui.AppViewModel
import com.daat.hitlist.ui.components.ConfirmDialog
import com.daat.hitlist.ui.components.EmptyState
import com.daat.hitlist.ui.components.Panel
import com.daat.hitlist.ui.components.StoragePhoto
import com.daat.hitlist.ui.components.TextEntryDialog
import com.daat.hitlist.ui.theme.Green
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@Composable
fun FeedScreen(vm: AppViewModel) {
    val snipes by vm.feed.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val me by vm.user.collectAsStateWithLifecycle()
    val now = rememberNow()
    val listState = rememberLazyListState()

    // Ask for the next page when the last card scrolls into view.
    LaunchedEffect(listState) {
        snapshotFlow {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 2
        }.distinctUntilChanged().filter { it }.collect { vm.loadMoreFeed() }
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(16.dp))
        GroupSwitcher(groups, group?.id, vm::selectGroup)
        if (snipes.isEmpty()) {
            EmptyState(
                title = "No snipes yet",
                body = "When someone in ${group?.name ?: "your group"} gets sniped, the photo shows up here.",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(snipes, key = { it.id }, contentType = { "snipe" }) { snipe ->
                SnipeCard(
                    snipe = snipe,
                    now = now,
                    liked = me?.id in snipe.likedBy,
                    isAdmin = group?.adminId == me?.id,
                    isMine = snipe.hunterUid == me?.id,
                    vm = vm,
                )
            }
        }
    }
}

@Composable
private fun SnipeCard(snipe: Snipe, now: Long, liked: Boolean, isAdmin: Boolean, isMine: Boolean, vm: AppViewModel) {
    var menu by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var blocking by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val voice = rememberVoiceShots()
    val scope = rememberCoroutineScope()

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${snipe.hunterName} sniped ${snipe.targetName}", style = MaterialTheme.typography.titleMedium)
                Text(
                    agoLabel(now, snipe.createdAt) + (snipe.distanceM?.let { "  ·  from ${it.toInt()} m" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!isMine) {
                        DropdownMenuItem(text = { Text("Report") }, onClick = { menu = false; reporting = true })
                        DropdownMenuItem(text = { Text("Block ${snipe.hunterName}") }, onClick = { menu = false; blocking = true })
                    }
                    if (isAdmin) {
                        DropdownMenuItem(text = { Text("Remove snipe") }, onClick = { menu = false; removing = true })
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        StoragePhoto(
            snipe.photoPath,
            vm::photoUrl,
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(14.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.toggleLike(snipe) }) {
                Icon(
                    if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (liked) "Unlike" else "Like",
                    tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(snipe.likedBy.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            snipe.soundPath?.let { path ->
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { scope.launch { vm.photoUrl(path)?.let(voice::playUrl) } }) {
                    Text("Play shot sound")
                }
            }
            Spacer(Modifier.weight(1f))
            Text("+${snipe.points}", fontWeight = FontWeight.Bold, color = Green)
            Spacer(Modifier.width(4.dp))
            Text("pts", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (reporting) {
        TextEntryDialog(
            title = "Report this snipe",
            label = "What's wrong with it?",
            confirm = "Report",
            maxLength = 200,
            onConfirm = { vm.report(snipe, it) },
            onDismiss = { reporting = false },
        )
    }
    if (blocking) {
        ConfirmDialog(
            title = "Block ${snipe.hunterName}?",
            body = "You won't see their snipes in your feed. You can unblock them from your profile.",
            confirm = "Block",
            destructive = true,
            onConfirm = { vm.setBlocked(snipe.hunterUid, true) },
            onDismiss = { blocking = false },
        )
    }
    if (removing) {
        ConfirmDialog(
            title = "Remove this snipe?",
            body = "The photo is deleted and ${snipe.hunterName} loses the ${snipe.points} points.",
            confirm = "Remove",
            destructive = true,
            onConfirm = { vm.removeSnipe(snipe) },
            onDismiss = { removing = false },
        )
    }
}
