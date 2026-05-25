# 流程资产与流程复用模块设计 design11

## 1. 当前状态

### 1.1 模块边界

本模块负责 design11 下的 reference-only process reuse、流程学习、curation、feedback 与 lineage 治理。当前固定结论为：

1. ready process asset 只提供 reference，不提供直接执行权。
2. reuse 命中后只生成 guidance layer 与 candidate reference context，最终仍进入统一 screenshot-first runtime。
3. learning 主链是 `execution -> raw material -> process learning material -> pending/recorded entry -> curation -> ready/page evidence/shortcut`。
4. system-intent completion 不进入 reusable process promotion，也不生成 process review / learning 资产。
5. passive 阶段命中可复用流程只能作为 topic / task evidence，不能因为 stage 预判自动 replay 或启动 runtime。

### 1.2 生命周期与正式对象

当前正式状态枚举为：`RECORDED / PENDING / READY / FAILED / SUPERSEDED`。

正式对象包括：

| 对象 | 作用 |
| --- | --- |
| `ProcessAssetEntry` | canonical 资产主记录 |
| `ReadyProcessAsset` | runtime 检索索引 |
| `PageEvidenceAsset` | 页面语义证据索引 |
| `ProcessShortcutCandidate` | grounded shortcut 索引 |
| `ProcessLearningMaterial` | 成功执行后的学习材料 |
| `ProcessCandidateRecord` | UI / feedback 侧候选记录 |
| `ProcessFeedbackRecord` | feedback 记录 |
| `ProcessSlotHint` | 流程资产侧的 namespaced slot metadata |

`ProcessSlotHint` 只能使用 canonical namespaced resolved slot key，例如 `communication.recipient`、`common.price`、`shopping.product_type`，不允许流程资产侧再造平行 parameter 命名。

### 1.3 复用主链

`ProcessReuseRuntime.resolve(...)` 当前按以下规则工作：

1. 基于 task-first boundary / runtime evidence 构造 `StructuredTaskIntent`。
2. 生成 `ProcessGuidanceLayer` 与 `CandidateProcessReferenceContext`。
3. 从 ready assets 中检索 grounded candidate references。
4. 必要时使用 `PROCESS_REFERENCE_SELECTION_QUERY` 进行模型选择，否则走本地选择。
5. 输出 preferred reference、whySelected、selectedStageHints 和 referenceCautions。
6. candidate reference 的 verification signal、matched anchor 和 selected stage hint 必须继续进入 route info / runtime process guidance，作为 execution 期的观察与完成判据输入。
7. execution start 前的 grounded shortcut ranking 可以读取 `ProcessShortcutAtlas` 中由偏好证据支撑的信号，但它只影响 shortcut 候选优先级，不改变 final route authority。
8. query 侧必须读取 `TaskSlotEvidenceSnapshot / boundaryPacket.structuredDetailSlots / boundaryPacket.resolvedSlots`，candidate 侧必须读取 `ReadyProcessAsset.slotHints`。

没有 grounded reference 时，runtime 必须回到 `exploratory` 主链；generic family scope 不能被兜成 grounded reusable reference。

### 1.4 学习、curation 与 feedback

当前学习与治理链固定为：

1. 非 system-intent 的成功执行进入 `ProcessLearningWritebackBridge.applyCompletedExecution()`。
2. 若复用 reference 与本轮真实 canonical process 一致，则生成 `RECORDED`；否则生成新的 `PENDING` lineage。
3. `ProcessTracePreprocessor` 负责 trace 去重、canonical bundle、verification signal 与 compact trace 生成。
4. `ProcessCurationRuntime.runProcessCurationOnce()` 一次处理一条 pending entry，优先走模型 curation，再做 local salvage。
5. reviewer 的 thumbs up / down 与可选 comment 必须回流为 `ProcessFeedbackRecord`、排序信号或同 lineage 的 pending revision。
6. offline process extraction 必须同时产出 reference-first stage/page/verification 资产和 asset-side 的 `ProcessSlotHint`。

### 1.5 命名与 lineage 约束

1. lineage 只能在同 `appScope + canonical process` 范围内延续。
2. `SUPERSEDED` 只允许发生在同 canonical process lineage 内。
3. ready asset、shortcut、candidate 的命名必须复用同一份 canonical `processAction` 结论。
4. feedback、candidate revision、curation revision 都必须保留 `pathIndex` 与版本语义。
5. ready asset 和 shortcut 只能影响候选优先级、方案生成和执行指导，不能替代用户显式执行请求。

## 2. 待开发项目

1. 让 `ActiveTaskContext / TaskCheckpoint / ContinuationGroundingResult` 直接驱动 reuse ranking、selection 和 feedback。
2. 补齐 path-aware 的独立结构化可视化面。
3. 继续收敛旧命名与输入冗余，完成全量 task-first 输入面收口。
4. 强化 route / lineage 结构化观测，而不是只靠文本摘要。
5. 为历史 ready assets 和旧 raw material 补足 slot shape compatibility，逐步减少只靠自然语言 `semantic_description` 推断参数语义的兼容路径。
