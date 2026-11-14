package com.loopandlearn.loopfollow.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.loopandlearn.loopfollow.LoopFollowApplication
import com.loopandlearn.loopfollow.helpers.LogManager
import com.loopandlearn.loopfollow.storage.Observable
import com.loopandlearn.loopfollow.storage.Storage
import java.io.File
import kotlin.math.roundToInt

/** Plays alarm sounds on the alarm stream, so they sound even in silent mode. */
// ANDROID-DEVIATION: Plays on the alarm stream with MediaPlayer, and the volume override sets the alarm stream volume.
object AlarmSound {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var soundFile: SoundFile = SoundFile.FALLBACK
    private var repeatDelay = 0L
    private var volumeBeforeOverride: Int? = null
    private var muted = false

    val isPlaying: Boolean get() = player?.isPlaying == true
    val isMuted: Boolean get() = muted

    private val context: Context get() = LoopFollowApplication.instance
    private val audioManager: AudioManager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun setSoundFile(file: SoundFile) {
        soundFile = if (uri(file) != null) {
            file
        } else {
            LogManager.log(LogManager.Category.ALARM, "AlarmSound - missing sound for ${file.key}; falling back to ${SoundFile.FALLBACK.key}")
            SoundFile.FALLBACK
        }
    }

    private fun uri(file: SoundFile): Uri? = when (file) {
        is SoundFile.Builtin -> {
            val id = context.resources.getIdentifier(SoundCatalog.resourceName(file.name), "raw", context.packageName)
            if (id == 0) null else Uri.parse("android.resource://${context.packageName}/$id")
        }
        is SoundFile.Custom -> CustomSoundStore.file(file.id)?.takeIf(File::exists)?.let { Uri.fromFile(it) }
    }

    private fun newPlayer(): MediaPlayer? {
        val source = uri(soundFile) ?: return null
        return try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(context, source)
                prepare()
            }
        } catch (e: Exception) {
            LogManager.log(LogManager.Category.ALARM, "AlarmSound - unable to play sound; error: $e")
            null
        }
    }

    /** Plays once, for previewing a sound. */
    fun playTest() {
        if (isPlaying) return
        player?.release()
        player = newPlayer()?.apply {
            setOnCompletionListener { stop() }
            start()
        }
    }

    fun play(repeating: Boolean, delay: Int = 0) {
        if (isPlaying) return
        // A finished sound, or one waiting for its next repeat, makes way for this one.
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        repeatDelay = if (repeating && delay > 0) delay * 1000L else 0L
        val p = newPlayer() ?: return
        p.isLooping = repeating && repeatDelay == 0L
        p.setOnCompletionListener { onCompletion() }
        player = p
        muted = false

        val config = Storage.alarmConfiguration.value
        if (config.overrideSystemOutputVolume) {
            if (volumeBeforeOverride == null) volumeBeforeOverride = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            VolumeButtonSnooze.noteOwnVolumeChange()
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, (config.forcedOutputVolume * max).roundToInt().coerceIn(1, max), 0)
        }
        p.start()
        Observable.alarmSoundPlaying.value = true
        if (repeatDelay > 0) {
            LogManager.log(LogManager.Category.ALARM, "AlarmSound - first sound playing immediately, delay (${repeatDelay / 1000}s) will apply between repeats")
        }
    }

    private fun onCompletion() {
        if (repeatDelay > 0) {
            handler.postDelayed({
                if (repeatDelay <= 0) return@postDelayed
                player?.release()
                player = newPlayer()?.apply {
                    setOnCompletionListener { onCompletion() }
                    if (muted) setVolume(0f, 0f)
                    start()
                }
                Observable.alarmSoundPlaying.value = player != null
            }, repeatDelay)
        } else {
            Observable.alarmSoundPlaying.value = false
        }
    }

    fun muteVolume() {
        player?.setVolume(0f, 0f)
        muted = true
        restoreSystemOutputVolume()
    }

    fun unmuteVolume() {
        player?.setVolume(1f, 1f)
        muted = false
    }

    fun stop() {
        Observable.alarmSoundPlaying.value = false
        repeatDelay = 0
        handler.removeCallbacksAndMessages(null)
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        restoreSystemOutputVolume()
    }

    private fun restoreSystemOutputVolume() {
        if (!Storage.alarmConfiguration.value.overrideSystemOutputVolume) return
        volumeBeforeOverride?.let {
            VolumeButtonSnooze.noteOwnVolumeChange()
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, it, 0)
        }
        volumeBeforeOverride = null
    }
}

/** User-imported alarm sounds, stored in the app's files directory. */
object CustomSoundStore {
    private val dir: File get() = File(LoopFollowApplication.instance.filesDir, "CustomSounds").apply { mkdirs() }
    private val names = com.loopandlearn.loopfollow.storage.storageValue("customSoundNames", emptyMap<String, String>())

    fun file(id: String): File? = dir.listFiles()?.firstOrNull { it.nameWithoutExtension == id }

    fun displayName(id: String): String? = names.value[id]

    fun all(): List<Pair<String, String>> = names.value.entries.filter { file(it.key) != null }.map { it.key to it.value }

    /** Copies an audio file in and returns its id. */
    fun import(uri: Uri, displayName: String): String? {
        val id = java.util.UUID.randomUUID().toString().uppercase()
        val ext = displayName.substringAfterLast('.', "m4a")
        val target = File(dir, "$id.$ext")
        val ok = runCatching {
            LoopFollowApplication.instance.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } != null
        }.getOrDefault(false)
        if (!ok) return null
        names.value = names.value + (id to displayName.substringBeforeLast('.'))
        return id
    }

    fun delete(id: String) {
        file(id)?.delete()
        names.value = names.value - id
    }
}
