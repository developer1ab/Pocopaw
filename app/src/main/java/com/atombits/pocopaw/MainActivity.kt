package com.atombits.pocopaw

import android.Manifest
import android.animation.ObjectAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.atombits.pocopaw.databinding.ActivityMainBinding
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import android.view.animation.LinearInterpolator
import java.text.DateFormat
import java.util.Date

private enum class MainSurface {
    CONSOLE,
    SETTINGS
}

private enum class ConsoleChannel {
    ALL,
    EXECUTION,
    CONVERSATION
}

private enum class DemoOnboardingStep {
    TOOL_DISCOVERY,
    ACCESSIBILITY,
    SCREEN_CAPTURE,
    DONE
}

class MainActivity : AppCompatActivity() {

    private companion object {
        private const val REQUEST_CODE_SHIZUKU_PERMISSION = 7001
        private const val LOADING_ANIMATION_INTERVAL_MS = 450L
        private const val STATE_SURFACE = "state_surface"
        private const val STATE_CONSOLE_CHANNEL = "state_console_channel"
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

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        UiStrings.initialize(this)
        DemoReleaseControl.initialize(applicationContext)
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
        chatAdapter = ChatAdapter()
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
        runCatching {
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
        }
        RuntimeServiceStatusNotifier.removeListener(runtimeServiceStatusListener)
        clearDemoHighlightAnimation()
        stopLoadingAnimation()
        super.onDestroy()
    }

    private fun bindPrimaryActions() {
        binding.sendButton.setOnClickListener { submitMessage() }
        binding.startPreparingButton.setOnClickListener { startPreparing() }
        binding.startExecutionButton.setOnClickListener { startExecution() }
        binding.processThumbsUpButton.setOnClickListener {
            submitCompletedExecutionFeedback(ProcessFeedbackType.THUMBS_UP)
        }
        binding.processThumbsDownButton.setOnClickListener {
            submitCompletedExecutionFeedback(ProcessFeedbackType.THUMBS_DOWN)
        }
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
        binding.runPreferenceExtractionButton.setOnClickListener {
            runPreferenceExtraction()
        }
        binding.runProcessExtractionButton.setOnClickListener {
            runProcessExtraction()
        }
        binding.providerProfileValueText.setOnClickListener {
            Snackbar.make(binding.root, getString(R.string.demo_settings_locked), Snackbar.LENGTH_SHORT).show()
        }
        binding.semanticModelValueText.setOnClickListener {
            Snackbar.make(binding.root, getString(R.string.demo_settings_locked), Snackbar.LENGTH_SHORT).show()
        }
        binding.visionModelValueText.setOnClickListener {
            Snackbar.make(binding.root, getString(R.string.demo_settings_locked), Snackbar.LENGTH_SHORT).show()
        }
        binding.searchProviderValueText.setOnClickListener {
            Snackbar.make(binding.root, getString(R.string.demo_settings_locked), Snackbar.LENGTH_SHORT).show()
        }
        binding.visionThinkingSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.visionThinkingSwitch.isChecked = false
            }
            visionRequestThinkingSettingsStore.writeEnabled(false)
            render(storeData)
        }
        binding.visionSearchSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.visionSearchSwitch.isChecked = false
            }
            visionRequestSearchSettingsStore.writeEnabled(false)
            render(storeData)
        }
    }

    private fun bindConversationOptionActions() {
        binding.thinkingSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.thinkingSwitch.isChecked = false
            }
            chatTurnOptions = chatTurnOptions.copy(thinkingEnabled = false)
            render(storeData)
        }
        binding.searchSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked) {
                binding.searchSwitch.isChecked = true
            }
            chatTurnOptions = chatTurnOptions.copy(searchEnabled = true)
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
        currentSurface = MainSurface.CONSOLE
        currentConsoleChannel = ConsoleChannel.CONVERSATION
        val submittedInput = userInput
        binding.inputEditText.setText("")
        setLoading(true)
        lifecycleScope.launch {
            val turnOptionsSnapshot = chatTurnOptions.copy(
                thinkingEnabled = false,
                searchEnabled = true
            )
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
                    if (result.executionMessage != null) {
                        currentConsoleChannel = ConsoleChannel.EXECUTION
                    }
                    pendingConversationMessages = emptyList()
                    storeData = result.updatedStore
                    render(storeData)
                    result.executionMessage?.let { message ->
                        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                    }
                }

                is ChatTurnSubmitResult.Failure -> {
                    if (result.attemptedExecutionStart) {
                        currentConsoleChannel = ConsoleChannel.EXECUTION
                    }
                    binding.inputEditText.setText(result.restoreInput)
                    pendingConversationMessages = result.pendingMessages
                    render(storeData)
                    Snackbar.make(binding.root, buildFailureMessage(result.throwable), Snackbar.LENGTH_LONG).show()
                }
            }
            setLoading(false)
        }
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
        currentSurface = MainSurface.CONSOLE
        currentConsoleChannel = ConsoleChannel.EXECUTION
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
        currentSurface = MainSurface.CONSOLE
        currentConsoleChannel = ConsoleChannel.ALL
        lifecycleScope.launch {
            storeData = executionEntryOrchestrator.startPreparing()
            render(storeData)
            Snackbar.make(binding.root, getString(R.string.preparing_started), Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun render(store: PrototypeStoreData) {
        val runtimePreferences = store.resolveSemanticRuntimePreferences() ?: SemanticRuntimePreferences()
        val constrainedTurnOptions = coerceSemanticTurnOptions(runtimePreferences, chatTurnOptions)
        val demoFixedTurnOptions = constrainedTurnOptions.copy(
            thinkingEnabled = false,
            searchEnabled = true
        )
        if (demoFixedTurnOptions != chatTurnOptions) {
            chatTurnOptions = demoFixedTurnOptions
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
        binding.composerInputLayout.hint = getString(R.string.message_hint)
        refreshLanguageCard()
        val loading = binding.loadingRow.visibility == View.VISIBLE
        refreshConversationControlAvailability(store, loading)
        refreshVisionControlAvailability(loading)
        refreshSearchProviderAvailability(loading = loading)
        refreshDemoOnboardingUi(force = false)
    }

    private fun setLoading(loading: Boolean) {
        binding.loadingRow.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) {
            startLoadingAnimation()
        } else {
            stopLoadingAnimation()
        }
        binding.sendButton.isEnabled = !loading
        binding.inputEditText.isEnabled = !loading
        binding.processReviewInputEditText.isEnabled = !loading
        refreshConversationControlAvailability(storeData, loading)
        refreshVisionControlAvailability(loading)
        binding.processThumbsUpButton.isEnabled = !loading
        binding.processThumbsDownButton.isEnabled = !loading
        binding.captureCompressionValueText.isEnabled = !loading
        binding.providerProfileValueText.isEnabled = false
        binding.semanticModelValueText.isEnabled = false
        binding.visionModelValueText.isEnabled = false
        refreshSearchProviderAvailability(loading)
        binding.runToolDiscoveryButton.isEnabled = !loading
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
        binding.thinkingSwitch.isEnabled = false
        binding.searchSwitch.isEnabled = false
        binding.thinkingSwitch.isChecked = false
        binding.searchSwitch.isChecked = true
    }

    private fun refreshVisionControlAvailability(loading: Boolean) {
        binding.visionThinkingSwitch.isEnabled = false
        binding.visionSearchSwitch.isEnabled = false
        binding.visionThinkingSwitch.isChecked = false
        binding.visionSearchSwitch.isChecked = false
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
        val searchProviderEditable = false
        binding.searchProviderValueText.isEnabled = searchProviderEditable
        binding.searchProviderValueText.isClickable = searchProviderEditable
        binding.searchProviderValueText.isFocusable = searchProviderEditable
        binding.searchProviderValueText.alpha = if (searchProviderEditable) 1f else 0.5f
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
        if (toolspaceCatalogManager.getSnapshot().updatedAt == null) {
            return DemoOnboardingStep.TOOL_DISCOVERY
        }
        if (!isAccessibilityServiceEnabledForDemo()) {
            return DemoOnboardingStep.ACCESSIBILITY
        }
        if (!screenCaptureManager.hasPermission()) {
            return DemoOnboardingStep.SCREEN_CAPTURE
        }
        return DemoOnboardingStep.DONE
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
        if (currentConfig.profileId != ProviderProfileId.CUSTOM) {
            return
        }
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
        if (currentConfig.profileId != ProviderProfileId.CUSTOM) {
            return
        }
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
        if (!BuildConfig.DEBUG || startupShizukuBootstrapAttempted || !shizukuBootstrapManager.isAutoBootstrapEnabled()) {
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
        if (!pendingStartupBinderRetry || !BuildConfig.DEBUG || !shizukuBootstrapManager.isAutoBootstrapEnabled()) {
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
        val visible = BuildConfig.DEBUG
        if (!visible) {
            return ShizukuSurfaceState(
                visible = false,
                topStatusText = "",
                settingsStatusText = "",
                lastBootstrapText = "",
                autoPrepareEnabled = false,
                autoPrepareSwitchEnabled = false
            )
        }
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