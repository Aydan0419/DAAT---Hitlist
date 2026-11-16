package com.daat.hitlist.data

import com.daat.hitlist.model.Assignment
import com.daat.hitlist.model.GameException
import com.daat.hitlist.model.GeoFix
import com.daat.hitlist.model.Group
import com.daat.hitlist.model.GroupSettings
import com.daat.hitlist.model.Member
import com.daat.hitlist.model.PointsBreakdown
import com.daat.hitlist.model.ProximityBand
import com.daat.hitlist.model.ProximityHint
import com.daat.hitlist.model.Session
import com.daat.hitlist.model.Snipe
import com.daat.hitlist.model.SnipeResult
import com.daat.hitlist.model.SnipeStatus
import com.daat.hitlist.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * An in-memory stand-in for the backend, so the whole UI can run with no
 * Firebase project: previews, UI work and tests. It applies simplified rules;
 * the real ones live in Cloud Functions.
 */
class FakeGameRepository(
    private val now: () -> Long = { 0L },
    startSignedIn: Boolean = true,
) : GameRepository {
    private val me = User(id = "me", name = "You", username = "you")
    private val others = listOf(
        User("u_ava", "Ava Chen", "ava"),
        User("u_ben", "Ben Ortiz", "benito"),
        User("u_cam", "Cam Reyes", "cam_r"),
        User("u_dee", "Dee Park", "deepark"),
    )
    private val allUsers = (others + me).associateBy { it.id }

    private val sessionState = MutableStateFlow<Session>(
        if (startSignedIn) Session.SignedIn(me) else Session.SignedOut,
    )
    private val groupState = MutableStateFlow(
        listOf(
            Group(
                id = "g1",
                name = "Dorm 4 West",
                adminId = "me",
                inviteCode = "HNTR42",
                memberIds = allUsers.keys.toList(),
                dayId = "2026-11-16",
                weekId = "2026-11-16",
            ),
        ),
    )
    private val memberState = MutableStateFlow(
        mapOf(
            "g1" to listOf(
                member(others[0], 1250, 4), member(others[1], 900, 0), member(me, 675, 2),
                member(others[2], 450, 1), member(others[3], 100, 0),
            ),
        ),
    )
    private val assignmentState = MutableStateFlow<Map<String, Assignment?>>(
        mapOf("g1" to Assignment(targetUid = "u_ben", assignedAt = 0)),
    )
    private val snipeState = MutableStateFlow(
        listOf(
            Snipe("s1", "g1", "u_ava", "u_cam", "Ava Chen", "Cam Reyes", "", SnipeStatus.VERIFIED, 225,
                PointsBreakdown(100, 75, 50, 0, 225), 3.2, 0, 0, listOf("u_ben")),
            Snipe("s2", "g1", "u_cam", "u_dee", "Cam Reyes", "Dee Park", "", SnipeStatus.VERIFIED, 100,
                PointsBreakdown(100, 0, 0, 0, 100), 31.0, 0, 0),
            Snipe("s3", "g1", "u_dee", "me", "Dee Park", "You", "", SnipeStatus.PENDING),
        ),
    )
    private val blockedState = MutableStateFlow(emptySet<String>())
    private var counter = 100

    private fun member(u: User, score: Int, streak: Int) = Member(
        uid = u.id, name = u.name, username = u.username, score = score, weeklyScore = score / 2,
        weekId = "2026-11-16", streak = streak, bestStreak = streak + 1, snipes = score / 125,
    )

    private fun updateMember(groupId: String, uid: String, change: (Member) -> Member) =
        memberState.update { all ->
            all + (groupId to all[groupId].orEmpty().map { if (it.uid == uid) change(it) else it }
                .sortedByDescending { it.score })
        }

    private fun updateSnipe(id: String, change: (Snipe) -> Snipe) =
        snipeState.update { list -> list.map { if (it.id == id) change(it) else it } }

    override val session: Flow<Session> = sessionState

    override suspend fun signInAsGuest() {
        sessionState.value = Session.NeedsRegistration("me", "")
    }

    override suspend fun completeRegistration(name: String, username: String) {
        if (others.any { it.username == username.lowercase() }) throw GameException("USERNAME_TAKEN")
        sessionState.value = Session.SignedIn(me.copy(name = name, username = username.lowercase()))
    }

    override suspend fun updateName(name: String) {
        sessionState.update { if (it is Session.SignedIn) Session.SignedIn(it.user.copy(name = name)) else it }
    }

    override suspend fun signOut() {
        sessionState.value = Session.SignedOut
    }

    override suspend fun deleteAccount() = signOut()
    override suspend fun registerPushToken(token: String) = Unit

    override fun groups(uid: String): Flow<List<Group>> = groupState
    override fun members(groupId: String): Flow<List<Member>> = memberState.map { it[groupId].orEmpty() }
    override fun weeklyMembers(groupId: String, weekId: String): Flow<List<Member>> =
        memberState.map { all -> all[groupId].orEmpty().sortedByDescending { it.weeklyScore } }
    override fun assignment(groupId: String, uid: String): Flow<Assignment?> = assignmentState.map { it[groupId] }
    override fun user(uid: String): Flow<User?> = flowOf(allUsers[uid])
    override fun feed(groupId: String, limit: Int): Flow<List<Snipe>> =
        snipeState.map { list -> list.filter { it.groupId == groupId && it.status == SnipeStatus.VERIFIED }.take(limit) }
    override fun awaitingMyAnswer(groupId: String, uid: String): Flow<List<Snipe>> =
        snipeState.map { list -> list.filter { it.targetUid == uid && it.status == SnipeStatus.PENDING } }
    override fun disputes(groupId: String): Flow<List<Snipe>> =
        snipeState.map { list -> list.filter { it.status == SnipeStatus.DISPUTED } }
    override fun blockedUids(uid: String): Flow<Set<String>> = blockedState
    override suspend fun photoUrl(path: String): String? = null

    override suspend fun reportLocation(fix: GeoFix) = Unit
    override suspend fun proximityHint(groupId: String, fix: GeoFix) =
        ProximityHint(band = ProximityBand.NEAR, direction = "NE")

    override suspend fun submitSnipe(
        groupId: String,
        targetUid: String,
        jpeg: ByteArray,
        fix: GeoFix,
        headingDeg: Double,
        capturedAt: Long,
    ): SnipeResult {
        val assignment = assignmentState.value[groupId] ?: throw GameException("NO_TARGET")
        if (assignment.targetUid != targetUid) throw GameException("NOT_YOUR_TARGET")
        val hunter = memberState.value[groupId].orEmpty().first { it.uid == "me" }
        val breakdown = PointsBreakdown(base = 100, streak = hunter.streak * 25, total = 100 + hunter.streak * 25)
        updateMember(groupId, "me") {
            it.copy(score = it.score + breakdown.total, streak = it.streak + 1, snipes = it.snipes + 1)
        }
        updateMember(groupId, targetUid) { it.copy(streak = 0, immuneUntil = now() + 3_600_000) }
        val target = allUsers[targetUid]
        snipeState.update { list ->
            listOf(
                Snipe("s${counter++}", groupId, "me", targetUid, hunter.name, target?.name.orEmpty(), "",
                    SnipeStatus.VERIFIED, breakdown.total, breakdown, 12.0, capturedAt, now()),
            ) + list
        }
        val next = others.map { it.id }.first { it != targetUid }
        assignmentState.update { it + (groupId to Assignment(next, now())) }
        return SnipeResult(SnipeStatus.VERIFIED, breakdown.total, breakdown)
    }

    override suspend fun respondToSnipe(groupId: String, snipeId: String, accept: Boolean) =
        updateSnipe(snipeId) { it.copy(status = if (accept) SnipeStatus.VERIFIED else SnipeStatus.DISPUTED) }

    override suspend fun setLiked(groupId: String, snipeId: String, liked: Boolean) =
        updateSnipe(snipeId) { it.copy(likedBy = if (liked) it.likedBy + "me" else it.likedBy - "me") }

    override suspend fun reportSnipe(groupId: String, snipe: Snipe, reason: String) = Unit

    override suspend fun setBlocked(uid: String, blocked: Boolean) =
        blockedState.update { if (blocked) it + uid else it - uid }

    override suspend fun createGroup(name: String): String {
        val id = "g${counter++}"
        groupState.update { it + Group(id = id, name = name, adminId = "me", inviteCode = "NEWGRP", memberIds = listOf("me")) }
        memberState.update { it + (id to listOf(member(me, 0, 0))) }
        return id
    }

    override suspend fun joinGroup(inviteCode: String) {
        if (inviteCode.length != 6) throw GameException("INVALID_CODE")
        createGroup("Group $inviteCode")
    }

    override suspend fun leaveGroup(groupId: String) = groupState.update { list -> list.filterNot { it.id == groupId } }

    override suspend fun updateGroup(groupId: String, name: String?, settings: GroupSettings?) =
        groupState.update { list ->
            list.map { if (it.id == groupId) it.copy(name = name ?: it.name, settings = settings ?: it.settings) else it }
        }

    override suspend fun kickMember(groupId: String, memberUid: String) {
        groupState.update { list ->
            list.map { if (it.id == groupId) it.copy(memberIds = it.memberIds - memberUid) else it }
        }
        memberState.update { all -> all + (groupId to all[groupId].orEmpty().filterNot { it.uid == memberUid }) }
    }

    override suspend fun transferAdmin(groupId: String, memberUid: String) =
        groupState.update { list -> list.map { if (it.id == groupId) it.copy(adminId = memberUid) else it } }

    override suspend fun regenerateInviteCode(groupId: String) =
        groupState.update { list -> list.map { if (it.id == groupId) it.copy(inviteCode = "CODE${counter++ % 100}") else it } }

    override suspend fun deleteGroup(groupId: String) = leaveGroup(groupId)

    override suspend fun resolveDispute(groupId: String, snipeId: String, approve: Boolean) =
        updateSnipe(snipeId) { it.copy(status = if (approve) SnipeStatus.VERIFIED else SnipeStatus.REJECTED) }

    override suspend fun removeSnipe(groupId: String, snipeId: String) =
        updateSnipe(snipeId) { it.copy(status = SnipeStatus.REMOVED) }
}
