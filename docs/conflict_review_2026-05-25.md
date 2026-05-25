# Conflict Review 2026-05-25

Source: popopaw (left) vs pocopaw (right) for unresolved non-overwrite conflicts.

## 1. ExecutionRecoveryBridge.kt

- Left: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\popopaw\ExecutionRecoveryBridge.kt
- Right: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\pocopaw\ExecutionRecoveryBridge.kt
- Status: DIFFERENT
- Line count: left=130 right=130
- Package left: package com.atombits.popopaw
- Package right: package com.atombits.pocopaw

```diff
diff --git "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\ExecutionRecoveryBridge.kt" "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\ExecutionRecoveryBridge.kt"
index be8b714..15c0e2a 100644
--- "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\ExecutionRecoveryBridge.kt"
+++ "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\ExecutionRecoveryBridge.kt"
@@ -1 +1 @@
-package com.atombits.popopaw
+package com.atombits.pocopaw
@@ -3,2 +3,2 @@ package com.atombits.popopaw
-import com.atombits.popopaw.process.curation.ProcessLearningWritebackBridge
-import com.atombits.popopaw.process.runtime.ProcessRecoveryContext
+import com.atombits.pocopaw.process.curation.ProcessLearningWritebackBridge
+import com.atombits.pocopaw.process.runtime.ProcessRecoveryContext
@@ -128 +128 @@ private fun buildExecutionRecoveryTimeoutMessage(recovery: ProcessRecoveryContex
-        ASSISTANT_NAME_ZH
+        currentAssistantDisplayName()
```

## 2. ExecutionRuntimeOrchestrator.kt

- Left: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\popopaw\ExecutionRuntimeOrchestrator.kt
- Right: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\pocopaw\ExecutionRuntimeOrchestrator.kt
- Status: DIFFERENT
- Line count: left=331 right=331
- Package left: package com.atombits.popopaw
- Package right: package com.atombits.pocopaw

```diff
diff --git "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\ExecutionRuntimeOrchestrator.kt" "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\ExecutionRuntimeOrchestrator.kt"
index 256a5b4..0e27d61 100644
--- "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\ExecutionRuntimeOrchestrator.kt"
+++ "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\ExecutionRuntimeOrchestrator.kt"
@@ -1 +1 @@
-package com.atombits.popopaw
+package com.atombits.pocopaw
@@ -3,3 +3,3 @@ package com.atombits.popopaw
-import com.atombits.popopaw.process.curation.ProcessLearningWritebackBridge
-import com.atombits.popopaw.process.runtime.ProcessRecoveryContext
-import com.atombits.popopaw.process.runtime.ProcessReviewContext
+import com.atombits.pocopaw.process.curation.ProcessLearningWritebackBridge
+import com.atombits.pocopaw.process.runtime.ProcessRecoveryContext
+import com.atombits.pocopaw.process.runtime.ProcessReviewContext
@@ -262 +262 @@ private fun buildExecutionOutcomeConversationMessage(
-                    ASSISTANT_NAME_ZH
+                    currentAssistantDisplayName()
@@ -279 +279 @@ private fun buildExecutionOutcomeConversationMessage(
-                    ASSISTANT_NAME_ZH,
+                    currentAssistantDisplayName(),
@@ -287 +287 @@ private fun buildExecutionOutcomeConversationMessage(
-                    ASSISTANT_NAME_ZH,
+                    currentAssistantDisplayName(),
```

## 3. ExploratoryAutomationRunner.kt

- Left: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\popopaw\ExploratoryAutomationRunner.kt
- Right: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\pocopaw\ExploratoryAutomationRunner.kt
- Status: DIFFERENT
- Line count: left=1395 right=1524
- Package left: package com.atombits.popopaw
- Package right: package com.atombits.pocopaw

```diff
diff --git "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\ExploratoryAutomationRunner.kt" "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\ExploratoryAutomationRunner.kt"
index 6f60793..cbb0cef 100644
--- "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\ExploratoryAutomationRunner.kt"
+++ "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\ExploratoryAutomationRunner.kt"
@@ -1 +1 @@
-package com.atombits.popopaw
+package com.atombits.pocopaw
@@ -4,2 +4,2 @@ import android.content.Context
-import com.atombits.popopaw.process.runtime.ProcessExplorationLoopConfig
-import com.atombits.popopaw.service.PrototypeAccessibilityService
+import com.atombits.pocopaw.process.runtime.ProcessExplorationLoopConfig
+import com.atombits.pocopaw.service.PrototypeAccessibilityService
@@ -16,0 +17 @@ import org.json.JSONObject
+import java.net.URLEncoder
@@ -118,2 +119,2 @@ interface AutomationAgentClient {
-class QwenAutomationAgentClient(
-    private val apiKey: String = ProviderRuntimeConfigs.qwenVision.apiKey,
+internal class VisionAutomationAgentClient(
+    private val runtimeConfigProvider: () -> ProviderRuntimeConfig = { ProviderRuntimeConfigs.vision },
@@ -121 +122 @@ class QwenAutomationAgentClient(
-    private val endpoint: String = ProviderRuntimeConfigs.qwenVision.endpoint
+    private val visionProviderKindProvider: () -> VisionProviderKind = { ProviderRuntimeConfigs.visionProviderKind() }
@@ -146 +147,2 @@ class QwenAutomationAgentClient(
-        if (apiKey.isBlank()) {
+        val runtimeConfig = runtimeConfigProvider()
+        if (!runtimeConfig.isConfigured()) {
@@ -151,2 +153,2 @@ class QwenAutomationAgentClient(
-                    R.string.automation_model_not_configured,
```

## 4. MainActivity.kt

- Left: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\popopaw\MainActivity.kt
- Right: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\pocopaw\MainActivity.kt
- Status: DIFFERENT
- Line count: left=1192 right=1480
- Package left: package com.atombits.popopaw
- Package right: package com.atombits.pocopaw

```diff
diff --git "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\MainActivity.kt" "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\MainActivity.kt"
index dd96bb4..1a2905c 100644
--- "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\MainActivity.kt"
+++ "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\MainActivity.kt"
@@ -1 +1 @@
-package com.atombits.popopaw
+package com.atombits.pocopaw
@@ -3,0 +4 @@ import android.Manifest
+import android.content.Context
@@ -17,17 +18,17 @@ import androidx.recyclerview.widget.LinearLayoutManager
-import com.atombits.popopaw.databinding.ActivityMainBinding
-import com.atombits.popopaw.orchestration.ChatTurnOrchestrator
-import com.atombits.popopaw.orchestration.ChatTurnSubmitResult
-import com.atombits.popopaw.orchestration.ExecutionEntryOrchestrator
-import com.atombits.popopaw.process.curation.ExecutionLearningPipeline
-import com.atombits.popopaw.shizuku.BootstrapTrigger
-import com.atombits.popopaw.shizuku.ShizukuBootstrapManager
-import com.atombits.popopaw.shizuku.ShizukuBootstrapPlan
-import com.atombits.popopaw.shizuku.ShizukuBootstrapStatus
-import com.atombits.popopaw.shizuku.ShizukuBootstrapStatusCode
-import com.atombits.popopaw.shizuku.ShizukuBootstrapSettingsStore
-import com.atombits.popopaw.shizuku.ShizukuPrivilegeIdentity
-import com.atombits.popopaw.shizuku.ShizukuStatusSnapshot
-import com.atombits.popopaw.service.RuntimeServiceStatusNotifier
-import com.atombits.popopaw.ui.ConsoleRenderAdapter
-import com.atombits.popopaw.ui.ConsoleRenderState
-import com.atombits.popopaw.ui.ShizukuSurfaceState
+import com.atombits.pocopaw.databinding.ActivityMainBinding
```

## 5. OfflineDialoguePreferenceExtraction.kt

- Left: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\popopaw\OfflineDialoguePreferenceExtraction.kt
- Right: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\pocopaw\OfflineDialoguePreferenceExtraction.kt
- Status: DIFFERENT
- Line count: left=432 right=432
- Package left: package com.atombits.popopaw
- Package right: package com.atombits.pocopaw

```diff
diff --git "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\OfflineDialoguePreferenceExtraction.kt" "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\OfflineDialoguePreferenceExtraction.kt"
index 38efa05..8b53d34 100644
--- "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\OfflineDialoguePreferenceExtraction.kt"
+++ "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\OfflineDialoguePreferenceExtraction.kt"
@@ -1 +1 @@
-package com.atombits.popopaw
+package com.atombits.pocopaw
@@ -3 +3 @@ package com.atombits.popopaw
-import com.atombits.popopaw.learning.LearningCurationGateway
+import com.atombits.pocopaw.learning.LearningCurationGateway
@@ -60,2 +60,2 @@ interface OfflineDialoguePreferenceExtractionResolver {
-class DeepSeekOfflineDialoguePreferenceExtractionResolver(
-    private val client: DeepSeekPrototypeClient = DeepSeekPrototypeClient()
+class SemanticOfflineDialoguePreferenceExtractionResolver(
+    private val client: SemanticPrototypeClient = SemanticPrototypeClient()
@@ -80 +80 @@ fun applyOfflineDialoguePreferenceExtractionProjection(
-    resolver: OfflineDialoguePreferenceExtractionResolver = DeepSeekOfflineDialoguePreferenceExtractionResolver()
+    resolver: OfflineDialoguePreferenceExtractionResolver = SemanticOfflineDialoguePreferenceExtractionResolver()
@@ -181 +181 @@ fun applyScheduledOfflineDialoguePreferenceExtractionProjection(
-    resolver: OfflineDialoguePreferenceExtractionResolver = DeepSeekOfflineDialoguePreferenceExtractionResolver(),
+    resolver: OfflineDialoguePreferenceExtractionResolver = SemanticOfflineDialoguePreferenceExtractionResolver(),
```

## 6. ProcessCandidateBridge.kt

- Left: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\popopaw\ProcessCandidateBridge.kt
- Right: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\pocopaw\ProcessCandidateBridge.kt
- Status: DIFFERENT
- Line count: left=920 right=920
- Package left: package com.atombits.popopaw
- Package right: package com.atombits.pocopaw

```diff
diff --git "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\ProcessCandidateBridge.kt" "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\ProcessCandidateBridge.kt"
index 2651762..2f9102a 100644
--- "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\ProcessCandidateBridge.kt"
+++ "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\ProcessCandidateBridge.kt"
@@ -1,11 +1,11 @@
-package com.atombits.popopaw
-
-import com.atombits.popopaw.process.curation.ProcessAssetEntry
-import com.atombits.popopaw.process.curation.ProcessAssetEvent
-import com.atombits.popopaw.process.curation.ProcessAssetEventType
-import com.atombits.popopaw.process.curation.ProcessAssetSourceType
-import com.atombits.popopaw.process.curation.ProcessAssetState
-import com.atombits.popopaw.process.curation.ProcessCurationSummary
-import com.atombits.popopaw.process.reuse.PrototypeStoreProcessAssetRepository
-import com.atombits.popopaw.process.runtime.ProcessRecoveryContext
-import com.atombits.popopaw.process.runtime.ProcessReviewContext
+package com.atombits.pocopaw
+
+import com.atombits.pocopaw.process.curation.ProcessAssetEntry
+import com.atombits.pocopaw.process.curation.ProcessAssetEvent
+import com.atombits.pocopaw.process.curation.ProcessAssetEventType
+import com.atombits.pocopaw.process.curation.ProcessAssetSourceType
+import com.atombits.pocopaw.process.curation.ProcessAssetState
+import com.atombits.pocopaw.process.curation.ProcessCurationSummary
+import com.atombits.pocopaw.process.reuse.PrototypeStoreProcessAssetRepository
+import com.atombits.pocopaw.process.runtime.ProcessRecoveryContext
+import com.atombits.pocopaw.process.runtime.ProcessReviewContext
@@ -646 +646 @@ private fun buildProcessFeedbackStoredMessage(
```

## 7. PromptCenter.kt

- Left: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\popopaw\PromptCenter.kt
- Right: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\pocopaw\PromptCenter.kt
- Status: DIFFERENT
- Line count: left=1412 right=1424
- Package left: package com.atombits.popopaw
- Package right: package com.atombits.pocopaw

```diff
diff --git "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\PromptCenter.kt" "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\PromptCenter.kt"
index c17a598..3112218 100644
--- "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\PromptCenter.kt"
+++ "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\PromptCenter.kt"
@@ -1 +1 @@
-package com.atombits.popopaw
+package com.atombits.pocopaw
@@ -3,4 +3,4 @@ package com.atombits.popopaw
-import com.atombits.popopaw.intent.IntentPromptPacketBuilder
-import com.atombits.popopaw.learning.LearningPromptPacketBuilder
-import com.atombits.popopaw.process.reuse.ReferenceSelectionPromptPacketBuilder
-import com.atombits.popopaw.reply.ChatReplyPromptPacketBuilder
+import com.atombits.pocopaw.intent.IntentPromptPacketBuilder
+import com.atombits.pocopaw.learning.LearningPromptPacketBuilder
+import com.atombits.pocopaw.process.reuse.ReferenceSelectionPromptPacketBuilder
+import com.atombits.pocopaw.reply.ChatReplyPromptPacketBuilder
@@ -20 +20 @@ internal const val ASSISTANT_NAME_ZH = "灏忕埅鐖?
-internal const val ASSISTANT_NAME_EN = "popopaw"
+internal const val ASSISTANT_NAME_EN = "pocopaw"
@@ -27 +27 @@ private const val LOCAL_REGION_REVERSE_GEOCODE_ENDPOINT = "https://nominatim.ope
-private const val LOCAL_REGION_RESOLVER_USER_AGENT = "popopaw/1.0"
+private const val LOCAL_REGION_RESOLVER_USER_AGENT = "pocopaw/1.0"
@@ -224 +224,13 @@ internal fun buildAssistantIdentityInstruction(): String {
-    return "The assistant's Chinese name is $ASSISTANT_NAME_ZH. If an English identifier is needed, use $ASSISTANT_NAME_EN. In user-visible replies, refer to the assistant as $ASSISTANT_NAME_ZH and never as $ASSISTANT_NAME_EN. $ASSISTANT_NAME_ZH is a silly, adorable little red panda who likes tinkering with things. Keep the tone lightly playful and endearing when it fits the user's tone. If $ASSISTANT_NAME_ZH does not fully understand the owner's request, briefly admit the confusion and ask a short clarification instead of guessing."
+    return if (AppLocaleManager.isEnglishLocale()) {
+        "The assistant's English identifier is $ASSISTANT_NAME_EN and its Chinese name is $ASSISTANT_NAME_ZH. In user-visible replies, refer to the assistant as $ASSISTANT_NAME_EN. If the user explicitly refers to $ASSISTANT_NAME_ZH, recognize it as the same assistant. Unless the user explicitly requests another language in the current turn, reply in English. $ASSISTANT_NAME_EN is a silly, adorable little red panda who likes tinkering with things. Keep the tone lightly playful and endearing when it fits the user's tone. If $ASSISTANT_NAME_EN does not fully understand the owner's request, briefly admit the confusion and ask a short clarification instead of guessing."
+    } else {
+        "The assistant's Chinese name is $ASSISTANT_NAME_ZH. If an English identifier is needed, use $ASSISTANT_NAME_EN. In user-visible replies, refer to the assistant as $ASSISTANT_NAME_ZH and never as $ASSISTANT_NAME_EN. Unless the user explicitly requests another language in the current turn, reply in Simplified Chinese. Keep technical terms such as DeepSeek, Qwen, and Shizuku in English. $ASSISTANT_NAME_ZH is a silly, adorable little red panda who likes tinkering with things. Keep the tone lightly playful and endearing when it fits the user's tone. If $ASSISTANT_NAME_ZH does not fully understand the owner's request, briefly admit the confusion and ask a short clarification instead of guessing."
```

## 8. PrototypeAutomationRunner.kt

- Left: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\popopaw\PrototypeAutomationRunner.kt
- Right: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\pocopaw\PrototypeAutomationRunner.kt
- Status: DIFFERENT
- Line count: left=852 right=852
- Package left: package com.atombits.popopaw
- Package right: package com.atombits.pocopaw

```diff
diff --git "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\PrototypeAutomationRunner.kt" "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\PrototypeAutomationRunner.kt"
index 94c1c14..022a5eb 100644
--- "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\PrototypeAutomationRunner.kt"
+++ "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\PrototypeAutomationRunner.kt"
@@ -1 +1 @@
-package com.atombits.popopaw
+package com.atombits.pocopaw
@@ -3,4 +3,4 @@ package com.atombits.popopaw
-import com.atombits.popopaw.process.runtime.ProcessVisionFallbackExecutor
-import com.atombits.popopaw.process.runtime.ProcessShortcutExecutionCoordinator
-import com.atombits.popopaw.process.reuse.CandidateProcessReference
-import com.atombits.popopaw.process.reuse.CandidateProcessReferenceContext
+import com.atombits.pocopaw.process.runtime.ProcessVisionFallbackExecutor
+import com.atombits.pocopaw.process.runtime.ProcessShortcutExecutionCoordinator
+import com.atombits.pocopaw.process.reuse.CandidateProcessReference
+import com.atombits.pocopaw.process.reuse.CandidateProcessReferenceContext
@@ -491 +491 @@ suspend fun executeAutomationCallbackFlow(
-    visionGroundingResolver: VisionGroundingResolver = QwenVisionGroundingResolver(),
+    visionGroundingResolver: VisionGroundingResolver = VisionGroundingResolverClient(),
```

## 9. UiStrings.kt

- Left: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\popopaw\UiStrings.kt
- Right: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\pocopaw\UiStrings.kt
- Status: DIFFERENT
- Line count: left=30 right=30
- Package left: package com.atombits.popopaw
- Package right: package com.atombits.pocopaw

```diff
diff --git "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\UiStrings.kt" "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\UiStrings.kt"
index 15e0bb5..6bbaa66 100644
--- "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\UiStrings.kt"
+++ "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\UiStrings.kt"
@@ -1 +1 @@
-package com.atombits.popopaw
+package com.atombits.pocopaw
@@ -13 +13 @@ internal object UiStrings {
-        appContext = context.applicationContext
+        appContext = AppLocaleManager.wrap(context.applicationContext)
```

## 10. ProcessCurationRuntime.kt

- Left: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\popopaw\process\curation\ProcessCurationRuntime.kt
- Right: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\pocopaw\process\curation\ProcessCurationRuntime.kt
- Status: DIFFERENT
- Line count: left=1776 right=1706
- Package left: package com.atombits.popopaw.process.curation
- Package right: package com.atombits.pocopaw.process.curation

```diff
diff --git "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\process\\curation\\ProcessCurationRuntime.kt" "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\process\\curation\\ProcessCurationRuntime.kt"
index d951650..177b326 100644
--- "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\process\\curation\\ProcessCurationRuntime.kt"
+++ "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\process\\curation\\ProcessCurationRuntime.kt"
@@ -1,43 +1,43 @@
-package com.atombits.popopaw.process.curation
-
-import com.atombits.popopaw.CanonicalTraceRawMaterial
-import com.atombits.popopaw.DeepSeekPrototypeClient
-import com.atombits.popopaw.ExecutionLifecycleStatus
-import com.atombits.popopaw.ExecutionEvent
-import com.atombits.popopaw.ExecutionEventPhase
-import com.atombits.popopaw.learning.LearningCurationGateway
-import com.atombits.popopaw.MemoryState
-import com.atombits.popopaw.ProcessLearningMaterial
-import com.atombits.popopaw.PromptCenter
-import com.atombits.popopaw.PromptMessage
-import com.atombits.popopaw.PromptPacket
-import com.atombits.popopaw.ProcessCurationPromptSpec
-import com.atombits.popopaw.ProcessExtractionGroupPlan
-import com.atombits.popopaw.ProcessCandidateCurationState
-import com.atombits.popopaw.PrototypeStoreData
-import com.atombits.popopaw.ReadyProcessAsset
-import com.atombits.popopaw.orGenericProcessScope
-import com.atombits.popopaw.alignProcessAssetBindingsToTraceSteps
-import com.atombits.popopaw.applyProcessShortcutProjection
-import com.atombits.popopaw.buildPayloadPreservingCanonicalTrace
-import com.atombits.popopaw.buildProcessExtractionGroupPlans
```

## 11. TaskExecutionStartResolver.kt

- Left: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\popopaw\process\projection\TaskExecutionStartResolver.kt
- Right: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\pocopaw\process\projection\TaskExecutionStartResolver.kt
- Status: DIFFERENT
- Line count: left=41 right=43
- Package left: package com.atombits.popopaw.process.projection
- Package right: package com.atombits.pocopaw.process.projection

```diff
diff --git "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\process\\projection\\TaskExecutionStartResolver.kt" "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\process\\projection\\TaskExecutionStartResolver.kt"
index b9170f5..18165cc 100644
--- "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\process\\projection\\TaskExecutionStartResolver.kt"
+++ "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\process\\projection\\TaskExecutionStartResolver.kt"
@@ -1 +1 @@
-package com.atombits.popopaw.process.projection
+package com.atombits.pocopaw.process.projection
@@ -3,4 +3,6 @@ package com.atombits.popopaw.process.projection
-import com.atombits.popopaw.TaskExecutionBoundaryPacket
-import com.atombits.popopaw.LocalConversationState
-import com.atombits.popopaw.TaskPhase
-import com.atombits.popopaw.toTaskExecutionBoundaryPacket
+import com.atombits.pocopaw.R
+import com.atombits.pocopaw.TaskExecutionBoundaryPacket
+import com.atombits.pocopaw.LocalConversationState
+import com.atombits.pocopaw.TaskPhase
+import com.atombits.pocopaw.UiStrings
+import com.atombits.pocopaw.toTaskExecutionBoundaryPacket
@@ -21,2 +23,2 @@ class TaskExecutionStartResolver {
-            userMessage = com.atombits.popopaw.UiStrings.resolve(
-                com.atombits.popopaw.R.string.task_execution_missing_structured_task,
+            userMessage = UiStrings.resolve(
+                R.string.task_execution_missing_structured_task,
@@ -30,2 +32,2 @@ class TaskExecutionStartResolver {
-                userMessage = com.atombits.popopaw.UiStrings.resolve(
-                    com.atombits.popopaw.R.string.task_execution_not_in_execution_phase,
+                userMessage = UiStrings.resolve(
+                    R.string.task_execution_not_in_execution_phase,
```

## 12. ConsoleRenderAdapter.kt

- Left: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\popopaw\ui\ConsoleRenderAdapter.kt
- Right: C:\Users\HP\AndroidStudioProjects\Pocopaw\app\src\main\java\com\atombits\pocopaw\ui\ConsoleRenderAdapter.kt
- Status: DIFFERENT
- Line count: left=381 right=448
- Package left: package com.atombits.popopaw.ui
- Package right: package com.atombits.pocopaw.ui

```diff
diff --git "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\ui\\ConsoleRenderAdapter.kt" "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\ui\\ConsoleRenderAdapter.kt"
index 3c6fb34..042822f 100644
--- "a/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\popopaw\\ui\\ConsoleRenderAdapter.kt"
+++ "b/C:\\Users\\HP\\AndroidStudioProjects\\Pocopaw\\app\\src\\main\\java\\com\\atombits\\pocopaw\\ui\\ConsoleRenderAdapter.kt"
@@ -1 +1 @@
-package com.atombits.popopaw.ui
+package com.atombits.pocopaw.ui
@@ -10,38 +10,43 @@ import androidx.core.view.isVisible
-import com.atombits.popopaw.ChatAdapter
-import com.atombits.popopaw.ChatMessage
-import com.atombits.popopaw.ChatTurnOptions
-import com.atombits.popopaw.ConsoleTaskFormatter
-import com.atombits.popopaw.ConsoleExecutionUiFormatter
-import com.atombits.popopaw.ExecutionLogAdapter
-import com.atombits.popopaw.MemoryState
-import com.atombits.popopaw.MessageRole
-import com.atombits.popopaw.PreferenceDiscoveryAppTarget
-import com.atombits.popopaw.PreferenceDiscoveryCatalog
-import com.atombits.popopaw.PreparingEntrySidecar
-import com.atombits.popopaw.ProcessFeedbackType
-import com.atombits.popopaw.ProviderRuntimeConfigs
-import com.atombits.popopaw.PrototypeStoreData
-import com.atombits.popopaw.QwenVisionModelSettingsStore
-import com.atombits.popopaw.QwenSearchSettingsStore
-import com.atombits.popopaw.QwenThinkingModeSettingsStore
-import com.atombits.popopaw.R
-import com.atombits.popopaw.RuntimeModuleSwitches
-import com.atombits.popopaw.ScreenCaptureCompressionSettingsStore
```

