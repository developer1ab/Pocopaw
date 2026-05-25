# pocopaw 主设计 design11

## 0. 最高指导原则：passive 链条用户驱动

1. passive 链条全部由用户最新显式语义驱动。
2. stage signal 只表达语义预判和本地状态，不拥有自动工作权。
3. active / silent topic 负责 passive topic authority；`TaskRecord -> TaskExecutionBoundaryPacket -> PreparedExecutionStart` 负责 live execution authority。
4. proactive 只有在把 passive 预判转换成用户可见请求并通过 safety gate 后，才允许进入后续动作。

## 1. 当前状态

### 1.1 文档边界

1. `design11.md` 只描述顶层架构、跨模块 authority 和模块分工。
2. 模块内部主链、对象和约束以下沉到对应 `module_*_design11.md` 的内容为准。
3. design11 系列文档是当前正式设计面；temp 设计和旧版拆分文档不再作为规范入口。

### 1.2 顶层架构结论

1. pocopaw 继续是单 `MainActivity` 壳层应用，统一承载聊天、执行观测和设置入口。
2. `PromptCenter` 是唯一 prompt 与 contract owner；UI、runtime、bridge 和离线链路都只能消费它输出的 packet。
3. passive 链条的最高 authority 是用户最新显式语义；`ENTER_*`、`STAY_*`、`SHOULD_ENTER_*` 只做预判，不触发方案或执行。
4. active / silent topic 和 `TopicDetailSlots` 是 passive topic authority；`TaskDraft` 只在明确方案或执行请求下生成，`TaskRecord` 只在明确执行请求或已批准 proactive request 下成为 live authority。
5. live execution authority 固定为 `TaskRecord -> TaskExecutionBoundaryPacket -> PreparedExecutionStart`。
6. execution runtime 是统一 screenshot-first shell；ready process asset 只提供 reference guidance，不提供 replay executor。
7. tool discovery 只提供真实 capability 目录和 task-filtered candidate subset，不拥有 final route authority。
8. memory、preference、personalization、proactive、process learning 共享同一 evidence plane，但都不能越权替代 execution authority。
9. `TaskDetailSlots.common/domain` 与 namespaced `resolvedSlots` 是 topic、task、boundary、extraction 和 reuse 的正式 slot authority；legacy flat `detailSlots` 只保留兼容角色。
10. 正式 domain 只保留统一主域；app identity 与 process policy 集中到 `CanonicalAppCatalog` 和 `AppProcessPolicyRegistry`。
11. communication system intent 只表达 bare route；真实 recipient、message body、`tel:`、`smsto:` 等参数由本地 boundary 和 device resolver 生成。
12. Shizuku bootstrap 只准备无障碍和 screen capture 前置状态，不改变 prompt、route 或业务执行 authority。
13. 大模型配置采用显式 provider profile 三轴结构（semantic / vision / search），默认档位为 `DOMESTIC_DEFAULT`、`GLOBAL_DEFAULT`、`CUSTOM`，禁止隐式 fallback。
14. 搜索 provider 固定通过 `SEARCH_PLAN_QUERY -> 搜索聚合 -> SEMANTIC_TURN` 主链接入，provider attribution 必须与运行时实际 provider 一致。

### 1.3 顶层 authority 分层

1. 壳层与交互层：`MainActivity`、`PrototypeStore` 和可见表面，负责显示和入口，不负责最终语义或执行裁决。
2. 语义与提示词层：`PromptCenter`、`DeepSeekPrototypeClient`、`ProviderRuntimeConfigs`、`ChatTurnOrchestrator`，负责 packet、contract、provider runtime 解析、语义回合和可见 turn 编排。
3. 主题与任务边界层：active / silent topic、`TaskDraft`、`TaskRecord`、`TaskExecutionBoundaryPacket` 和 `PreparedExecutionStart`，负责把用户语义收口为本地 authority。
4. 执行与学习层：runtime、writeback、memory、process learning、process reuse 和 proactive，负责消费上游 authority 并写回结果。

### 1.4 端到端主链

1. 用户消息进入统一聊天窗口，并立即创建 pending user / assistant turn。
2. `PromptCenter` 组装 `SEMANTIC_TURN`；必要时先经过 `SEARCH_PLAN_QUERY -> 搜索聚合 -> SEMANTIC_TURN`。
3. 语义结果优先更新 active / silent topic 和 `TopicDetailSlots`；普通 passive turn 不提升成 live task authority。
4. 明确方案请求时，topic 槽位进入 `TaskDraft` 和自然语言方案输出。
5. 明确执行请求时，本地生成 `TaskRecord`、`TaskExecutionBoundaryPacket` 和 `PreparedExecutionStart`。
6. UI 展示执行复述后进入 execution；route 解析和 process reuse 在启动前决定 `process_reference / shortcut / exploratory` 入口。
7. runtime 执行统一 screenshot-first 主链，并通过 writeback、review、recovery、memory 和 process learning 收尾。
8. 结果通过 `ExecutionReturnToPrototypeBridge` 回流到统一聊天和执行观察面；非 system-intent 的成功执行才继续进入流程学习和复用闭环。

### 1.5 当前模块分工

| 模块文档 | 当前职责 |
| --- | --- |
| `module_user_interaction_design11.md` | 页面结构、统一聊天窗口、可见 turn 生命周期与设置面 |
| `module_prompt_governance_design11.md` | packet 家族、contract、budget、slot section 与 prompt caller 边界 |
| `module_semantic_projection_and_handoff_design11.md` | 语义收口、topic/task authority、start boundary、route 与 continuation 补偿 |
| `module_execution_runtime_design11.md` | runtime 主链、输入栈、写回、completion、recovery 与 route observability |
| `module_process_asset_and_reuse_design11.md` | reference-only reuse、流程学习、curation、feedback 与 lineage |
| `module_memory_runtime_design11.md` | evidence plane、topic storage contract、continuation 派生视图与 memory bundle |
| `module_preference_system_design11.md` | preference discovery / extraction、structured memory、recall、slot recommendation 与 observability |
| `module_personalization_and_proactive_design11.md` | 个性化策略、主动机会、主动投递桥与 passive/proactive 分层 |
| `module_tool_discovery_design11.md` | capability 目录、task-filtered bundle、signal-group 收窄与 catalog/policy 接口 |
| `module_safety_boundary_design11.md` | 外层安全裁决、risk gate、confirm/advisory/downgrade 与 Shizuku/system-intent 边界 |

## 2. 待开发项目

1. 继续增强 active / silent topic 的 matching index、多主题 reactivation 和 continuation graph。
2. 继续清理 legacy flat `detailSlots`、旧 snapshot shape、旧 preference store 和 generic raw processId fallback 等兼容路径。
3. 扩展 `RouteDecisionRecord` 之上的 route family taxonomy、replan lineage 和更强的 route observability。
4. 让 proactive 以可见提案 / 执行请求的形式正式接入 ordinary live turn flow，而不是复用 passive 自动执行。
5. 继续收紧 transient task fact 与 durable preference 的边界，避免任务槽位回声被长期复用成稳定偏好。
6. 丰富 settings、`CONVERSATION` debug sidecar 和 `EXECUTION` evidence drawer 的对账与诊断粒度。
7. 完成更广的 MCP capability 注册链、continuation/task-context 图结构和跨 route family 的一致治理。
8. 收紧输入增强剩余面，并继续做 Shizuku、system intent、联系人解析和特殊输入框的真机差异验证。
