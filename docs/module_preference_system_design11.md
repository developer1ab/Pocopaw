# 偏好系统模块设计 design11

## 1. 当前状态

### 1.1 模块定位

本模块统一承担 design11 下的 preference system：

1. preference / habit / style 证据入口。
2. structured preference memory 与 interaction bias memory。
3. recall、sibling expansion、slot recommendation。
4. settings、`CONVERSATION` debug sidecar 与 `EXECUTION` evidence drawer 的 preference observability。

它描述的是同一条 preference 数据链的写入面、存储面和读取面，而不是三套并列子系统。

本模块当前不拥有：

1. execution authority。
2. route authority。
3. `PromptCenter` owner 权限。
4. personalization policy 与 proactive delivery 的最终策略裁决。

### 1.2 正式数据面

`MemoryState` 在 preference system 相关部分的正式数据面固定为：

| 子 store | 作用 |
| --- | --- |
| `structuredPreferenceMemory` | 用户长期/近期偏好 facts、recent facts、semantic chunks |
| `interactionBiasMemory` | `preferred_process_id`、`preferred_page_signature`、`preferred_shortcut_screen` 等交互偏置 |
| `habitMemoryStore` | 习惯证据 |
| `interactionStyleStore` | 表达 / 风格证据 |
| `preferenceDebugStore` | projection / recall / sibling expansion / slot mapping 诊断快照 |
| `dialoguePreferenceBacklog` | 对话偏好待抽取 backlog |

旧 `preferenceEvidenceStore` 与 `preferenceSummaryCards` 只保留迁移兼容角色，不再是 live consumer 面。

### 1.3 入口链

当前 preference ingress 分两类：

1. preference discovery：app / page / shortcut / history 侧 observation 入口。
2. offline dialogue preference extraction：对话 backlog 侧的稳定偏好抽取入口。

discovery 侧正式入口分两层：

1. live 手动入口：`MainActivity.runPreferenceDiscovery() -> PreferenceDiscoveryManualScanRunner.run(...) -> applyPreferenceDiscoveryScan(...)`。
2. library batch 入口：`projectPreferenceDiscoveryScansFromLocalHistory(...)`、`projectPreferenceDiscoveryScansFromThirdPartyUsage(...)`、`schedulePreferenceDiscoveryScanBatch(...)`、`enqueuePreferenceDiscoveryScan(...)`、`applyQueuedPreferenceDiscoveryScans(...)`。

当前固定结论为：

1. manual scan 是当前 live ready 的 discovery 主路径。
2. batch schedule / apply API 仍是 projection 与调度面，尚未并入 ordinary live turn 或后台自治调度主链。
3. discovery observation 必须先通过 domain-supported slot catalog、`CanonicalAppCatalog` 和 projector 归一化，再进入统一写回面。
4. vision / app scan 不能直接写 durable preference fact；它只能先产出 order / service history record，再由 projector 合并 observation count、source metadata、recent fact、semantic chunk 和 interaction bias。
5. key app 目标通过 `CanonicalAppCatalog.appsForDomain(...)` 投影，不能在 preference ingress 内私有维护 app list。

offline extraction 侧正式入口包括：

1. `applyOfflineDialoguePreferenceExtractionProjection(...)`
2. `applyScheduledOfflineDialoguePreferenceExtractionProjection(...)`

当前 live 入口固定为 settings one-off 手动动作：`MainActivity.runPreferenceExtraction() -> applyScheduledOfflineDialoguePreferenceExtractionProjection(...)`。

当前固定规则为：

1. backlog 达到阈值才触发 extraction。
2. 若模型没有产出稳定 preference / habit / style 证据，则 backlog 保留。
3. parser 继续兼容受控 alias 字段，但不恢复自由文本扩张。
4. extraction prompt 把 user messages、structured slots 和 resolved slots 当作权威证据。
5. `DialoguePreferenceBacklogRecord` 必须持久化 `TaskSlotEvidenceSnapshot`；batch 固定输出 `authoritative_slot_source`、`structured_detail_slots.common/domain` 与 `authoritative_resolved_slots`。
6. extraction 结果先生成 `DialoguePreferenceRecord`，再由 `PreferenceMemorySignalProjector` 写入正式 preference stores。

### 1.4 统一写回与存储规则

两条入口最终统一写回 `MemoryState` 的同一 preference 事实面：

1. `structuredPreferenceMemory`
2. `interactionBiasMemory`
3. `habitMemoryStore`
4. `interactionStyleStore`
5. `preferenceDebugStore`

当前这层“统一”以 normalized projection 为边界：discovery 更偏向 order / service history projection 和 app-aware bias projection，extraction 更偏向 stable preference / habit / style 解释；两者共享的是同一 writeback plane，而不是同一上游语义。

canonical preference key taxonomy 必须对齐 discovery slot catalog、`TaskDetailSlots / resolvedSlots`、`TaskSlotEvidenceSnapshot` 与下游 recall / reuse 消费面。

### 1.5 Recall、sibling expansion 与 slot recommendation

当前 recall 主链固定为：

1. 当前用户输入先构造 `PreferenceRecallRequest`。
2. `PreferenceRecallResolver` 先执行 current-domain direct recall。
3. direct evidence 偏弱时，允许进入 sibling expansion。
4. sibling expansion 必须受 `domainRoot`、sibling distance、transferable facets 和 live gating 约束。
5. 最终输出 `PreferenceRecallBundle`，显式区分 direct preferences、recent facts、semantic evidence、neighbor evidence 与 derived hypotheses。

正式约束为：

1. sibling expansion 只能发生在共享同一个 `domainRoot` 的 sibling domains 内。
2. derived hypothesis 永远低于 direct evidence。
3. derived hypothesis 可以进入 live 主链，但必须显式标记为推断结果。
4. derived hypothesis 不得直接回写为 durable preference fact。

`PreferenceSlotRecommendationEngine` 把 recall bundle 映射为 `RecommendedDetailSlotBundle`。当前映射优先级固定为：

1. 用户显式输入。
2. 当前 authoritative topic/task/boundary slots。
3. 订单 / 服务 direct facts。
4. 对话 direct facts。
5. recent evidence。
6. semantic evidence。
7. neighbor evidence。
8. derived hypotheses。

recommended detail slots 只服务 semantic / plan / reuse 弱提示，不自动推进 execution，也不覆盖 authoritative boundary slot。

### 1.6 可见面与调试合同

当前正式 observability surface 固定为三处：

1. settings 页：展示最近一次 discovery / extraction projection、recall 和 mapping trace 摘要，服务于 one-off 调试。
2. `CONVERSATION` debug sidecar：展示当前 turn 的 recall bundle、recommended slots、sibling expansion 状态和 direct / neighbor / derived 来源层级。
3. `EXECUTION` evidence drawer：展示 execution start 之前消费的 frozen preference snapshot，说明哪些推荐进入了 boundary，哪些被拒绝、覆盖或要求确认。

### 1.7 设计约束

1. 新入口必须统一写回 `MemoryState`，不能创建第二份偏好 profile store。
2. preference system 只能提供 evidence、debug trace 和 recommendation，不能生成 execution authority。
3. discovery 与 extraction 的差异必须体现在 sourceType、projection 结果和 runtime state，而不是分裂成两套事实面。
4. preference / habit / style 只能补上下文和排序，不拥有 passive stage signal 或 execution start 权限。
5. preference extraction 的正式 slot authority 必须来自 `TaskSlotEvidenceSnapshot.structuredDetailSlots / resolvedSlots`；legacy flat `detailSlots` 只保留 debug / migration 角色。
6. UI debug 面只能展示 recall / inference / mapping 结果，不能绕过本地 resolver 直接写 store。
7. interaction bias memory 不得重新污染用户偏好 recall 结果。
8. prompt contract 必须区分 direct / neighbor / derived 三个层级，不能重新合成模糊 preference 文本块。

## 2. 待开发项目

1. 继续统一 discovery、extraction、recall、personalization 和 shortcut reuse 之间的 canonical preference key taxonomy 与 source metadata。
2. 继续明确 durable preference evidence 与 transient task fact 的分层 / 加权边界。
3. 在受控、可见的前提下，把 batch discovery scheduling / consume 接到正式 live 路径，或明确降级为纯 one-off 工具面。
4. 扩展 settings、`CONVERSATION` debug sidecar 与 `EXECUTION` evidence drawer 的 projection / recall / mapping diagnostics 粒度。
5. 继续完善 multi-source weighting、domain-specific promotion gate 和 sibling expansion 的可解释性。
6. 扩展 canonical facet-to-slot mapping，尤其是 transport、food、local service 与更多 commerce 外 domain。
7. 扩展第三方历史源。
8. 继续验证 recommended slots 在 semantic / plan / reuse 中只作为 evidence 与 weak hints，不会绕过 task authority。