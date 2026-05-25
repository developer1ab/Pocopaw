# 记忆运行时模块设计 design11

## 1. 当前状态

### 1.1 模块定位

design11 下的记忆模块是统一 evidence plane，不是第二个 agent brain。它当前负责：

1. 持久化 grounding、continuation、recent fact、preference、habit、style、process feedback 等证据。
2. 把这些证据投影成 semantic、proactive、offline extraction 和 reuse 可消费的 bundle。
3. 为 active / silent topic 提供正式的本地 topic storage contract，并为 continuation 和 task-context 提供派生 grounding 视图。

它当前不负责最终 route、execution、policy 或 curation authority。

### 1.2 正式数据面

`MemoryState` 在当前主链上的正式数据面包括：

| 子 store | 作用 |
| --- | --- |
| `activeGroundingStore` | 当前对象 / 动作 grounding |
| `continuationStore` | 轻量 continuation 记录 |
| `recentFactStore` | 最近事实 |
| `structuredPreferenceMemory` | 用户长期/近期偏好 facts、recent facts、semantic chunks |
| `interactionBiasMemory` | process / page / shortcut 偏置信号 |
| `preferenceDebugStore` | projection / recall / mapping 诊断快照 |
| `habitMemoryStore` | 习惯证据 |
| `interactionStyleStore` | 表达 / 风格证据 |
| `dialoguePreferenceBacklog` | 对话偏好待抽取 backlog |
| `processCandidateStore` | 流程候选索引 |
| `processFeedbackStore` | 流程反馈索引 |
| `topicContextStore` | active topic id、silent topic records、`TopicDetailSlots`、最近触达轮次与 matching hints |

active / silent topic 的正式 persisted storage contract 是 `topicContextStore`。普通 passive semantic turn 必须优先写入 topic records 和 `TopicDetailSlots`，不能把 `currentTaskRecord/currentTaskDraft` 当成 topic persistence。

`TaskSlotEvidenceSnapshot` 是当前 memory 侧的正式冻结视图：它把 topic / task / boundary authority 正向导出成 extraction / reuse 可消费的 slot snapshot，而不是第二份 task store。

### 1.3 正式入口与 bundle 输出

当前关键入口包括：

1. `recordPassiveTurn(...)`
2. `recordExecutionStart(...)`
3. `buildPassiveEvidence(...)`
4. `buildOfflineDialoguePreferenceMemoryBundle(...)`
5. `buildDialoguePreferenceBacklogBatch(...)`

当前正式输出包括：

1. passive evidence bundle：grounding、recent facts、semantic recall、preference / habit / style evidence 与 confidence summary。
2. topic evidence bundle：active topic、silent topic、topic detail slots、最近触达轮次与 matching hints。
3. offline preference extraction memory bundle：与 passive memory bundle 共用同一事实面。
4. slot evidence snapshot：把 topic / task / boundary 的 structured slot authority 冻结成 backlog、raw material 与 reuse query 可复用的输入面。
5. preference recall / mapping debug snapshots：供 settings、LLM debug sidecar 和 execution evidence drawer 展示最基本映射过程。

### 1.4 Continuation 与 task-context 派生视图

当前 task-context 不在 `MemoryState` 内另起平行 store，而是由以下面共同构成派生 continuation 视图：

1. `MemoryState` 证据面。
2. `PrototypeStoreData.lastContinuationGroundingResult`。
3. `ContinuationGroundingResolver.buildTaskContinuationEvidence(...)`、`buildPromptContext(...)` 和 `resolve(...)` 的派生结果。

active / silent topic 已经是正式 persisted topic contract；`ActiveTaskContext`、`TaskCheckpoint` 当前仍是派生视图，不替代 topic storage。

### 1.5 设计约束

1. 新记忆结构必须首先证明自己属于 evidence plane，而不是 route / policy / execution owner。
2. memory bundle 只能投事实和摘要，不能偷偷承载 runtime authority 文本。
3. topic 槽位持久化不等于准备或执行工作；它只为后续明确方案请求或明确执行请求提供上下文。
4. 同一 evidence plane 不等于同一 policy meaning；transient task fact 不能直接冒充 durable preference。
5. `TaskSlotEvidenceSnapshot` 只能从 structured authority 正向导出，不能反向从 legacy flat `detailSlots` 猜测重建。

## 2. 待开发项目

1. 继续增强 `topicContextStore` 的 topic matching index、多主题 reactivation 与旧派生 topic projection 清理。
2. 强化 active recall 编排和 object checkpoint 回放。
3. 继续治理跨多轮、多对象的长期 continuation graph。
4. 补齐 transient task fact 与 durable preference 的分层 / 加权治理，避免任务槽位回声被长期复用成稳定偏好。
5. 继续收缩旧 preference 兼容面，直到完全退役旧 store 的 live 依赖。
6. 继续把剩余 process raw material / reuse query 的兼容面从 legacy flat slot echo 清理到 `TaskSlotEvidenceSnapshot` / `ProcessSlotHint` 口径。
