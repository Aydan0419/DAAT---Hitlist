package com.daat.hitlist.platform

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File

private class AndroidVoiceShots(private val context: Context) : VoiceShots {
    private val dir = File(context.filesDir, "shots").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("voice_shots", Context.MODE_PRIVATE)
    private var recorder: MediaRecorder? = null
    private var recordingSlot = -1
    private var player: MediaPlayer? = null

    private fun file(slot: Int) = File(dir, "slot$slot.m4a")
    private fun temp(slot: Int) = File(dir, "slot$slot.tmp.m4a")

    override fun hasClip(slot: Int): Boolean = file(slot).length() > 0

    override var activeSlot: Int
        get() = prefs.getInt("active", -1)
        set(value) = prefs.edit().putInt("active", value).apply()

    override var playOnShoot: Boolean
        get() = prefs.getBoolean("playOnShoot", true)
        set(value) = prefs.edit().putBoolean("playOnShoot", value).apply()

    override fun startRecording(slot: Int): Boolean {
        stopRecording()
        stopPlayback()
        if (!context.hasPermission(Manifest.permission.RECORD_AUDIO)) return false
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(64_000)
            r.setMaxDuration(VoiceShots.MAX_MS.toInt())
            r.setOutputFile(temp(slot).absolutePath)
            r.prepare()
            r.start()
            recorder = r
            recordingSlot = slot
            true
        } catch (e: Exception) {
            r.release()
            temp(slot).delete()
            false
        }
    }

    override fun stopRecording() {
        val r = recorder ?: return
        val slot = recordingSlot
        recorder = null
        recordingSlot = -1
        // stop() throws if nothing was captured (for example a tap that was too quick).
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            false
        }
        r.release()
        val tmp = temp(slot)
        if (ok && tmp.length() > 0) {
            file(slot).delete()
            tmp.renameTo(file(slot))
            if (activeSlot < 0) activeSlot = slot
        } else {
            tmp.delete()
        }
    }

    override fun delete(slot: Int) {
        stopPlayback()
        file(slot).delete()
        if (activeSlot == slot) activeSlot = (0 until VoiceShots.SLOTS).firstOrNull(::hasClip) ?: -1
    }

    override fun play(slot: Int) {
        if (hasClip(slot)) start(file(slot).absolutePath)
    }

    override fun playUrl(url: String) = start(url)

    private fun start(source: String) {
        stopPlayback()
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            p.setDataSource(source)
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener { if (player === it) stopPlayback() }
            p.setOnErrorListener { mp, _, _ ->
                if (player === mp) stopPlayback()
                true
            }
            player = p
            p.prepareAsync()
        } catch (e: Exception) {
            p.release()
            player = null
        }
    }

    private fun stopPlayback() {
        player?.let { runCatching { it.release() } }
        player = null
    }

    override fun activeClip(): ByteArray? {
        val slot = activeSlot
        if (slot < 0 || !hasClip(slot)) return null
        return runCatching { file(slot).readBytes() }.getOrNull()?.takeIf { it.size <= VoiceShots.MAX_BYTES }
    }

    fun release() {
        stopRecording()
        stopPlayback()
    }
}

@Composable
actual fun rememberVoiceShots(): VoiceShots {
    val context = LocalContext.current.applicationContext
    val shots = remember { AndroidVoiceShots(context) }
    DisposableEffect(Unit) { onDispose { shots.release() } }
    return shots
}
