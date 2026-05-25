# 执行运行时模块设计 design11

# Execution Runtime Design

This is the public English copy of the execution runtime module summary.

## 1. Module role

The execution runtime module is responsible for:

- consuming the task-first execution boundary,
- orchestrating the main runtime route families,
- and handling execution writeback, review, recovery, and runtime observability.

It does not own prompt construction, passive-stage interpretation, or authority promotion from ordinary topic state into live execution.

## 2. Current execution chain

The runtime is currently organized as a unified screenshot-first shell.

The simplified flow is:

1. `PreparedExecutionStart` enters the runtime.
2. The runtime selects the already-resolved route family.
3. Input stack handling, readiness checks, screen capture, grounding, plan-step consumption, and local verification run inside the same runtime surface.
4. Results are written back through a unified writeback bridge.
5. Failure goes into explicit review, recovery, or retry boundaries instead of falling through silent fallback paths.

## 3. Runtime constraints

The current constraints are intentional:

1. Execution may only start from a task-first local boundary.
2. System-intent routes and screenshot-driven routes obey the same boundary and verification rules.
3. Route-family choice is resolved before runtime start.
4. Runtime may retry or recover, but it may not rewrite the user's objective on its own.
5. Historical process assets can guide runtime behavior, but they do not replay as an executor by themselves.

## 4. Observability and completion

The execution surface should expose:

- runtime lifecycle,
- route summary,
- key execution events,
- and final outcome.

Success and failure both write back into the conversation surface. Review, recovery, learning, and reuse consume finished execution output; they do not pre-empt execution authority.

## 5. Current direction

The next public-facing runtime work is centered on:

- stronger resume and recovery paths,
- better route observability,
- clearer verification explanations,
- and broader validation across more input stacks and device-specific behaviors.
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
