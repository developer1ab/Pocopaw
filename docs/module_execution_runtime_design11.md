# 执行运行时模块设计 design11

## 1. 当前状态

### 1.1 模块边界

本模块负责 execution start、runtime、writeback、completion 与 return-to-pocopaw。它当前拥有：

1. task-first start boundary 的执行侧消费。
2. screenshot-first action loop。
3. runtime state、trace、event、result 的统一写回。
4. review、recovery 和 return-to-chat 收尾链。

本模块当前不负责：

1. prompt 与语义解析。
2. 长期 memory、personalization policy 或最终 route 仲裁。
3. 把 passive stage signal 解释成 execution trigger。

### 1.2 启动与运行主链

当前 runtime 主链固定为：

1. 只有 latest-turn `START_EXECUTING` 或已批准 proactive execution request 才能进入 `ExecutionFlowRunner.run()` 和 `ProcessExplorationRuntime.run()`。
2. 本地从 `TaskRecord` 派生 `TaskExecutionBoundaryPacket`，再由 `ExecutionPlanningPipeline.prepare()` 生成 `PreparedExecutionStart`。
3. `PrototypeStore.startExecutionRuntime()` 会写入 execution runtime、execution session、process runtime 与 reuse context。
4. `ExecutionSession.boundaryPacket` 是 execution 期恢复 carrier；launch、writeback、recovery 和 return-to-chat 都优先读取它。
5. runtime 启动后只消费冻结的 execution snapshot、本地偏好、route/process guidance 和设备 UI truth；执行中不再发起搜索，也不热插新的搜索结果。
6. route reuse 已提供的 `matched_verification`、stage hints 和 page anchor cue 会继续进入 runtime process guidance，而不是停留在 selection 文本。
7. communication system intent 只选择 bare capability；`tel:`、`smsto:`、recipient、message body 和联系人唯一性由本地 execution boundary 与 `PhoneContactResolver` 生成和校验。
8. 页面内输入统一走 execution input stack，当前默认 owner 是 Layer 3：focus binding、clipboard paste、readback verification、IME affordance fallback 与 failure note。

### 1.3 当前执行与验证规则

1. execution 主值必须来自 `targetKey` 导出的 execution summary；`targetLabel` 只作为 display alias，不能覆盖 runtime objective、plan、route guidance 或 terminal verification。
2. constraint monotonicity guard 会阻止执行动作把已锁定的型号、规格或其他 decisive slot 静默降级成更宽的品牌或品类搜索词。
3. terminal verification 只把结构化 automation terminal payload 当作可信业务证据面；`final_user_summary` 不能单独覆盖真实 payload。
4. `add_to_cart` 场景允许以 cart badge / count、mini-cart 或 sidebar cart 数量变化、按钮状态变化等被动线索作为成功证据，并禁止在未观察这些线索前立即重复点击同一加购按钮。
5. Shizuku bootstrap 只提升 runtime readiness，不授予业务执行 authority。

### 1.4 写回与收尾

1. `ExecutionWritebackBridge` 统一合并 trace、execution result、event 和 runtime/session 状态。
2. 非 system-intent 的成功执行才进入 `ProcessLearningWritebackBridge`，并继续生成 review / feedback / process learning material。
3. success 生成 `ProcessReviewContext`，failure 生成 `ProcessRecoveryContext`。
4. system-intent completed / failed 跳过普通 process learning，但仍回到聊天面请求下一步用户指导。
5. `ExecutionReturnToPrototypeBridge` 统一控制回跳 `MainActivity` 与默认落点频道。
6. 若 execution start 前消费了 preference recommendation，则 execution evidence 面必须保留 frozen preference mapping snapshot。

### 1.5 正式对象

| 对象 | 作用 |
| --- | --- |
| `TaskExecutionBoundaryPacket` | task-aligned execution boundary packet |
| `PreparedExecutionStart` | runtime 启动 envelope |
| `ExecutionSession` | 当前执行会话持久化对象 |
| `ExecutionRuntimeState` | 运行期桥接状态 |
| `ProcessRuntimeState` | runtime 过程态 |
| `ExecutionTrace` | 执行 trace |
| `ExecutionResult` | 执行结果摘要 |
| `ProcessReviewContext` | 成功后的复盘上下文 |
| `ProcessRecoveryContext` | 失败后的恢复上下文 |
| `TextInputExecutionResult` | 输入 slice 的 Layer 2/3/4 诊断与 verification 结果 |

### 1.6 设计约束

1. execution start 只能从 task-first boundary 进入，不能由 UI 或模型自造 tool/process id。
2. runtime 只消费 selected route，不在执行中二次仲裁 route。
3. screenshot-first verification 仍是 runtime truth，ready asset 不能回退成 execution authority。
4. runtime 不解释自然语言，也不消费孤立 `current_phase=EXECUTION` 或 `SHOULD_ENTER_EXECUTING`。
5. system intent 参数不得来自 model-authored URI、catalog sample URI 或 runtime 临时拼接。
6. Shizuku shell-command path 只允许白名单 readiness 操作，不暴露任意命令能力给 UI、prompt 或 runtime action loop。

## 2. 待开发项目

1. 扩展 `RouteDecisionRecord` 之上的 route family label taxonomy、更多 replan lineage 与更强的 join / report surface。
2. 让 execution chat reply 成为更稳定的统一收尾层。
3. 把 task-context checkpoint 驱动的 resume route 做成正式主链，并补足更广的 route family fallback。
4. 在现有 execution evidence 面基础上，继续补足按 slot 展开的 accepted / blocked / confirmation 明细。
5. 补齐 input stack 剩余面：Layer 2 semantic replace gate、bounded reread、重复注入防线和更细失败分型。
6. 将 mail、map、document 等未来 system intent 扩展继续沿用 bare route + local boundary parameter launch 模式。
7. 继续验证 Shizuku `PROJECT_MEDIA` / capture consent、联系人权限与特殊输入框在目标 ROM 上的兼容边界。
