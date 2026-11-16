package com.daat.hitlist.platform

import androidx.compose.runtime.Composable

/**
 * Recorded "shot sounds": short clips the player records and that play when they
 * snipe someone. The clips are kept on this phone; the chosen one is uploaded
 * with each snipe so the group can hear it in the feed.
 */
interface VoiceShots {
    fun hasClip(slot: Int): Boolean

    /** The slot used for snipes, or -1 for none. */
    var activeSlot: Int

    /** Whether the sound also plays out loud on this phone at the moment of the shot. */
    var playOnShoot: Boolean

    /** Starts recording into [slot]. Returns false if the microphone is unavailable. */
    fun startRecording(slot: Int): Boolean

    /** Finishes the recording. Recording also stops by itself after [MAX_MS]. */
    fun stopRecording()

    fun delete(slot: Int)
    fun play(slot: Int)

    /** Plays a clip from the internet (another player's shot sound). */
    fun playUrl(url: String)

    /** The active clip as an .m4a file, or null if none is chosen. */
    fun activeClip(): ByteArray?

    companion object {
        const val SLOTS = 3
        const val MAX_MS = 3000L

        /** Uploads above this size are refused by the storage rules. */
        const val MAX_BYTES = 300 * 1024

        /**
         * Shot sounds will be a one-time purchase. Until the store purchase is
         * wired up they are open to everyone so they can be tested.
         */
        const val FREE_FOR_TESTING = true
    }
}

@Composable
expect fun rememberVoiceShots(): VoiceShots

/** Microphone permission, needed only to record a shot sound. */
@Composable
expect fun rememberMicPermission(): PermissionState
