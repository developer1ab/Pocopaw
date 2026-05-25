package com.atombits.pocopaw.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import com.atombits.pocopaw.R

class CaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile var appContext: Context? = null
        @Volatile var imageReader: ImageReader? = null
        @Volatile var captureWidth: Int = 0
        @Volatile var captureHeight: Int = 0
        @Volatile var isReady: Boolean = false
    }

    private val handlerThread = HandlerThread("prototype-capture-svc").also { it.start() }
    private val backgroundHandler = Handler(handlerThread.looper)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        startCaptureForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: return START_NOT_STICKY
        val resultData: Intent = intent.getParcelableExtra(EXTRA_RESULT_DATA) ?: return START_NOT_STICKY

        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.defaultDisplay.getRealMetrics(metrics)
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels
        val density = metrics.densityDpi

        tearDown()
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
        val display = projection.createVirtualDisplay(
            "prototype-capture",
            captureWidth,
            captureHeight,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            backgroundHandler
        )

        mediaProjection = projection
        virtualDisplay = display
        imageReader = reader
        isReady = true
        RuntimeServiceStatusNotifier.notifyChanged()
        return START_STICKY
    }

    override fun onDestroy() {
        isReady = false
        RuntimeServiceStatusNotifier.notifyChanged()
        tearDown()
        handlerThread.quitSafely()
        super.onDestroy()
    }

    private fun tearDown() {
        isReady = false
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun startCaptureForeground() {
        val channelId = "prototype_capture"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(channelId, getString(R.string.capture_notification_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        startForeground(1001, notification)
    }
}