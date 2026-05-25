# 主动引擎模块设计 design8

## 0. 状态约定

本文只描述当前 proactive 代码层已经实现的机会生成、个性化规划、投递和反馈写回逻辑，并明确说明它当前没有接到主页面 live path。任何把它描述成已上线主动交互能力的说法，都必须放到文末 `未实现功能`。

## 1. 当前实现摘要

当前 proactive 模块在代码里已经具备三层能力：

- 机会生成：`applyProactiveOpportunityRefresh(...)`
- 计划生成：`applyPersonalizationPolicyRefresh(...)`
- 可见投递 / 反馈：`applyPendingProactiveDeliveryPlan(...)`、`applyProactiveFeedback(...)`

但当前必须先写明结论：

- `RuntimeModuleSwitches.proactiveEngineEnabled = true`
- 代码路径存在
- 但 `MainActivity` / `PrototypeStore` 当前没有 live caller 去触发 proactive refresh 或 delivery

也就是说，当前 proactive 是“代码可运行模块”，不是“主链已上线功能”。

## 2. 代码锚点

当前权威代码锚点：

- `app/src/main/java/com/atombits/popopaw/RuntimeModuleSwitches.kt`
- `app/src/main/java/com/atombits/popopaw/ProactiveOpportunityEngine.kt`
- `app/src/main/java/com/atombits/popopaw/PersonalizationEngine.kt`
- `app/src/main/java/com/atombits/popopaw/ProactiveRuntimeBridge.kt`
- `app/src/main/java/com/atombits/popopaw/SafetyBoundary.kt`
- `app/src/main/java/com/atombits/popopaw/PromptCenter.kt`

## 3. 当前机会生成层

### 3.1 总入口

`applyProactiveOpportunityRefresh(store, now)` 当前会先检查 `RuntimeModuleSwitches.proactiveEngineEnabled`。如果关闭，则：

- 清理 `workflowLane`、`stageOwner`
- 清空 `pendingProactiveDeliveryPlan`
- 清空 `proactiveOpportunityStore`

### 3.2 机会来源

当前机会来源是分层的：

1. 先看 `habitMemoryStore`
2. 如果 habit 没生成机会，再回退到 `preferenceEvidenceStore`
3. 如果 evidence 也没生成机会，再回退到 `preferenceSummaryCards`

也就是说当前规则是：

- 习惯优先
- preference evidence 次之
- summary card 最后兜底

### 3.3 当前产物

机会当前写成 `ProactiveOpportunityRecord`，典型字段包括：

- `signal`
- `title`
- `summary`
- `anchorObject`
- `sourceType`
- `confidence`
- `lastObservedAt`

最终 `proactiveOpportunityStore` 会：

- 去重
- 按时间排序
- 最多保留 6 条

## 4. 当前计划生成层

### 4.1 计划生成入口

`applyPersonalizationPolicyRefresh(...)` 当前会：

1. 构造 `PersonalizationPolicyBundle`
2. 如果 proactive 开关关闭，则清 proactive state
3. 否则调用 `planProactiveDelivery(...)`
4. 把结果写到 `pendingProactiveDeliveryPlan`

### 4.2 当前 delivery plan

当前计划对象是 `ProactiveDeliveryPlan`，字段包括：

- `opportunityId`
- `signal`
- `deliveryStyle`
- `title`
- `summary`
- `plannedAt`

`planProactiveDelivery(...)` 当前还会执行：

- cooldown 检查
- 最小投递间隔检查
- 同 plan 短时间重投抑制

## 5. 当前可见投递层

### 5.1 投递入口

`applyPendingProactiveDeliveryPlan(...)` 当前逻辑是：

- 如果 proactive 开关关闭，清理 proactive state
- 如果没有 pending plan，直接返回
- 先经过 `applyProactiveSafetyGate(...)`
- 再根据 signal 选择可见 stage
- 用 resolver 或本地 formatter 生成 assistant reply
- 把消息和 snapshot 追加进 store

### 5.2 当前可见 stage 映射

当前 proactive signal 到可见 stage 的映射是：

- `REQUEST_PROACTIVE_CONFIRM` -> `PREPARING`
- `ENTER_PROACTIVE_EXECUTING` -> `EXECUTING`
- 其它 -> `ACCUMULATING`

### 5.3 当前反馈写回

`applyProactiveFeedback(...)` 当前支持：

- `ACCEPTED`
- `IGNORED`
- `DISMISSED`
- `REJECTED`

并把反馈写成 `ProactiveFeedbackRecord`，同时更新 cooldown。

## 6. 当前边界

### 6.1 没有 live caller

这是当前 proactive 模块最重要的现实边界：代码存在，但 `MainActivity` / `PrototypeStore` 没有主链调用它。文档不能把它写成当前用户能看到的主动提醒系统。

### 6.2 UI 没接线

当前主页面没有主动提示卡、主动确认面或主动执行按钮，所以即便 delivery 逻辑代码存在，也没有 live UI 壳层接住它。

### 6.3 prompt 层也不是 live

`PromptCenter.buildProactiveTurnPacket(...)` 存在，但当前没有主页面 caller 持续刷新并发送 proactive packet。

## 7. 重建要求

重建当前 proactive 模块时，必须保留以下事实：

- proactive opportunity 生成来源是 habit 优先、preference/evidence 兜底
- personalization 会生成 pending delivery plan
- safety gate 会在 delivery 之前生效
- feedback 写回会刷新 cooldown
- 当前代码虽然完整，但没有接到主页面 live path

## 8. 未实现功能

以下内容如果继续出现在设计中，只能归入未实现：

- 主动链 live caller
- 主动提示 / 主动确认 / 主动执行的用户表面
- 周期性 proactive refresh 调度器
- 已上线的 proactive prompt 主链
- 自动从 proactive plan 直接起跑 execution 的真实产品链路