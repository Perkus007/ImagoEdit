package com.perkus.imagoedit.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.os.VibrationEffect
import android.os.Vibrator
import com.perkus.imagoedit.R

private const val VOLUME = 0.2f // громкость, 0..1
private const val SILENCE_FRAMES = 4800

// Same attributes + fast flag for the pool and the silent track: both land on the same output
private val ATTRS = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

/** SoundPool: MediaPlayer.create blocks the main thread; sounds preload in init (play before load is silent). The output sleeps after ~3 s of silence and waking
it delays a sound, so while visible with sounds on a looping silent track keeps it awake */
object Feedback {
    private lateinit var settings: AppSettings
    private lateinit var vibrator: Vibrator
    private lateinit var pool: SoundPool
    private lateinit var silence: AudioTrack
    private var tapSound = 0
    private var doneSound = 0
    private val tapVibe = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK) // doesn't blur on rapid taps
    private val doneVibe = VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)

    fun init(ctx: Context) {
        if (::pool.isInitialized) return
        val app = ctx.applicationContext
        settings = AppSettings(app)
        vibrator = app.getSystemService(Vibrator::class.java)
        pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(ATTRS).build()
        tapSound = pool.load(app, R.raw.tap, 1)
        doneSound = pool.load(app, R.raw.export1, 1)
        // Zeros looped from shared memory; no sample rate → the output's native one, so the fast path isn't denied
        silence = AudioTrack.Builder()
            .setAudioAttributes(ATTRS)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(SILENCE_FRAMES * 2)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .apply { write(ShortArray(SILENCE_FRAMES), 0, SILENCE_FRAMES); setLoopPoints(0, SILENCE_FRAMES, -1) }
    }

    var sounds: Boolean
        get() = settings.sounds
        set(v) { settings.sounds = v; keepAwake() }

    var visible = false
        set(v) { field = v; keepAwake() }

    private fun keepAwake() { if (visible && sounds) silence.play() else silence.pause() }

    fun tap() = play(tapSound, tapVibe)

    fun done() = play(doneSound, doneVibe)

    private fun play(sound: Int, vibe: VibrationEffect) {
        if (sounds) pool.play(sound, VOLUME, VOLUME, 1, 0, 1f)
        if (settings.vibration) vibrator.vibrate(vibe)
    }
}
