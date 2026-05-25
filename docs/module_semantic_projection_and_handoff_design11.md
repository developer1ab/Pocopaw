# 语义投影与执行交接模块设计 design11

## 1. 当前状态

### 1.1 模块边界

本模块负责把模型语义收口成可本地消费的 task-first execution authority。当前固定结论为：

1. 模型负责语义，不负责最终 executable authority。
2. passive 链条里模型输出的 stage / progress signal 只表示对用户语义情景的预判，不允许直接触发提案或 execution runtime。
3. active topic 是当前唯一 live topic authority；silent topic 可被用户后续语义重新激活，并继续更新该 topic 的本地持久化详细槽位。
4. `TaskRecord` 是明确执行请求后的 live task authority；system intent 回到 chat 后，live task authority 必须退役。
5. `TaskExecutionStartResolver` 与 `ExecutionPlanningPipeline` 是 task-first start 的本地 owner。
6. 正式 domain 只有一套统一主域；更细的执行入口和 route 差异由 `capability_id`、app policy、tool metadata、`action_code` 和 structured detail slots 承担。
7. `TaskDetailSlots.common/domain` 是 topic、task、boundary、extraction 和 reuse 的 slot authority；legacy flat `detailSlots` 只保留兼容投影。

### 1.2 控制链与语义状态

当前 passive control 的权威顺序固定为：

1. `PassiveUserTransitionIntent`
2. `UserProgressSignal`
3. legacy `dialogue_stage`

正式语义对象继续包括：`SemanticIntentCandidate`、`SemanticIntentState`、`CanonicalAction`、`SemanticIntentReadiness`、`SemanticPhaseType`、`SemanticPhaseStatus` 和 `SemanticNextMove`。

这些对象的职责是把模型输出压缩成可本地 resolver 消费的受控状态，而不是形成第二套 execution authority。

### 1.3 Topic-first passive 主链

passive 语义主链先处理 topic，再决定是否生成方案或执行：

1. 用户闲聊时，assistant 只陪聊；识别到主题后初始化 active topic。
2. 本地把历史对话、历史偏好、semantic recall 和 active / silent topic 槽位作为 `SEMANTIC_TURN` 输入。
3. 模型输出更新后的 topic 详细槽位；本地持久化到 active / silent topic，这是 topic write，不是 `TaskRecord` write。
4. 用户明确要求出方案时，模型基于匹配 topic 槽位或当前上下文生成自然语言方案，并同时回传槽位更新。
5. 用户明确要求执行时，模型基于匹配 topic 槽位或当前上下文生成执行计划；本地再进入 task-first start resolution。

### 1.4 Task-first start resolution

当前 start resolution 主链固定为：

1. `DeepSeekPrototypeClient.buildSemanticTurnResponse()` 解析 semantic-intent envelope，不再解析 model-authored `execution_handoff_brief`。
2. `IntentGateway` 与 `ChatReplyGateway` 在发起语义请求前必须先走 `ProviderRuntimeConfigs.semanticRuntimeConfig(...)`，按当前 provider profile 与 `SemanticRuntimePreferences` 选择 runtime config。
3. 普通 passive 语义结果只写回 active / silent topic 槽位与 `currentSemanticIntentState`；没有明确执行请求时不得把 `currentTaskDraft/currentTaskRecord` 当成 live authority。
4. 明确方案请求才允许从 topic 槽位生成 `TaskDraft`；明确执行请求或已批准 proactive execution request 才允许生成 / 激活 `TaskRecord`，并调用 `TaskExecutionStartResolver.resolve(...)` 判断其是否达到可执行条件。
5. system intent 结束并回到 chat 后，live authority 字段必须清空，只保留 `pendingExecutionRecovery` 作为 non-authoritative follow-up hint。
6. `ExecutionPlanningPipeline.prepare(...)` 归一化 capability / process binding，并生成 `PreparedExecutionStart`、verification checks、`routeInfo` 与 `routeEntryType`。
7. downstream runtime 只消费本地 task-first boundary，不再回退为 prompt 内自由文本控制。
8. communication system intent 的参数 authority 也收口在本模块边界：semantic / route 侧只允许选择 bare capability；recipient、message body、`tel:`、`smsto:` 和联系人唯一性必须在本地 start boundary 之后由 execution runtime 与 device resolver 生成和校验。

### 1.5 Route 与 continuation 补偿

本模块当前同时承担两类本地补偿：

1. route 补偿：消费 `SemanticRouteHints`、service alias、task/capability binding 和当前 family ranking，把“去哪做”收口到本地 route authority。
2. continuation 补偿：`ContinuationGroundingResolver` 从 `MemoryState + TopicStore/topicContextStore + currentTaskRecord/currentTaskDraft + currentProcessRuntime + review/recovery context + semantic state` 派生 `ContinuationTaskEvidence / ActiveTaskContext / TaskCheckpoint / ContinuationGroundingResult`。

两类补偿都只能提供 local evidence 或 local repair，不允许越权把模型或 memory 变成新的 authority。

### 1.6 设计约束

1. passive topic 字段优先挂在 TopicStore/topicContextStore 和 `SemanticIntentState`；execution 字段才挂在 `TaskRecord`、`TaskExecutionBoundaryPacket`、`PreparedExecutionStart` 或 trace 对象上。
2. route 选择必须在 local resolver 内完成，不能回退为模型 author tool/process id。
3. active / silent topic 必须是本地持久化 topic contract；`ActiveTaskContext / TaskCheckpoint` 可以保持派生视图，但不能替代 topic storage。
4. passive stage signal 只能驱动 topic 槽位持久化，不能自行触发方案生成、execution start 或 proactive delivery。

## 2. 待开发项目

1. 继续收缩 persisted candidate mirror、snapshot baggage、旧 domain alias 与旧 flat slot 兼容视图。
2. 继续收口 `routeEntryType`、route family ranking、generic raw processId rewrite 与 task-side route enrichment。
3. 继续增强 active / silent topic 的独立持久化结构、topic id 和 topic matching index。
4. 让 `ActiveTaskContext / TaskCheckpoint` 在不打破 evidence plane 边界的前提下获得更强的持久化图能力。
5. 继续增强 continuation 对 reuse / route reranking 的直接输入。
