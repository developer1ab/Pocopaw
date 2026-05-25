# 安全边界模块设计 design11

## 1. 当前状态

### 1.1 模块定位

本模块负责 design11 的外层安全裁决。它当前职责为：

1. 在 prompt 构建或 proactive delivery 之前给出安全裁决。
2. 对高风险能力和主动链做 downgrade、advisory 或 confirm。
3. 显式暴露边界风险，而不是把风险留给 runtime 深处静默失败。

它当前不负责最终 route 选择、execution runtime 调度，也不负责把孤立 passive stage signal 升级成执行命令。

### 1.2 正式对象

| 对象 | 作用 |
| --- | --- |
| `SafetyDecision` | 安全裁决结果 |
| `SafetyBoundaryContext` | lane、tool risk、个性化等输入上下文 |
| `SafetyBoundaryEngine` | 安全裁决 owner |

### 1.3 裁决规则

本模块当前固定三类裁决面：

1. proactive lane：当 `workflowLane=PROACTIVE` 且主动容忍度较低时，执行类主动信号要降级成 hint-only 或进入 confirm。
2. tool risk：`RESTRICTED` 工具必须 confirm，`SENSITIVE` 工具至少进入 advisory，普通工具再继续看 execution readiness。
3. execution readiness：安全边界只给裁决，不接管 execution authority；当任务已经 ready to start 时，安全层只能 advisory 或要求确认，不能自己发起执行。
4. passive lane：`ACCUMULATION / PREPARATION / EXECUTION` 是状态，`START_*` 是用户请求语义，`SHOULD_ENTER_*` 是阶段时机判别；若没有 `START_PREPARING`、`START_EXECUTING` 或 approved proactive request，安全层必须阻止下游把状态或时机判别当作自动工作触发器。
5. communication system intent 必须在 launch 前完成本地 recipient / permission / contact unique-number precondition；缺参、无权限、零匹配或多匹配必须显式失败，不能回退到 sample URI 或 silent fallback。
6. Shizuku setup 必须由用户主动开始，auto prepare 必须由用户显式打开；Shizuku shell command 只允许 settings / appops / id / probe 等白名单 readiness 操作。

### 1.4 设计约束

1. 安全边界只能在外层显式暴露，不能在 UI 或 runtime 内做静默 fallback。
2. 新的高风险 capability 必须同步补 `ToolRisk` 和 `SafetyBoundaryEngine` 规则。
3. 主动链即使正式进入 live path，也必须先过 safety gate，再进入可见投递。
4. 高风险执行请求即使由用户明确提出，也必须保留可见确认或 advisory 规则。
5. 安全边界不使用关键词白名单重判用户话术，也不把 `current_phase` 或 `SHOULD_ENTER_*` 当作执行授权。
6. Shizuku readiness 不能被解释成业务执行授权；它只能准备无障碍和 screen capture 前置状态。
7. 本地 contacts resolver、system settings 写入和 AppOps 写入都必须保留可观察失败原因。

## 2. 待开发项目

1. 补齐更细粒度的 domain / app / action 确认矩阵。
2. 扩展 execution route 级 confirm 策略。
3. 覆盖更多 route family 的统一细粒度政策矩阵。
4. 继续补齐 Shizuku blocked / pending / appops unsupported 等状态到用户可见安全文案的映射。
