# 清单差异审批单 2026-05-25

目标基线：`temp_restore_manifest_20260525_1830.json`。

当前差异快照文件：`temp_manifest_diff_after_conflict_cleanup.json`。

## 摘要

- 清单外文件：43
- 清单内缺失文件：33
- 缺失文件中有直接替代路径的数量：33
- 缺失文件中无替代路径的数量：0

说明：这 33 个缺失文件全部是 `com/atombits/popopaw/...` 路径，并且都存在对应的 `com/atombits/pocopaw/...` 文件。它们是你批准“冲突保留右侧”后产生的命名空间/路径标准化结果。

## 审批块 A（建议批准保留）

处理动作：保留以下文件，并将对应 `popopaw` 缺失路径视为“有意替换”。

- `app/src/main/java/com/atombits/pocopaw/AppLaunchAutomationRunner.kt`
- `app/src/main/java/com/atombits/pocopaw/ChatAdapter.kt`
- `app/src/main/java/com/atombits/pocopaw/ConsoleExecutionUiFormatter.kt`
- `app/src/main/java/com/atombits/pocopaw/ExecutionFlowRunner.kt`
- `app/src/main/java/com/atombits/pocopaw/ExecutionWritebackBridge.kt`
- `app/src/main/java/com/atombits/pocopaw/PhoneContactResolver.kt`
- `app/src/main/java/com/atombits/pocopaw/PreferenceDiscovery.kt`
- `app/src/main/java/com/atombits/pocopaw/PreferenceExtractionSettingsFormatter.kt`
- `app/src/main/java/com/atombits/pocopaw/ProactiveRuntimeBridge.kt`
- `app/src/main/java/com/atombits/pocopaw/process/curation/ProcessLearningWritebackBridge.kt`
- `app/src/main/java/com/atombits/pocopaw/process/runtime/ExecutionPlanningPipeline.kt`
- `app/src/main/java/com/atombits/pocopaw/process/runtime/ExecutionVerificationEvaluator.kt`
- `app/src/main/java/com/atombits/pocopaw/process/runtime/ProcessExplorationRuntime.kt`
- `app/src/main/java/com/atombits/pocopaw/process/runtime/ProcessShortcutExecutionCoordinator.kt`
- `app/src/main/java/com/atombits/pocopaw/process/runtime/ProcessVisionFallbackExecutor.kt`
- `app/src/main/java/com/atombits/pocopaw/PrototypeStore.kt`
- `app/src/main/java/com/atombits/pocopaw/ReadyProcessStore.kt`
- `app/src/main/java/com/atombits/pocopaw/service/CaptureService.kt`
- `app/src/main/java/com/atombits/pocopaw/ToolDiscovery.kt`
- `app/src/main/java/com/atombits/pocopaw/ToolspaceCatalogManager.kt`
- `app/src/test/java/com/atombits/pocopaw/PromptGovernanceTest.kt`

## 审批块 B（建议本地保留审计）

处理动作：为恢复可追溯性保留；如果你希望未来公开仓库更干净，可在发布时排除。

- `docs/recovery_audit_2026-05-25.md`
- `docs/conflict_review_2026-05-25.md`

## 审批块 C（建议发布前移除）

处理动作：除非你明确要保留这些临时设计/迁移产物，否则建议从受控仓库中移除（或移到仓库外）。

- `docs/temp_canonical_app_catalog_centralization_design_2026-05-20.md`
- `docs/temp_capability_prior_narrowing_design_2026-05-18.md`
- `docs/temp_design_to_code_matrix_2026-05-22.md`
- `docs/temp_domain_detail_slot_schema_design_2026-05-18.md`
- `docs/temp_domain_unification_refactor_design.md`
- `docs/temp_execution_boundary_session_persistence_fix_2026-05-18.md`
- `docs/temp_preference_memory_replacement_design_2026-05-21.md`
- `docs/temp_shizuku_execution_bootstrap_design_2026-05-19.md`
- `docs/temp_structured_detail_slot_extraction_reuse_alignment_design_2026-05-21.md`
- `docs/temp_triple_route_orchestration_design_2026-05-11.md`
- `docs/temp_ui_display_correction_design_2026-05-23.md`
- `docs/temp_unified_input_stack_design_2026-05-11.md`
- `scripts/lib/package-manager.js`
- `scripts/lib/utils.js`
- `scripts/python/create_tests.py`
- `scripts/python/parse_json.py`
- `scripts/setup-package-manager.js`
- `tools/temp_migrate_execution_contract_calls.ps1`
- `tools/temp_migrate_execution_slots.ps1`
- `tools/temp_restore_corrupted_test.ps1`

## 快速审批方式

如果你希望最快收敛到“可发布候选”状态：

1. 批准 A 保留。
2. 批准 B 本地保留（或标记发布排除）。
3. 批准 C 移除。

## 执行后对齐文档

审批执行后的最终对齐说明见：`docs/publish_alignment_2026-05-25.md`。
