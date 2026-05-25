# 提示词治理模块设计 design11

## 1. 当前状态

### 1.1 模块边界

`PromptCenter.kt` 是 design11 下唯一的 prompt 与 contract owner。它当前统一负责：

1. packet 类型和 builder。
2. system contract 与 response contract。
3. token budget 与 context clipping。
4. prompt messages 组装。

任何 UI、runtime、bridge 或离线模块都只能调用它，不能自己拼 prompt 或维护平行 JSON schema。

### 1.2 Packet 体系

当前正式 `PromptPacketType` 包括：

1. `SEMANTIC_TURN`
2. `SEARCH_PLAN_QUERY`
3. `EXECUTION_CHAT_REPLY`
4. `AUTOMATION_QUERY`
5. `VISION_QUERY`
6. `PROACTIVE_TURN`
7. `PROCESS_CURATION_QUERY`
8. `PROCESS_REFERENCE_SELECTION_QUERY`
9. `OFFLINE_PROCESS_EXTRACTION`
10. `OFFLINE_DIALOGUE_PREFERENCE_EXTRACTION`

`Fast / Expert`、`深度思考`、`互联网搜索` 只改变当前回合能力配置，不改变 `PromptCenter` 作为唯一 owner 的地位。

provider profile（`DOMESTIC_DEFAULT / GLOBAL_DEFAULT / CUSTOM`）当前作为 prompt caller 的运行时配置输入：

1. semantic caller 通过 `ProviderRuntimeConfigs.semanticRuntimeConfig(...)` 选择 API key / endpoint / model。
2. vision caller 通过 `ProviderRuntimeConfigs.vision` 选择 API key / endpoint / model。
3. search caller 通过 `ProviderRuntimeConfigs` 当前 search provider 选择聚合通道，并把 provider attribution 回写到语义链路。

### 1.3 预算与裁剪规则

当前 budget 与 clipping 继续集中治理：

1. `SEMANTIC_TURN` 承载 full-history prompt，但仍保留 `requestMaxTokens` 约束。
2. `SEARCH_PLAN_QUERY`、`AUTOMATION_QUERY`、`VISION_QUERY`、offline extraction、process curation 等 packet 继续使用各自 budget / clipping 规则。
3. `ContextSubsetPlanner` 仍是正式裁剪 owner；caller 不自行重建裁剪逻辑。

### 1.4 Contract 分层

当前 contract 分层固定为：

1. `SEMANTIC_TURN` 负责输出 `workflow_lane`、`stage_owner`、`passive_user_progress_signal`、`next_move`、`phase_type`、`phase_status` 和 `task_draft`；其中 passive stage signal 只做预判和持久化提示，不是自动工作命令。
2. `AUTOMATION_QUERY` / `VISION_QUERY` 的 execution 参数必须来自 task-first execution boundary；`targetKey` 和 authoritative resolved slots 是 execution authority，`targetLabel` 只保留 display alias 角色。
3. `PROCESS_CURATION_QUERY` / `PROCESS_REFERENCE_SELECTION_QUERY` 继续执行 reference-first contract，不把 ready asset 当 replay script。
4. `PROACTIVE_TURN` 必须继续通过 `PromptCenter`；只有 proactive 引擎把 passive 预判转换成可见请求并过 safety gate 后，才允许进入后续动作。
5. 搜索增强固定走 `SEARCH_PLAN_QUERY -> 搜索聚合 -> SEMANTIC_TURN`；planner、搜索 provider 和第二次 semantic turn 都受统一 contract 治理。
6. `OFFLINE_DIALOGUE_PREFERENCE_EXTRACTION`、`OFFLINE_PROCESS_EXTRACTION` 与 `PROCESS_REFERENCE_SELECTION_QUERY` 必须显式消费 structured slot authority section。
7. `SEMANTIC_TURN` 的 user-visible `assistant_reply` 必须是本轮完整可展示内容，不能用占位回复替代实际方案、解释或执行复述。
8. `START_ACCUMULATING` 必须输出有信息量的回答或探索内容；`START_PREPARING` 必须同 turn 输出实际方案；`START_EXECUTING` 必须同 packet 给出 execution semantics 与可本地校验的 `task_draft`。
9. automation / system-intent packet 不得要求模型生成带用户数据的 parameterized URI；system intent route 只表达 bare capability，真实参数由本地 execution boundary 生成。
10. automation packet 必须显式编码 constraint monotonicity 与 shopping `add_to_cart` 的被动成功判据。

### 1.5 Caller 边界

| Caller | 可以做什么 | 不可以做什么 |
| --- | --- | --- |
| `DeepSeekPrototypeClient` | 组 packet、发请求、解析 JSON、输出统一 stream event，并消费 profile 驱动 runtime config | 内联 prompt 合同 |
| runtime / curation / extraction bridge | 提供 spec、消费 packet 结果 | 重建 schema 或绕过 `PromptCenter` |
| UI / `MainActivity` | 选择何时发起哪类 packet、切换 thinking/search 开关 | 手写 prompt 字符串或自造第二套 chat contract |

### 1.6 设计约束

1. 新 prompt 能力必须先补 packet 类型、builder、budget 和 response contract，再补 caller。
2. contract 变动必须同步治理 parser、store 和测试对齐。
3. 新字段优先收口到 controlled enum / contract family，不能把 free-text 重新变成 authority。
4. 涉及 slot authority 的 packet 必须优先使用 `TaskDetailSlots + resolvedSlots` 的 controlled section；legacy flat `detailSlots` 最多保留 compatibility debug section。

## 2. 待开发项目

1. 当 `PROACTIVE_TURN` 正式接入 ordinary live turn flow 时，补齐 passive 预判 -> 可见提案 / 执行请求的转换合同。
2. 让 `Fast / Expert` 与 `深度思考 / 互联网搜索` 的统一聊天窗口配置继续向更完整的 runtime / packet config 收口。
3. 补齐来源 footer、显式 `规划中 / 搜索中 / 生成中` 状态标签与更细的 reasoning/search 可见化治理规则。
4. 清理只消费 legacy flat `detailSlots` 的旧 prompt / parser / store 兼容路径。
5. 建立更细粒度的 packet-level observability 面板。
6. 将 communication 之外的 map / ride / commerce 等 capability signal-group prompt 规则继续铺开。
