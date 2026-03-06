package com.decli.chinesechess.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.decli.chinesechess.R
import com.decli.chinesechess.game.Side
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class FeedbackController(
    private val context: Context,
) : TextToSpeech.OnInitListener {
    private val notificationManager = NotificationManagerCompat.from(context)
    private val textToSpeech = TextToSpeech(context, this)
    private val audioExecutor = Executors.newSingleThreadExecutor()
    private val normalMoveSamples = buildWoodenMoveSamples(strength = 0.75, pitch = 170.0)
    private val aiMoveSamples = buildWoodenMoveSamples(strength = 0.82, pitch = 150.0)
    private val captureSamples = buildWoodenMoveSamples(strength = 1.0, pitch = 128.0)
    private val pendingSpeech = ArrayDeque<String>()
    private var ttsReady = false

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "机器人播报",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "机器人落子后的幽默播报"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val localeResult = sequenceOf(
                Locale.SIMPLIFIED_CHINESE,
                Locale.CHINA,
                Locale.CHINESE,
                Locale.getDefault(),
            ).firstNotNullOfOrNull { locale ->
                val result = textToSpeech.setLanguage(locale)
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    result
                } else {
                    null
                }
            }
            ttsReady = localeResult != null
            textToSpeech.setPitch(0.95f)
            textToSpeech.setSpeechRate(0.92f)
            if (ttsReady) {
                while (pendingSpeech.isNotEmpty()) {
                    textToSpeech.speak(pendingSpeech.removeFirst(), TextToSpeech.QUEUE_ADD, null, "robot_move_queue")
                }
            }
        }
    }

    fun playMove(side: Side, capture: Boolean) {
        val samples = when {
            capture -> captureSamples
            side == Side.BLACK -> aiMoveSamples
            else -> normalMoveSamples
        }
        audioExecutor.execute {
            playPcm(samples)
        }
    }

    fun speak(text: String) {
        if (ttsReady) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "robot_move")
        } else {
            pendingSpeech += text
        }
    }

    fun notify(text: String, notificationsGranted: Boolean) {
        if (!notificationsGranted) {
            return
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(1002, notification)
    }

    fun release() {
        textToSpeech.stop()
        textToSpeech.shutdown()
        audioExecutor.shutdownNow()
    }

    private fun playPcm(samples: ShortArray) {
        val sampleRate = 22_050
        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            samples.size * 2,
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        track.write(samples, 0, samples.size)
        track.play()
        Thread.sleep(150)
        track.stop()
        track.release()
    }

    private fun buildWoodenMoveSamples(strength: Double, pitch: Double): ShortArray {
        val sampleRate = 22_050
        val duration = 0.14
        val totalSamples = (sampleRate * duration).toInt()
        return ShortArray(totalSamples) { index ->
            val t = index / sampleRate.toDouble()
            val decay = exp(-28 * t)
            val transient = sin(2 * PI * pitch * t) * decay
            val resonance = sin(2 * PI * (pitch * 2.35) * t) * exp(-22 * t) * 0.45
            val knock = if (index < sampleRate / 120) {
                sin(2 * PI * 520 * t) * exp(-60 * t) * 0.9
            } else {
                0.0
            }
            val mixed = (transient + resonance + knock) * strength
            (mixed.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
        }
    }

    private companion object {
        const val CHANNEL_ID = "robot_commentary"
    }
}

