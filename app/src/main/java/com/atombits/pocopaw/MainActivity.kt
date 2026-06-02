package com.atombits.pocopaw

import android.Manifest
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.atombits.pocopaw.databinding.ActivityMainBinding
import com.atombits.pocopaw.earnings.buildAndroidEarningsHubStoreController
import com.atombits.pocopaw.earnings.earningsHubOrDefault
import com.atombits.pocopaw.orchestration.ChatTurnOrchestrator
import com.atombits.pocopaw.orchestration.ChatTurnSubmitResult
import com.atombits.pocopaw.orchestration.ExecutionEntryOrchestrator
import com.atombits.pocopaw.process.curation.ExecutionLearningPipeline
import com.atombits.pocopaw.shizuku.BootstrapTrigger
import com.atombits.pocopaw.shizuku.ShizukuBootstrapManager
import com.atombits.pocopaw.shizuku.ShizukuBootstrapPlan
import com.atombits.pocopaw.shizuku.ShizukuBootstrapStatus
import com.atombits.pocopaw.shizuku.ShizukuBootstrapStatusCode
import com.atombits.pocopaw.shizuku.ShizukuBootstrapSettingsStore
import com.atombits.pocopaw.shizuku.ShizukuPrivilegeIdentity
import com.atombits.pocopaw.shizuku.ShizukuStatusSnapshot
import com.atombits.pocopaw.service.PrototypeAccessibilityService
import com.atombits.pocopaw.service.RuntimeServiceStatusNotifier
import com.atombits.pocopaw.ui.ConsoleRenderAdapter
import com.atombits.pocopaw.ui.ConsoleRenderState
import com.atombits.pocopaw.ui.ShizukuSurfaceState
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import rikka.shizuku.Shizuku
import android.view.animation.LinearInterpolator
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private enum class MainSurface {
    CONSOLE,
    SETTINGS
}

private enum class ConsoleChannel {
    ALL,
    EXECUTION,
    CONVERSATION
}

private const val TOGGLE_TRACE_TAG = "ToggleTrace"

private enum class DemoOnboardingStep {
    TOOL_DISCOVERY,
    ACCESSIBILITY,
    SCREEN_CAPTURE,
    DONE
}

class MainActivity : AppCompatActivity() {

    private companion object {
        private const val VOICE_DEBUG_TAG = "VoiceInputDebug"
        private const val REQUEST_CODE_SHIZUKU_PERMISSION = 7001
        private const val LOADING_ANIMATION_INTERVAL_MS = 450L
        private const val HOLD_TO_TALK_ANIMATION_INTERVAL_MS = 140L
        private const val STATE_SURFACE = "state_surface"
        private const val STATE_CONSOLE_CHANNEL = "state_console_channel"
        private const val TENCENT_TTS_VOICE_BOY = 101015
        private const val TENCENT_TTS_VOICE_GIRL = 101016
        private const val TENCENT_TTS_VOICE_CHAT_CHILD = 502007
        private const val TENCENT_TTS_VOICE_SOFT_BOY = 603002
        private val HOLD_TO_TALK_RECORDING_FRAMES = arrayOf(
            "<<<>>>",
            "<<<<>>>>",
            "<<<<<>>>>>",
            "<<<<<<>>>>>>",
            "<<<<<>>>>>",
            "<<<<>>>>"
        )
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var executionLogAdapter: ExecutionLogAdapter
    private lateinit var consoleRenderAdapter: ConsoleRenderAdapter
    private lateinit var prototypeStore: PrototypeStore
    private val semanticClient = SemanticPrototypeClient()
    private val preparingEntrySidecar = PreparingEntrySidecar(enabled = false)
    private val screenCaptureManager by lazy(LazyThreadSafetyMode.NONE) {
        PrototypeScreenCaptureManager(applicationContext)
    }
    private val toolspaceCatalogManager by lazy(LazyThreadSafetyMode.NONE) {
        ToolspaceCatalogManager(applicationContext)
    }
    private val preferenceDiscoveryManualScanRunner by lazy(LazyThreadSafetyMode.NONE) {
        PreferenceDiscoveryManualScanRunner(applicationContext)
    }
    private val searchAugmentationClient by lazy(LazyThreadSafetyMode.NONE) {
        SearchAugmentationClient()
    }
    private val executionEntryOrchestrator by lazy(LazyThreadSafetyMode.NONE) {
        ExecutionEntryOrchestrator(
            prototypeStore = prototypeStore,
            executionFlowRunner = ExecutionFlowRunner(
                applicationContext = applicationContext,
                prototypeStore = prototypeStore,
                toolspaceCatalogManager = toolspaceCatalogManager,
                screenCaptureManager = screenCaptureManager
            )
        )
    }
    private val earningsHubStoreController by lazy(LazyThreadSafetyMode.NONE) {
        buildAndroidEarningsHubStoreController(
            context = applicationContext,
            prototypeStore = prototypeStore,
            toolspaceCatalogManager = toolspaceCatalogManager,
            screenCaptureManager = screenCaptureManager
        )
    }
    private val chatTurnOrchestrator by lazy(LazyThreadSafetyMode.NONE) {
        ChatTurnOrchestrator(
            context = applicationContext,
            prototypeStore = prototypeStore,
            semanticClient = semanticClient,
            searchAugmentationClient = searchAugmentationClient,
            toolspaceCatalogManager = toolspaceCatalogManager,
            executionEntryOrchestrator = executionEntryOrchestrator
        )
    }
    private val captureCompressionSettingsStore by lazy(LazyThreadSafetyMode.NONE) {
        ScreenCaptureCompressionSettingsStore(applicationContext)
    }
    private val visionRequestThinkingSettingsStore by lazy(LazyThreadSafetyMode.NONE) {
        VisionRequestThinkingSettingsStore(applicationContext)
    }
    private val visionRequestSearchSettingsStore by lazy(LazyThreadSafetyMode.NONE) {
        VisionRequestSearchSettingsStore(applicationContext)
    }
    private val providerProfileSettingsStore by lazy(LazyThreadSafetyMode.NONE) {
        ProviderProfileSettingsStore(applicationContext)
    }
    private val voiceRecognitionSettingsStore by lazy(LazyThreadSafetyMode.NONE) {
        VoiceRecognitionSettingsStore(applicationContext)
    }
    private val shizukuBootstrapSettingsStore by lazy(LazyThreadSafetyMode.NONE) {
        ShizukuBootstrapSettingsStore(applicationContext)
    }
    private val shizukuBootstrapManager by lazy(LazyThreadSafetyMode.NONE) {
        ShizukuBootstrapManager(
            context = applicationContext,
            settingsStore = shizukuBootstrapSettingsStore
        )
    }
    private var storeData: PrototypeStoreData = PrototypeStoreData()
    private var chatTurnOptions: ChatTurnOptions = ChatTurnOptions()
    private var pendingConversationMessages: List<ChatMessage> = emptyList()
    private var currentSurface: MainSurface = MainSurface.CONSOLE
    private var currentConsoleChannel: ConsoleChannel = ConsoleChannel.ALL
    private var syncingShizukuAutoPrepareSwitch = false
    private var startupShizukuBootstrapAttempted = false
    private var pendingStartupBinderRetry = false
    private var autoCaptureDeniedThisProcess = false
    private var pendingCaptureLaunchTrigger: BootstrapTrigger? = null
    private var pendingShizukuPermissionTrigger: BootstrapTrigger? = null
    private var demoOnboardingStep: DemoOnboardingStep = DemoOnboardingStep.DONE
    private var demoHighlightAnimator: ObjectAnimator? = null
    private var loadingAnimationRunning = false
    private var loadingAnimationFrame = 0
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private val tencentTtsClient = TencentTtsClient()
    private var cloudTtsPlayer: MediaPlayer? = null
    private var cloudTtsTempFile: File? = null
    private var cloudTtsJob: Job? = null
    private var activeSpeechMessageId: String? = null
    private var activeSpeechUtteranceId: String? = null
    private val asrRoutingClient = AsrRoutingClient()
    private var isVoiceRecording = false
    private var composerPressToTalkMode = false
    private var lastAutoSpokenMessageId: String? = null
    private var pendingAutoExecutionStore: PrototypeStoreData? = null
    private var pendingAutoExecutionUtteranceId: String? = null
    private var pendingAutoExecutionCloudMessageId: String? = null
    private var audioRecord: AudioRecord? = null
    private var voiceRecordJob: Job? = null
    private var recordedPcmBuffer: ByteArrayOutputStream? = null
    private var voiceRecordingStartedAtMs: Long = 0L
    private var holdToTalkAnimationFrame = 0

    private val speechInputLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!::binding.isInitialized) {
            return@registerForActivityResult
        }
        if (result.resultCode != RESULT_OK) {
            Snackbar.make(binding.root, getString(R.string.voice_input_no_result), Snackbar.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val transcript = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (transcript.isBlank()) {
            Snackbar.make(binding.root, getString(R.string.voice_input_no_result), Snackbar.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val existing = binding.inputEditText.text?.toString()?.trim().orEmpty()
        val merged = if (existing.isBlank()) transcript else "$existing $transcript"
        binding.inputEditText.setText(merged)
        binding.inputEditText.setSelection(merged.length)
        submitMessage()
    }
    private val loadingAnimationRunnable = object : Runnable {
        override fun run() {
            if (!loadingAnimationRunning || !::binding.isInitialized) {
                return
            }
            val frames = loadingAnimationFrames()
            binding.loadingText.text = frames[loadingAnimationFrame]
            loadingAnimationFrame = (loadingAnimationFrame + 1) % frames.size
            binding.loadingText.postDelayed(this, LOADING_ANIMATION_INTERVAL_MS)
        }
    }
    private val holdToTalkAnimationRunnable = object : Runnable {
        override fun run() {
            if (!::binding.isInitialized || !isVoiceRecording) {
                return
            }
            binding.holdToTalkButton.text = HOLD_TO_TALK_RECORDING_FRAMES[
                holdToTalkAnimationFrame % HOLD_TO_TALK_RECORDING_FRAMES.size
            ]
            holdToTalkAnimationFrame = (holdToTalkAnimationFrame + 1) % HOLD_TO_TALK_RECORDING_FRAMES.size
            binding.holdToTalkButton.postDelayed(this, HOLD_TO_TALK_ANIMATION_INTERVAL_MS)
        }
    }
    private val lastUpdatedFormatter: DateFormat = DateFormat.getDateTimeInstance(
        DateFormat.SHORT,
        DateFormat.SHORT
    )
    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            maybeRetryStartupShizukuBootstrapAfterBinder()
            render(storeData)
        }
    }
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            render(storeData)
        }
    }
    private val runtimeServiceStatusListener: () -> Unit = {
        runOnUiThread {
            if (::binding.isInitialized) {
                render(storeData)
            }
        }
    }
    private val shizukuPermissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != REQUEST_CODE_SHIZUKU_PERMISSION) {
            return@OnRequestPermissionResultListener
        }
        runOnUiThread {
            if (!::binding.isInitialized) {
                return@runOnUiThread
            }
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                continueShizukuPrepareAfterPermissionGrant(
                    pendingShizukuPermissionTrigger ?: BootstrapTrigger.MANUAL
                )
            } else {
                pendingShizukuPermissionTrigger = null
                shizukuBootstrapManager.onPermissionDenied()
                render(storeData)
                Snackbar.make(binding.root, getString(R.string.shizuku_message_permission_denied), Snackbar.LENGTH_SHORT).show()
            }
        }
    }
    private val screenCapturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!::binding.isInitialized) {
            return@registerForActivityResult
        }
        val captureTrigger = pendingCaptureLaunchTrigger
        pendingCaptureLaunchTrigger = null
        val resultData = result.data
        if (result.resultCode == RESULT_OK && resultData != null) {
            screenCaptureManager.savePermission(result.resultCode, resultData)
            if (!DemoReleaseControl.isOnboardingCompleted()) {
                DemoReleaseControl.markScreenCaptureOnboardingCompleted()
            }
            if (captureTrigger != null) {
                lifecycleScope.launch {
                    val captureStatus = shizukuBootstrapManager.onCapturePermissionGranted()
                    render(storeData)
                    refreshDemoOnboardingUi(force = true)
                    Snackbar.make(binding.root, buildShizukuPlanMessage(captureStatus), Snackbar.LENGTH_SHORT).show()
                }
            } else {
                render(storeData)
                refreshDemoOnboardingUi(force = true)
                Snackbar.make(binding.root, getString(R.string.screen_capture_permission_granted), Snackbar.LENGTH_SHORT).show()
            }
        } else {
            if (captureTrigger == BootstrapTrigger.STARTUP) {
                autoCaptureDeniedThisProcess = true
            }
            if (captureTrigger != null) {
                shizukuBootstrapManager.onCapturePermissionDenied()
                render(storeData)
                refreshDemoOnboardingUi(force = true)
                Snackbar.make(binding.root, getString(R.string.shizuku_message_capture_denied), Snackbar.LENGTH_SHORT).show()
            } else {
                refreshDemoOnboardingUi(force = true)
                Snackbar.make(binding.root, getString(R.string.screen_capture_permission_denied), Snackbar.LENGTH_SHORT).show()
            }
        }
    }
    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!::binding.isInitialized) {
            return@registerForActivityResult
        }
        val message = if (granted) {
            getString(R.string.contacts_permission_granted)
        } else {
            getString(R.string.contacts_permission_denied)
        }
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }
    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!::binding.isInitialized) {
            return@registerForActivityResult
        }
        if (!granted) {
            Snackbar.make(binding.root, getString(R.string.voice_input_permission_denied), Snackbar.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        beginVoiceRecordingSession()
    }
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (!::binding.isInitialized) {
            return@registerForActivityResult
        }
        uri ?: return@registerForActivityResult
        handleSelectedImage(uri = uri, fromCamera = false)
    }
    private val cameraPreviewLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (!::binding.isInitialized) {
            return@registerForActivityResult
        }
        bitmap ?: return@registerForActivityResult
        val uri = saveCapturedBitmap(bitmap)
        if (uri == null) {
            Snackbar.make(binding.root, getString(R.string.image_capture_failed), Snackbar.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        handleSelectedImage(uri = uri, fromCamera = true)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiStrings.initialize(this)
        DemoReleaseControl.initialize(applicationContext)
        binding.toolbarVersionText.text = getString(R.string.toolbar_version_badge, BuildConfig.VERSION_NAME)
        if (savedInstanceState != null) {
            currentSurface = savedInstanceState.getString(STATE_SURFACE)
                ?.let(MainSurface::valueOf)
                ?: currentSurface
            currentConsoleChannel = savedInstanceState.getString(STATE_CONSOLE_CHANNEL)
                ?.let(ConsoleChannel::valueOf)
                ?: currentConsoleChannel
        } else if (DemoReleaseControl.isOnboardingCompleted()) {
            currentSurface = MainSurface.CONSOLE
            currentConsoleChannel = ConsoleChannel.CONVERSATION
        } else {
            currentSurface = MainSurface.SETTINGS
            currentConsoleChannel = ConsoleChannel.CONVERSATION
        }

        prototypeStore = PrototypeStore(applicationContext)
        providerProfileSettingsStore.applyStoredConfig()
        captureCompressionSettingsStore.applyStoredScale()
        visionRequestThinkingSettingsStore.applyStoredEnabled()
        visionRequestSearchSettingsStore.applyStoredEnabled()
        chatTurnOptions = ChatTurnOptions(thinkingEnabled = false, searchEnabled = true)
        chatAdapter = ChatAdapter(
            object : ChatAdapter.MessageActionListener {
                override fun onSpeakMessage(message: ChatMessage) {
                    speakMessage(message)
                }

                override fun onCopyMessage(message: ChatMessage) {
                    copyMessage(message)
                }

                override fun onForwardMessage(message: ChatMessage) {
                    forwardMessage(message)
                }
            }
        )
        executionLogAdapter = ExecutionLogAdapter()
        consoleRenderAdapter = ConsoleRenderAdapter(
            context = applicationContext,
            binding = binding,
            chatAdapter = chatAdapter,
            executionLogAdapter = executionLogAdapter,
            preparingEntrySidecar = preparingEntrySidecar,
            toolspaceCatalogManager = toolspaceCatalogManager,
            captureCompressionSettingsStore = captureCompressionSettingsStore,
            visionRequestThinkingSettingsStore = visionRequestThinkingSettingsStore,
            visionRequestSearchSettingsStore = visionRequestSearchSettingsStore
        )

        binding.messagesRecyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.messagesRecyclerView.adapter = chatAdapter
        binding.executionLogRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.executionLogRecyclerView.adapter = executionLogAdapter

        bindPrimaryActions()
        bindSurfaceActions()
        bindSettingsActions()
        bindConversationOptionActions()
        initTextToSpeech()
        registerShizukuListeners()
        RuntimeServiceStatusNotifier.addListener(runtimeServiceStatusListener)
        ensureContactsPermissionIfNeeded()
        if (isExecutionReturnToPrototypeIntent(intent)) {
            currentSurface = MainSurface.CONSOLE
            currentConsoleChannel = ConsoleChannel.CONVERSATION
        }
        render(storeData)
        refreshDemoOnboardingUi(force = true)

        lifecycleScope.launch {
            storeData = prototypeStore.load()
            render(storeData)
            refreshDemoOnboardingUi(force = true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SURFACE, currentSurface.name)
        outState.putString(STATE_CONSOLE_CHANNEL, currentConsoleChannel.name)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isExecutionReturnToPrototypeIntent(intent)) {
            currentSurface = MainSurface.CONSOLE
            currentConsoleChannel = ConsoleChannel.CONVERSATION
            refreshSurface(showMessage = false)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            refreshSurface()
            maybeRunStartupShizukuBootstrap()
            refreshDemoOnboardingUi(force = true)
        }
    }

    override fun onDestroy() {
        clearPendingAutoExecutionAfterSpeech()
        stopVoiceRecordingSession(cancelOnly = true)
        stopSpeechPlayback(cancelPendingAutoExecution = true)
        runCatching {
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
        }
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ttsReady = false
        RuntimeServiceStatusNotifier.removeListener(runtimeServiceStatusListener)
        clearDemoHighlightAnimation()
        stopLoadingAnimation()
        super.onDestroy()
    }

    private fun bindPrimaryActions() {
        binding.inputEditText.doAfterTextChanged {
            refreshComposerActionButtons()
        }
        binding.inputEditText.setOnEditorActionListener { _, actionId, event ->
            val shouldSubmit = actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (!shouldSubmit) {
                return@setOnEditorActionListener false
            }
            submitMessage()
            true
        }
        binding.inputModeToggleButton.setOnClickListener {
            setComposerPressToTalkMode(!composerPressToTalkMode)
        }
        binding.holdToTalkButton.setOnTouchListener { _, event ->
            val settings = voiceRecognitionSettingsStore.read()
            when (settings.mode) {
                VoiceRecognitionMode.LOCAL -> {
                    if (event.action == MotionEvent.ACTION_UP) {
                        startLocalVoiceInput()
                    }
                    true
                }

                VoiceRecognitionMode.CLOUD -> {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            startVoiceInput()
                            true
                        }

                        MotionEvent.ACTION_UP -> {
                            stopVoiceRecordingSession(cancelOnly = false)
                            true
                        }

                        MotionEvent.ACTION_CANCEL -> {
                            stopVoiceRecordingSession(cancelOnly = true)
                            true
                        }

                        else -> false
                    }
                }
            }
        }
        setComposerPressToTalkMode(true)
        binding.sendMessageButton.setOnClickListener { submitMessage() }
        binding.pickImageButton.setOnClickListener { imagePickerLauncher.launch("image/*") }
        binding.captureImageButton.setOnClickListener { cameraPreviewLauncher.launch(null) }
        binding.startPreparingButton.setOnClickListener { startPreparing() }
        binding.startExecutionButton.setOnClickListener { startExecution() }
        binding.processThumbsUpButton.setOnClickListener {
            submitCompletedExecutionFeedback(ProcessFeedbackType.THUMBS_UP)
        }
        binding.processThumbsDownButton.setOnClickListener {
            submitCompletedExecutionFeedback(ProcessFeedbackType.THUMBS_DOWN)
        }
    }

    private fun setComposerPressToTalkMode(enabled: Boolean) {
        composerPressToTalkMode = enabled
        binding.holdToTalkButton.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.inputEditText.visibility = if (enabled) View.GONE else View.VISIBLE
        val iconRes = if (enabled) {
            R.drawable.ic_wechat_keyboard
        } else {
            R.drawable.ic_wechat_mic
        }
        binding.inputModeToggleButton.setImageResource(iconRes)
        val contentDescriptionRes = if (enabled) {
            R.string.voice_input_toggle_to_text
        } else {
            R.string.voice_input_toggle_to_voice
        }
        binding.inputModeToggleButton.contentDescription = getString(contentDescriptionRes)
        updateHoldToTalkVisual(isRecording = false)
        refreshComposerActionButtons()
    }

    private fun refreshComposerActionButtons() {
        val hasInput = binding.inputEditText.text?.toString()?.trim()?.isNotEmpty() == true
        val showSend = !composerPressToTalkMode && hasInput
        binding.sendMessageButton.visibility = if (showSend) View.VISIBLE else View.GONE
        binding.pickImageButton.visibility = if (showSend) View.GONE else View.VISIBLE
    }

    private fun updateHoldToTalkVisual(isRecording: Boolean) {
        if (isRecording) {
            binding.holdToTalkButton.setBackgroundResource(R.drawable.bg_chat_hold_to_talk_active)
            binding.holdToTalkButton.setTextColor(ContextCompat.getColor(this, R.color.white))
            startHoldToTalkRecordingAnimation()
            binding.holdToTalkButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        } else {
            stopHoldToTalkRecordingAnimation()
            binding.holdToTalkButton.setBackgroundResource(R.drawable.bg_chat_hold_to_talk)
            binding.holdToTalkButton.setTextColor(ContextCompat.getColor(this, R.color.ink_900))
            binding.holdToTalkButton.text = getString(R.string.voice_hold_to_talk)
            binding.holdToTalkButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
        }
    }

    private fun startHoldToTalkRecordingAnimation() {
        binding.holdToTalkButton.removeCallbacks(holdToTalkAnimationRunnable)
        holdToTalkAnimationFrame = 0
        binding.holdToTalkButton.text = HOLD_TO_TALK_RECORDING_FRAMES.first()
        holdToTalkAnimationFrame = 1
        binding.holdToTalkButton.postDelayed(holdToTalkAnimationRunnable, HOLD_TO_TALK_ANIMATION_INTERVAL_MS)
    }

    private fun stopHoldToTalkRecordingAnimation() {
        if (!::binding.isInitialized) {
            return
        }
        binding.holdToTalkButton.removeCallbacks(holdToTalkAnimationRunnable)
        holdToTalkAnimationFrame = 0
    }

    private fun updateSpeakingMessage(messageId: String?) {
        if (activeSpeechMessageId == messageId) {
            return
        }
        activeSpeechMessageId = messageId
        if (::chatAdapter.isInitialized) {
            chatAdapter.setSpeakingMessageId(messageId)
        }
    }

    private fun clearActiveSpeechPlayback() {
        activeSpeechUtteranceId = null
        updateSpeakingMessage(null)
    }

    private fun releaseCloudTtsPlayback(player: MediaPlayer? = cloudTtsPlayer) {
        player?.let { mediaPlayer ->
            runCatching { mediaPlayer.stop() }
            runCatching { mediaPlayer.reset() }
            runCatching { mediaPlayer.release() }
        }
        if (player == null || cloudTtsPlayer === player) {
            cloudTtsPlayer = null
        }
        cloudTtsTempFile?.delete()
        cloudTtsTempFile = null
    }

    private fun stopSpeechPlayback(cancelPendingAutoExecution: Boolean) {
        val pendingStoreToStart = if (cancelPendingAutoExecution) {
            null
        } else {
            pendingAutoExecutionStore
        }
        clearPendingAutoExecutionAfterSpeech()
        clearActiveSpeechPlayback()
        cloudTtsJob?.cancel()
        cloudTtsJob = null
        textToSpeech?.stop()
        releaseCloudTtsPlayback()
        pendingStoreToStart?.let(::startAutoExecutionAfterSpeech)
    }

    private fun bindSurfaceActions() {
        binding.settingsToggleButton.setOnClickListener {
            if (!DemoReleaseControl.isOnboardingCompleted() && currentSurface == MainSurface.SETTINGS) {
                Snackbar.make(binding.root, getString(R.string.demo_onboarding_keep_settings), Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            currentSurface = if (currentSurface == MainSurface.SETTINGS) {
                MainSurface.CONSOLE
            } else {
                MainSurface.SETTINGS
            }
            render(storeData)
            refreshDemoOnboardingUi(force = false)
            if (currentSurface == MainSurface.SETTINGS) {
                binding.settingsPageContainer.post {
                    binding.settingsPageContainer.fullScroll(View.FOCUS_UP)
                }
            }
        }
        binding.refreshStatusButton.setOnClickListener {
            refreshSurface(showMessage = true)
        }
        binding.btnChannelAll.setOnClickListener { showConsoleChannel(ConsoleChannel.ALL) }
        binding.btnChannelExecution.setOnClickListener { showConsoleChannel(ConsoleChannel.EXECUTION) }
        binding.btnChannelConversation.setOnClickListener { showConsoleChannel(ConsoleChannel.CONVERSATION) }
    }

    private fun bindSettingsActions() {
        binding.openAccessibilitySettingsButton.setOnClickListener {
            if (shouldBlockDemoOnboardingAction(DemoOnboardingStep.ACCESSIBILITY)) {
                return@setOnClickListener
            }
            if (!DemoReleaseControl.isOnboardingCompleted()) {
                DemoReleaseControl.markAccessibilityOnboardingSettingsOpened()
            }
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.openUsageAccessSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        binding.requestScreenCaptureButton.setOnClickListener {
            if (shouldBlockDemoOnboardingAction(DemoOnboardingStep.SCREEN_CAPTURE)) {
                return@setOnClickListener
            }
            pendingCaptureLaunchTrigger = null
            screenCapturePermissionLauncher.launch(screenCaptureManager.createCaptureIntent())
        }
        binding.prepareWithShizukuButton.setOnClickListener {
            prepareWithShizuku()
        }
        binding.switchLanguageButton.setOnClickListener {
            toggleLanguage()
        }
        binding.shizukuAutoPrepareSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (syncingShizukuAutoPrepareSwitch) {
                return@setOnCheckedChangeListener
            }
            updateShizukuAutoPrepare(isChecked)
        }
        binding.captureCompressionValueText.setOnClickListener {
            showCaptureCompressionScaleDialog()
        }
        binding.runToolDiscoveryButton.setOnClickListener {
            if (shouldBlockDemoOnboardingAction(DemoOnboardingStep.TOOL_DISCOVERY)) {
                return@setOnClickListener
            }
            runToolDiscoveryScan()
        }
        binding.runPreferenceDiscoveryButton.setOnClickListener {
            runPreferenceDiscovery()
        }
        binding.runEarningsScanButton.setOnClickListener {
            runEarningsScanOnly()
        }
        binding.compileEarningsPlanButton.setOnClickListener {
            compileEarningsPlanFromCurrentScan()
        }
        binding.toggleEarningsAutomationButton.setOnClickListener {
            toggleEarningsAutomation()
        }
        binding.runEarningsTickButton.setOnClickListener {
            runEarningsTick()
        }
        binding.runPreferenceExtractionButton.setOnClickListener {
            runPreferenceExtraction()
        }
        binding.runProcessExtractionButton.setOnClickListener {
            runProcessExtraction()
        }
        binding.providerProfileValueText.setOnClickListener {
            showProviderProfileDialog()
        }
        binding.semanticModelValueText.setOnClickListener {
            showSemanticModelTierDialog()
        }
        binding.visionModelValueText.setOnClickListener {
            showQwenVisionModelDialog()
        }
        binding.searchProviderValueText.setOnClickListener {
            showSearchProviderDialog()
        }
        binding.voiceRecognitionModeValueText.setOnClickListener {
            showVoiceRecognitionModeDialog()
        }
        binding.voiceCloudRegionValueText.setOnClickListener {
            showVoiceCloudRegionDialog()
        }
        binding.voiceCloudCnProviderValueText.setOnClickListener {
            showVoiceCnProviderDialog()
        }
        binding.voiceCloudTencentTtsVoiceValueText.setOnClickListener {
            showTencentTtsVoiceDialog()
        }
        binding.voiceCloudGlobalProviderValueText.setOnClickListener {
            showVoiceGlobalProviderDialog()
        }
        binding.voiceAutoSpeakValueText.setOnClickListener {
            showVoiceAutoSpeakDialog()
        }
        binding.visionThinkingSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            Log.d(
                TOGGLE_TRACE_TAG,
                "listener visionThinkingSwitch isChecked=$isChecked pressed=${buttonView.isPressed}"
            )
            val persisted = visionRequestThinkingSettingsStore.writeEnabled(isChecked)
            Log.d(
                TOGGLE_TRACE_TAG,
                "listener visionThinkingSwitch storeWriteResult=$persisted"
            )
            render(storeData)
        }
        binding.visionSearchSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            Log.d(
                TOGGLE_TRACE_TAG,
                "listener visionSearchSwitch isChecked=$isChecked pressed=${buttonView.isPressed}"
            )
            val persisted = visionRequestSearchSettingsStore.writeEnabled(isChecked)
            Log.d(
                TOGGLE_TRACE_TAG,
                "listener visionSearchSwitch storeWriteResult=$persisted"
            )
            render(storeData)
        }
    }

    private fun bindConversationOptionActions() {
        binding.thinkingSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            Log.d(
                TOGGLE_TRACE_TAG,
                "listener thinkingSwitch isChecked=$isChecked pressed=${buttonView.isPressed} before=${chatTurnOptions.thinkingEnabled}"
            )
            chatTurnOptions = chatTurnOptions.copy(thinkingEnabled = isChecked)
            Log.d(
                TOGGLE_TRACE_TAG,
                "listener thinkingSwitch updated=${chatTurnOptions.thinkingEnabled} store=inMemory"
            )
            render(storeData)
        }
        binding.searchSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            Log.d(
                TOGGLE_TRACE_TAG,
                "listener searchSwitch isChecked=$isChecked pressed=${buttonView.isPressed} before=${chatTurnOptions.searchEnabled}"
            )
            chatTurnOptions = chatTurnOptions.copy(searchEnabled = isChecked)
            Log.d(
                TOGGLE_TRACE_TAG,
                "listener searchSwitch updated=${chatTurnOptions.searchEnabled} store=inMemory"
            )
            render(storeData)
        }
    }

    private fun ensureContactsPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
    }

    private fun submitMessage() {
        val userInput = binding.inputEditText.text?.toString()?.trim().orEmpty()
        if (userInput.isBlank()) {
            return
        }
        clearPendingAutoExecutionAfterSpeech()
        currentSurface = MainSurface.CONSOLE
        currentConsoleChannel = ConsoleChannel.CONVERSATION
        val submittedInput = userInput
        binding.inputEditText.setText("")
        setLoading(true)
        lifecycleScope.launch {
            val turnOptionsSnapshot = chatTurnOptions.copy()
            when (
                val result = chatTurnOrchestrator.submitMessage(
                    submittedInput = submittedInput,
                    currentStore = storeData,
                    turnOptions = turnOptionsSnapshot,
                    onPendingConversationChanged = ::updatePendingConversation
                )
            ) {
                is ChatTurnSubmitResult.FeedbackApplied -> {
                    pendingConversationMessages = emptyList()
                    storeData = result.updatedStore
                    render(storeData)
                    Snackbar.make(binding.root, result.message, Snackbar.LENGTH_SHORT).show()
                }

                is ChatTurnSubmitResult.MissingConfiguration -> {
                    binding.inputEditText.setText(result.restoreInput)
                    pendingConversationMessages = emptyList()
                    render(storeData)
                    Snackbar.make(binding.root, getString(R.string.semantic_model_not_configured), Snackbar.LENGTH_LONG).show()
                }

                is ChatTurnSubmitResult.ConversationCompleted -> {
                    pendingConversationMessages = emptyList()
                    storeData = result.updatedStore
                    render(storeData)
                    val waitingForSpeech = maybeAutoSpeakLatestAssistantMessage(
                        store = result.updatedStore,
                        deferExecutionStart = result.shouldAutoStartExecution
                    )
                    if (result.shouldAutoStartExecution && !waitingForSpeech) {
                        performConversationAutoExecution(result.updatedStore)
                    }
                }

                is ChatTurnSubmitResult.Failure -> {
                    binding.inputEditText.setText(result.restoreInput)
                    pendingConversationMessages = result.pendingMessages
                    render(storeData)
                    Snackbar.make(binding.root, buildFailureMessage(result.throwable), Snackbar.LENGTH_LONG).show()
                }
            }
            setLoading(false)
        }
    }

    private fun startVoiceInput() {
        if (isVoiceRecording) {
            return
        }
        val settings = voiceRecognitionSettingsStore.read()
        if (!asrRoutingClient.isConfigured(settings)) {
            Snackbar.make(binding.root, getString(R.string.voice_input_not_configured), Snackbar.LENGTH_SHORT).show()
            return
        }
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        beginVoiceRecordingSession()
    }

    private fun beginVoiceRecordingSession() {
        val sampleRate = 16000
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            Snackbar.make(binding.root, getString(R.string.voice_input_not_supported), Snackbar.LENGTH_SHORT).show()
            return
        }
        val recorder = buildVoiceRecorder(sampleRate, minBufferSize)
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            Snackbar.make(binding.root, getString(R.string.voice_input_not_supported), Snackbar.LENGTH_SHORT).show()
            return
        }
        stopSpeechPlayback(cancelPendingAutoExecution = true)
        recordedPcmBuffer = ByteArrayOutputStream()
        audioRecord = recorder
        isVoiceRecording = true
        voiceRecordingStartedAtMs = System.currentTimeMillis()
        updateHoldToTalkVisual(isRecording = true)
        binding.holdToTalkButton.alpha = 0.7f
        recorder.startRecording()
        voiceRecordJob = lifecycleScope.launch(Dispatchers.IO) {
            val readBuffer = ByteArray(minBufferSize)
            while (isVoiceRecording) {
                val count = recorder.read(readBuffer, 0, readBuffer.size)
                if (count > 0) {
                    recordedPcmBuffer?.write(readBuffer, 0, count)
                } else {
                    delay(10)
                }
            }
        }
    }

    private fun buildVoiceRecorder(sampleRate: Int, minBufferSize: Int): AudioRecord {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.DEFAULT
        )
        val bufferSize = minBufferSize * 2
        for (source in sources) {
            val recorder = AudioRecord(
                source,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                return recorder
            }
            recorder.release()
        }
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
    }

    private fun stopVoiceRecordingSession(cancelOnly: Boolean) {
        val recorder = audioRecord
        if (recorder == null) {
            isVoiceRecording = false
            updateHoldToTalkVisual(isRecording = false)
            binding.holdToTalkButton.alpha = 1f
            return
        }
        isVoiceRecording = false
        runCatching { recorder.stop() }
        recorder.release()
        audioRecord = null
        voiceRecordJob?.cancel()
        voiceRecordJob = null
        updateHoldToTalkVisual(isRecording = false)
        binding.holdToTalkButton.alpha = 1f
        if (cancelOnly) {
            recordedPcmBuffer = null
            return
        }
        val recordingDurationMs = System.currentTimeMillis() - voiceRecordingStartedAtMs
        if (recordingDurationMs < 300L) {
            recordedPcmBuffer = null
            Snackbar.make(binding.root, getString(R.string.voice_input_no_result), Snackbar.LENGTH_SHORT).show()
            return
        }
        val pcmBytes = recordedPcmBuffer?.toByteArray() ?: byteArrayOf()
        recordedPcmBuffer = null
        if (pcmBytes.isEmpty()) {
            Snackbar.make(binding.root, getString(R.string.voice_input_no_result), Snackbar.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            setLoading(true)
            val transcriptResult = withContext(Dispatchers.IO) {
                runCatching {
                    val wav = pcmToWav(
                        pcmBytes = pcmBytes,
                        sampleRate = 16000,
                        channels = 1,
                        bitsPerSample = 16
                    )
                    asrRoutingClient.recognizeWav(wav, voiceRecognitionSettingsStore.read()).trim()
                }
            }
            setLoading(false)
            if (transcriptResult.isFailure) {
                val message = transcriptResult.exceptionOrNull()?.message
                    ?.take(140)
                    .orEmpty()
                    .ifBlank { getString(R.string.request_failed_generic) }
                Log.e(VOICE_DEBUG_TAG, "Cloud ASR failed: $message", transcriptResult.exceptionOrNull())
                Snackbar.make(
                    binding.root,
                    getString(R.string.voice_input_cloud_failed, message),
                    Snackbar.LENGTH_LONG
                ).show()
                return@launch
            }
            val transcript = transcriptResult.getOrNull()
            if (transcript.isNullOrBlank()) {
                Snackbar.make(binding.root, getString(R.string.voice_input_no_result), Snackbar.LENGTH_SHORT).show()
                return@launch
            }
            val existing = binding.inputEditText.text?.toString()?.trim().orEmpty()
            val merged = if (existing.isBlank()) transcript else "$existing $transcript"
            binding.inputEditText.setText(merged)
            binding.inputEditText.setSelection(merged.length)
            submitMessage()
        }
    }

    private fun pcmToWav(
        pcmBytes: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = pcmBytes.size + 36
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
        writeIntLE(out, totalDataLen)
        out.write(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))
        out.write(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()))
        writeIntLE(out, 16)
        writeShortLE(out, 1)
        writeShortLE(out, channels)
        writeIntLE(out, sampleRate)
        writeIntLE(out, byteRate)
        writeShortLE(out, channels * bitsPerSample / 8)
        writeShortLE(out, bitsPerSample)
        out.write(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()))
        writeIntLE(out, pcmBytes.size)
        out.write(pcmBytes)
        return out.toByteArray()
    }

    private fun writeIntLE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value shr 8) and 0xff)
        out.write((value shr 16) and 0xff)
        out.write((value shr 24) and 0xff)
    }

    private fun writeShortLE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value shr 8) and 0xff)
    }

    private fun startLocalVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_input_prompt))
        }
        val canResolve = intent.resolveActivity(packageManager) != null
        if (!canResolve) {
            Snackbar.make(binding.root, getString(R.string.voice_input_not_supported), Snackbar.LENGTH_SHORT).show()
            return
        }
        stopSpeechPlayback(cancelPendingAutoExecution = true)
        speechInputLauncher.launch(intent)
    }

    private fun showVoiceRecognitionModeDialog() {
        val options = arrayOf(VoiceRecognitionMode.LOCAL, VoiceRecognitionMode.CLOUD)
        val labels = arrayOf(
            getString(R.string.settings_voice_mode_local),
            getString(R.string.settings_voice_mode_cloud)
        )
        val current = voiceRecognitionSettingsStore.read()
        var selected = options.indexOf(current.mode).takeIf { it >= 0 } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_voice_mode_dialog_title)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                voiceRecognitionSettingsStore.writeMode(options[selected])
                render(storeData)
                Snackbar.make(binding.root, getString(R.string.settings_voice_setting_updated), Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showVoiceCloudRegionDialog() {
        val current = voiceRecognitionSettingsStore.read()
        if (current.mode != VoiceRecognitionMode.CLOUD) {
            Snackbar.make(binding.root, getString(R.string.settings_voice_cloud_only_hint), Snackbar.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf(VoiceCloudRegion.CN, VoiceCloudRegion.GLOBAL)
        val labels = arrayOf(
            getString(R.string.settings_voice_cloud_region_cn),
            getString(R.string.settings_voice_cloud_region_global)
        )
        var selected = options.indexOf(current.cloudRegion).takeIf { it >= 0 } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_voice_cloud_region_dialog_title)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                voiceRecognitionSettingsStore.writeCloudRegion(options[selected])
                render(storeData)
                Snackbar.make(binding.root, getString(R.string.settings_voice_setting_updated), Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showVoiceCnProviderDialog() {
        val current = voiceRecognitionSettingsStore.read()
        if (current.mode != VoiceRecognitionMode.CLOUD || current.cloudRegion != VoiceCloudRegion.CN) {
            Snackbar.make(binding.root, getString(R.string.settings_voice_provider_cn_only_hint), Snackbar.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf(VoiceCnProvider.TENCENT, VoiceCnProvider.ALI)
        val labels = arrayOf(
            getString(R.string.settings_voice_provider_tencent),
            getString(R.string.settings_voice_provider_ali)
        )
        var selected = options.indexOf(current.cnProvider).takeIf { it >= 0 } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_voice_cn_provider_dialog_title)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                voiceRecognitionSettingsStore.writeCnProvider(options[selected])
                render(storeData)
                Snackbar.make(binding.root, getString(R.string.settings_voice_setting_updated), Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showVoiceGlobalProviderDialog() {
        val current = voiceRecognitionSettingsStore.read()
        if (current.mode != VoiceRecognitionMode.CLOUD || current.cloudRegion != VoiceCloudRegion.GLOBAL) {
            Snackbar.make(binding.root, getString(R.string.settings_voice_provider_global_only_hint), Snackbar.LENGTH_SHORT).show()
            return
        }
        val options = arrayOf(VoiceGlobalProvider.DEEPGRAM, VoiceGlobalProvider.GOOGLE)
        val labels = arrayOf(
            getString(R.string.settings_voice_provider_deepgram),
            getString(R.string.settings_voice_provider_google)
        )
        var selected = options.indexOf(current.globalProvider).takeIf { it >= 0 } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_voice_global_provider_dialog_title)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                voiceRecognitionSettingsStore.writeGlobalProvider(options[selected])
                render(storeData)
                Snackbar.make(binding.root, getString(R.string.settings_voice_setting_updated), Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showTencentTtsVoiceDialog() {
        val current = voiceRecognitionSettingsStore.read()
        val isTencentCloud =
            current.mode == VoiceRecognitionMode.CLOUD &&
                current.cloudRegion == VoiceCloudRegion.CN &&
                current.cnProvider == VoiceCnProvider.TENCENT
        if (!isTencentCloud) {
            Snackbar.make(binding.root, getString(R.string.settings_voice_tts_tencent_only_hint), Snackbar.LENGTH_SHORT).show()
            return
        }
        val presetOptions = intArrayOf(
            TENCENT_TTS_VOICE_BOY,
            TENCENT_TTS_VOICE_GIRL,
            TENCENT_TTS_VOICE_CHAT_CHILD,
            TENCENT_TTS_VOICE_SOFT_BOY
        )
        val labels = arrayOf(
            getString(R.string.settings_voice_tts_voice_boy),
            getString(R.string.settings_voice_tts_voice_girl),
            getString(R.string.settings_voice_tts_voice_chat_child),
            getString(R.string.settings_voice_tts_voice_soft_boy)
        )
        val presetIndex = presetOptions.indexOf(current.tencentTtsVoiceType)
        var selected = if (presetIndex >= 0) presetIndex else 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_voice_tts_voice_dialog_title)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                voiceRecognitionSettingsStore.writeTencentTtsVoiceType(presetOptions[selected])
                render(storeData)
                Snackbar.make(binding.root, getString(R.string.settings_voice_setting_updated), Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showVoiceAutoSpeakDialog() {
        val current = voiceRecognitionSettingsStore.read()
        val options = booleanArrayOf(true, false)
        val labels = arrayOf(
            getString(R.string.settings_voice_auto_speak_on),
            getString(R.string.settings_voice_auto_speak_off)
        )
        var selected = if (current.autoSpeakEnabled) 0 else 1
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_voice_auto_speak_dialog_title)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                voiceRecognitionSettingsStore.writeAutoSpeakEnabled(options[selected])
                render(storeData)
                Snackbar.make(binding.root, getString(R.string.settings_voice_setting_updated), Snackbar.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun maybeAutoSpeakLatestAssistantMessage(
        store: PrototypeStoreData,
        deferExecutionStart: Boolean = false
    ): Boolean {
        val settings = voiceRecognitionSettingsStore.read()
        if (!settings.autoSpeakEnabled) {
            return false
        }
        val latestAssistantMessage = store.messages.lastOrNull { message ->
            message.role == MessageRole.ASSISTANT && message.content.isNotBlank()
        } ?: return false
        if (latestAssistantMessage.id == lastAutoSpokenMessageId) {
            return false
        }
        val started = speakMessage(
            message = latestAssistantMessage,
            deferredExecutionStore = if (deferExecutionStart) store else null,
            toggleIfAlreadySpeaking = false
        )
        if (started) {
            lastAutoSpokenMessageId = latestAssistantMessage.id
        }
        return started
    }

    private fun handleSelectedImage(uri: Uri, fromCamera: Boolean) {
        val source = if (fromCamera) {
            getString(R.string.image_source_camera)
        } else {
            getString(R.string.image_source_gallery)
        }
        lifecycleScope.launch {
            setLoading(true)
            val imageDataUrl = withContext(Dispatchers.IO) { buildImageDataUrl(uri) }
            val imageNarrative = if (imageDataUrl != null) {
                withContext(Dispatchers.IO) {
                    describeImageForConversation(imageDataUrl, source)
                }
            } else {
                null
            }
            setLoading(false)
            val currentInput = binding.inputEditText.text?.toString()?.trim().orEmpty()
            val mergedInput = buildString {
                if (currentInput.isNotBlank()) {
                    append(currentInput).append('\n')
                }
                append(imageNarrative ?: getString(R.string.image_message_parse_fallback, source))
            }
            binding.inputEditText.setText(mergedInput)
            binding.inputEditText.setSelection(mergedInput.length)
            submitMessage()
        }
    }

    private fun buildImageDataUrl(uri: Uri): String? {
        return runCatching {
            val bitmap = contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: return null
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            val payload = android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
            "data:image/jpeg;base64,$payload"
        }.getOrNull()
    }

    private fun describeImageForConversation(imageDataUrl: String, source: String): String {
        if (!semanticClient.isConfigured()) {
            return getString(R.string.image_message_parse_fallback, source)
        }
        return runCatching {
            val promptMessages = listOf(
                PromptMessage(
                    role = "system",
                    content = "You are a visual assistant. Output JSON only: {\"reply\":\"...\"}."
                ),
                PromptMessage(
                    role = "user",
                    content = "请总结这张图片",
                    contentParts = listOf(
                        PromptContentPart(
                            type = "text",
                            text = "请用中文简洁总结这张图片，并提取关键视觉信息。"
                        ),
                        PromptContentPart(
                            type = "image_url",
                            imageDataUrl = imageDataUrl
                        )
                    )
                )
            )
            val rawResponse = semanticClient.requestPromptMessages(
                promptMessages = promptMessages,
                requestConfig = SemanticPrototypeClient.PromptRequestConfig(
                    temperature = 0.2,
                    topP = 0.85,
                    maxTokens = 600,
                    requestTag = "image_multimodal_summary"
                )
            )
            val content = org.json.JSONObject(rawResponse)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
            val reply = runCatching {
                org.json.JSONObject(content).optString("reply")
            }.getOrNull().orEmpty().trim()
            val normalized = if (reply.isNotBlank()) reply else content.trim()
            if (normalized.isBlank()) {
                getString(R.string.image_message_parse_fallback, source)
            } else {
                getString(R.string.image_message_parse_template, source, normalized)
            }
        }.getOrElse {
            getString(R.string.image_message_parse_fallback, source)
        }
    }

    private fun saveCapturedBitmap(bitmap: Bitmap): Uri? {
        return runCatching {
            val file = File(cacheDir, "captured_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
            Uri.fromFile(file)
        }.getOrNull()
    }

    private fun initTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                ttsReady = false
                return@TextToSpeech
            }
            val tts = textToSpeech ?: return@TextToSpeech
            val languageResult = tts.setLanguage(Locale.getDefault())
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        handleLocalSpeechPlaybackFinished(utteranceId)
                    }
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        handleLocalSpeechPlaybackFinished(utteranceId)
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    runOnUiThread {
                        handleLocalSpeechPlaybackFinished(utteranceId)
                    }
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    runOnUiThread {
                        handleLocalSpeechPlaybackFinished(utteranceId)
                    }
                }
            })
            ttsReady = languageResult != TextToSpeech.LANG_MISSING_DATA && languageResult != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    private fun speakMessage(
        message: ChatMessage,
        deferredExecutionStore: PrototypeStoreData? = null,
        toggleIfAlreadySpeaking: Boolean = true
    ): Boolean {
        val text = message.content.trim()
        if (text.isBlank()) {
            Snackbar.make(binding.root, getString(R.string.message_action_speak_empty), Snackbar.LENGTH_SHORT).show()
            return false
        }
        if (toggleIfAlreadySpeaking && activeSpeechMessageId == message.id) {
            stopSpeechPlayback(cancelPendingAutoExecution = false)
            return false
        }
        stopSpeechPlayback(cancelPendingAutoExecution = true)
        val settings = voiceRecognitionSettingsStore.read()
        if (settings.mode == VoiceRecognitionMode.CLOUD) {
            return speakMessageWithCloudTts(text, settings, message.id, deferredExecutionStore)
        }
        val tts = textToSpeech
        if (!ttsReady || tts == null) {
            Snackbar.make(binding.root, getString(R.string.message_action_tts_unavailable), Snackbar.LENGTH_SHORT).show()
            return false
        }
        val utteranceId = buildMessageUtteranceId(message.id)
        if (deferredExecutionStore != null) {
            pendingAutoExecutionStore = deferredExecutionStore
            pendingAutoExecutionUtteranceId = utteranceId
        }
        activeSpeechUtteranceId = utteranceId
        updateSpeakingMessage(message.id)
        val status = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (status != TextToSpeech.SUCCESS) {
            clearPendingAutoExecutionAfterSpeech()
            clearActiveSpeechPlayback()
            return false
        }
        return true
    }

    private fun speakMessageWithCloudTts(
        text: String,
        settings: VoiceRecognitionSettings,
        messageId: String,
        deferredExecutionStore: PrototypeStoreData? = null
    ): Boolean {
        val providerLabel = resolveCloudProviderLabel(settings)
        when (settings.cloudRegion) {
            VoiceCloudRegion.CN -> {
                when (settings.cnProvider) {
                    VoiceCnProvider.TENCENT -> {
                        if (!tencentTtsClient.isConfigured()) {
                            Snackbar.make(
                                binding.root,
                                getString(R.string.message_action_tts_cloud_not_configured, providerLabel),
                                Snackbar.LENGTH_SHORT
                            ).show()
                            return false
                        }
                        if (deferredExecutionStore != null) {
                            pendingAutoExecutionStore = deferredExecutionStore
                            pendingAutoExecutionCloudMessageId = messageId
                        }
                        activeSpeechUtteranceId = null
                        updateSpeakingMessage(messageId)
                        cloudTtsJob = lifecycleScope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    tencentTtsClient.synthesizeMp3(
                                        text = text,
                                        voiceType = settings.tencentTtsVoiceType
                                    )
                                }
                            }.onSuccess { audioBytes ->
                                playCloudTtsAudio(audioBytes, messageId)
                            }.onFailure { error ->
                                if (error is kotlinx.coroutines.CancellationException) {
                                    if (activeSpeechMessageId == messageId) {
                                        clearActiveSpeechPlayback()
                                    }
                                    return@launch
                                }
                                Log.e(VOICE_DEBUG_TAG, "Cloud TTS failed", error)
                                val reason = error.message?.takeIf { it.isNotBlank() }
                                    ?: getString(R.string.request_failed_generic)
                                if (activeSpeechMessageId == messageId) {
                                    clearActiveSpeechPlayback()
                                }
                                Snackbar.make(
                                    binding.root,
                                    getString(R.string.message_action_tts_cloud_failed, reason),
                                    Snackbar.LENGTH_SHORT
                                ).show()
                                maybeStartPendingAutoExecutionForCloudMessage(messageId)
                            }
                        }
                        return true
                    }

                    VoiceCnProvider.ALI -> {
                        Snackbar.make(
                            binding.root,
                            getString(R.string.message_action_tts_cloud_not_ready, providerLabel),
                            Snackbar.LENGTH_SHORT
                        ).show()
                        return false
                    }
                }
            }

            VoiceCloudRegion.GLOBAL -> {
                Snackbar.make(
                    binding.root,
                    getString(R.string.message_action_tts_cloud_not_ready, providerLabel),
                    Snackbar.LENGTH_SHORT
                ).show()
                return false
            }
        }
    }

    private fun resolveCloudProviderLabel(settings: VoiceRecognitionSettings): String {
        return when (settings.cloudRegion) {
            VoiceCloudRegion.CN -> when (settings.cnProvider) {
                VoiceCnProvider.TENCENT -> getString(R.string.settings_voice_provider_tencent)
                VoiceCnProvider.ALI -> getString(R.string.settings_voice_provider_ali)
            }

            VoiceCloudRegion.GLOBAL -> when (settings.globalProvider) {
                VoiceGlobalProvider.DEEPGRAM -> getString(R.string.settings_voice_provider_deepgram)
                VoiceGlobalProvider.GOOGLE -> getString(R.string.settings_voice_provider_google)
            }
        }
    }

    private fun playCloudTtsAudio(audioBytes: ByteArray, messageId: String) {
        runCatching {
            releaseCloudTtsPlayback()
            val file = File(cacheDir, "tts_${System.currentTimeMillis()}.mp3")
            FileOutputStream(file).use { output ->
                output.write(audioBytes)
            }
            cloudTtsTempFile = file

            val player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { mp ->
                    releaseCloudTtsPlayback(mp)
                    handleCloudSpeechPlaybackFinished(messageId)
                }
                setOnErrorListener { mp, _, _ ->
                    releaseCloudTtsPlayback(mp)
                    handleCloudSpeechPlaybackFinished(messageId)
                    false
                }
                prepare()
                start()
            }
            cloudTtsPlayer = player
        }.onFailure { error ->
            Log.e(VOICE_DEBUG_TAG, "Play cloud TTS audio failed", error)
            val reason = error.message?.takeIf { it.isNotBlank() }
                ?: getString(R.string.request_failed_generic)
            if (activeSpeechMessageId == messageId) {
                clearActiveSpeechPlayback()
            }
            Snackbar.make(
                binding.root,
                getString(R.string.message_action_tts_cloud_failed, reason),
                Snackbar.LENGTH_SHORT
            ).show()
            maybeStartPendingAutoExecutionForCloudMessage(messageId)
        }
    }

    private fun buildMessageUtteranceId(messageId: String): String {
        return "message_$messageId"
    }

    private fun clearPendingAutoExecutionAfterSpeech() {
        pendingAutoExecutionStore = null
        pendingAutoExecutionUtteranceId = null
        pendingAutoExecutionCloudMessageId = null
    }

    private fun handleLocalSpeechPlaybackFinished(utteranceId: String?) {
        if (utteranceId != null && activeSpeechUtteranceId == utteranceId) {
            clearActiveSpeechPlayback()
        }
        maybeStartPendingAutoExecutionForUtterance(utteranceId)
    }

    private fun handleCloudSpeechPlaybackFinished(messageId: String) {
        if (activeSpeechMessageId == messageId) {
            clearActiveSpeechPlayback()
        }
        maybeStartPendingAutoExecutionForCloudMessage(messageId)
    }

    private fun maybeStartPendingAutoExecutionForUtterance(utteranceId: String?) {
        val expectedUtteranceId = pendingAutoExecutionUtteranceId ?: return
        if (expectedUtteranceId != utteranceId) {
            return
        }
        runOnUiThread {
            startPendingAutoExecutionAfterSpeech()
        }
    }

    private fun maybeStartPendingAutoExecutionForCloudMessage(messageId: String) {
        val expectedMessageId = pendingAutoExecutionCloudMessageId ?: return
        if (expectedMessageId != messageId) {
            return
        }
        runOnUiThread {
            startPendingAutoExecutionAfterSpeech()
        }
    }

    private fun startPendingAutoExecutionAfterSpeech() {
        val pendingStore = pendingAutoExecutionStore ?: return
        clearPendingAutoExecutionAfterSpeech()
        startAutoExecutionAfterSpeech(pendingStore)
    }

    private fun startAutoExecutionAfterSpeech(store: PrototypeStoreData) {
        setLoading(true)
        lifecycleScope.launch {
            performConversationAutoExecution(store)
            setLoading(false)
        }
    }

    private suspend fun performConversationAutoExecution(store: PrototypeStoreData) {
        runCatching {
            executionEntryOrchestrator.autoStartExecution(store)
        }.onSuccess { executionOutcome ->
            storeData = executionOutcome.updatedStore
            render(storeData)
            Snackbar.make(binding.root, executionOutcome.message, Snackbar.LENGTH_SHORT).show()
        }.onFailure { throwable ->
            render(storeData)
            Snackbar.make(binding.root, buildFailureMessage(throwable), Snackbar.LENGTH_LONG).show()
        }
    }

    private fun copyMessage(message: ChatMessage) {
        val text = message.content.trim()
        if (text.isBlank()) {
            Snackbar.make(binding.root, getString(R.string.message_action_copy_empty), Snackbar.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Snackbar.make(binding.root, getString(R.string.message_action_copy_done), Snackbar.LENGTH_SHORT).show()
    }

    private fun forwardMessage(message: ChatMessage) {
        val text = message.content.trim()
        if (text.isBlank()) {
            Snackbar.make(binding.root, getString(R.string.message_action_forward_empty), Snackbar.LENGTH_SHORT).show()
            return
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.message_action_forward_title)))
    }

    private fun updatePendingConversation(messages: List<ChatMessage>) {
        runOnUiThread {
            pendingConversationMessages = messages
            render(storeData)
        }
    }

    private fun showCaptureCompressionScaleDialog() {
        val currentScale = captureCompressionSettingsStore.readScale()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.settings_capture_compression_input_hint)
            setText(currentScale.toString())
            setSelection(text?.length ?: 0)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_capture_compression_dialog_title)
            .setMessage(R.string.settings_capture_compression_dialog_message)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsedScale = input.text?.toString()?.trim()?.toIntOrNull()
                if (parsedScale == null || parsedScale < 1) {
                    input.error = getString(R.string.settings_capture_compression_error)
                    return@setOnClickListener
                }
                input.error = null
                val appliedScale = captureCompressionSettingsStore.writeScale(parsedScale)
                render(storeData)
                Snackbar.make(
                    binding.root,
                    getString(
                        R.string.settings_capture_compression_updated,
                        formatCaptureCompressionSummary(appliedScale)
                    ),
                    Snackbar.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun startExecution() {
        clearPendingAutoExecutionAfterSpeech()
        setLoading(true)
        lifecycleScope.launch {
            runCatching {
                executionEntryOrchestrator.startManualExecution()
            }.onSuccess { executionOutcome ->
                val updatedStore = executionOutcome.updatedStore
                storeData = updatedStore
                render(storeData)
                Snackbar.make(binding.root, executionOutcome.message, Snackbar.LENGTH_SHORT).show()
            }.onFailure { throwable ->
                render(storeData)
                Snackbar.make(binding.root, buildFailureMessage(throwable), Snackbar.LENGTH_LONG).show()
            }
            setLoading(false)
        }
    }

    private fun startPreparing() {
        clearPendingAutoExecutionAfterSpeech()
        currentSurface = MainSurface.CONSOLE
        currentConsoleChannel = ConsoleChannel.ALL
        lifecycleScope.launch {
            storeData = executionEntryOrchestrator.startPreparing()
            render(storeData)
            Snackbar.make(binding.root, getString(R.string.preparing_started), Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun runEarningsScanOnly() {
        if (PrototypeAccessibilityService.instance == null) {
            render(storeData)
            Snackbar.make(binding.root, getString(R.string.accessibility_service_not_connected), Snackbar.LENGTH_LONG).show()
            startupShizukuBootstrapAttempted = false
            maybeRunStartupShizukuBootstrap()
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            runCatching {
                earningsHubStoreController.runFullScan()
            }.onSuccess { result ->
                storeData = result.updatedStore
                render(storeData)
                Snackbar.make(
                    binding.root,
                    getString(R.string.earnings_operation_completed, result.summary),
                    Snackbar.LENGTH_SHORT
                ).show()
            }.onFailure { throwable ->
                render(storeData)
                Snackbar.make(binding.root, buildFailureMessage(throwable), Snackbar.LENGTH_LONG).show()
            }
            setLoading(false)
        }
    }

    private fun compileEarningsPlanFromCurrentScan() {
        setLoading(true)
        lifecycleScope.launch {
            runCatching {
                earningsHubStoreController.compilePlanFromCurrentScan()
            }.onSuccess { result ->
                storeData = result.updatedStore
                render(storeData)
                Snackbar.make(
                    binding.root,
                    getString(R.string.earnings_operation_completed, result.summary),
                    Snackbar.LENGTH_SHORT
                ).show()
            }.onFailure { throwable ->
                render(storeData)
                Snackbar.make(binding.root, buildFailureMessage(throwable), Snackbar.LENGTH_LONG).show()
            }
            setLoading(false)
        }
    }

    private fun toggleEarningsAutomation() {
        val enabled = !storeData.earningsHubOrDefault().enabled
        setLoading(true)
        lifecycleScope.launch {
            runCatching {
                earningsHubStoreController.setAutomationEnabled(enabled)
            }.onSuccess { result ->
                storeData = result.updatedStore
                render(storeData)
                Snackbar.make(
                    binding.root,
                    getString(R.string.earnings_operation_completed, result.summary),
                    Snackbar.LENGTH_SHORT
                ).show()
            }.onFailure { throwable ->
                render(storeData)
                Snackbar.make(binding.root, buildFailureMessage(throwable), Snackbar.LENGTH_LONG).show()
            }
            setLoading(false)
        }
    }

    private fun runEarningsTick() {
        setLoading(true)
        lifecycleScope.launch {
            runCatching {
                earningsHubStoreController.tick()
            }.onSuccess { result ->
                storeData = result.updatedStore
                render(storeData)
                Snackbar.make(
                    binding.root,
                    getString(R.string.earnings_operation_completed, result.summary),
                    Snackbar.LENGTH_SHORT
                ).show()
            }.onFailure { throwable ->
                render(storeData)
                Snackbar.make(binding.root, buildFailureMessage(throwable), Snackbar.LENGTH_LONG).show()
            }
            setLoading(false)
        }
    }

    private fun render(store: PrototypeStoreData) {
        val runtimePreferences = store.resolveSemanticRuntimePreferences() ?: SemanticRuntimePreferences()
        val constrainedTurnOptions = coerceSemanticTurnOptions(runtimePreferences, chatTurnOptions)
        if (constrainedTurnOptions != chatTurnOptions) {
            chatTurnOptions = constrainedTurnOptions
        }
        syncingShizukuAutoPrepareSwitch = true
        try {
            consoleRenderAdapter.render(
                store = store,
                state = ConsoleRenderState(
                    pendingConversationMessages = pendingConversationMessages,
                    chatTurnOptions = chatTurnOptions,
                    showingSettings = currentSurface == MainSurface.SETTINGS,
                    showingAllChannel = currentConsoleChannel == ConsoleChannel.ALL,
                    showingExecutionChannel = currentConsoleChannel == ConsoleChannel.EXECUTION,
                    showingConversationChannel = currentConsoleChannel == ConsoleChannel.CONVERSATION,
                    shizukuSurfaceState = buildShizukuSurfaceState()
                )
            )
        } finally {
            syncingShizukuAutoPrepareSwitch = false
        }
        binding.inputEditText.hint = getString(R.string.message_hint)
        refreshComposerActionButtons()
        renderEarningsHub(store)
        refreshLanguageCard()
        val loading = binding.loadingRow.visibility == View.VISIBLE
        refreshConversationControlAvailability(store, loading)
        refreshVisionControlAvailability(loading)
        refreshSearchProviderAvailability(loading = loading)
        refreshVoiceSettingAvailability(loading)
        refreshDemoOnboardingUi(force = false)
    }

    private fun renderEarningsHub(store: PrototypeStoreData) {
        val hub = store.earningsHubOrDefault()
        val projection = hub.uiProjectionState
        val nextWake = hub.executionLaneState.nextWakeAt?.let { timestamp ->
            lastUpdatedFormatter.format(Date(timestamp))
        } ?: "none"
        val lastScan = hub.lastFullScanAt?.let { timestamp ->
            lastUpdatedFormatter.format(Date(timestamp))
        } ?: "never"
        binding.earningsStatusText.text = buildString {
            append("enabled=")
            append(hub.enabled)
            append("\nlane=")
            append(hub.executionLaneState.laneStatus.name)
            append("\nnextWake=")
            append(nextWake)
            append("\nlastScan=")
            append(lastScan)
            hub.executionLaneState.blockReason?.takeIf { value -> value.isNotBlank() }?.let { reason ->
                append("\nblock=")
                append(reason)
            }
        }.ifBlank { getString(R.string.earnings_status_empty) }
        binding.earningsScanSummaryText.text = projection.scanSummaryCard
            ?.takeIf { value -> value.isNotBlank() }
            ?: hub.scanState.lastScanSummary
            ?: getString(R.string.earnings_scan_empty)
        binding.earningsQueueText.text = projection.importantQueueCards
            .joinToString("\n")
            .ifBlank { getString(R.string.earnings_queue_empty) }
        val rewardLines = projection.dailyRewardSummaryCards.takeIf { cards -> cards.isNotEmpty() }
            ?: hub.rewardLedgerState.todayByApp.map { summary ->
                "${summary.appId.displayName}: ${summary.totalCoins} coins (${summary.successCount})"
            }
        binding.earningsRewardText.text = rewardLines
            .take(6)
            .joinToString("\n")
            .ifBlank { getString(R.string.earnings_rewards_empty) }
        val diagnostics = (hub.diagnosticsState.runtimeDiagnostics + hub.diagnosticsState.plannerDiagnostics.map { item ->
            "${item.severity}: ${item.message}"
        }).takeLast(4)
        binding.earningsDiagnosticsText.text = diagnostics
            .joinToString("\n")
            .ifBlank { getString(R.string.earnings_diagnostics_empty) }
        binding.toggleEarningsAutomationButton.text = getString(
            if (hub.enabled) R.string.earnings_disable_automation else R.string.earnings_enable_automation
        )
    }

    private fun setLoading(loading: Boolean) {
        binding.loadingRow.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) {
            startLoadingAnimation()
        } else {
            stopLoadingAnimation()
        }
        binding.inputModeToggleButton.isEnabled = !loading
        binding.holdToTalkButton.isEnabled = !loading
        binding.pickImageButton.isEnabled = !loading
        binding.sendMessageButton.isEnabled = !loading
        binding.captureImageButton.isEnabled = !loading
        binding.inputEditText.isEnabled = !loading
        binding.processReviewInputEditText.isEnabled = !loading
        refreshConversationControlAvailability(storeData, loading)
        refreshVisionControlAvailability(loading)
        binding.processThumbsUpButton.isEnabled = !loading
        binding.processThumbsDownButton.isEnabled = !loading
        binding.captureCompressionValueText.isEnabled = !loading
        binding.providerProfileValueText.isEnabled = !loading
        binding.semanticModelValueText.isEnabled = !loading
        binding.visionModelValueText.isEnabled = !loading
        refreshSearchProviderAvailability(loading)
        refreshVoiceSettingAvailability(loading)
        binding.runToolDiscoveryButton.isEnabled = !loading
        binding.runEarningsScanButton.isEnabled = !loading
        binding.compileEarningsPlanButton.isEnabled = !loading
        binding.toggleEarningsAutomationButton.isEnabled = !loading
        binding.runEarningsTickButton.isEnabled = !loading
        binding.runPreferenceDiscoveryButton.isEnabled = !loading
        binding.runPreferenceExtractionButton.isEnabled = !loading
        binding.runProcessExtractionButton.isEnabled = !loading
        binding.requestScreenCaptureButton.isEnabled = !loading
        binding.prepareWithShizukuButton.isEnabled = !loading
        binding.switchLanguageButton.isEnabled = !loading
        binding.shizukuAutoPrepareSwitch.isEnabled = !loading && buildShizukuSurfaceState().autoPrepareSwitchEnabled
        preparingEntrySidecar.updateLoadingState(binding, loading, storeData)
    }

    private fun refreshConversationControlAvailability(
        store: PrototypeStoreData,
        loading: Boolean
    ) {
        binding.thinkingSwitch.isEnabled = !loading
        binding.searchSwitch.isEnabled = !loading
    }

    private fun refreshVisionControlAvailability(loading: Boolean) {
        binding.visionThinkingSwitch.isEnabled = !loading
        binding.visionSearchSwitch.isEnabled = !loading
    }

    private fun loadingAnimationFrames(): Array<String> {
        return arrayOf(
            getString(R.string.thinking_frame_1),
            getString(R.string.thinking_frame_2),
            getString(R.string.thinking_frame_3)
        )
    }

    private fun refreshLanguageCard() {
        val currentLanguage = AppLocaleManager.currentLanguage(this)
        binding.languageCurrentValueText.text = getString(
            if (currentLanguage == AppLanguage.ENGLISH) {
                R.string.settings_language_current_english
            } else {
                R.string.settings_language_current_chinese
            }
        )
        binding.switchLanguageButton.text = getString(
            if (currentLanguage == AppLanguage.ENGLISH) {
                R.string.settings_language_switch_to_chinese
            } else {
                R.string.settings_language_switch_to_english
            }
        )
    }

    private fun toggleLanguage() {
        val nextLanguage = AppLocaleManager.toggle(applicationContext)
        val messageResId = if (nextLanguage == AppLanguage.ENGLISH) {
            R.string.settings_language_switching_english
        } else {
            R.string.settings_language_switching_chinese
        }
        Snackbar.make(binding.root, getString(messageResId), Snackbar.LENGTH_SHORT).show()
        binding.root.post {
            recreate()
        }
    }

    private fun formatProviderProfileLabel(profileId: ProviderProfileId): String {
        return when (profileId) {
            ProviderProfileId.DOMESTIC_DEFAULT -> getString(R.string.settings_provider_profile_domestic)
            ProviderProfileId.GLOBAL_DEFAULT -> getString(R.string.settings_provider_profile_global)
            ProviderProfileId.CUSTOM -> getString(R.string.settings_provider_profile_custom)
        }
    }

    private fun formatSearchProviderLabel(provider: SearchProviderKind): String {
        return when (provider) {
            SearchProviderKind.ALIYUN_OPENSEARCH -> getString(R.string.settings_search_provider_aliyun)
            SearchProviderKind.GOOGLE_CSE -> getString(R.string.settings_search_provider_google)
        }
    }

    private fun refreshSearchProviderAvailability(loading: Boolean) {
        val searchProviderEditable = !loading
        binding.searchProviderValueText.isEnabled = searchProviderEditable
        binding.searchProviderValueText.isClickable = searchProviderEditable
        binding.searchProviderValueText.isFocusable = searchProviderEditable
        binding.searchProviderValueText.alpha = if (searchProviderEditable) 1f else 0.5f
    }

    private fun refreshVoiceSettingAvailability(loading: Boolean) {
        val settings = voiceRecognitionSettingsStore.read()
        val canEdit = !loading
        binding.voiceRecognitionModeValueText.isEnabled = canEdit
        binding.voiceRecognitionModeValueText.isClickable = canEdit
        binding.voiceRecognitionModeValueText.isFocusable = canEdit
        binding.voiceRecognitionModeValueText.alpha = if (canEdit) 1f else 0.5f

        val regionEditable = canEdit && settings.mode == VoiceRecognitionMode.CLOUD
        binding.voiceCloudRegionValueText.isEnabled = regionEditable
        binding.voiceCloudRegionValueText.isClickable = regionEditable
        binding.voiceCloudRegionValueText.isFocusable = regionEditable
        binding.voiceCloudRegionValueText.alpha = if (regionEditable) 1f else 0.5f

        val cnEditable = regionEditable && settings.cloudRegion == VoiceCloudRegion.CN
        binding.voiceCloudCnProviderValueText.isEnabled = cnEditable
        binding.voiceCloudCnProviderValueText.isClickable = cnEditable
        binding.voiceCloudCnProviderValueText.isFocusable = cnEditable
        binding.voiceCloudCnProviderValueText.alpha = if (cnEditable) 1f else 0.5f

        val tencentVoiceEditable = cnEditable && settings.cnProvider == VoiceCnProvider.TENCENT
        binding.voiceCloudTencentTtsVoiceValueText.isEnabled = tencentVoiceEditable
        binding.voiceCloudTencentTtsVoiceValueText.isClickable = tencentVoiceEditable
        binding.voiceCloudTencentTtsVoiceValueText.isFocusable = tencentVoiceEditable
        binding.voiceCloudTencentTtsVoiceValueText.alpha = if (tencentVoiceEditable) 1f else 0.5f

        val globalEditable = regionEditable && settings.cloudRegion == VoiceCloudRegion.GLOBAL
        binding.voiceCloudGlobalProviderValueText.isEnabled = globalEditable
        binding.voiceCloudGlobalProviderValueText.isClickable = globalEditable
        binding.voiceCloudGlobalProviderValueText.isFocusable = globalEditable
        binding.voiceCloudGlobalProviderValueText.alpha = if (globalEditable) 1f else 0.5f

        binding.voiceAutoSpeakValueText.isEnabled = canEdit
        binding.voiceAutoSpeakValueText.isClickable = canEdit
        binding.voiceAutoSpeakValueText.isFocusable = canEdit
        binding.voiceAutoSpeakValueText.alpha = if (canEdit) 1f else 0.5f
    }

    private fun startLoadingAnimation() {
        if (loadingAnimationRunning) {
            return
        }
        loadingAnimationRunning = true
        loadingAnimationFrame = 0
        loadingAnimationRunnable.run()
    }

    private fun stopLoadingAnimation() {
        loadingAnimationRunning = false
        if (!::binding.isInitialized) {
            return
        }
        binding.loadingText.removeCallbacks(loadingAnimationRunnable)
        binding.loadingText.text = getString(R.string.thinking)
    }

    private fun submitCompletedExecutionFeedback(feedbackType: ProcessFeedbackType) {
        currentSurface = MainSurface.CONSOLE
        currentConsoleChannel = ConsoleChannel.CONVERSATION
        val feedbackComment = binding.processReviewInputEditText.text?.toString().orEmpty()
        setLoading(true)
        lifecycleScope.launch {
            runCatching {
                chatTurnOrchestrator.submitCompletedExecutionFeedback(
                    feedbackType = feedbackType,
                    comment = feedbackComment
                )
            }.onSuccess { result ->
                storeData = result.updatedStore
                if (result.applied) {
                    binding.processReviewInputEditText.setText("")
                }
                render(storeData)
                Snackbar.make(binding.root, result.message, Snackbar.LENGTH_SHORT).show()
            }.onFailure { throwable ->
                render(storeData)
                Snackbar.make(binding.root, buildFailureMessage(throwable), Snackbar.LENGTH_LONG).show()
            }
            setLoading(false)
        }
    }

    private fun refreshSurface(showMessage: Boolean = false) {
        lifecycleScope.launch {
            storeData = prototypeStore.load()
            render(storeData)
            refreshDemoOnboardingUi(force = true)
            if (showMessage) {
                Snackbar.make(binding.root, getString(R.string.status_refreshed), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun showConsoleChannel(channel: ConsoleChannel) {
        currentSurface = MainSurface.CONSOLE
        currentConsoleChannel = channel
        render(storeData)
    }

    private fun shouldBlockDemoOnboardingAction(requiredStep: DemoOnboardingStep): Boolean {
        if (DemoReleaseControl.isOnboardingCompleted()) {
            return false
        }
        val currentStep = resolveDemoOnboardingStep()
        if (currentStep == requiredStep) {
            return false
        }
        Snackbar.make(binding.root, getString(R.string.demo_onboarding_wrong_step), Snackbar.LENGTH_SHORT).show()
        return true
    }

    private fun refreshDemoOnboardingUi(force: Boolean) {
        if (DemoReleaseControl.isOnboardingCompleted()) {
            demoOnboardingStep = DemoOnboardingStep.DONE
            clearDemoHighlightAnimation()
            return
        }
        maybeCompleteAccessibilityOnboardingStep()
        val resolvedStep = resolveDemoOnboardingStep()
        if (!force && resolvedStep == demoOnboardingStep) {
            return
        }
        demoOnboardingStep = resolvedStep
        when (resolvedStep) {
            DemoOnboardingStep.TOOL_DISCOVERY -> {
                applyDemoHighlightAnimation(binding.runToolDiscoveryButton)
            }

            DemoOnboardingStep.ACCESSIBILITY -> {
                applyDemoHighlightAnimation(binding.openAccessibilitySettingsButton)
            }

            DemoOnboardingStep.SCREEN_CAPTURE -> {
                applyDemoHighlightAnimation(binding.requestScreenCaptureButton)
            }

            DemoOnboardingStep.DONE -> {
                DemoReleaseControl.markOnboardingCompleted()
                clearDemoHighlightAnimation()
                currentSurface = MainSurface.CONSOLE
                currentConsoleChannel = ConsoleChannel.CONVERSATION
                render(storeData)
                Snackbar.make(binding.root, getString(R.string.demo_onboarding_completed), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun resolveDemoOnboardingStep(): DemoOnboardingStep {
        if (!DemoReleaseControl.isToolDiscoveryOnboardingCompleted()) {
            return DemoOnboardingStep.TOOL_DISCOVERY
        }
        if (!DemoReleaseControl.isAccessibilityOnboardingCompleted()) {
            return DemoOnboardingStep.ACCESSIBILITY
        }
        if (!DemoReleaseControl.isScreenCaptureOnboardingCompleted()) {
            return DemoOnboardingStep.SCREEN_CAPTURE
        }
        return DemoOnboardingStep.DONE
    }

    private fun maybeCompleteAccessibilityOnboardingStep() {
        if (DemoReleaseControl.isOnboardingCompleted()) {
            return
        }
        if (DemoReleaseControl.isAccessibilityOnboardingCompleted()) {
            return
        }
        if (!DemoReleaseControl.hasOpenedAccessibilityOnboardingSettings()) {
            return
        }
        if (!isAccessibilityServiceEnabledForDemo()) {
            return
        }
        DemoReleaseControl.markAccessibilityOnboardingCompleted()
    }

    private fun isAccessibilityServiceEnabledForDemo(): Boolean {
        val expectedComponent = ComponentName(this, PrototypeAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return PrototypeAccessibilityService.instance != null || enabledServices.split(':').any { service ->
            service.equals(expectedComponent, ignoreCase = true)
        }
    }

    private fun applyDemoHighlightAnimation(target: View) {
        clearDemoHighlightAnimation()
        resetDemoOnboardingButtonStyles()
        target.alpha = 1f
        demoHighlightAnimator = ObjectAnimator.ofFloat(target, View.ALPHA, 1f, 0.35f, 1f).apply {
            duration = 900L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun clearDemoHighlightAnimation() {
        demoHighlightAnimator?.cancel()
        demoHighlightAnimator = null
        resetDemoOnboardingButtonStyles()
    }

    private fun resetDemoOnboardingButtonStyles() {
        binding.openAccessibilitySettingsButton.alpha = 1f
        binding.requestScreenCaptureButton.alpha = 1f
        binding.runToolDiscoveryButton.alpha = 1f
    }

    private fun showProviderProfileDialog() {
        val currentConfig = ProviderProfileRuntime.current()
        val profileOptions = ProviderProfileId.values()
        val labels = arrayOf(
            getString(R.string.settings_provider_profile_domestic),
            getString(R.string.settings_provider_profile_global),
            getString(R.string.settings_provider_profile_custom)
        )
        var selectedIndex = profileOptions.indexOf(currentConfig.profileId).takeIf { it >= 0 } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_provider_profile_dialog_title)
            .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                updateProviderProfile(profileOptions[selectedIndex])
            }
            .show()
    }

    private fun updateProviderProfile(profileId: ProviderProfileId) {
        val updatedConfig = providerProfileSettingsStore.writeConfig(
            buildProviderProfilePreset(
                profileId = profileId,
                currentConfig = ProviderProfileRuntime.current()
            )
        )
        render(storeData)
        Snackbar.make(
            binding.root,
            getString(
                R.string.settings_provider_profile_updated,
                formatProviderProfileLabel(updatedConfig.profileId)
            ),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun showSemanticModelTierDialog() {
        val currentConfig = ProviderProfileRuntime.current()
        if (currentConfig.profileId == ProviderProfileId.CUSTOM) {
            showCustomSemanticModelDialog(currentConfig)
            return
        }
        val currentTier = (storeData.semanticRuntimePreferences ?: SemanticRuntimePreferences()).modelTier
        val options = arrayOf(
            getString(R.string.settings_semantic_mode_fast),
            getString(R.string.settings_semantic_mode_expert)
        )
        var selectedIndex = currentTier.ordinal
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_semantic_model_dialog_title)
            .setSingleChoiceItems(options, currentTier.ordinal) { _, which ->
                selectedIndex = which
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                updateSemanticModelTier(SemanticModelTier.values()[selectedIndex])
            }
            .show()
    }

    private fun updateSemanticModelTier(tier: SemanticModelTier) {
        setLoading(true)
        lifecycleScope.launch {
            runCatching {
                prototypeStore.updateSemanticRuntimePreferences(
                    SemanticRuntimePreferences(modelTier = tier)
                )
            }.onSuccess { updatedStore ->
                storeData = updatedStore
                render(storeData)
            }.onFailure { throwable ->
                render(storeData)
                Snackbar.make(binding.root, buildFailureMessage(throwable), Snackbar.LENGTH_LONG).show()
            }
            setLoading(false)
        }
    }

    private fun showCustomSemanticModelDialog(currentConfig: ProviderProfileRuntimeConfig) {
        val options = CUSTOM_SEMANTIC_MODEL_OPTIONS.toTypedArray()
        val currentModel = normalizeSemanticCustomModelSelection(
            currentConfig.semantic.fastModel,
            CUSTOM_SEMANTIC_MODEL_OPTIONS.first()
        )
        var selectedIndex = CUSTOM_SEMANTIC_MODEL_OPTIONS.indexOf(currentModel).takeIf { it >= 0 } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_semantic_model_dialog_title)
            .setSingleChoiceItems(options, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                updateCustomSemanticModel(options[selectedIndex])
            }
            .show()
    }

    private fun updateCustomSemanticModel(modelName: String) {
        val updatedConfig = providerProfileSettingsStore.writeConfig(
            withCustomSemanticModel(
                config = ProviderProfileRuntime.current(),
                modelName = modelName
            )
        )
        render(storeData)
        Snackbar.make(
            binding.root,
            getString(R.string.settings_semantic_model_updated, updatedConfig.semantic.fastModel),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun showQwenVisionModelDialog() {
        val currentConfig = ProviderProfileRuntime.current()
        if (currentConfig.profileId == ProviderProfileId.CUSTOM) {
            showCustomVisionModelDialog(currentConfig)
            return
        }
        val options = arrayOf(
            getString(R.string.settings_semantic_mode_fast),
            getString(R.string.settings_semantic_mode_expert)
        )
        var selectedIndex = currentConfig.vision.modelTier.ordinal
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_vision_model_dialog_title)
            .setSingleChoiceItems(options, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                updateVisionModelTier(SemanticModelTier.values()[selectedIndex])
            }
            .show()
    }

    private fun showCustomVisionModelDialog(currentConfig: ProviderProfileRuntimeConfig) {
        val options = CUSTOM_VISION_MODEL_OPTIONS.toTypedArray()
        val currentModel = normalizeVisionCustomModelSelection(
            currentConfig.vision.model,
            CUSTOM_VISION_MODEL_OPTIONS.first()
        )
        var selectedIndex = CUSTOM_VISION_MODEL_OPTIONS.indexOf(currentModel).takeIf { it >= 0 } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_vision_model_dialog_title)
            .setSingleChoiceItems(options, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                updateQwenVisionModel(options[selectedIndex])
            }
            .show()
    }

    private fun updateQwenVisionModel(modelName: String) {
        val updatedConfig = providerProfileSettingsStore.writeConfig(
            withCustomVisionModel(
                config = ProviderProfileRuntime.current(),
                modelName = modelName
            )
        )
        render(storeData)
        Snackbar.make(
            binding.root,
            getString(R.string.settings_vision_model_updated, updatedConfig.vision.model),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun updateVisionModelTier(tier: SemanticModelTier) {
        val updatedConfig = providerProfileSettingsStore.writeConfig(
            withVisionModelTier(
                config = ProviderProfileRuntime.current(),
                tier = tier
            )
        )
        render(storeData)
        Snackbar.make(
            binding.root,
            getString(R.string.settings_vision_model_updated, updatedConfig.vision.model),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun runToolDiscoveryScan() {
        setLoading(true)
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    toolspaceCatalogManager.refreshFromDevice()
                }
            }.onSuccess {
                if (!DemoReleaseControl.isOnboardingCompleted()) {
                    DemoReleaseControl.markToolDiscoveryOnboardingCompleted()
                }
                currentSurface = MainSurface.SETTINGS
                render(storeData)
                refreshDemoOnboardingUi(force = true)
                Snackbar.make(binding.root, getString(R.string.tool_discovery_scan_complete), Snackbar.LENGTH_SHORT).show()
            }.onFailure { throwable ->
                refreshDemoOnboardingUi(force = true)
                Snackbar.make(binding.root, buildFailureMessage(throwable), Snackbar.LENGTH_LONG).show()
            }
            setLoading(false)
        }
    }

    private fun showSearchProviderDialog() {
        val currentConfig = ProviderProfileRuntime.current()
        val providerOptions = arrayOf(
            SearchProviderKind.ALIYUN_OPENSEARCH,
            SearchProviderKind.GOOGLE_CSE
        )
        val labels = arrayOf(
            getString(R.string.settings_search_provider_aliyun),
            getString(R.string.settings_search_provider_google)
        )
        var selectedIndex = providerOptions.indexOf(currentConfig.search.provider).takeIf { it >= 0 } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_search_provider_dialog_title)
            .setSingleChoiceItems(labels, selectedIndex) { _, which ->
                selectedIndex = which
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                updateSearchProvider(providerOptions[selectedIndex])
            }
            .show()
    }

    private fun updateSearchProvider(provider: SearchProviderKind) {
        val currentConfig = ProviderProfileRuntime.current()
        if (currentConfig.search.provider == provider) {
            return
        }
        val updatedConfig = providerProfileSettingsStore.writeConfig(
            withSearchProvider(
                config = currentConfig,
                provider = provider
            )
        )
        render(storeData)
        Snackbar.make(
            binding.root,
            getString(
                R.string.settings_search_provider_updated,
                formatSearchProviderLabel(updatedConfig.search.provider)
            ),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun runPreferenceDiscovery() {
        if (!ProviderRuntimeConfigs.vision.isConfigured()) {
            Snackbar.make(
                binding.root,
                getString(R.string.preference_discovery_vision_unavailable),
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        val installedTargetsByDomain = PreferenceDiscoveryCatalog.domains()
            .map { domain ->
                domain to PreferenceDiscoveryCatalog.installedTargets(domain) { packageName ->
                    packageManager.getLaunchIntentForPackage(packageName) != null
                }
            }
            .filter { (_, targets) -> targets.isNotEmpty() }
        if (installedTargetsByDomain.isEmpty()) {
            Snackbar.make(
                binding.root,
                getString(R.string.preference_discovery_no_installed_targets),
                Snackbar.LENGTH_LONG
            ).show()
            return
        }

        showPreferenceDiscoveryDomainDialog(installedTargetsByDomain)
    }

    private fun showPreferenceDiscoveryDomainDialog(
        installedTargetsByDomain: List<Pair<CapabilityDomain, List<PreferenceDiscoveryAppTarget>>>
    ) {
        val domainLabels = installedTargetsByDomain.map { (domain, targets) ->
            "${PreferenceDiscoveryCatalog.displayName(domain)} (${targets.size})"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.preference_discovery_select_domain_title)
            .setItems(domainLabels) { _, which ->
                val (selectedDomain, targets) = installedTargetsByDomain[which]
                showPreferenceDiscoveryAppDialog(selectedDomain, targets)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPreferenceDiscoveryAppDialog(
        selectedDomain: CapabilityDomain,
        targets: List<PreferenceDiscoveryAppTarget>
    ) {
        val appLabels = targets.map { target -> target.displayName }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.preference_discovery_select_app_title, PreferenceDiscoveryCatalog.displayName(selectedDomain)))
            .setItems(appLabels) { _, which ->
                showPreferenceDiscoveryPageCountDialog(targets[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPreferenceDiscoveryPageCountDialog(target: PreferenceDiscoveryAppTarget) {
        val pageOptions = listOf(3, 5, 10, 50)
        val pageLabels = pageOptions.map { pageCount -> pageCount.toString() }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.preference_discovery_select_page_count_title, target.displayName))
            .setItems(pageLabels) { _, which ->
                executePreferenceDiscovery(target, pageOptions[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun executePreferenceDiscovery(target: PreferenceDiscoveryAppTarget, pageCount: Int) {
        setLoading(true)
        lifecycleScope.launch {
            runCatching {
                val latestStore = prototypeStore.load()
                val discoveryOutcome = withContext(Dispatchers.IO) {
                    preferenceDiscoveryManualScanRunner.run(
                        store = latestStore,
                        request = PreferenceDiscoveryManualScanRequest(
                            target = target,
                            pageCount = pageCount,
                            countdownSeconds = 20
                        )
                    )
                }
                val persistedStore = prototypeStore.replaceStore(discoveryOutcome.updatedStore)
                persistedStore to discoveryOutcome.message
            }.onSuccess { (updatedStore, message) ->
                storeData = updatedStore
                currentSurface = MainSurface.SETTINGS
                render(storeData)
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            }.onFailure { throwable ->
                Snackbar.make(binding.root, buildFailureMessage(throwable), Snackbar.LENGTH_LONG).show()
            }
            setLoading(false)
        }
    }

    private fun runPreferenceExtraction() {
        if (!semanticClient.isConfigured()) {
            Snackbar.make(binding.root, getString(R.string.semantic_model_not_configured), Snackbar.LENGTH_LONG).show()
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            runCatching {
                val latestStore = prototypeStore.load()
                val extractionOutcome = withContext(Dispatchers.IO) {
                    applyScheduledOfflineDialoguePreferenceExtractionProjection(latestStore)
                }
                val persistedStore = prototypeStore.replaceStore(extractionOutcome.updatedStore)
                persistedStore to extractionOutcome.message
            }.onSuccess { (updatedStore, message) ->
                storeData = updatedStore
                currentSurface = MainSurface.SETTINGS
                render(storeData)
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            }.onFailure { throwable ->
                Snackbar.make(binding.root, buildFailureMessage(throwable), Snackbar.LENGTH_LONG).show()
            }
            setLoading(false)
        }
    }

    private fun runProcessExtraction() {
        setLoading(true)
        lifecycleScope.launch {
            runCatching {
                val latestStore = prototypeStore.load()
                val extractionOutcome = withContext(Dispatchers.IO) {
                    ExecutionLearningPipeline.runCuration(latestStore)
                }
                val persistedStore = if (extractionOutcome.applied) {
                    prototypeStore.replaceStore(extractionOutcome.updatedStore)
                } else {
                    latestStore
                }
                persistedStore to extractionOutcome.message
            }.onSuccess { (updatedStore, message) ->
                storeData = updatedStore
                currentSurface = MainSurface.SETTINGS
                render(storeData)
                Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
            }.onFailure { throwable ->
                Snackbar.make(binding.root, buildFailureMessage(throwable), Snackbar.LENGTH_LONG).show()
            }
            setLoading(false)
        }
    }

    private fun buildFailureMessage(throwable: Throwable): String {
        return formatRequestFailureMessage(this, throwable)
    }

    private fun registerShizukuListeners() {
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
    }

    private fun prepareWithShizuku() {
        pendingStartupBinderRetry = false
        setLoading(true)
        lifecycleScope.launch {
            runCatching {
                shizukuBootstrapManager.prepareManually()
            }.onSuccess { plan ->
                setLoading(false)
                render(storeData)
                handleShizukuBootstrapPlan(plan, initiatedByUser = true)
            }.onFailure { throwable ->
                setLoading(false)
                render(storeData)
                Snackbar.make(binding.root, buildFailureMessage(throwable), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun continueShizukuPrepareAfterPermissionGrant(trigger: BootstrapTrigger) {
        pendingShizukuPermissionTrigger = null
        pendingStartupBinderRetry = false
        setLoading(true)
        lifecycleScope.launch {
            runCatching {
                shizukuBootstrapManager.continueAfterPermissionGrant(trigger)
            }.onSuccess { plan ->
                setLoading(false)
                render(storeData)
                handleShizukuBootstrapPlan(plan, initiatedByUser = true)
            }.onFailure { throwable ->
                setLoading(false)
                render(storeData)
                Snackbar.make(binding.root, buildFailureMessage(throwable), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun maybeRunStartupShizukuBootstrap() {
        if (startupShizukuBootstrapAttempted || !shizukuBootstrapManager.isAutoBootstrapEnabled()) {
            return
        }
        startupShizukuBootstrapAttempted = true
        lifecycleScope.launch {
            runCatching {
                shizukuBootstrapManager.prepareOnStartup()
            }.onSuccess { plan ->
                pendingStartupBinderRetry = plan.status.code == ShizukuBootstrapStatusCode.SHIZUKU_BINDER_UNAVAILABLE
                render(storeData)
                handleShizukuBootstrapPlan(plan, initiatedByUser = false)
            }.onFailure {
                pendingStartupBinderRetry = false
                render(storeData)
            }
        }
    }

    private fun maybeRetryStartupShizukuBootstrapAfterBinder() {
        if (!pendingStartupBinderRetry || !shizukuBootstrapManager.isAutoBootstrapEnabled()) {
            return
        }
        pendingStartupBinderRetry = false
        startupShizukuBootstrapAttempted = false
        maybeRunStartupShizukuBootstrap()
    }

    private fun handleShizukuBootstrapPlan(
        plan: ShizukuBootstrapPlan,
        initiatedByUser: Boolean
    ) {
        when {
            plan.requestPermission -> {
                pendingShizukuPermissionTrigger = plan.status.trigger
                runCatching {
                    Shizuku.requestPermission(REQUEST_CODE_SHIZUKU_PERMISSION)
                }.onSuccess {
                    Snackbar.make(binding.root, getString(R.string.shizuku_permission_request_started), Snackbar.LENGTH_SHORT).show()
                }.onFailure { throwable ->
                    Snackbar.make(binding.root, buildFailureMessage(throwable), Snackbar.LENGTH_LONG).show()
                }
            }

            plan.launchCapture && (initiatedByUser || !autoCaptureDeniedThisProcess) -> {
                pendingCaptureLaunchTrigger = plan.status.trigger
                screenCapturePermissionLauncher.launch(screenCaptureManager.createCaptureIntent())
                Snackbar.make(binding.root, buildShizukuPlanMessage(plan.status), Snackbar.LENGTH_SHORT).show()
            }

            plan.launchCapture -> {
                shizukuBootstrapManager.onCapturePermissionDenied()
                render(storeData)
                Snackbar.make(binding.root, getString(R.string.shizuku_message_capture_denied), Snackbar.LENGTH_SHORT).show()
            }

            plan.status.code != ShizukuBootstrapStatusCode.DISABLED -> {
                Snackbar.make(binding.root, buildShizukuPlanMessage(plan.status), Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateShizukuAutoPrepare(enabled: Boolean) {
        pendingStartupBinderRetry = false
        val snapshot = shizukuBootstrapManager.currentStatusSnapshot()
        if (enabled && !snapshot.permissionGranted) {
            shizukuBootstrapManager.setAutoBootstrapEnabled(false)
            render(storeData)
            Snackbar.make(binding.root, getString(R.string.shizuku_auto_prepare_requires_permission), Snackbar.LENGTH_SHORT).show()
            return
        }
        shizukuBootstrapManager.setAutoBootstrapEnabled(enabled)
        if (enabled) {
            startupShizukuBootstrapAttempted = false
        }
        render(storeData)
        Snackbar.make(
            binding.root,
            getString(if (enabled) R.string.shizuku_auto_prepare_enabled else R.string.shizuku_auto_prepare_disabled),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun buildShizukuSurfaceState(): ShizukuSurfaceState {
        val snapshot = shizukuBootstrapManager.currentStatusSnapshot()
        val lastStatusCode = shizukuBootstrapManager.readLastBootstrapStatusCode()
        val lastAttemptAt = shizukuBootstrapManager.readLastBootstrapAttemptAt()
        val autoPrepareEnabled = shizukuBootstrapManager.isAutoBootstrapEnabled()
        val displayStatusCode = if (snapshot.hasLiveReadySession()) {
            ShizukuBootstrapStatusCode.READY
        } else {
            lastStatusCode
        }
        return ShizukuSurfaceState(
            visible = true,
            topStatusText = formatShizukuTopStatus(snapshot, displayStatusCode),
            settingsStatusText = formatShizukuSettingsStatus(snapshot, displayStatusCode),
            lastBootstrapText = formatShizukuLastBootstrap(lastStatusCode, lastAttemptAt),
            autoPrepareEnabled = autoPrepareEnabled,
            autoPrepareSwitchEnabled = snapshot.permissionGranted || autoPrepareEnabled
        )
    }

    private fun ShizukuStatusSnapshot.hasLiveReadySession(): Boolean {
        return installed && binderAvailable && !preV11 && permissionGranted
    }

    private fun formatShizukuTopStatus(
        snapshot: ShizukuStatusSnapshot,
        lastStatusCode: ShizukuBootstrapStatusCode
    ): String {
        return when {
            !snapshot.installed -> getString(R.string.status_shizuku_unavailable)
            !snapshot.binderAvailable || snapshot.preV11 -> getString(R.string.status_shizuku_blocked)
            !snapshot.permissionGranted -> getString(R.string.status_shizuku_permission_required)
            lastStatusCode == ShizukuBootstrapStatusCode.ACCESSIBILITY_WRITE_FAILED ||
                lastStatusCode == ShizukuBootstrapStatusCode.APPOPS_UNSUPPORTED ||
                lastStatusCode == ShizukuBootstrapStatusCode.APPOPS_WRITE_FAILED ||
                lastStatusCode == ShizukuBootstrapStatusCode.APPOPS_VERIFY_FAILED ||
                lastStatusCode == ShizukuBootstrapStatusCode.CAPTURE_CONSENT_REQUIRED_OR_DENIED -> {
                getString(R.string.status_shizuku_blocked)
            }

            snapshot.privilegeIdentity == ShizukuPrivilegeIdentity.ROOT -> getString(R.string.status_shizuku_ready_root)
            snapshot.privilegeIdentity == ShizukuPrivilegeIdentity.SHELL -> getString(R.string.status_shizuku_ready_shell)
            else -> getString(R.string.status_shizuku_ready_unknown)
        }
    }

    private fun formatShizukuSettingsStatus(
        snapshot: ShizukuStatusSnapshot,
        lastStatusCode: ShizukuBootstrapStatusCode
    ): String {
        if (!snapshot.installed) {
            return getString(R.string.settings_shizuku_status_unavailable)
        }
        if (!snapshot.binderAvailable || snapshot.preV11) {
            return getString(R.string.settings_shizuku_status_binder_unavailable)
        }
        if (!snapshot.permissionGranted) {
            return getString(
                if (snapshot.shouldShowRequestPermissionRationale) {
                    R.string.settings_shizuku_status_permission_denied
                } else {
                    R.string.settings_shizuku_status_permission_required
                }
            )
        }
        return when (lastStatusCode) {
            ShizukuBootstrapStatusCode.ACCESSIBILITY_ENABLED_PENDING_CONNECTION -> {
                getString(R.string.settings_shizuku_status_accessibility_pending)
            }

            ShizukuBootstrapStatusCode.ACCESSIBILITY_WRITE_FAILED -> {
                getString(R.string.settings_shizuku_status_accessibility_failed)
            }

            ShizukuBootstrapStatusCode.APPOPS_UNSUPPORTED -> {
                getString(R.string.settings_shizuku_status_appops_unsupported)
            }

            ShizukuBootstrapStatusCode.APPOPS_WRITE_FAILED,
            ShizukuBootstrapStatusCode.APPOPS_VERIFY_FAILED -> {
                getString(R.string.settings_shizuku_status_appops_failed)
            }

            ShizukuBootstrapStatusCode.CAPTURE_REQUEST_LAUNCHED -> {
                getString(R.string.settings_shizuku_status_capture_pending)
            }

            ShizukuBootstrapStatusCode.CAPTURE_CONSENT_REQUIRED_OR_DENIED -> {
                getString(R.string.settings_shizuku_status_capture_denied)
            }

            else -> when (snapshot.privilegeIdentity) {
                ShizukuPrivilegeIdentity.ROOT -> getString(R.string.settings_shizuku_status_ready_root)
                ShizukuPrivilegeIdentity.SHELL -> getString(R.string.settings_shizuku_status_ready_shell)
                ShizukuPrivilegeIdentity.UNKNOWN -> getString(R.string.settings_shizuku_status_ready_unknown)
            }
        }
    }

    private fun formatShizukuLastBootstrap(
        lastStatusCode: ShizukuBootstrapStatusCode,
        lastAttemptAt: Long?
    ): String {
        if (lastAttemptAt == null) {
            return getString(R.string.settings_shizuku_last_bootstrap_never)
        }
        return getString(
            R.string.settings_shizuku_last_bootstrap_value,
            formatShizukuStatusCodeLabel(lastStatusCode),
            lastUpdatedFormatter.format(Date(lastAttemptAt))
        )
    }

    private fun formatShizukuStatusCodeLabel(code: ShizukuBootstrapStatusCode): String {
        return when (code) {
            ShizukuBootstrapStatusCode.IDLE -> getString(R.string.settings_not_updated_yet)
            ShizukuBootstrapStatusCode.DISABLED -> getString(R.string.shizuku_auto_prepare_disabled)
            ShizukuBootstrapStatusCode.READY -> getString(R.string.shizuku_message_ready)
            ShizukuBootstrapStatusCode.SHIZUKU_UNAVAILABLE -> getString(R.string.shizuku_message_install_first)
            ShizukuBootstrapStatusCode.SHIZUKU_BINDER_UNAVAILABLE -> getString(R.string.shizuku_message_start_service)
            ShizukuBootstrapStatusCode.SHIZUKU_PERMISSION_REQUIRED -> getString(R.string.shizuku_message_permission_required)
            ShizukuBootstrapStatusCode.SHIZUKU_PERMISSION_DENIED -> getString(R.string.shizuku_message_permission_denied)
            ShizukuBootstrapStatusCode.ACCESSIBILITY_ENABLED_PENDING_CONNECTION -> getString(R.string.shizuku_message_accessibility_pending)
            ShizukuBootstrapStatusCode.ACCESSIBILITY_WRITE_FAILED -> getString(R.string.shizuku_message_accessibility_failed)
            ShizukuBootstrapStatusCode.APPOPS_UNSUPPORTED -> getString(R.string.shizuku_message_appops_unsupported)
            ShizukuBootstrapStatusCode.APPOPS_WRITE_FAILED,
            ShizukuBootstrapStatusCode.APPOPS_VERIFY_FAILED -> getString(R.string.shizuku_message_appops_failed)
            ShizukuBootstrapStatusCode.CAPTURE_REQUEST_LAUNCHED -> getString(R.string.shizuku_message_preparing_capture)
            ShizukuBootstrapStatusCode.CAPTURE_CONSENT_REQUIRED_OR_DENIED -> getString(R.string.shizuku_message_capture_denied)
        }
    }

    private fun buildShizukuPlanMessage(status: ShizukuBootstrapStatus): String {
        return when (status.code) {
            ShizukuBootstrapStatusCode.SHIZUKU_UNAVAILABLE -> getString(R.string.shizuku_message_install_first)
            ShizukuBootstrapStatusCode.SHIZUKU_BINDER_UNAVAILABLE -> getString(R.string.shizuku_message_start_service)
            ShizukuBootstrapStatusCode.SHIZUKU_PERMISSION_REQUIRED -> getString(R.string.shizuku_message_permission_required)
            ShizukuBootstrapStatusCode.SHIZUKU_PERMISSION_DENIED -> getString(R.string.shizuku_message_permission_denied)
            ShizukuBootstrapStatusCode.ACCESSIBILITY_ENABLED_PENDING_CONNECTION -> getString(R.string.shizuku_message_accessibility_pending)
            ShizukuBootstrapStatusCode.ACCESSIBILITY_WRITE_FAILED -> getString(R.string.shizuku_message_accessibility_failed)
            ShizukuBootstrapStatusCode.APPOPS_UNSUPPORTED -> getString(R.string.shizuku_message_appops_unsupported)
            ShizukuBootstrapStatusCode.APPOPS_WRITE_FAILED,
            ShizukuBootstrapStatusCode.APPOPS_VERIFY_FAILED -> getString(R.string.shizuku_message_appops_failed)
            ShizukuBootstrapStatusCode.CAPTURE_REQUEST_LAUNCHED -> getString(R.string.shizuku_message_preparing_capture)
            ShizukuBootstrapStatusCode.CAPTURE_CONSENT_REQUIRED_OR_DENIED -> getString(R.string.shizuku_message_capture_denied)
            ShizukuBootstrapStatusCode.READY -> getString(R.string.shizuku_message_ready)
            else -> formatShizukuSettingsStatus(status.probe, status.code)
        }
    }
}