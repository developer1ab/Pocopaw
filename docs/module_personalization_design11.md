# 个性化模块设计 design8

## 0. 状态约定

本文只描述当前 `PersonalizationEngine.kt` 已实现的 policy bundle 推导、live semantic prompt 注入和 proactive delivery planning。任何把它写成“当前 semantic 主链已深度依赖个性化决定 authority”的说法，都必须放到文末 `未实现功能`，因为当前 live passive semantic 对 personalization 的消费仍停留在 advisory prompt layer。

## 1. 当前实现摘要

当前个性化模块的真实职责是：

- 从 `MemoryState` 推导 `PersonalizationPolicyBundle`
- 为 proactive 模块生成 `pendingProactiveDeliveryPlan`
- 为 ready process 选择提供 ranking hints

它当前是“派生式 policy 层”，不是独立用户画像服务。

## 2. 代码锚点

当前权威代码锚点：

- `app/src/main/java/com/atombits/popopaw/PersonalizationEngine.kt`
- `app/src/main/java/com/atombits/popopaw/ExecutionProcessSelector.kt`
- `app/src/main/java/com/atombits/popopaw/ProactiveRuntimeBridge.kt`
- `app/src/main/java/com/atombits/popopaw/SafetyBoundary.kt`
- `app/src/main/java/com/atombits/popopaw/PromptCenter.kt`

## 3. 当前数据结构

### 3.1 `PersonalizationPolicyBundle`

当前 bundle 由四个子策略组成：

- `ExpressionPolicy(brevityLevel, directnessLevel, explanationDepth)`
- `SoftCompletionPolicy(defaultFillConfidence)`
- `ProactiveDeliveryPolicy(proactiveTolerance, reminderAggressiveness, minimumDeliveryIntervalMs)`
- `DefaultToolRankingPolicy(preferredPlatformOrder, preferredProcessOrder)`

### 3.2 `ProactiveDeliveryPlan`

当前 proactive 计划字段是：

- `opportunityId`
- `signal`
- `deliveryStyle`
- `title`
- `summary`
- `plannedAt`

## 4. 当前推导规则

### 4.1 表达风格

`buildPersonalizationPolicyBundle(...)` 当前从 `interactionStyleStore` 推导：

- `brevityLevel`
- `directnessLevel`
- `explanationDepth`

例如：

- `brevityLevel = HIGH` 时，`explanationDepth` 会被压成 `SHORT`
- `brevityLevel = LOW` 时，`explanationDepth` 会变成 `DETAILED`

### 4.2 默认补全置信度

`defaultFillConfidence` 当前取自：

- `preferenceEvidenceStore.confidence`
- `preferenceSummaryCards.confidence`

平均值再限制在 `0.3 ~ 0.95` 之间。

### 4.3 主动容忍度和提醒强度

当前 `proactiveTolerance` 主要由以下信号推导：

- 高稳定 habit
- 高置信 preference evidence
- 高置信 summary card

`reminderAggressiveness` 则会看：

- habit 的 `preferredDeliveryStyle`
- proactiveTolerance 高低

当前最小投递间隔有三档：

- `HIGH` -> 15 分钟
- `MEDIUM` -> 30 分钟
- `LOW` -> 60 分钟

### 4.4 平台和流程排序

当前 `preferredPlatformOrder` 来自：

- preference evidence 的 `sourceApp`
- summary card 的 `supportingSourceApps`

当前 `preferredProcessOrder` 来自：

- preference evidence 中的 `preferred_process_id`
- summary card 文本里抽出的 `preferred_process_id`
- 再和 `readyProcessAssets` 对齐排序

## 5. 当前消费者

### 5.1 proactive 计划生成

`applyPersonalizationPolicyRefresh(...)` 当前会：

1. 构造 `PersonalizationPolicyBundle`
2. 交给 `planProactiveDelivery(...)`
3. 把结果写到 `pendingProactiveDeliveryPlan`

### 5.2 proactive 安全门

`ProactiveRuntimeBridge.applyProactiveSafetyGate(...)` 当前会把 personalization bundle 传给 `SafetyBoundaryEngine.assess(...)`，用于 hint-only / confirm-required 判定。

### 5.3 ready process 排序提示

`ExecutionProcessSelector.resolveExecutionHandoffWithReadyProcesses(...)` 当前支持接收 `DefaultToolRankingPolicy`，并用：

- `preferredProcessOrder`
- `preferredPlatformOrder`

作为 tie-breaker 排序信号。

## 6. 当前边界

### 6.1 当前 live semantic caller 已接 personalization bundle，但仍是 advisory layer

这是当前个性化文档必须写清的事实：

- `PersonalizationPolicyBundle.toPromptSection()` 已实现
- `PromptCenter.buildSemanticTurnPacket(...)` 支持注入 `personalization_bundle`
- `DeepSeekPrototypeClient.buildSemanticTurnPacket(...)` 当前已经把 personalization bundle 传进 live semantic request

因此现在可以写成“当前主语义请求已经消费个性化 bundle”，但不能把它写成“个性化已经成为 passive semantic 主链的深层 authority”。

### 6.2 不是独立画像服务

当前个性化完全依赖本地 `MemoryState` 推导，没有独立 profile schema、远程画像或分层同步。

### 6.3 当前最稳定的消费者仍是 proactive / selector 边路，加上 passive semantic prompt advisory

个性化目前最明确的真实消费者是 proactive planning / safety gating / ready process ranking；本轮 live passive semantic 主请求也开始消费 personalization bundle，但作用仍主要是 prompt advisory，而不是阶段判定权。

## 7. 重建要求

重建当前个性化模块时，必须保留以下事实：

- bundle 由表达风格、补全置信度、主动投递、平台/流程排序四块组成
- 输入主要来自 `MemoryState`
- `preferredProcessOrder` 要和 `readyProcessAssets` 对齐
- proactive planning 当前是最直接消费者
- live semantic personalization 注入当前已接线，但仍是 prompt advisory 层

## 8. 未实现功能

以下内容如果继续写进设计，只能归入未实现：

- 独立用户画像服务或跨设备同步画像
- 更复杂的 bandit / reinforcement 排序策略
- 个性化策略的远程训练和回传闭环
- 多层级 persona / etiquette / product policy 统一编排器