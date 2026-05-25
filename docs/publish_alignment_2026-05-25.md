# 发布前最终对齐说明 2026-05-25

本说明用于固化当前“恢复后可发布候选状态”的差异解释，避免后续将已批准替代误判为缺失。

## 当前基线

- 基线清单：`temp_restore_manifest_20260525_1830.json`
- 机器快照：`temp_publish_alignment_snapshot_20260525.json`
- 清单外文件：24
- 清单内缺失文件：33
- 缺失项存在替代路径：33
- 缺失项无替代路径：0

结论：33 个缺失路径全部为 `com/atombits/popopaw/...`，且已由对应 `com/atombits/pocopaw/...` 文件替代，属于经审批通过的命名空间标准化结果。

## 审批结果固化

- A：保留
- B：本地保留
- C：删除（已执行，`C_REMAINING=0`）

## 缺失到替代映射（33/33）

1. `app/src/main/java/com/atombits/popopaw/AppLaunchAutomationRunner.kt` -> `app/src/main/java/com/atombits/pocopaw/AppLaunchAutomationRunner.kt`
2. `app/src/main/java/com/atombits/popopaw/ChatAdapter.kt` -> `app/src/main/java/com/atombits/pocopaw/ChatAdapter.kt`
3. `app/src/main/java/com/atombits/popopaw/ConsoleExecutionUiFormatter.kt` -> `app/src/main/java/com/atombits/pocopaw/ConsoleExecutionUiFormatter.kt`
4. `app/src/main/java/com/atombits/popopaw/ExecutionFlowRunner.kt` -> `app/src/main/java/com/atombits/pocopaw/ExecutionFlowRunner.kt`
5. `app/src/main/java/com/atombits/popopaw/ExecutionRecoveryBridge.kt` -> `app/src/main/java/com/atombits/pocopaw/ExecutionRecoveryBridge.kt`
6. `app/src/main/java/com/atombits/popopaw/ExecutionRuntimeOrchestrator.kt` -> `app/src/main/java/com/atombits/pocopaw/ExecutionRuntimeOrchestrator.kt`
7. `app/src/main/java/com/atombits/popopaw/ExecutionWritebackBridge.kt` -> `app/src/main/java/com/atombits/pocopaw/ExecutionWritebackBridge.kt`
8. `app/src/main/java/com/atombits/popopaw/ExploratoryAutomationRunner.kt` -> `app/src/main/java/com/atombits/pocopaw/ExploratoryAutomationRunner.kt`
9. `app/src/main/java/com/atombits/popopaw/MainActivity.kt` -> `app/src/main/java/com/atombits/pocopaw/MainActivity.kt`
10. `app/src/main/java/com/atombits/popopaw/OfflineDialoguePreferenceExtraction.kt` -> `app/src/main/java/com/atombits/pocopaw/OfflineDialoguePreferenceExtraction.kt`
11. `app/src/main/java/com/atombits/popopaw/PhoneContactResolver.kt` -> `app/src/main/java/com/atombits/pocopaw/PhoneContactResolver.kt`
12. `app/src/main/java/com/atombits/popopaw/PreferenceDiscovery.kt` -> `app/src/main/java/com/atombits/pocopaw/PreferenceDiscovery.kt`
13. `app/src/main/java/com/atombits/popopaw/PreferenceExtractionSettingsFormatter.kt` -> `app/src/main/java/com/atombits/pocopaw/PreferenceExtractionSettingsFormatter.kt`
14. `app/src/main/java/com/atombits/popopaw/ProactiveRuntimeBridge.kt` -> `app/src/main/java/com/atombits/pocopaw/ProactiveRuntimeBridge.kt`
15. `app/src/main/java/com/atombits/popopaw/process/curation/ProcessCurationRuntime.kt` -> `app/src/main/java/com/atombits/pocopaw/process/curation/ProcessCurationRuntime.kt`
16. `app/src/main/java/com/atombits/popopaw/process/curation/ProcessLearningWritebackBridge.kt` -> `app/src/main/java/com/atombits/pocopaw/process/curation/ProcessLearningWritebackBridge.kt`
17. `app/src/main/java/com/atombits/popopaw/process/projection/TaskExecutionStartResolver.kt` -> `app/src/main/java/com/atombits/pocopaw/process/projection/TaskExecutionStartResolver.kt`
18. `app/src/main/java/com/atombits/popopaw/process/runtime/ExecutionPlanningPipeline.kt` -> `app/src/main/java/com/atombits/pocopaw/process/runtime/ExecutionPlanningPipeline.kt`
19. `app/src/main/java/com/atombits/popopaw/process/runtime/ExecutionVerificationEvaluator.kt` -> `app/src/main/java/com/atombits/pocopaw/process/runtime/ExecutionVerificationEvaluator.kt`
20. `app/src/main/java/com/atombits/popopaw/process/runtime/ProcessExplorationRuntime.kt` -> `app/src/main/java/com/atombits/pocopaw/process/runtime/ProcessExplorationRuntime.kt`
21. `app/src/main/java/com/atombits/popopaw/process/runtime/ProcessShortcutExecutionCoordinator.kt` -> `app/src/main/java/com/atombits/pocopaw/process/runtime/ProcessShortcutExecutionCoordinator.kt`
22. `app/src/main/java/com/atombits/popopaw/process/runtime/ProcessVisionFallbackExecutor.kt` -> `app/src/main/java/com/atombits/pocopaw/process/runtime/ProcessVisionFallbackExecutor.kt`
23. `app/src/main/java/com/atombits/popopaw/ProcessCandidateBridge.kt` -> `app/src/main/java/com/atombits/pocopaw/ProcessCandidateBridge.kt`
24. `app/src/main/java/com/atombits/popopaw/PromptCenter.kt` -> `app/src/main/java/com/atombits/pocopaw/PromptCenter.kt`
25. `app/src/main/java/com/atombits/popopaw/PrototypeAutomationRunner.kt` -> `app/src/main/java/com/atombits/pocopaw/PrototypeAutomationRunner.kt`
26. `app/src/main/java/com/atombits/popopaw/PrototypeStore.kt` -> `app/src/main/java/com/atombits/pocopaw/PrototypeStore.kt`
27. `app/src/main/java/com/atombits/popopaw/ReadyProcessStore.kt` -> `app/src/main/java/com/atombits/pocopaw/ReadyProcessStore.kt`
28. `app/src/main/java/com/atombits/popopaw/service/CaptureService.kt` -> `app/src/main/java/com/atombits/pocopaw/service/CaptureService.kt`
29. `app/src/main/java/com/atombits/popopaw/ToolDiscovery.kt` -> `app/src/main/java/com/atombits/pocopaw/ToolDiscovery.kt`
30. `app/src/main/java/com/atombits/popopaw/ToolspaceCatalogManager.kt` -> `app/src/main/java/com/atombits/pocopaw/ToolspaceCatalogManager.kt`
31. `app/src/main/java/com/atombits/popopaw/ui/ConsoleRenderAdapter.kt` -> `app/src/main/java/com/atombits/pocopaw/ui/ConsoleRenderAdapter.kt`
32. `app/src/main/java/com/atombits/popopaw/UiStrings.kt` -> `app/src/main/java/com/atombits/pocopaw/UiStrings.kt`
33. `app/src/test/java/com/atombits/popopaw/PromptGovernanceTest.kt` -> `app/src/test/java/com/atombits/pocopaw/PromptGovernanceTest.kt`

## 发布解释口径（建议）

对外或后续提交说明可使用：

“本次恢复后清单对齐中，`popopaw` 命名空间路径统一迁移为 `pocopaw`。基线清单中的 33 条 `popopaw` 路径在工作区不存在，但全部有一一对应的 `pocopaw` 替代文件，且经审批确认属于有意标准化替换，不是数据丢失。”
