# Temporary Design: SystemIntent Local Parameter Launch for design11

## 0. 问题现象与原因

当时看到的问题现象是一串连续暴露出来的 system-intent 通信链路失败，而不是单一的 `PREPARING` 问题：

1. 最早的参数化 system intent 路径并不是稳定的 task payload carrier。平台能力本身是存在的，例如 `ACTION_DIAL + tel:` 可以预填号码，`ACTION_SENDTO + smsto:` 可以带入收件人或会话；但 popopaw 当时的真实参数来源混在 model / automation 可能输出的 query、catalog probe 的 `executeDataUri` 示例值，以及尚未稳定落到 execution boundary 的用户 payload 之间。直接现象不是“Android 不能带参启动”，而是号码 / 收件人 / 正文不能稳定从用户任务传到 launch：dial / SMS 可能落到 `10086` 这类 probe 示例，`system.intent.call` 会走 `ACTION_CALL tel:` 并撞上 `CALL_PHONE` 权限，`sendto_sms` 即使通过 `smsto:` 进到会话也不会可靠预填 message body。
2. 后来架构改成 bare system intent 启动，再依赖后续自动化识屏输入 recipient 和 message body；这又暴露了短信 compose 页的多输入框绑定问题。更准确地说，早期 store / boundary 里 recipient 可能只是 `TARGET_OBJECT`，body 可能只是 generic `CONSTRAINT`，runtime 没有清晰的 `communication.recipient` / `communication.message_body` authority；后续即使语义 slot 已经补齐，`INPUT` action 的 target coordinates 也曾在 vision-to-bridge 链路里丢失，accessibility fallback 于是选中当前焦点或第一个 editable node。OPPO compose tree 里 `recipient_text_view` 排在 `compose_message_text` 前面，所以直接现象是正文或重复文本落进联系人框，trace 里甚至看不到对 `compose_message_text` 的命中。
3. 随后改成 system intent 本地带参启动：模型和 automation 只选择 bare `sys://system.intent.xxx` capability，真实 `smsto:` / `tel:` 和 `sms_body` 由本地根据 `TaskExecutionBoundaryPacket` 拼接。这个阶段已经能越过参数来源混乱和双输入框自动化混乱，完成“给某某号码发短信 / 给某某号码打电话”这类号码已知任务。
4. 再往后，命名联系人任务仍会卡住：`给某某人发短信 / 打电话` 需要把联系人名解析成唯一 live phone number，但真实通讯录里可能存在已删除 raw contact、同一联系人多个号码、重复号码、同名或账号同步污染等情况。于是又补了联系人读取、active raw contact 过滤、号码 canonicalize / dedupe，以及“必须解析到唯一号码，否则 launch 前显式失败”的本地 precondition。

问题原因对应这条演进链分三层：第一，参数化 launch 的 authority 没有收口，route selection、probe 示例参数、model / automation 可能生成的 query 参数和真实 task payload 混在一起，导致平台可支持的 `tel:` / `smsto:` 能力没有被稳定地接到用户任务；第二，bare system intent 后把结构化通信参数转交给截图 / accessibility 自动化输入，会把 SMS recipient 和 body 这类有明确角色的字段降级成脆弱的 UI 填框问题，而 slot authority 与 input target coordinates 任一断裂都会让文本落到错误输入框；第三，命名联系人解析属于本地设备状态，不属于 prompt 或 catalog probe，必须由本地 resolver 在 launch 前完成权限、删除联系人、多号码和重复数据过滤。最终结论是：system intent capability 只表达 route selection，真实 launch 参数由本地 execution boundary + device contact resolver 生成和验证。

与当前 design11 最高原则的关系：bare route + local parameter launch 只适用于 `START_EXECUTING` 已经被接受之后。联系人 / message_body 槽位可以在 passive topic 中静默补齐，但不能因槽位完整、孤立 `current_phase=EXECUTION` 或孤立 `SHOULD_ENTER_EXECUTING` 阶段判别自动发短信、拨号或启动 system intent。

## 1. 详细设计

### 1.1 设计目标

本专题把最新提交 `e813c60` 中的 system-intent 启动策略反写为设计约束：

> system intent route 只负责选择能力，用户参数必须在本地 execution boundary 内补齐，不能由模型或 automation agent 拼进 launch URI。

目标覆盖三类当前 live communication system intent：

1. `system.intent.sendto_sms`
2. `system.intent.dial`
3. legacy `system.intent.call`

### 1.2 本地补参 authority

system intent 启动时的参数 authority 固定为：

1. `TaskExecutionBoundaryPacket.structuredDetailSlots`
2. `TaskExecutionBoundaryPacket.resolvedSlots`
3. `targetType == CONTACT` 时的 `targetLabel / targetKey`
4. 本地 `PhoneContactResolver`

模型、automation prompt 和 route bundle 只允许输出 bare `sys://system.intent.xxx`。它们不得输出 `tel:`、`smsto:`、`mailto:`、`geo:`、`content:`、`package:`、`intent:` 或带用户数据的 parameterized `sys://`。

### 1.3 SMS / dial / call 补参规则

`SystemIntentLaunchSupport.parseSystemIntentLaunchRequest(...)` 对 communication system intent 执行本地补参：

1. recipient 读取顺序为 `communication.recipient`、`structuredDetailSlots.domain["recipient"]`、contact target label、contact target key。
2. recipient 若已经是直接电话号码，则先做本地规范化。
3. recipient 若是联系人名，则通过 `AndroidPhoneContactResolver` 查询 `ContactsContract.CommonDataKinds.Phone`，并过滤 `RawContacts.DELETED = 0` 的 live raw contact。
4. SMS data URI 由本地生成 `smsto:<encoded phone>`。
5. dial / call data URI 由本地生成 `tel:<encoded phone>`。
6. SMS message body 从 `communication.message_body` 或 `structuredDetailSlots.domain["message_body"]` 读取，并写入 `sms_body` extra。

### 1.4 启动前失败边界

本轮明确禁止 SMS / dial / call 使用 catalog probe 的 sample `executeDataUri` 作为 fallback。以下情况必须在 launch 前失败，并通过 `AppLaunchAutomationRunner` 写回可见 precondition failure：

1. 没有 grounded recipient。
2. 缺少 `READ_CONTACTS` 权限，且 recipient 不是直接电话号码。
3. 联系人名没有匹配到唯一号码。
4. 联系人名匹配到多个号码。

这样可以避免把真实用户请求误发到示例号码或空号码，同时保持 system intent 参数由本地 authority 补齐。

### 1.5 权限与 completion 边界

运行期需要 `READ_CONTACTS` 权限支持命名联系人解析。当前代码已在 manifest 声明权限，并由 `MainActivity` 启动时请求。

system intent 完成或失败并回到 chat 后，live task authority 必须退役：`currentSemanticIntentState / currentTaskDraft / currentTaskRecord / activeCandidateId / currentDialogueCandidates` 不再作为下一 turn authority 继续存在，只保留 `pendingExecutionRecovery` 作为 non-authoritative follow-up hint。

## 2. 已完成项目

1. `SystemIntentLaunchSupport.kt` 已支持带 `TaskExecutionBoundaryPacket` 的本地补参启动。
2. `PhoneContactResolver.kt` 已新增 Android contacts resolver、直接号码规范化、live raw contact 过滤和唯一号码判定。
3. `AppLaunchAutomationRunner.kt` 已在 app launch 前检查 SMS / dial / call 的 recipient / contact precondition，并把失败作为 writeback 终止。
4. `PromptCenter.buildAutomationAgentPacket(...)` 已禁止 automation agent 生成带用户数据的 system intent URI。
5. `AndroidManifest.xml` 和 `MainActivity.kt` 已接入 `READ_CONTACTS` 权限声明与启动请求。
6. `SystemIntentLaunchSupportTest`、`PhoneContactResolverTest` 和 `AppLaunchAutomationRunnerTest` 已覆盖 SMS / dial 本地补参、拒绝 sample fallback、联系人解析和 precondition failure。

### 2.1 当前代码回写（2026-05-22）

1. `SystemIntentLaunchSupport` + `PhoneContactResolver` 仍是 communication system intent 的本地补参 owner：direct number normalize、已删除 raw contact 过滤、canonicalize / dedupe 和唯一号码判定都在本地完成。
2. 当前 communication system-intent 主链仍保持 bare route + local parameter launch；launch 前缺参、无权限、零匹配或多匹配都直接走可见 precondition failure，不会回退到 sample URI 或 model-authored 参数。
3. execution-boundary session persistence 修复已经把该链的 return-to-chat / completion recovery 补齐，system-intent 启动后的后续恢复不再依赖 live `currentTaskRecord` 持续存活。

## 3. 待完成项目

1. 在 Samsung / OPPO 真机上继续验证联系人权限、同名多号码联系人和无权限路径的用户可见文案。
2. 如果后续扩展 mail / map / document system intent，应沿用同一 local boundary 补参模式，而不是恢复 model-authored URI 参数。
3. 若 contacts 查询需要支持模糊名、昵称或多账号合并，应在 `PhoneContactResolver` 内扩展，不应下放到 prompt 或 automation agent。