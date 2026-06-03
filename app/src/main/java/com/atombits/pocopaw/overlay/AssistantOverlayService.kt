package com.atombits.pocopaw.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.atombits.pocopaw.R

class AssistantOverlayService : Service() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "assistant_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "AssistantOverlayService"

        @Volatile
        var isRunning = false

        var voicePressStartBridge: (() -> Unit)? = null
        var voicePressEndBridge: (() -> Unit)? = null
        var stopSpeakingBridge: (() -> Unit)? = null
        var replaySpeakingBridge: (() -> Unit)? = null
        var returnToMainBridge: (() -> Unit)? = null

        private var controllerInstance: AssistantOverlayController? = null

        fun setProcessingState(processing: Boolean) {
            controllerInstance?.setProcessing(processing)
        }

        fun setVoiceInputActive(active: Boolean) {
            controllerInstance?.setVoiceInputActive(active)
        }

        fun setSpeechPlaying(playing: Boolean) {
            controllerInstance?.setSpeechPlaying(playing)
        }

        fun setSpeechIdle() {
            controllerInstance?.setSpeechIdle()
        }

        fun start(context: Context) {
            val intent = Intent(context, AssistantOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AssistantOverlayService::class.java)
            context.stopService(intent)
        }
    }

    private var controller: AssistantOverlayController? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        controllerInstance = AssistantOverlayController(applicationContext).also { ctrl ->
            ctrl.onVoicePressStart = { voicePressStartBridge?.invoke() }
            ctrl.onVoicePressEnd = { voicePressEndBridge?.invoke() }
            ctrl.onStopSpeaking = { stopSpeakingBridge?.invoke() }
            ctrl.onReplaySpeaking = { replaySpeakingBridge?.invoke() }
            ctrl.onReturnToMain = { returnToMainBridge?.invoke() }
        }
        controllerInstance?.show()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        isRunning = false
        controllerInstance?.hide()
        controllerInstance = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.overlay_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.overlay_notification_channel_description)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(R.drawable.pocopaw_avatar_outline)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}