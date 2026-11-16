package com.daat.hitlist

import com.daat.hitlist.model.agoLabel
import com.daat.hitlist.model.bandLabel
import com.daat.hitlist.model.directionLabel
import com.daat.hitlist.model.durationLabel
import com.daat.hitlist.model.errorMessage
import com.daat.hitlist.model.minuteLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessagesTest {
    @Test
    fun knownErrorCodesGetAFriendlyMessage() {
        for (code in listOf("TOO_FAR", "WRONG_ORIENTATION", "TARGET_IMMUNE", "DAILY_LIMIT", "INVALID_CODE", "USERNAME_TAKEN")) {
            val text = errorMessage(code)
            assertTrue(text != code && text.length > 10, "no message for $code")
        }
    }

    @Test
    fun unknownCodesNeverShowRawServerText() {
        assertEquals("Something went wrong. Try again.", errorMessage("SOME_NEW_CODE"))
        assertEquals("Something went wrong. Try again.", errorMessage("UNKNOWN"))
    }

    @Test
    fun durationsRoundUpToTheMinute() {
        assertEquals("1 min", durationLabel(1))
        assertEquals("1 min", durationLabel(60_000))
        assertEquals("2 min", durationLabel(60_001))
        assertEquals("1 h", durationLabel(3_600_000))
        assertEquals("2 h 5 min", durationLabel(2 * 3_600_000 + 5 * 60_000))
        assertEquals("0 min", durationLabel(-5_000))
    }

    @Test
    fun agoLabels() {
        val now = 10L * 24 * 3_600_000
        assertEquals("just now", agoLabel(now, now - 30_000))
        assertEquals("12 min ago", agoLabel(now, now - 12 * 60_000))
        assertEquals("3 h ago", agoLabel(now, now - 3 * 3_600_000))
        assertEquals("2 d ago", agoLabel(now, now - 2L * 24 * 3_600_000))
    }

    @Test
    fun clockTimes() {
        assertEquals("12:00 AM", minuteLabel(0))
        assertEquals("8:05 AM", minuteLabel(8 * 60 + 5))
        assertEquals("12:00 PM", minuteLabel(12 * 60))
        assertEquals("10:30 PM", minuteLabel(22 * 60 + 30))
    }

    @Test
    fun hintLabels() {
        assertEquals("Very close", bandLabel("VERY_CLOSE"))
        assertEquals("Unknown", bandLabel("anything else"))
        assertEquals("north-east", directionLabel("NE"))
        assertNull(directionLabel(null))
    }
}
