# 用户交互模块设计 design11

## 1. 当前状态

### 1.1 页面与频道结构

用户交互层当前固定为单 `MainActivity` 壳层应用。当前可见表面为：

1. 顶层 surface：`CONSOLE` 与 `SETTINGS`。
2. `CONSOLE` 内部频道：`ALL`、`EXECUTION`、`CONVERSATION`。
3. `CONVERSATION` 是唯一统一聊天窗口；普通聊天、深度思考聊天和搜索增强聊天都在同一窗口内完成。
4. settings 页负责 provider profile（`DOMESTIC_DEFAULT / GLOBAL_DEFAULT / CUSTOM`）、search provider 和全局默认语义模型档位 `Fast / Expert`；chat 页负责当前回合 `深度思考 / 互联网搜索` 能力开关。
5. settings 页视觉模型显示值必须来自当前 profile 的视觉 provider runtime 配置；不能固定读取 Qwen 本地设置。
6. 当前视觉模型编辑入口仅在 `QWEN_VISION` provider 下开放；`GEMINI_VISION / OPENAI_VISION` 在 settings 中以只读展示为准。
7. 当前正式 diagnostics surface 为 settings 页的 one-off diagnostics、`CONVERSATION` 内的 LLM debug sidecar / prompt trace，以及 `EXECUTION` 内的 frozen mapping trace。

### 1.2 统一聊天窗口合同

`CONVERSATION` 当前承担以下合同：

1. 用户消息提交后立即进入聊天流。
2. assistant turn 立即创建 pending 槽位，并通过 streaming 逐步填充。
3. 若开启深度思考，reasoning content 与最终答复在同一 assistant turn 中分层显示。
4. 若开启互联网搜索，planner、搜索汇总、reasoning 和 final answer 仍归属于同一 assistant turn；该开关只影响 semantic / plan / pre-execution 阶段，不授权 execution runtime 在启动后继续搜索。
5. 聊天流只展示用户消息、assistant 消息，以及 success / failure 两类 system outcome message。
6. passive 链条下，UI 不能因为旧状态、搜索结果、capability 命中、`current_phase=PREPARATION|EXECUTION` 或 `SHOULD_ENTER_*` 就自行发起提案或执行。
7. 只有最新 turn 的 `START_PREPARING` / `START_EXECUTING` 并通过本地校验时，UI 才展示方案或进入执行复述。

当前语义状态显示固定拆成三块：

1. 当前状态：`ACCUMULATION / PREPARATION / EXECUTION`。
2. 用户请求：`START_ACCUMULATING / START_PREPARING / START_EXECUTING`。
3. 时机判别：`SHOULD_ENTER_ACCUMULATING / SHOULD_ENTER_PREPARING / SHOULD_ENTER_EXECUTING`。

若当前 turn 附带 preference recall / sibling expansion / recommended slots，`CONVERSATION` debug sidecar 必须至少展示 recall summary、sibling expansion 开关、mapping trace 摘要，以及 direct / neighbor / derived 来源标签。

### 1.3 交互主链

当前 UI 主链固定为：

1. `MainActivity.submitMessage()` 读取 composer 文本。
2. 用户消息立即进入 pending conversation。
3. chat 页读取本轮能力开关，并与 settings 默认模型档位组合成本轮配置。
4. `ChatTurnOrchestrator` 创建 pending assistant turn，并驱动统一 streaming。
5. `PrototypeStore.appendSemanticTurn()` 在 streaming 收尾后提交正式语义结果，并持久化 active / silent topic 槽位更新。
6. `START_PREPARING` 直接在对话面输出自然语言方案，不额外进入 execution。
7. `START_EXECUTING` 在启动前可以消费本轮已开启的搜索增强补齐外部事实，但必须先展示 1-2 秒执行任务复述，然后切到 `EXECUTION` 并进入 execution 主链。
8. 仅有 `current_phase` 或 `SHOULD_ENTER_*` 更新时，UI 只刷新状态显示和 topic/task 状态，不主动发起方案或执行。
9. 一旦进入 `EXECUTION`，本轮 `互联网搜索` 开关不再影响 runtime；需要新搜索时必须回到对话 / plan 阶段生成新的执行快照。

### 1.4 review、settings 与 Shizuku 表面

1. review card 属于对话面，承载流程 id、独立反馈输入框和 thumbs up / down 提交入口。
2. settings 页负责权限入口、截图参数、工具发现、偏好发现、偏好抽取、流程提取等 one-off 手动入口。
3. settings 页同时展示 preference discovery / extraction / recall / mapping 的最小 diagnostics summary。
4. settings 页的 provider 相关交互当前包括：provider profile 切换、search provider 切换、语义模型档位切换、视觉模型只读/可编辑状态提示。
4. 当前没有独立 LLM 页面时，`CONVERSATION` debug sidecar 就是正式 LLM surface。
5. 回到聊天面时默认落到 `CONVERSATION` 频道，并由 `onNewIntent()` 触发 store 刷新。
6. settings 页包含 Shizuku execution bootstrap 区块：状态、`Prepare with Shizuku`、`Auto prepare on startup` 和最近 bootstrap 结果；它只准备无障碍 / screen capture 前置状态，不提供业务任务入口。
7. Shizuku 状态必须以 live system truth 收敛：binder / permission、`PrototypeAccessibilityService` connected / destroyed、`CaptureService` ready / destroyed 都需要触发 UI 刷新。

### 1.5 设计约束

1. UI 只能消费 store 和 formatter 产物，不能自己推导语义或 execution authority。
2. `MainActivity` 不能硬编码 domain -> app route 规则。
3. 所有 prompt 文案、JSON contract 和 token budget 都必须回到 `PromptCenter`。
4. UI 可以维护 pending turn 与 streaming 中间态，但这些中间态不能替代 `PrototypeStore` 的正式 authority。
5. UI 的自动开始执行只允许发生在 `START_EXECUTING` 且本地校验通过后的复述延迟之后。
6. UI 的 `互联网搜索` 开关是当前 semantic / planning 回合能力，不是 execution runtime 权限。

## 2. 待开发项目

1. 补齐来源 footer、显式 `规划中 / 搜索中 / 生成中` 状态标签和更完整的搜索增强产品合同。
2. 增强 task-context / route compensation 的显式 debug 面板。
3. 丰富 execution evidence drawer 与 route trace 可见面。
4. 收口 sidecar 的最终去留与统一交互合同。
5. 继续加强三块语义状态显示的稳定性，避免退化成单一混合 stage 标签。
6. 扩展三处 preference observability surface 的来源层级和对账能力。
7. 补齐 Shizuku 跨 ROM 的 blocked / pending / ready 文案与 release 隐藏策略。
