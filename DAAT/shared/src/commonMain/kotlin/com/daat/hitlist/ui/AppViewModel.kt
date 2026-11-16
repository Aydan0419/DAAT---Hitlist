package com.daat.hitlist.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daat.hitlist.data.GameRepository
import com.daat.hitlist.model.Assignment
import com.daat.hitlist.model.GameException
import com.daat.hitlist.model.GeoFix
import com.daat.hitlist.model.Group
import com.daat.hitlist.model.GroupSettings
import com.daat.hitlist.model.Member
import com.daat.hitlist.model.ProximityHint
import com.daat.hitlist.model.Session
import com.daat.hitlist.model.Snipe
import com.daat.hitlist.model.SnipeResult
import com.daat.hitlist.model.User
import com.daat.hitlist.model.errorMessage
import com.daat.hitlist.platform.CapturedPhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the Hunt tab shows. */
@Immutable
data class HuntState(
    val group: Group? = null,
    val me: Member? = null,
    val assignment: Assignment? = null,
    val target: Member? = null,
    val hint: ProximityHint? = null,
    /** Snipes on me that I need to confirm or dispute. */
    val awaitingMyAnswer: List<Snipe> = emptyList(),
)

sealed interface SnipeUi {
    data object Idle : SnipeUi
    data object Sending : SnipeUi
    data class Done(val result: SnipeResult, val targetName: String) : SnipeUi
    data class Failed(val message: String) : SnipeUi
}

const val FEED_PAGE = 20

class AppViewModel(private val repo: GameRepository) : ViewModel() {

    // Listeners detach five seconds after the last screen stops watching, so a
    // rotation doesn't restart them but a backgrounded app doesn't hold them open.
    private val whileVisible = SharingStarted.WhileSubscribed(5_000)

    val session: StateFlow<Session> = repo.session.stateIn(viewModelScope, whileVisible, Session.Loading)

    private val uid: Flow<String?> = session.map { (it as? Session.SignedIn)?.user?.id }.distinctUntilChanged()
    val user: StateFlow<User?> =
        session.map { (it as? Session.SignedIn)?.user }.stateIn(viewModelScope, whileVisible, null)

    val groups: StateFlow<List<Group>> = uid
        .flatMapLatest { if (it == null) flowOf(emptyList()) else repo.groups(it) }
        .stateIn(viewModelScope, whileVisible, emptyList())

    private val selectedGroupId = MutableStateFlow<String?>(null)

    /** The group every tab is showing: the one picked, or the first one. */
    val group: StateFlow<Group?> = combine(groups, selectedGroupId) { all, picked ->
        all.firstOrNull { it.id == picked } ?: all.firstOrNull()
    }.stateIn(viewModelScope, whileVisible, null)

    private val groupId: Flow<String?> = group.map { it?.id }.distinctUntilChanged()

    private fun <T> perGroup(empty: T, source: (String) -> Flow<T>): Flow<T> =
        groupId.flatMapLatest { if (it == null) flowOf(empty) else source(it) }

    val members: StateFlow<List<Member>> = perGroup(emptyList()) { repo.members(it) }
        .stateIn(viewModelScope, whileVisible, emptyList())

    val weeklyMembers: StateFlow<List<Member>> = group
        .map { it?.let { g -> g.id to g.weekId } }
        .distinctUntilChanged()
        .flatMapLatest { if (it == null) flowOf(emptyList()) else repo.weeklyMembers(it.first, it.second) }
        .stateIn(viewModelScope, whileVisible, emptyList())

    private val assignment: Flow<Assignment?> = combine(groupId, uid) { g, u -> g to u }
        .flatMapLatest { (g, u) -> if (g == null || u == null) flowOf(null) else repo.assignment(g, u) }

    private val awaiting: Flow<List<Snipe>> = combine(groupId, uid) { g, u -> g to u }
        .flatMapLatest { (g, u) -> if (g == null || u == null) flowOf(emptyList()) else repo.awaitingMyAnswer(g, u) }

    private val hint = MutableStateFlow<ProximityHint?>(null)

    val hunt: StateFlow<HuntState> = combine(
        combine(group, members, uid) { g, m, u -> Triple(g, m, u) },
        assignment,
        hint,
        awaiting,
    ) { (g, m, u), a, h, waiting ->
        HuntState(
            group = g,
            me = m.firstOrNull { it.uid == u },
            assignment = a,
            target = a?.let { m.firstOrNull { member -> member.uid == it.targetUid } },
            hint = if (a == null) null else h,
            awaitingMyAnswer = waiting,
        )
    }.stateIn(viewModelScope, whileVisible, HuntState())

    private val feedLimit = MutableStateFlow(FEED_PAGE)
    private val blocked: Flow<Set<String>> = uid.flatMapLatest { if (it == null) flowOf(emptySet()) else repo.blockedUids(it) }

    val feed: StateFlow<List<Snipe>> = combine(groupId, feedLimit) { g, limit -> g to limit }
        .flatMapLatest { (g, limit) -> if (g == null) flowOf(emptyList()) else repo.feed(g, limit) }
        .combine(blocked) { snipes, hidden -> snipes.filterNot { it.hunterUid in hidden } }
        .stateIn(viewModelScope, whileVisible, emptyList())

    val blockedUids: StateFlow<Set<String>> = blocked.stateIn(viewModelScope, whileVisible, emptySet())

    val disputes: StateFlow<List<Snipe>> = perGroup(emptyList()) { repo.disputes(it) }
        .stateIn(viewModelScope, whileVisible, emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** One-off text for a snackbar. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _snipe = MutableStateFlow<SnipeUi>(SnipeUi.Idle)
    val snipe: StateFlow<SnipeUi> = _snipe.asStateFlow()

    fun messageShown() {
        _message.value = null
    }

    fun showError(code: String) {
        _message.value = errorMessage(code)
    }

    /** Runs a backend action with a busy flag, turning failures into a snackbar message. */
    private fun act(success: String? = null, onDone: () -> Unit = {}, block: suspend () -> Unit): Job =
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
                if (success != null) _message.value = success
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: GameException) {
                _message.value = errorMessage(e.code)
            } catch (e: Exception) {
                _message.value = errorMessage("UNKNOWN")
            } finally {
                _busy.value = false
            }
        }

    // ── Account ─────────────────────────────────────────────────

    fun signInAsGuest() = act { repo.signInAsGuest() }

    /** Wraps a platform sign-in (Google, Apple) so its errors surface the same way. */
    fun signInWith(provider: suspend () -> Unit) = act { provider() }

    fun completeRegistration(name: String, username: String) = act { repo.completeRegistration(name.trim(), username.trim()) }
    fun updateName(name: String) = act("Name updated") { repo.updateName(name) }
    fun signOut() = act { repo.signOut() }
    fun deleteAccount() = act { repo.deleteAccount() }
    fun registerPushToken(token: String) {
        viewModelScope.launch { repo.registerPushToken(token) }
    }

    // ── Groups ──────────────────────────────────────────────────

    fun selectGroup(id: String) {
        if (selectedGroupId.value != id) {
            selectedGroupId.value = id
            hint.value = null
            feedLimit.value = FEED_PAGE
        }
    }

    fun createGroup(name: String, onDone: () -> Unit = {}) = act("Group created", onDone) {
        selectedGroupId.value = repo.createGroup(name.trim())
    }

    fun joinGroup(code: String, onDone: () -> Unit = {}) = act("You're in", onDone) { repo.joinGroup(code) }
    fun leaveGroup(id: String, onDone: () -> Unit = {}) = act("You left the group", onDone) { repo.leaveGroup(id) }
    fun renameGroup(id: String, name: String) = act("Group renamed") { repo.updateGroup(id, name = name.trim()) }
    fun saveSettings(id: String, settings: GroupSettings) = act("Settings saved") { repo.updateGroup(id, settings = settings) }
    fun setSoundMuted(id: String, memberUid: String, muted: Boolean) =
        act(if (muted) "Shot sound muted" else "Shot sound allowed") { repo.setSoundMuted(id, memberUid, muted) }

    fun kick(id: String, memberUid: String) = act("Player removed") { repo.kickMember(id, memberUid) }
    fun transferAdmin(id: String, memberUid: String, onDone: () -> Unit = {}) =
        act("Admin transferred", onDone) { repo.transferAdmin(id, memberUid) }
    fun regenerateInviteCode(id: String) = act("New invite code created") { repo.regenerateInviteCode(id) }
    fun deleteGroup(id: String, onDone: () -> Unit = {}) = act("Group deleted", onDone) { repo.deleteGroup(id) }

    // ── Hunting ─────────────────────────────────────────────────

    /**
     * Called with each new location fix while the app is open. Always shares it
     * with the server (so hunters can be verified against it); refreshes the
     * proximity hint only when the Hunt tab is showing and there is a target.
     */
    fun onLocation(fix: GeoFix, refreshHint: Boolean) {
        viewModelScope.launch {
            repo.reportLocation(fix)
            val state = hunt.value
            val g = state.group ?: return@launch
            if (refreshHint && state.assignment != null && g.settings.hintEnabled) {
                runCatching { repo.proximityHint(g.id, fix) }.onSuccess { hint.value = it }
            }
        }
    }

    fun submitSnipe(photo: CapturedPhoto, fix: GeoFix?, sound: ByteArray? = null) {
        val state = hunt.value
        val g = state.group
        val target = state.target
        if (g == null || target == null) {
            _snipe.value = SnipeUi.Failed(errorMessage("NO_TARGET"))
            return
        }
        if (fix == null) {
            _snipe.value = SnipeUi.Failed(errorMessage("NO_LOCATION"))
            return
        }
        _snipe.value = SnipeUi.Sending
        viewModelScope.launch {
            _snipe.value = try {
                val result = repo.submitSnipe(g.id, target.uid, photo.jpeg, fix, photo.headingDeg, photo.capturedAt, sound)
                SnipeUi.Done(result, target.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GameException) {
                SnipeUi.Failed(errorMessage(e.code))
            } catch (e: Exception) {
                SnipeUi.Failed(errorMessage("UNKNOWN"))
            }
        }
    }

    fun snipeFailed(code: String) {
        _snipe.value = SnipeUi.Failed(errorMessage(code))
    }

    fun clearSnipe() {
        _snipe.value = SnipeUi.Idle
    }

    fun respondToSnipe(snipe: Snipe, accept: Boolean) =
        act(if (accept) "Snipe confirmed" else "Sent to your group admin") {
            repo.respondToSnipe(snipe.groupId, snipe.id, accept)
        }

    // ── Feed ────────────────────────────────────────────────────

    fun loadMoreFeed() {
        if (feed.value.size >= feedLimit.value) feedLimit.update { it + FEED_PAGE }
    }

    fun toggleLike(snipe: Snipe) {
        val me = user.value?.id ?: return
        act { repo.setLiked(snipe.groupId, snipe.id, liked = me !in snipe.likedBy) }
    }

    fun report(snipe: Snipe, reason: String) = act("Reported to your group admin") {
        repo.reportSnipe(snipe.groupId, snipe, reason)
    }

    fun setBlocked(uid: String, blocked: Boolean) =
        act(if (blocked) "Player blocked" else "Player unblocked") { repo.setBlocked(uid, blocked) }

    suspend fun photoUrl(path: String): String? = repo.photoUrl(path)

    // ── Admin ───────────────────────────────────────────────────

    fun resolveDispute(snipe: Snipe, approve: Boolean) =
        act(if (approve) "Snipe approved" else "Snipe rejected") { repo.resolveDispute(snipe.groupId, snipe.id, approve) }

    fun removeSnipe(snipe: Snipe) = act("Snipe removed") { repo.removeSnipe(snipe.groupId, snipe.id) }
}
