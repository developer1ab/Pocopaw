# 偏好入口模块设计 design11

## 1. 当前状态

### 1.1 模块定位

本模块统一承担所有 preference / habit / style 证据的入口层。它当前拥有：

1. app / page / shortcut / history 侧的 preference discovery。
2. dialogue backlog 侧的 offline preference extraction。
3. 入口链路的最小调度状态、失败诊断面和统一写回规则。

本模块当前不拥有：

1. structured preference memory 的正式存储结构。
2. recall、sibling expansion、slot recommendation。
3. personalization policy、proactive delivery 或 execution route authority。

### 1.2 Preference Discovery 入口链

当前 discovery 侧的正式入口分两层：

1. live 手动入口：`MainActivity.runPreferenceDiscovery() -> PreferenceDiscoveryManualScanRunner.run(...) -> applyPreferenceDiscoveryScan(...)`。
2. library batch 入口：`projectPreferenceDiscoveryScansFromLocalHistory(...)`、`projectPreferenceDiscoveryScansFromThirdPartyUsage(...)`、`schedulePreferenceDiscoveryScanBatch(...)`、`enqueuePreferenceDiscoveryScan(...)`、`applyQueuedPreferenceDiscoveryScans(...)`。

当前固定结论为：

1. manual scan 是当前 live ready 的 discovery 主路径。
2. batch schedule / apply API 仍是 projection 与调度面，尚未并入 ordinary live turn 或后台自治调度主链。
3. discovery observation 必须先通过 domain-supported slot catalog、`CanonicalAppCatalog` 和 projector 归一化，再进入统一写回面。
4. vision / app scan 不能直接写 durable preference fact；它只能先产出 order / service history record，再由 projector 合并 observation count、source metadata、recent fact、semantic chunk 和 interaction bias。
5. key app 目标通过 `CanonicalAppCatalog.appsForDomain(...)` 投影，不能在 ingestion 内私有维护 app list。

### 1.3 Offline Dialogue Preference Extraction 入口链

当前 offline extraction 侧的正式入口包括：

1. `applyOfflineDialoguePreferenceExtractionProjection(...)`
2. `applyScheduledOfflineDialoguePreferenceExtractionProjection(...)`

当前 live 入口固定为 settings one-off 手动动作：`MainActivity.runPreferenceExtraction() -> applyScheduledOfflineDialoguePreferenceExtractionProjection(...)`。

当前固定规则为：

1. backlog 达到阈值才触发 extraction。
2. 若模型没有产出稳定 preference / habit / style 证据，则 backlog 保留。
3. parser 继续兼容受控 alias 字段，但不恢复自由文本扩张。
4. extraction prompt 把 user messages、structured slots 和 resolved slots 当作权威证据。
5. `DialoguePreferenceBacklogRecord` 必须持久化 `TaskSlotEvidenceSnapshot`；batch 固定输出 `authoritative_slot_source`、`structured_detail_slots.common/domain` 与 `authoritative_resolved_slots`。
6. extraction 结果先生成 `DialoguePreferenceRecord`，再由 `PreferenceMemorySignalProjector` 写入 `structuredPreferenceMemory / interactionBiasMemory / habitMemoryStore / interactionStyleStore`。

### 1.4 统一写回面

两条入口最终统一写回 `MemoryState` 的同一 ingress-facing 事实面：

1. `structuredPreferenceMemory`
2. `interactionBiasMemory`
3. `habitMemoryStore`
4. `interactionStyleStore`
5. `preferenceDebugStore`

当前这层“统一”以 normalized projection 为边界：discovery 更偏向 order / service history projection 和 app-aware bias projection，extraction 更偏向 stable preference / habit / style 解释；两者共享的是同一 writeback plane，而不是同一上游语义。

### 1.5 设计约束

1. 新入口必须统一写回 `MemoryState`，不能创建第二份偏好 profile store。
2. discovery 与 extraction 的差异必须体现在 sourceType、projection 结果和 runtime state，而不是分裂成两套事实面。
3. canonical preference key taxonomy 必须对齐 discovery slot catalog、`TaskDetailSlots / resolvedSlots` 与下游 recall / reuse 消费面。
4. preference / habit / style 只能补上下文和排序，不拥有 passive stage signal 或 execution start 权限。
5. preference extraction 的正式 slot authority 必须来自 `TaskSlotEvidenceSnapshot.structuredDetailSlots / resolvedSlots`；legacy flat `detailSlots` 只保留 debug / migration 角色。
6. settings 页必须能看到 discovery / extraction 的最小 projection 诊断摘要。

## 2. 待开发项目

1. 继续统一 discovery、extraction、personalization 和 shortcut reuse 之间的 canonical preference key taxonomy 与 source metadata。
2. 继续明确 durable preference evidence 与 transient task fact 的分层 / 加权边界。
3. 在受控、可见的前提下，把 batch discovery scheduling / consume 接到正式 live 路径，或明确降级为纯 one-off 工具面。
4. 补强 multi-source weighting 与 domain-specific promotion gate。
5. 扩展第三方历史源。
6. 继续把 settings 页的 discovery / extraction 区块升级为 projection diagnostics surface。
