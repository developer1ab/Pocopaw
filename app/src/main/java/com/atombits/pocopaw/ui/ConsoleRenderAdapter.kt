package com.atombits.pocopaw.ui

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.atombits.pocopaw.ChatAdapter
import com.atombits.pocopaw.ChatMessage
import com.atombits.pocopaw.ChatTurnOptions
import com.atombits.pocopaw.ConsoleTaskFormatter
import com.atombits.pocopaw.ConsoleExecutionUiFormatter
import com.atombits.pocopaw.ExecutionLogAdapter
import com.atombits.pocopaw.MemoryState
import com.atombits.pocopaw.MessageRole
import com.atombits.pocopaw.PreferenceDiscoveryAppTarget
import com.atombits.pocopaw.PreferenceDiscoveryCatalog
import com.atombits.pocopaw.PreparingEntrySidecar
import com.atombits.pocopaw.ProcessFeedbackType
import com.atombits.pocopaw.ProviderProfileId
import com.atombits.pocopaw.ProviderProfileRuntime
import com.atombits.pocopaw.ProviderRuntimeConfigs
import com.atombits.pocopaw.PrototypeStoreData
import com.atombits.pocopaw.RegionMode
import com.atombits.pocopaw.SearchProviderKind
import com.atombits.pocopaw.R
import com.atombits.pocopaw.RuntimeModuleSwitches
import com.atombits.pocopaw.ScreenCaptureCompressionSettingsStore
import com.atombits.pocopaw.SemanticModelTier
import com.atombits.pocopaw.SemanticRuntimePreferences
import com.atombits.pocopaw.ToolspaceCatalogManager
import com.atombits.pocopaw.VisionRequestSearchSettingsStore
import com.atombits.pocopaw.VisionRequestThinkingSettingsStore
import com.atombits.pocopaw.buildPreferenceDiscoveryStatusSummary
import com.atombits.pocopaw.buildPreferenceExtractionStatusSummary
import com.atombits.pocopaw.buildProcessExtractionSettingsSummary
import com.atombits.pocopaw.currentSemanticModelControls
import com.atombits.pocopaw.resolveLatestCompletedProcessReviewContext
import com.atombits.pocopaw.resolveVisionModelControls
import com.atombits.pocopaw.databinding.ActivityMainBinding
import com.atombits.pocopaw.formatCaptureCompressionSummary
import com.atombits.pocopaw.resolveVisibleProcessReviewContext
import com.atombits.pocopaw.resolveConversationMessages
import com.atombits.pocopaw.resolveCurrentState
import com.atombits.pocopaw.resolveExecutionEvents
import com.atombits.pocopaw.resolveMemoryState
import com.atombits.pocopaw.resolveSemanticRuntimePreferences
import com.atombits.pocopaw.semanticModelName
import com.atombits.pocopaw.service.CaptureService
import com.atombits.pocopaw.service.PrototypeAccessibilityService
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class ConsoleRenderState(
    val pendingConversationMessages: List<ChatMessage>,
    val chatTurnOptions: ChatTurnOptions,
    val showingSettings: Boolean,
    val showingAllChannel: Boolean,
    val showingExecutionChannel: Boolean,
    val showingConversationChannel: Boolean,
    val shizukuSurfaceState: ShizukuSurfaceState
)

internal data class ShizukuSurfaceState(
    val visible: Boolean,
    val topStatusText: String,
    val settingsStatusText: String,
    val lastBootstrapText: String,
    val autoPrepareEnabled: Boolean,
    val autoPrepareSwitchEnabled: Boolean
)

private const val PROCESS_REVIEW_PANEL_ENABLED = false

internal class ConsoleRenderAdapter(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val chatAdapter: ChatAdapter,
    private val executionLogAdapter: ExecutionLogAdapter,
    private val preparingEntrySidecar: PreparingEntrySidecar,
    private val toolspaceCatalogManager: ToolspaceCatalogManager,
    private val captureCompressionSettingsStore: ScreenCaptureCompressionSettingsStore,
    private val visionRequestThinkingSettingsStore: VisionRequestThinkingSettingsStore,
    private val visionRequestSearchSettingsStore: VisionRequestSearchSettingsStore
) {
    private val timestampFormatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    fun render(
        store: PrototypeStoreData,
        state: ConsoleRenderState
    ) {
        val resolvedState = store.resolveCurrentState()
        val visibleMessages = (store.resolveConversationMessages().filter(::isVisibleConversationMessage) + state.pendingConversationMessages)
            .sortedBy { message -> message.timestamp }
        val executionTimeline = ConsoleExecutionUiFormatter.buildExecutionTimeline(store)
        val executionSummary = ConsoleExecutionUiFormatter.buildExecutionSurfaceSummary(store)
        chatAdapter.submitList(visibleMessages)
        executionLogAdapter.submitList(executionTimeline)
        renderSurfaceState(state)
        renderStatusSummary(state.shizukuSurfaceState)
        renderSettingsPanel(store, state)
        renderConversationControls(store, state.chatTurnOptions)
        binding.executionOverviewText.text = executionSummary.overview
        binding.executionRouteText.text = executionSummary.route

        if (!state.showingSettings && state.showingAllChannel) {
            binding.allPageScroll.post {
                binding.allPageScroll.fullScroll(View.FOCUS_DOWN)
            }
        }
        if (!state.showingSettings && state.showingConversationChannel && visibleMessages.isNotEmpty()) {
            binding.messagesRecyclerView.post {
                binding.messagesRecyclerView.scrollToPosition(visibleMessages.lastIndex)
            }
        }
        if (!state.showingSettings && state.showingExecutionChannel && executionTimeline.isNotEmpty()) {
            binding.executionLogRecyclerView.post {
                binding.executionLogRecyclerView.scrollToPosition(executionTimeline.lastIndex)
            }
        }

        binding.stageDetailText.text = ConsoleTaskFormatter.formatStageDisplaySummary(resolvedState)
        binding.persistenceText.text = "${store.resolveConversationMessages().size} messages and ${store.resolveExecutionEvents().size} execution events stored locally."
        binding.candidateSummaryText.text = ConsoleTaskFormatter.formatLiveTaskDisplaySummary(store)
        binding.inactiveTopicSummaryText.text = ConsoleTaskFormatter.formatInactiveTopicDisplaySummary(store)
        binding.executionLogEmptyText.isVisible = executionTimeline.isEmpty()
        renderProcessReviewPanel(store)

        preparingEntrySidecar.render(binding, context, store)
        binding.executionCard.isVisible = false
    }

    private fun isVisibleConversationMessage(message: ChatMessage): Boolean {
        if (message.role != MessageRole.SYSTEM) {
            return true
        }
        val content = message.content.trim()
        return content.startsWith("Execution completed for ") ||
            content.startsWith("Execution failed for ")
    }

    private fun renderProcessReviewPanel(store: PrototypeStoreData) {
        if (!PROCESS_REVIEW_PANEL_ENABLED) {
            binding.processReviewCard.isVisible = false
            binding.processReviewInputEditText.setText("")
            return
        }
        val reviewContext = store.resolveLatestCompletedProcessReviewContext()
        val hasReview = reviewContext != null
        binding.processReviewCard.isVisible = hasReview
        if (!hasReview) {
            binding.processReviewInputEditText.setText("")
            return
        }

        val processDisplayId = reviewContext?.processAssetName?.takeIf { it.isNotBlank() }
            ?: reviewContext?.processAssetEntryId?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.process_review_unknown_flow)
        binding.processReviewBodyText.text = processDisplayId
        binding.processReviewInputLayout.hint = context.getString(R.string.process_review_input_hint)
        updateButtonState(
            binding.processThumbsUpButton,
            false,
            R.color.moss_500,
            R.color.slate_500
        )
        updateButtonState(
            binding.processThumbsDownButton,
            false,
            R.color.clay_500,
            R.color.slate_500
        )
    }

    private fun renderSurfaceState(state: ConsoleRenderState) {
        binding.settingsPageContainer.isVisible = state.showingSettings
        binding.consolePageContainer.isVisible = !state.showingSettings
        binding.allPageScroll.isVisible = !state.showingSettings && state.showingAllChannel
        binding.executionSectionCard.isVisible = !state.showingSettings && state.showingExecutionChannel
        binding.conversationSectionCard.isVisible = !state.showingSettings && state.showingConversationChannel
        binding.settingsToggleButton.text = if (state.showingSettings) {
            context.getString(R.string.close_settings_page)
        } else {
            context.getString(R.string.open_settings_page)
        }
        updateButtonState(binding.settingsToggleButton, state.showingSettings, R.color.moss_500, R.color.clay_500)
        updateButtonState(
            binding.btnChannelAll,
            !state.showingSettings && state.showingAllChannel,
            R.color.clay_500,
            R.color.slate_500
        )
        updateButtonState(
            binding.btnChannelExecution,
            !state.showingSettings && state.showingExecutionChannel,
            R.color.clay_500,
            R.color.slate_500
        )
        updateButtonState(
            binding.btnChannelConversation,
            !state.showingSettings && state.showingConversationChannel,
            R.color.clay_500,
            R.color.slate_500
        )
    }

    private fun renderStatusSummary(shizukuSurfaceState: ShizukuSurfaceState) {
        binding.accStatusText.text = context.getString(
            if (isAccessibilityServiceEnabled()) R.string.status_accessibility_on else R.string.status_accessibility_off
        )
        binding.captureStatusText.text = context.getString(
            if (CaptureService.isReady) R.string.status_capture_on else R.string.status_capture_off
        )
        binding.usageAccessStatusText.text = context.getString(
            if (isUsageAccessEnabled()) R.string.status_usage_access_on else R.string.status_usage_access_off
        )
        binding.shizukuStatusText.isVisible = shizukuSurfaceState.visible
        binding.shizukuStatusText.text = shizukuSurfaceState.topStatusText
    }

    private fun renderSettingsPanel(store: PrototypeStoreData, state: ConsoleRenderState) {
        val memoryState = store.resolveMemoryState() ?: MemoryState()
        val toolspaceSnapshot = toolspaceCatalogManager.getSnapshot()
        val installedPreferenceTargets = resolveInstalledPreferenceDiscoveryTargets()
        val runtimePreferences = store.resolveSemanticRuntimePreferences() ?: SemanticRuntimePreferences()
        val providerProfile = ProviderProfileRuntime.current()
        binding.providerProfileValueText.text = context.getString(
            R.string.settings_provider_profile_value,
            formatProviderProfileLabel(providerProfile.profileId),
            formatRegionModeLabel(providerProfile.regionMode)
        )
        binding.semanticModelValueText.text = if (providerProfile.profileId == ProviderProfileId.CUSTOM) {
            context.getString(
                R.string.settings_semantic_model_value,
                providerProfile.semantic.fastModel
            )
        } else {
            context.getString(
                R.string.settings_semantic_model_value_with_tier,
                formatSemanticModelTier(runtimePreferences.modelTier),
                runtimePreferences.modelTier.semanticModelName()
            )
        }
        binding.visionModelValueText.text = if (providerProfile.profileId == ProviderProfileId.CUSTOM) {
            context.getString(
                R.string.settings_vision_model_value,
                providerProfile.vision.model
            )
        } else {
            context.getString(
                R.string.settings_vision_model_value_with_tier,
                formatSemanticModelTier(providerProfile.vision.modelTier),
                providerProfile.vision.model
            )
        }
        if (binding.visionThinkingSwitch.isChecked != visionRequestThinkingSettingsStore.isEnabled()) {
            binding.visionThinkingSwitch.isChecked = visionRequestThinkingSettingsStore.isEnabled()
        }
        if (binding.visionSearchSwitch.isChecked != visionRequestSearchSettingsStore.isEnabled()) {
            binding.visionSearchSwitch.isChecked = visionRequestSearchSettingsStore.isEnabled()
        }
        val visionControls = resolveVisionModelControls(providerProfile.vision.model)
        binding.visionThinkingSwitch.isEnabled = visionControls.thinkingSupported
        binding.visionSearchSwitch.isEnabled = visionControls.searchSupported
        val semanticControls = currentSemanticModelControls(runtimePreferences)
        binding.thinkingSwitch.isEnabled = semanticControls.thinkingSupported
        binding.searchSwitch.isEnabled = semanticControls.searchSupported
        if (binding.thinkingSwitch.isChecked != state.chatTurnOptions.thinkingEnabled) {
            binding.thinkingSwitch.isChecked = state.chatTurnOptions.thinkingEnabled
        }
        if (binding.searchSwitch.isChecked != state.chatTurnOptions.searchEnabled) {
            binding.searchSwitch.isChecked = state.chatTurnOptions.searchEnabled
        }
        binding.searchProviderValueText.text = formatSearchProviderLabel(providerProfile.search.provider)
        binding.captureCompressionValueText.text = "Screenshot upload: ${formatCaptureCompressionSummary(captureCompressionSettingsStore.readScale())}"
        binding.scanAppsValueText.text = formatToolDiscoverySummary(toolspaceSnapshot)
        binding.preferenceDiscoveryValueText.text = buildPreferenceDiscoveryStatusSummary(
            context,
            memoryState,
            installedPreferenceTargets.size
        )
        binding.semanticPreferenceValueText.text = buildPreferenceExtractionStatusSummary(context, memoryState)
        binding.shoppingHistoryValueText.text = buildProcessExtractionSettingsSummary(store)
        val proactiveStatus = if (RuntimeModuleSwitches.proactiveEngineEnabled) {
            "runtime switch: enabled"
        } else {
            "runtime switch: disabled"
        }
        val inheritedProactiveStatus = if (RuntimeModuleSwitches.proactiveEngineEnabled) {
            "same runtime switch: enabled"
        } else {
            "same runtime switch: disabled"
        }
        binding.autoActivateValueText.text = proactiveStatus
        binding.proactiveMasterValueText.text = proactiveStatus
        binding.proactiveInChatValueText.text = inheritedProactiveStatus
        binding.proactiveOffChatValueText.text = inheritedProactiveStatus
        binding.preferenceCompletionValueText.text = buildString {
            append("memory diagnostics: ")
            append(memoryState.habitMemoryStore.size)
            append(" habit(s), ")
            append(memoryState.interactionStyleStore.size)
            append(" style entry(ies), ")
            append(memoryState.dialoguePreferenceBacklog.size)
            append(" backlog item(s)")
        }
        binding.shizukuSettingsGroup.isVisible = state.shizukuSurfaceState.visible
        binding.shizukuSettingsStatusText.text = state.shizukuSurfaceState.settingsStatusText
        binding.shizukuLastBootstrapText.text = state.shizukuSurfaceState.lastBootstrapText
        if (binding.shizukuAutoPrepareSwitch.isChecked != state.shizukuSurfaceState.autoPrepareEnabled) {
            binding.shizukuAutoPrepareSwitch.isChecked = state.shizukuSurfaceState.autoPrepareEnabled
        }
        binding.shizukuAutoPrepareSwitch.isEnabled = state.shizukuSurfaceState.autoPrepareSwitchEnabled
    }

    private fun renderConversationControls(
        store: PrototypeStoreData,
        chatTurnOptions: ChatTurnOptions
    ) {
        val runtimePreferences = store.resolveSemanticRuntimePreferences() ?: SemanticRuntimePreferences()
        val providerProfile = ProviderProfileRuntime.current()
        binding.chatModeSummaryText.text = if (providerProfile.profileId == ProviderProfileId.CUSTOM) {
            context.getString(
                R.string.chat_mode_summary_custom,
                providerProfile.semantic.fastModel,
                formatChatTurnOptionState(chatTurnOptions.thinkingEnabled),
                formatChatTurnOptionState(chatTurnOptions.searchEnabled)
            )
        } else {
            context.getString(
                R.string.chat_mode_summary,
                formatSemanticModelTier(runtimePreferences.modelTier),
                formatChatTurnOptionState(chatTurnOptions.thinkingEnabled),
                formatChatTurnOptionState(chatTurnOptions.searchEnabled)
            )
        }
        if (binding.thinkingSwitch.isChecked != chatTurnOptions.thinkingEnabled) {
            binding.thinkingSwitch.isChecked = chatTurnOptions.thinkingEnabled
        }
        if (binding.searchSwitch.isChecked != chatTurnOptions.searchEnabled) {
            binding.searchSwitch.isChecked = chatTurnOptions.searchEnabled
        }
        val controls = currentSemanticModelControls(runtimePreferences)
        binding.thinkingSwitch.isEnabled = controls.thinkingSupported
        binding.searchSwitch.isEnabled = controls.searchSupported
    }

    private fun formatSemanticModelTier(tier: SemanticModelTier): String {
        return when (tier) {
            SemanticModelTier.FAST -> context.getString(R.string.settings_semantic_mode_fast)
            SemanticModelTier.EXPERT -> context.getString(R.string.settings_semantic_mode_expert)
        }
    }

    private fun formatProviderProfileLabel(profileId: ProviderProfileId): String {
        return when (profileId) {
            ProviderProfileId.DOMESTIC_DEFAULT -> context.getString(R.string.settings_provider_profile_domestic)
            ProviderProfileId.GLOBAL_DEFAULT -> context.getString(R.string.settings_provider_profile_global)
            ProviderProfileId.CUSTOM -> context.getString(R.string.settings_provider_profile_custom)
        }
    }

    private fun formatRegionModeLabel(mode: RegionMode): String {
        return when (mode) {
            RegionMode.DOMESTIC -> context.getString(R.string.settings_region_mode_domestic)
            RegionMode.GLOBAL -> context.getString(R.string.settings_region_mode_global)
            RegionMode.CUSTOM -> context.getString(R.string.settings_region_mode_custom)
        }
    }

    private fun formatSearchProviderLabel(provider: SearchProviderKind): String {
        return when (provider) {
            SearchProviderKind.ALIYUN_OPENSEARCH -> context.getString(R.string.settings_search_provider_aliyun)
            SearchProviderKind.GOOGLE_CSE -> context.getString(R.string.settings_search_provider_google)
        }
    }

    private fun formatChatTurnOptionState(enabled: Boolean): String {
        return if (enabled) {
            context.getString(R.string.chat_option_on)
        } else {
            context.getString(R.string.chat_option_off)
        }
    }

    private fun updateButtonState(
        button: MaterialButton,
        selected: Boolean,
        selectedColorRes: Int,
        defaultColorRes: Int
    ) {
        button.backgroundTintList = ContextCompat.getColorStateList(
            context,
            if (selected) selectedColorRes else defaultColorRes
        )
        button.alpha = if (selected) 1.0f else 0.82f
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = ComponentName(context, PrototypeAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return PrototypeAccessibilityService.instance != null || enabledServices.split(':').any { service ->
            service.equals(expectedComponent, ignoreCase = true)
        }
    }

    private fun isUsageAccessEnabled(): Boolean {
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOpsManager.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            context.applicationInfo.uid,
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun resolveInstalledPreferenceDiscoveryTargets(): List<PreferenceDiscoveryAppTarget> {
        return PreferenceDiscoveryCatalog.domains().flatMap { domain ->
            PreferenceDiscoveryCatalog.installedTargets(domain) { packageName ->
                context.packageManager.getLaunchIntentForPackage(packageName) != null
            }
        }
    }

    private fun formatToolDiscoverySummary(snapshot: ToolspaceCatalogManager.ToolspaceSnapshot): String {
        val updatedAt = snapshot.updatedAt?.let { timestamp ->
            timestampFormatter.format(Date(timestamp))
        } ?: context.getString(R.string.tool_discovery_not_scanned)
        return buildString {
            append("system=")
            append(snapshot.stats.system)
            append(", app=")
            append(snapshot.stats.app)
            append(", MCP=")
            append(snapshot.stats.mcp)
            append("\n")
            append(context.getString(R.string.tool_discovery_updated_at_prefix))
            append(updatedAt)
        }
    }
}
