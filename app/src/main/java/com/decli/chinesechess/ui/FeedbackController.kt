package com.decli.chinesechess.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.decli.chinesechess.R
import com.decli.chinesechess.game.Side
import java.util.Locale

class FeedbackController(
    private val context: Context,
) : TextToSpeech.OnInitListener {
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
    private val notificationManager = NotificationManagerCompat.from(context)
    private val textToSpeech = TextToSpeech(context, this)
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
            ttsReady = textToSpeech.setLanguage(Locale.CHINA) >= TextToSpeech.LANG_AVAILABLE
            textToSpeech.setPitch(0.95f)
            textToSpeech.setSpeechRate(0.92f)
        }
    }

    fun playMove(side: Side, capture: Boolean) {
        val tone = when {
            capture -> ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
            side == Side.RED -> ToneGenerator.TONE_PROP_BEEP2
            else -> ToneGenerator.TONE_PROP_ACK
        }
        toneGenerator.startTone(tone, if (capture) 180 else 110)
    }

    fun speak(text: String) {
        if (ttsReady) {
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "robot_move")
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
        toneGenerator.release()
    }

    private companion object {
        const val CHANNEL_ID = "robot_commentary"
    }
}

