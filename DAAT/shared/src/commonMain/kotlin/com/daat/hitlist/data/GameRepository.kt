package com.daat.hitlist.data

import com.daat.hitlist.model.Assignment
import com.daat.hitlist.model.GeoFix
import com.daat.hitlist.model.Group
import com.daat.hitlist.model.GroupSettings
import com.daat.hitlist.model.Member
import com.daat.hitlist.model.ProximityHint
import com.daat.hitlist.model.Session
import com.daat.hitlist.model.Snipe
import com.daat.hitlist.model.SnipeResult
import com.daat.hitlist.model.User
import kotlinx.coroutines.flow.Flow

/**
 * Everything the UI needs from the backend. Reads are live flows; anything
 * that changes scores, targets or membership is a Cloud Function call.
 * Failures throw [com.daat.hitlist.model.GameException].
 */
interface GameRepository {
    // Account
    val session: Flow<Session>
    suspend fun signInAsGuest()
    suspend fun completeRegistration(name: String, username: String)
    suspend fun updateName(name: String)
    suspend fun signOut()
    suspend fun deleteAccount()
    suspend fun registerPushToken(token: String)

    // Reading game state
    fun groups(uid: String): Flow<List<Group>>
    fun members(groupId: String): Flow<List<Member>>
    fun weeklyMembers(groupId: String, weekId: String): Flow<List<Member>>
    fun assignment(groupId: String, uid: String): Flow<Assignment?>
    fun user(uid: String): Flow<User?>
    fun feed(groupId: String, limit: Int): Flow<List<Snipe>>
    /** Snipes on this player that are waiting for them to confirm or dispute. */
    fun awaitingMyAnswer(groupId: String, uid: String): Flow<List<Snipe>>
    fun disputes(groupId: String): Flow<List<Snipe>>
    fun blockedUids(uid: String): Flow<Set<String>>
    suspend fun photoUrl(path: String): String?

    // Hunting
    suspend fun reportLocation(fix: GeoFix)
    suspend fun proximityHint(groupId: String, fix: GeoFix): ProximityHint
    suspend fun submitSnipe(
        groupId: String,
        targetUid: String,
        jpeg: ByteArray,
        fix: GeoFix,
        headingDeg: Double,
        capturedAt: Long,
    ): SnipeResult
    suspend fun respondToSnipe(groupId: String, snipeId: String, accept: Boolean)

    // Feed actions
    suspend fun setLiked(groupId: String, snipeId: String, liked: Boolean)
    suspend fun reportSnipe(groupId: String, snipe: Snipe, reason: String)
    suspend fun setBlocked(uid: String, blocked: Boolean)

    // Groups
    suspend fun createGroup(name: String): String
    suspend fun joinGroup(inviteCode: String)
    suspend fun leaveGroup(groupId: String)

    // Admin
    suspend fun updateGroup(groupId: String, name: String? = null, settings: GroupSettings? = null)
    suspend fun kickMember(groupId: String, memberUid: String)
    suspend fun transferAdmin(groupId: String, memberUid: String)
    suspend fun regenerateInviteCode(groupId: String)
    suspend fun deleteGroup(groupId: String)
    suspend fun resolveDispute(groupId: String, snipeId: String, approve: Boolean)
    suspend fun removeSnipe(groupId: String, snipeId: String)
}
