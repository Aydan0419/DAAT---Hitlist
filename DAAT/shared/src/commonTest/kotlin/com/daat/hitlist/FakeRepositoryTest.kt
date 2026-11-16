package com.daat.hitlist

import com.daat.hitlist.data.FakeGameRepository
import com.daat.hitlist.model.GameException
import com.daat.hitlist.model.GeoFix
import com.daat.hitlist.model.Session
import com.daat.hitlist.model.SnipeStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The fake backs UI work and previews, so it has to behave like a small, honest game. */
class FakeRepositoryTest {
    private val fix = GeoFix(41.8781, -87.6298, 5.0)

    @Test
    fun aSnipeScoresRotatesTheTargetAndShowsInTheFeed() = runTest {
        val repo = FakeGameRepository()
        val before = repo.members("g1").first().first { it.uid == "me" }
        val target = repo.assignment("g1", "me").first()!!.targetUid

        val result = repo.submitSnipe("g1", target, ByteArray(0), fix, 0.0, 0)

        assertEquals(SnipeStatus.VERIFIED, result.status)
        val after = repo.members("g1").first().first { it.uid == "me" }
        assertEquals(before.score + result.points, after.score)
        assertEquals(before.streak + 1, after.streak)
        assertNotEquals(target, repo.assignment("g1", "me").first()!!.targetUid)
        assertEquals("me", repo.feed("g1", 20).first().first().hunterUid)
        assertEquals(0, repo.members("g1").first().first { it.uid == target }.streak)
    }

    @Test
    fun snipingSomeoneWhoIsNotYourTargetFails() = runTest {
        val error = assertFailsWith<GameException> {
            FakeGameRepository().submitSnipe("g1", "u_ava", ByteArray(0), fix, 0.0, 0)
        }
        assertEquals("NOT_YOUR_TARGET", error.code)
    }

    @Test
    fun likesToggle() = runTest {
        val repo = FakeGameRepository()
        repo.setLiked("g1", "s1", true)
        assertTrue("me" in repo.feed("g1", 20).first().first { it.id == "s1" }.likedBy)
        repo.setLiked("g1", "s1", false)
        assertTrue("me" !in repo.feed("g1", 20).first().first { it.id == "s1" }.likedBy)
    }

    @Test
    fun disputingAPendingSnipeSendsItToTheAdmin() = runTest {
        val repo = FakeGameRepository()
        assertEquals(1, repo.awaitingMyAnswer("g1", "me").first().size)
        repo.respondToSnipe("g1", "s3", accept = false)
        assertEquals(0, repo.awaitingMyAnswer("g1", "me").first().size)
        assertEquals("s3", repo.disputes("g1").first().single().id)
    }

    @Test
    fun registrationFlow() = runTest {
        val repo = FakeGameRepository(startSignedIn = false)
        assertIs<Session.SignedOut>(repo.session.first())
        repo.signInAsGuest()
        assertIs<Session.NeedsRegistration>(repo.session.first())
        assertFailsWith<GameException> { repo.completeRegistration("X", "ava") }
        repo.completeRegistration("New Player", "newbie")
        assertEquals("newbie", (repo.session.first() as Session.SignedIn).user.username)
    }
}
