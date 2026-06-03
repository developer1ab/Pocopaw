package com.atombits.pocopaw.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import com.atombits.pocopaw.R

class AssistantOverlayController(private val context: Context) {

    var onVoicePressStart: (() -> Unit)? = null
    var onVoicePressEnd: (() -> Unit)? = null
    var onStopSpeaking: (() -> Unit)? = null
    var onReplaySpeaking: (() -> Unit)? = null
    var onReturnToMain: (() -> Unit)? = null

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var isShowing = false
    private var layoutX: Int = 0
    private var layoutY: Int = 200
    private var isVoiceRecording = false
    private var speechPlaying = false
    private var speechPaused = false
    private var speechFailed = false
    private var hasRecentSpeech = false
    private var isProcessing = false
    private var processingDotFrame = 0
    private val handler = Handler(Looper.getMainLooper())
    private val processingDots = arrayOf(".", "..", "...", "..")

    private val processingAnimRunnable = object : Runnable {
        override fun run() {
            if (!isProcessing) return
            val panel = overlayView?.findViewById<View>(R.id.overlay_panel)
            val speechStatusText = panel?.findViewById<TextView>(R.id.overlay_speech_status_text) ?: return
            processingDotFrame = (processingDotFrame + 1) % processingDots.size
            speechStatusText.text = context.getString(R.string.overlay_processing) + processingDots[processingDotFrame]
            handler.postDelayed(this, 400L)
        }
    }

    @Suppress("DEPRECATION")
    private val overlayType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun makeLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = layoutX
            y = layoutY
        }
    }

    fun show() {
        if (isShowing) return
        val localeContext = com.atombits.pocopaw.AppLocaleManager.wrap(context)
        val themedContext = ContextThemeWrapper(localeContext, R.style.Theme_Pocopaw)
        val inflater = LayoutInflater.from(themedContext)
        overlayView = inflater.inflate(R.layout.overlay_assistant, null)
        setupUI()
        try {
            windowManager.addView(overlayView, makeLayoutParams())
            isShowing = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hide() {
        if (!isShowing) return
        handler.removeCallbacks(processingAnimRunnable)
        try {
            overlayView?.let { windowManager.removeView(it) }
            overlayView = null
            isShowing = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun expand() {
        setBubbleVisible(false)
        setPanelVisible(true)
    }

    fun collapse() {
        setBubbleVisible(true)
        setPanelVisible(false)
        isVoiceRecording = false
        refreshUI()
    }

    fun setVoiceInputActive(active: Boolean) {
        isVoiceRecording = active
        refreshUI()
    }

    fun setSpeechPlaying(playing: Boolean) {
        speechPlaying = playing
        speechPaused = false
        speechFailed = false
        isProcessing = false
        handler.removeCallbacks(processingAnimRunnable)
        refreshUI()
    }

    fun setSpeechPaused(paused: Boolean) {
        speechPaused = paused
        speechPlaying = false
        speechFailed = false
        refreshUI()
    }

    fun setSpeechFailed(failed: Boolean) {
        speechFailed = failed
        speechPlaying = false
        speechPaused = false
        refreshUI()
    }

    fun setSpeechIdle() {
        speechPlaying = false
        speechPaused = false
        speechFailed = false
        isProcessing = false
        handler.removeCallbacks(processingAnimRunnable)
        refreshUI()
    }

    fun setProcessing(processing: Boolean) {
        isProcessing = processing
        speechPlaying = false
        speechPaused = false
        speechFailed = false
        if (processing) {
            processingDotFrame = 0
            handler.post(processingAnimRunnable)
        } else {
            handler.removeCallbacks(processingAnimRunnable)
        }
        refreshUI()
    }

    fun setHasRecentSpeech(has: Boolean) {
        hasRecentSpeech = has
        refreshUI()
    }

    private fun refreshUI() {
        val panel = overlayView?.findViewById<View>(R.id.overlay_panel) ?: return

        val voiceStatusText = panel.findViewById<TextView>(R.id.overlay_voice_status_text)
        val holdToTalk = panel.findViewById<TextView>(R.id.overlay_hold_to_talk)
        val speechStatusText = panel.findViewById<TextView>(R.id.overlay_speech_status_text)
        val stopButton = panel.findViewById<Button>(R.id.overlay_stop_speaking_button)
        val replayButton = panel.findViewById<Button>(R.id.overlay_replay_speaking_button)

        if (isVoiceRecording) {
            voiceStatusText.text = context.getString(R.string.overlay_voice_listening)
            voiceStatusText.setTextColor(context.resources.getColor(R.color.warning_500, null))
            holdToTalk.text = context.getString(R.string.overlay_release_to_send)
            holdToTalk.setBackgroundResource(R.drawable.bg_chat_hold_to_talk_active)
            holdToTalk.setTextColor(context.resources.getColor(android.R.color.white, null))
        } else {
            voiceStatusText.text = context.getString(R.string.overlay_idle)
            voiceStatusText.setTextColor(context.resources.getColor(R.color.moss_500, null))
            holdToTalk.text = context.getString(R.string.voice_hold_to_talk)
            holdToTalk.setBackgroundResource(R.drawable.bg_chat_hold_to_talk)
            holdToTalk.setTextColor(context.resources.getColor(R.color.ink_900, null))
        }

        when {
            isProcessing -> {
                speechStatusText.text = context.getString(R.string.overlay_processing) + processingDots[processingDotFrame]
                speechStatusText.setTextColor(context.resources.getColor(R.color.warning_500, null))
                stopButton.visibility = View.GONE
                replayButton.visibility = View.GONE
            }
            speechPlaying -> {
                speechStatusText.text = context.getString(R.string.overlay_playing)
                speechStatusText.setTextColor(context.resources.getColor(R.color.moss_500, null))
                stopButton.visibility = View.VISIBLE
                replayButton.visibility = View.GONE
            }
            speechPaused -> {
                speechStatusText.text = context.getString(R.string.overlay_paused)
                speechStatusText.setTextColor(context.resources.getColor(R.color.warning_500, null))
                stopButton.visibility = View.VISIBLE
                replayButton.visibility = View.VISIBLE
            }
            speechFailed -> {
                speechStatusText.text = context.getString(R.string.overlay_failed)
                speechStatusText.setTextColor(context.resources.getColor(R.color.warning_500, null))
                stopButton.visibility = View.GONE
                replayButton.visibility = View.VISIBLE
            }
            else -> {
                speechStatusText.text = context.getString(R.string.overlay_ready)
                speechStatusText.setTextColor(context.resources.getColor(R.color.moss_500, null))
                stopButton.visibility = View.GONE
                replayButton.visibility = if (hasRecentSpeech) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setBubbleVisible(visible: Boolean) {
        overlayView?.findViewById<View>(R.id.overlay_bubble)?.visibility =
            if (visible) View.VISIBLE else View.GONE
    }

    private fun setPanelVisible(visible: Boolean) {
        val panel = overlayView?.findViewById<View>(R.id.overlay_panel) ?: return
        panel.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) refreshUI()
    }

    private fun setupUI() {
        val view = overlayView ?: return

        // Bubble with TextureView + MediaPlayer for rounded video
        setupBubbleVideo(view)

        // Close button
        view.findViewById<View>(R.id.overlay_close_button)?.setOnClickListener { collapse() }

        // Hold to talk
        val holdToTalk = view.findViewById<TextView>(R.id.overlay_hold_to_talk)
        holdToTalk?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isVoiceRecording = true
                    refreshUI()
                    onVoicePressStart?.invoke()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isVoiceRecording) {
                        isVoiceRecording = false
                        refreshUI()
                        onVoicePressEnd?.invoke()
                    }
                    true
                }
                else -> false
            }
        }

        // Buttons
        view.findViewById<Button>(R.id.overlay_stop_speaking_button)?.setOnClickListener { onStopSpeaking?.invoke() }
        view.findViewById<Button>(R.id.overlay_replay_speaking_button)?.setOnClickListener { onReplaySpeaking?.invoke() }
        view.findViewById<Button>(R.id.overlay_return_button)?.setOnClickListener { onReturnToMain?.invoke() }

        // Drag
        val bubble = view.findViewById<View>(R.id.overlay_bubble)
        bubble?.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0
            private var downY = 0
            private var touchStartX = 0f
            private var touchStartY = 0f
            private var dragged = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                return when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = layoutX
                        downY = layoutY
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        dragged = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - touchStartX
                        val dy = event.rawY - touchStartY
                        if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) dragged = true
                        layoutX = downX + dx.toInt()
                        layoutY = downY + dy.toInt()
                        windowManager.updateViewLayout(overlayView, makeLayoutParams())
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragged) expand()
                        true
                    }
                    else -> false
                }
            }
        })
    }

    private fun setupBubbleVideo(view: View) {
        val bubbleFrame = view.findViewById<FrameLayout>(R.id.overlay_bubble) ?: return

        val textureView = android.view.TextureView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        bubbleFrame.addView(textureView)

        val player = MediaPlayer.create(context, R.raw.haha).apply {
            isLooping = true
            setVolume(0f, 0f)
        }
        textureView.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                player.setSurface(android.view.Surface(surface))
                player.start()
            }
            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
        }
        textureView.tag = player

        bubbleFrame.setOnClickListener { expand() }

        val radius = 32f * context.resources.displayMetrics.density
        bubbleFrame.clipToOutline = true
        bubbleFrame.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }
}