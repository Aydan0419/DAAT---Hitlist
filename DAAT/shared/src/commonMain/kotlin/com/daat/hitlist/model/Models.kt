package com.daat.hitlist.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Firestore document shapes. They mirror `functions/src/types.ts`.
 * Every field has a default so a document with a missing field still decodes,
 * and anything the server may write as null is nullable.
 */

@Immutable
@Serializable
data class User(
    val id: String = "",
    val name: String = "",
    val username: String = "",
    val photoUrl: String? = null,
    val createdAt: Long = 0,
)

@Immutable
@Serializable
data class SafeZone(
    val id: String = "",
    val name: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val radiusM: Double = 100.0,
    val startMinute: Int? = null,
    val endMinute: Int? = null,
)

@Immutable
@Serializable
data class QuietHours(val startMinute: Int = 0, val endMinute: Int = 0)

@Immutable
@Serializable
data class GroupSettings(
    val reshuffleHour: Int = 8,
    val timezone: String = "America/Chicago",
    val maxSnipesPerDay: Int = 1,
    val immunityMinutes: Int = 60,
    val snipedPenalty: Int = 0,
    val hintEnabled: Boolean = true,
    val maxDistanceM: Double = 50.0,
    val safeZones: List<SafeZone> = emptyList(),
    val quietHours: QuietHours? = null,
)

@Immutable
@Serializable
data class Group(
    val id: String = "",
    val name: String = "",
    val adminId: String = "",
    val inviteCode: String = "",
    val memberIds: List<String> = emptyList(),
    val settings: GroupSettings = GroupSettings(),
    val dayId: String = "",
    val weekId: String = "",
    val nextReshuffleAt: Long = 0,
    val createdAt: Long = 0,
)

@Immutable
@Serializable
data class Member(
    val uid: String = "",
    val name: String = "",
    val username: String = "",
    val photoUrl: String? = null,
    val score: Int = 0,
    val weeklyScore: Int = 0,
    val weekId: String = "",
    val streak: Int = 0,
    val bestStreak: Int = 0,
    val snipes: Int = 0,
    val timesSniped: Int = 0,
    val snipesToday: Int = 0,
    val snipeDayId: String = "",
    val immuneUntil: Long = 0,
    val joinedAt: Long = 0,
    /** The admin has turned off this player's shot sounds in this group. */
    val soundMuted: Boolean = false,
)

@Immutable
@Serializable
data class Assignment(
    val targetUid: String = "",
    val assignedAt: Long = 0,
    val pendingSnipeId: String? = null,
)

object SnipeStatus {
    const val PENDING = "PENDING"
    const val VERIFIED = "VERIFIED"
    const val DISPUTED = "DISPUTED"
    const val REJECTED = "REJECTED"
    const val REMOVED = "REMOVED"
}

@Immutable
@Serializable
data class PointsBreakdown(
    val base: Int = 0,
    val streak: Int = 0,
    val closeQuarters: Int = 0,
    val speed: Int = 0,
    val total: Int = 0,
)

@Immutable
@Serializable
data class Snipe(
    val id: String = "",
    val groupId: String = "",
    val hunterUid: String = "",
    val targetUid: String = "",
    val hunterName: String = "",
    val targetName: String = "",
    val photoPath: String = "",
    val status: String = SnipeStatus.PENDING,
    val points: Int = 0,
    val breakdown: PointsBreakdown? = null,
    val distanceM: Double? = null,
    val capturedAt: Long = 0,
    val createdAt: Long = 0,
    val likedBy: List<String> = emptyList(),
    /** The hunter's recorded shot sound, if they attached one. */
    val soundPath: String? = null,
)

/** A location reading from the phone. */
@Immutable
data class GeoFix(val lat: Double, val lon: Double, val accuracyM: Double)

object ProximityBand {
    const val UNKNOWN = "UNKNOWN"
    const val FAR = "FAR"
    const val NEAR = "NEAR"
    const val CLOSE = "CLOSE"
    const val VERY_CLOSE = "VERY_CLOSE"
}

/** The coarse "how close is my target" hint. The server never sends coordinates. */
@Immutable
@Serializable
data class ProximityHint(
    val band: String = ProximityBand.UNKNOWN,
    val direction: String? = null,
    val targetImmuneUntil: Long = 0,
)

@Immutable
@Serializable
data class SnipeResult(
    val status: String = SnipeStatus.PENDING,
    val points: Int = 0,
    val breakdown: PointsBreakdown? = null,
)

/** Where the signed-in person is in the sign-up flow. */
sealed interface Session {
    data object Loading : Session
    data object SignedOut : Session
    data class NeedsRegistration(val uid: String, val suggestedName: String) : Session
    data class SignedIn(val user: User) : Session
}

/** A game-rule rejection or other failure, carrying the server's stable error code. */
class GameException(val code: String, cause: Throwable? = null) : Exception(code, cause)
