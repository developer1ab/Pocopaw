# 个性化与主动性模块设计 design11

## 1. 当前状态

### 1.1 模块定位

design11 把个性化与主动性视为同一条策略链：

1. `MemoryState` 提供事实。
2. `PersonalizationEngine` 把事实压缩成 `PersonalizationPolicyBundle`。
3. `ProactiveOpportunityEngine` 生成机会。
4. `ProactiveRuntimeBridge` 决定机会是否进入可见 proactive turn。

这条策略链可以影响 prompt、排序和 UI 节奏，但不能越权决定 execution runtime 的最终 authority。

passive 链条与 proactive 链条保持严格分离：passive 中的 `ACCUMULATION / PREPARATION / EXECUTION` 与 `SHOULD_ENTER_*` 只表达状态和时机判别；只有 proactive 引擎把这些预判转换成用户可见请求并通过 safety gate 后，才允许推动后续动作。

### 1.2 正式对象

| 对象 | 作用 |
| --- | --- |
| `ExpressionPolicy` | 表达风格 |
| `SoftCompletionPolicy` | 默认补全置信度 |
| `ProactiveDeliveryPolicy` | 主动容忍度、节奏、最小间隔 |
| `DefaultToolRankingPolicy` | 默认平台 / 流程偏好排序 |
| `PersonalizationPolicyBundle` | 个性化总输出 |
| `ProactiveOpportunityRecord` | 主动机会记录 |
| `ProactiveDeliveryPlan` | 待投递主动计划 |
| `ProactiveFeedbackRecord` | 主动反馈与 cooldown |

### 1.3 当前主链

当前策略链分三步：

1. `applyPersonalizationPolicyRefresh()` 从 `structuredPreferenceMemory`、`interactionBiasMemory`、habit、style 和 recall confidence 生成 `PersonalizationPolicyBundle`；该 bundle 当前可被 semantic / search prompt、proactive safety 与排序策略消费。
2. `applyProactiveOpportunityRefresh()` 优先消费 habit memory 和 structured preference facts；旧 summary projection 只保留兼容角色，不是 live 主输入。
3. `ProactiveRuntimeBridge.applyPendingProactiveDeliveryPlan()` 对 pending plan 做 safety gate、重复抑制，并通过 `PromptCenter.buildProactiveTurnPacket()` 生成可见 proactive packet。

除 proactive 外，当前 preference reuse 还有两条 live 消费面：

1. `PersonalizationPolicyBundle` 已直接进入 semantic turn 与 search plan packet。
2. 默认流程 / shortcut 排序可以消费 interaction bias memory、direct-evidence-backed recommended slots 与 habit/style 派生出的偏好结论，但这些结论只影响排序和表达，不改变 execution authority。

当前代码状态仍保持一条重要边界：proactive bridge 已具备完整对象和 delivery 逻辑，但 ordinary live path 还没有正式调度 `applyProactiveRuntimeRefresh()`；proactive 子系统存在，但尚未接入普通每轮对话主链。

### 1.4 设计约束

1. 个性化只能影响表达、排序、节奏和保守度，不能修改 execution authority。
2. proactive signal 必须先过 `SafetyBoundaryEngine`，再决定是否 visible。
3. proactive 的 prompt 和可见 turn 必须继续通过 `PromptCenter`。
4. proactive 只能把 passive 预判转换成用户可见提案 / 执行请求，不能把 passive signal 直接当作执行命令。

## 2. 待开发项目

1. 让 `ProactiveRuntimeBridge` 正式接入 ordinary live turn flow，但仍以可见提案 / 执行请求形式承接 passive 预判。
2. 让 `PROACTIVE_TURN` 进入主 UI 每轮 turn 的常规一环。
3. 补齐多轮 proactive session 状态机与更细粒度的用户可见策略控制面。
4. 继续扩展 direct / neighbor / derived preference evidence 对 expression、soft completion 和 proactive tolerance 的可解释映射，但不让推荐槽位成为 execution authority。
