package com.daat.hitlist.data

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
import com.daat.hitlist.model.SnipeStatus
import com.daat.hitlist.model.User
import com.daat.hitlist.platform.currentTimeZoneId
import com.daat.hitlist.platform.platformName
import com.daat.hitlist.platform.randomId
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.Direction
import dev.gitlive.firebase.firestore.DocumentSnapshot
import dev.gitlive.firebase.firestore.FieldValue
import dev.gitlive.firebase.firestore.QuerySnapshot
import dev.gitlive.firebase.firestore.firestore
import dev.gitlive.firebase.functions.FirebaseFunctionsException
import dev.gitlive.firebase.functions.functions
import dev.gitlive.firebase.storage.storage
import dev.gitlive.firebase.storage.storageMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
private data class CreateGroupResult(val groupId: String = "", val inviteCode: String = "")

class FirebaseGameRepository : GameRepository {
    private val auth get() = Firebase.auth
    private val db get() = Firebase.firestore
    private val functions by lazy { Firebase.functions("us-central1") }
    private val storage get() = Firebase.storage
    private val photoUrls = HashMap<String, String>()

    private val uid: String get() = auth.currentUser?.uid ?: throw GameException("NOT_REGISTERED")

    // ── Helpers ─────────────────────────────────────────────────

    /** Runs a Cloud Function and converts its failure into a [GameException] with the server's code. */
    private suspend inline fun <reified T> call(name: String, data: Map<String, Any?>): T = guarded {
        functions.httpsCallable(name).invoke(data).data<T>()
    }

    private suspend fun run(name: String, data: Map<String, Any?> = emptyMap()) {
        guarded { functions.httpsCallable(name).invoke(data) }
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: GameException) {
        throw e
    } catch (e: FirebaseFunctionsException) {
        // The server sends its stable code as the error message, e.g. "TOO_FAR".
        val message = e.message.orEmpty()
        val code = Regex("[A-Z][A-Z_]{2,}").find(message)?.value
        throw GameException(code ?: message.ifBlank { "UNKNOWN" }, e)
    } catch (e: Exception) {
        throw GameException("OFFLINE", e)
    }

    private inline fun <reified T> DocumentSnapshot.decodeOrNull(): T? =
        if (!exists) null else runCatching { data<T>() }.getOrNull()

    private inline fun <reified T> QuerySnapshot.decodeAll(): List<T> =
        documents.mapNotNull { it.decodeOrNull<T>() }

    /** A listener that loses permission (e.g. the player was removed from the group) ends quietly. */
    private fun <T> Flow<List<T>>.orEmptyOnError(): Flow<List<T>> = catch { emit(emptyList()) }

    private fun group(groupId: String) = db.collection("groups").document(groupId)

    // ── Account ─────────────────────────────────────────────────

    override val session: Flow<Session> = auth.authStateChanged.flatMapLatest { firebaseUser ->
        if (firebaseUser == null) {
            flowOf<Session>(Session.SignedOut)
        } else {
            db.collection("users").document(firebaseUser.uid).snapshots
                .map { snap ->
                    snap.decodeOrNull<User>()?.let { Session.SignedIn(it) }
                        ?: Session.NeedsRegistration(firebaseUser.uid, firebaseUser.displayName.orEmpty())
                }
                .catch { emit(Session.NeedsRegistration(firebaseUser.uid, firebaseUser.displayName.orEmpty())) }
        }
    }.distinctUntilChanged()

    override suspend fun signInAsGuest() {
        guarded { auth.signInAnonymously() }
    }

    override suspend fun completeRegistration(name: String, username: String) =
        run("completeRegistration", mapOf("name" to name, "username" to username, "ageConfirmed" to true))

    override suspend fun updateName(name: String) {
        guarded { db.collection("users").document(uid).update("name" to name.trim()) }
    }

    override suspend fun signOut() {
        // Best effort: stop being findable before the session ends.
        runCatching { db.collection("locations").document(uid).delete() }
        guarded { auth.signOut() }
    }

    override suspend fun deleteAccount() {
        run("deleteAccount")
        runCatching { auth.signOut() }
    }

    override suspend fun registerPushToken(token: String) {
        val user = auth.currentUser?.uid ?: return
        if (token.isBlank() || token.contains('/')) return
        runCatching {
            db.collection("devices").document(user).collection("tokens").document(token)
                .set(mapOf("platform" to platformName, "updatedAt" to FieldValue.serverTimestamp))
        }
    }

    // ── Reading game state ──────────────────────────────────────

    override fun groups(uid: String): Flow<List<Group>> =
        db.collection("groups").where { "memberIds" contains uid }.snapshots
            .map { it.decodeAll<Group>().sortedBy { g -> g.createdAt } }
            .orEmptyOnError()

    override fun members(groupId: String): Flow<List<Member>> =
        group(groupId).collection("members").orderBy("score", Direction.DESCENDING).snapshots
            .map { it.decodeAll<Member>() }
            .orEmptyOnError()

    override fun weeklyMembers(groupId: String, weekId: String): Flow<List<Member>> =
        group(groupId).collection("members")
            .where { "weekId" equalTo weekId }
            .orderBy("weeklyScore", Direction.DESCENDING)
            .snapshots
            .map { it.decodeAll<Member>() }
            .orEmptyOnError()

    override fun assignment(groupId: String, uid: String): Flow<Assignment?> =
        group(groupId).collection("assignments").document(uid).snapshots
            .map { it.decodeOrNull<Assignment>() }
            .catch { emit(null) }

    override fun user(uid: String): Flow<User?> =
        db.collection("users").document(uid).snapshots
            .map { it.decodeOrNull<User>() }
            .catch { emit(null) }

    override fun feed(groupId: String, limit: Int): Flow<List<Snipe>> =
        group(groupId).collection("snipes")
            .where { "status" equalTo SnipeStatus.VERIFIED }
            .orderBy("createdAt", Direction.DESCENDING)
            .limit(limit)
            .snapshots
            .map { it.decodeAll<Snipe>() }
            .orEmptyOnError()

    override fun awaitingMyAnswer(groupId: String, uid: String): Flow<List<Snipe>> =
        group(groupId).collection("snipes")
            .where { ("targetUid" equalTo uid) and ("status" equalTo SnipeStatus.PENDING) }
            .snapshots
            .map { it.decodeAll<Snipe>() }
            .orEmptyOnError()

    override fun disputes(groupId: String): Flow<List<Snipe>> =
        group(groupId).collection("snipes")
            .where { "status" equalTo SnipeStatus.DISPUTED }
            .snapshots
            .map { it.decodeAll<Snipe>() }
            .orEmptyOnError()

    override fun blockedUids(uid: String): Flow<Set<String>> =
        db.collection("users").document(uid).collection("blocked").snapshots
            .map { snap -> snap.documents.map { it.id }.toSet() }
            .catch { emit(emptySet()) }

    override suspend fun photoUrl(path: String): String? {
        if (path.isBlank()) return null
        photoUrls[path]?.let { return it }
        return runCatching { storage.reference.child(path).getDownloadUrl() }
            .getOrNull()
            ?.also { photoUrls[path] = it }
    }

    // ── Hunting ─────────────────────────────────────────────────

    override suspend fun reportLocation(fix: GeoFix) {
        val user = auth.currentUser?.uid ?: return
        runCatching {
            db.collection("locations").document(user).set(
                mapOf(
                    "lat" to fix.lat,
                    "lon" to fix.lon,
                    "accuracy" to fix.accuracyM,
                    "updatedAt" to FieldValue.serverTimestamp,
                ),
            )
        }
    }

    override suspend fun proximityHint(groupId: String, fix: GeoFix): ProximityHint =
        call("getProximityHint", mapOf("groupId" to groupId, "lat" to fix.lat, "lon" to fix.lon))

    override suspend fun submitSnipe(
        groupId: String,
        targetUid: String,
        jpeg: ByteArray,
        fix: GeoFix,
        headingDeg: Double,
        capturedAt: Long,
    ): SnipeResult {
        val snipeId = randomId()
        val path = "snipes/$groupId/$uid/$snipeId.jpg"
        try {
            storage.reference.child(path).putData(jpeg.toStorageData(), storageMetadata { contentType = "image/jpeg" })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw GameException("PHOTO_MISSING", e)
        }
        return call(
            "submitSnipe",
            mapOf(
                "groupId" to groupId,
                "snipeId" to snipeId,
                "targetUid" to targetUid,
                "lat" to fix.lat,
                "lon" to fix.lon,
                "accuracy" to fix.accuracyM,
                "heading" to headingDeg,
                "capturedAt" to capturedAt,
            ),
        )
    }

    override suspend fun respondToSnipe(groupId: String, snipeId: String, accept: Boolean) =
        run("respondToSnipe", mapOf("groupId" to groupId, "snipeId" to snipeId, "accept" to accept))

    // ── Feed actions ────────────────────────────────────────────

    override suspend fun setLiked(groupId: String, snipeId: String, liked: Boolean) {
        val change = if (liked) FieldValue.arrayUnion(uid) else FieldValue.arrayRemove(uid)
        guarded { group(groupId).collection("snipes").document(snipeId).update("likedBy" to change) }
    }

    override suspend fun reportSnipe(groupId: String, snipe: Snipe, reason: String) {
        guarded {
            group(groupId).collection("reports").document(randomId()).set(
                mapOf(
                    "reporterUid" to uid,
                    "snipeId" to snipe.id,
                    "reportedUid" to snipe.hunterUid,
                    "reason" to reason.take(500),
                    "createdAt" to FieldValue.serverTimestamp,
                ),
            )
        }
    }

    override suspend fun setBlocked(uid: String, blocked: Boolean) {
        val ref = db.collection("users").document(this.uid).collection("blocked").document(uid)
        guarded { if (blocked) ref.set(mapOf("blocked" to true)) else ref.delete() }
    }

    // ── Groups ──────────────────────────────────────────────────

    override suspend fun createGroup(name: String): String =
        call<CreateGroupResult>("createGroup", mapOf("name" to name, "timezone" to currentTimeZoneId())).groupId

    override suspend fun joinGroup(inviteCode: String) =
        run("joinGroup", mapOf("inviteCode" to inviteCode.trim().uppercase()))

    override suspend fun leaveGroup(groupId: String) = run("leaveGroup", mapOf("groupId" to groupId))

    // ── Admin ───────────────────────────────────────────────────

    override suspend fun updateGroup(groupId: String, name: String?, settings: GroupSettings?) {
        val data = HashMap<String, Any?>()
        data["groupId"] = groupId
        if (name != null) data["name"] = name
        if (settings != null) data["settings"] = settings.toMap()
        run("updateGroup", data)
    }

    override suspend fun kickMember(groupId: String, memberUid: String) =
        run("kickMember", mapOf("groupId" to groupId, "memberUid" to memberUid))

    override suspend fun transferAdmin(groupId: String, memberUid: String) =
        run("transferAdmin", mapOf("groupId" to groupId, "memberUid" to memberUid))

    override suspend fun regenerateInviteCode(groupId: String) =
        run("regenerateInviteCode", mapOf("groupId" to groupId))

    override suspend fun deleteGroup(groupId: String) = run("deleteGroup", mapOf("groupId" to groupId))

    override suspend fun resolveDispute(groupId: String, snipeId: String, approve: Boolean) =
        run("resolveDispute", mapOf("groupId" to groupId, "snipeId" to snipeId, "approve" to approve))

    override suspend fun removeSnipe(groupId: String, snipeId: String) =
        run("removeSnipe", mapOf("groupId" to groupId, "snipeId" to snipeId))
}

/** Plain maps keep the callable payload free of serializer surprises (nulls stay explicit). */
private fun GroupSettings.toMap(): Map<String, Any?> = mapOf(
    "reshuffleHour" to reshuffleHour,
    "timezone" to timezone,
    "maxSnipesPerDay" to maxSnipesPerDay,
    "immunityMinutes" to immunityMinutes,
    "snipedPenalty" to snipedPenalty,
    "hintEnabled" to hintEnabled,
    "maxDistanceM" to maxDistanceM,
    "safeZones" to safeZones.map { z ->
        mapOf(
            "id" to z.id,
            "name" to z.name,
            "lat" to z.lat,
            "lon" to z.lon,
            "radiusM" to z.radiusM,
            "startMinute" to z.startMinute,
            "endMinute" to z.endMinute,
        )
    },
    "quietHours" to quietHours?.let { mapOf("startMinute" to it.startMinute, "endMinute" to it.endMinute) },
)
