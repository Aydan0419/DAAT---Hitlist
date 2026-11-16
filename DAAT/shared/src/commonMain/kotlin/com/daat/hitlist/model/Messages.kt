package com.daat.hitlist.model

/** Turns the server's error codes into something a player can act on. */
fun errorMessage(code: String): String = when (code) {
    "TOO_FAR" -> "Too far away. Get closer to your target and try again."
    "WRONG_ORIENTATION" -> "Your phone wasn't pointing at your target."
    "TARGET_IMMUNE" -> "Your target was just sniped and is immune for now."
    "NOT_YOUR_TARGET" -> "That isn't your current target."
    "NO_TARGET" -> "You don't have a target right now."
    "DAILY_LIMIT" -> "You've hit today's snipe limit. New targets come at the next reshuffle."
    "SNIPE_PENDING" -> "You already have a snipe waiting on your target's answer."
    "PHOTO_TOO_OLD" -> "That photo is too old. Take a new one."
    "PHOTO_MISSING" -> "The photo didn't upload. Check your connection and try again."
    "QUIET_HOURS" -> "Sniping is off during quiet hours."
    "SAFE_ZONE" -> "No sniping here: this is a safe zone."
    "GROUP_TOO_SMALL" -> "The game starts once the group has 3 players."
    "NOT_A_MEMBER" -> "You're not in this group."
    "GROUP_NOT_FOUND" -> "That group no longer exists."
    "GROUP_FULL" -> "That group is full."
    "INVALID_CODE" -> "No group has that invite code."
    "TOO_MANY_GROUPS" -> "You're in the maximum number of groups."
    "NOT_ADMIN" -> "Only the group admin can do that."
    "CANNOT_KICK_SELF" -> "You can't remove yourself. Leave the group instead."
    "USERNAME_TAKEN" -> "That username is taken."
    "ALREADY_REGISTERED" -> "You already have a profile."
    "AGE_NOT_CONFIRMED" -> "You must be 13 or older to play."
    "NOT_REGISTERED" -> "Finish setting up your profile first."
    "ALREADY_RESOLVED" -> "That snipe has already been settled."
    "NOT_YOUR_SNIPE" -> "Only the target can answer that."
    "NO_LOCATION" -> "Couldn't get your location. Check that location is turned on."
    "NO_PERMISSION" -> "Hitlist needs camera and location access to snipe."
    "SIGN_IN_CANCELLED" -> "Sign-in was cancelled."
    "SIGN_IN_FAILED" -> "Sign-in failed. Try again."
    "OFFLINE" -> "You're offline. Try again when you have a connection."
    else -> if (code.any { it.isLowerCase() }) code else "Something went wrong. Try again."
}

fun bandLabel(band: String): String = when (band) {
    ProximityBand.VERY_CLOSE -> "Very close"
    ProximityBand.CLOSE -> "Close"
    ProximityBand.NEAR -> "Near"
    ProximityBand.FAR -> "Far"
    else -> "Unknown"
}

fun bandDetail(band: String): String = when (band) {
    ProximityBand.VERY_CLOSE -> "Within 30 m"
    ProximityBand.CLOSE -> "Within 100 m"
    ProximityBand.NEAR -> "Within 1 km"
    ProximityBand.FAR -> "More than 1 km away"
    else -> "No recent location for your target"
}

fun directionLabel(direction: String?): String? = when (direction) {
    "N" -> "north"
    "NE" -> "north-east"
    "E" -> "east"
    "SE" -> "south-east"
    "S" -> "south"
    "SW" -> "south-west"
    "W" -> "west"
    "NW" -> "north-west"
    else -> null
}

/** "5 min", "2 h 10 min" */
fun durationLabel(ms: Long): String {
    val totalMin = ((ms + 59_999) / 60_000).coerceAtLeast(0)
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "$h h $m min"
        h > 0 -> "$h h"
        else -> "$m min"
    }
}

/** "just now", "12 min ago", "3 h ago", "2 d ago" */
fun agoLabel(now: Long, then: Long): String {
    val min = (now - then) / 60_000
    return when {
        min < 1 -> "just now"
        min < 60 -> "$min min ago"
        min < 60 * 24 -> "${min / 60} h ago"
        else -> "${min / (60 * 24)} d ago"
    }
}

/** Minutes since midnight as "8:05 AM". */
fun minuteLabel(minute: Int): String {
    val h = (minute / 60) % 24
    val m = minute % 60
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "$h12:${m.toString().padStart(2, '0')} ${if (h < 12) "AM" else "PM"}"
}
