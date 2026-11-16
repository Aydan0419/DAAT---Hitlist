@file:OptIn(ExperimentalForeignApi::class)

package com.daat.hitlist.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.*
import platform.AVFoundation.*
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.*
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile

internal fun micGranted(): Boolean =
    AVAudioSession.sharedInstance().recordPermission == AVAudioSessionRecordPermissionGranted

private class IosVoiceShots : VoiceShots {
    private val defaults = NSUserDefaults.standardUserDefaults
    private val files = NSFileManager.defaultManager
    private val dir: String =
        (NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String) + "/shots"

    // Players must be kept alive while they play, or the sound is cut off.
    private var recorder: AVAudioRecorder? = null
    private var recordingSlot = -1
    private var player: AVAudioPlayer? = null
    private var remotePlayer: AVPlayer? = null

    init {
        files.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    }

    private fun path(slot: Int) = "$dir/slot$slot.m4a"
    private fun temp(slot: Int) = "$dir/slot$slot.tmp.m4a"

    override fun hasClip(slot: Int): Boolean = files.fileExistsAtPath(path(slot))

    override var activeSlot: Int
        get() = if (defaults.objectForKey(KEY_ACTIVE) == null) -1 else defaults.integerForKey(KEY_ACTIVE).toInt()
        set(value) = defaults.setInteger(value.toLong(), forKey = KEY_ACTIVE)

    override var playOnShoot: Boolean
        get() = !defaults.boolForKey(KEY_SILENT)
        set(value) = defaults.setBool(!value, forKey = KEY_SILENT)

    override fun startRecording(slot: Int): Boolean {
        stopRecording()
        stopPlayback()
        if (!micGranted()) return false
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayAndRecord, error = null)
        session.setActive(true, error = null)
        files.removeItemAtPath(temp(slot), error = null)
        val settings = mapOf<Any?, Any?>(
            AVFormatIDKey to kAudioFormatMPEG4AAC.toInt(),
            AVSampleRateKey to 44_100.0,
            AVNumberOfChannelsKey to 1,
        )
        val r = AVAudioRecorder(uRL = NSURL.fileURLWithPath(temp(slot)), settings = settings, error = null)
        if (!r.recordForDuration(VoiceShots.MAX_MS / 1000.0)) return false
        recorder = r
        recordingSlot = slot
        return true
    }

    override fun stopRecording() {
        val r = recorder ?: return
        val slot = recordingSlot
        recorder = null
        recordingSlot = -1
        r.stop()
        if (files.fileExistsAtPath(temp(slot))) {
            files.removeItemAtPath(path(slot), error = null)
            files.moveItemAtPath(temp(slot), toPath = path(slot), error = null)
            if (activeSlot < 0 && hasClip(slot)) activeSlot = slot
        }
    }

    override fun delete(slot: Int) {
        stopPlayback()
        files.removeItemAtPath(path(slot), error = null)
        if (activeSlot == slot) activeSlot = (0 until VoiceShots.SLOTS).firstOrNull(::hasClip) ?: -1
    }

    private fun useSpeaker() {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, error = null)
        session.setActive(true, error = null)
    }

    override fun play(slot: Int) {
        if (!hasClip(slot)) return
        stopPlayback()
        useSpeaker()
        val p = AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath(path(slot)), error = null)
        player = p
        p.play()
    }

    override fun playUrl(url: String) {
        val target = NSURL.URLWithString(url) ?: return
        stopPlayback()
        useSpeaker()
        val p = AVPlayer(uRL = target)
        remotePlayer = p
        p.play()
    }

    private fun stopPlayback() {
        player?.stop()
        player = null
        remotePlayer?.pause()
        remotePlayer = null
    }

    override fun activeClip(): ByteArray? {
        val slot = activeSlot
        if (slot < 0 || !hasClip(slot)) return null
        return NSData.dataWithContentsOfFile(path(slot))?.toByteArray()?.takeIf { it.size <= VoiceShots.MAX_BYTES }
    }

    fun release() {
        stopRecording()
        stopPlayback()
    }

    private companion object {
        const val KEY_ACTIVE = "voiceShotActive"
        const val KEY_SILENT = "voiceShotSilent"
    }
}

@Composable
actual fun rememberVoiceShots(): VoiceShots {
    val shots = remember { IosVoiceShots() }
    DisposableEffect(Unit) { onDispose { shots.release() } }
    return shots
}
