# 工具发现模块设计 design11

## 1. 当前状态

### 1.1 模块定位

工具发现模块当前只做两件事：

1. 建立设备上真实 capability 的目录。
2. 基于任务文本给 semantic / execution path 提供受控 capability subset。

它不拥有 final route authority，但持续为本地 route 解析提供真实目录和 task-filtered candidate subset。

在 passive 链条中，工具发现只参与候选收窄和上下文提供，不能把 capability 命中、风险分数、`current_phase=EXECUTION` 或 `SHOULD_ENTER_EXECUTING` 升级成执行触发。

### 1.2 目录构建

`ToolspaceCatalogManager` 当前负责：

1. 扫描 launchable app。
2. 扫描本地 system controls。
3. 将能力目录写入 `files/toolspace/toolspace_catalog.json`。
4. 通过 `CanonicalAppCatalog` 读取 canonical app identity、包名、alias、domain membership、preferred discovery targets 与 tool terms。
5. 通过 `AppProcessPolicyRegistry` 读取 app-specific process scope 与 action override，不在工具发现内私有维护 app behavior policy。

正式对象继续包括：`ToolCapability`、`ToolCapabilityBundle`、`ToolDomain`、`ToolRisk`、`ToolState` 与 `ToolspaceCatalogManager`。

app identity 的解析顺序固定为 canonical app id、完整包名、包名 token sequence、alias terms。`com.tencent.mm`、`app.com.tencent.mm.open`、`other-com-tencent-mm-run` 这类输入必须通过 package-token 解析收口到 canonical app id。

### 1.3 Task-filtered bundle 与打分规则

`ToolCapabilityBundleBuilder` 当前会：

1. 用 task terms / intent groups 匹配 capability。
2. 引入 service alias，如 `jd / taobao / didi / wechat`。
3. 通过 `local_service`、generic commerce 等 profile 收窄候选家族。
4. 生成 `selectionMode / matchedProfiles / matchedTerms / capabilities`。

当前打分规则固定为两步：

1. 先计算语义资格分；只有 `capabilityId`、`displayName` 和 `summary` 参与语义匹配。
2. 在语义资格分大于 0 的前提下，再叠加 `preferredDomains`、状态和风险作为 tiebreaker。

`intentAction`、`probeDataUri`、`executeDataUri`、`handlerCount` 等执行元数据不能反推用户语义，也不能单独让一个能力通过过滤门。

capability prior 必须先按用户原话识别任务 profile，再在 profile 内识别更细 signal group。communication 当前正式 signal group 为 `send_message / call / mail / contact_selection`；明确 grounded send-message 请求已经具备 recipient、message body 与 channel 时，相邻 sibling capability 不得作为 blocking alternative 把任务拉回澄清。

### 1.4 与 start boundary 的接口

execution start 当前按以下顺序消费本模块输出：

1. 先尝试命中 selected tool id。
2. 再用 task query 请求默认 capability。
3. 如有必要，刷新设备目录后重试。

这些步骤只发生在 execution start 已经由上游合法触发之后；passive topic 槽位更新阶段只可读取 task-filtered bundle 作为 evidence。

### 1.5 设计约束

1. capability 目录必须来自设备或本地注册源，不能由模型发明。
2. `selected_tool_id` 只能命中 catalog 中存在的 capability，未命中就必须回到本地解析层重算。
3. 新 capability source 要统一写回 `ToolspaceCatalogManager`，不要各自维护私有列表。
4. capability prior 不能作为 blocking alternative 污染明确用户语义，也不能单独把 passive 预判推成 runtime start。

## 2. 待开发项目

1. 将当前 communication 第一批 signal-group 收窄扩展到 map / ride / commerce 等更多 domain。
2. 让 continuation / task-context 驱动 capability reranking。
3. 补齐更广的 app family ranking。
4. 完成 MCP capability 扫描、注册与 orchestration 主链。
5. 继续删除保守 fallback 和旧分散 alias / service map，确保 catalog 与 policy registry 是唯一真源。
