# 偏好记忆与检索模块设计 design11

## 1. 当前状态

### 1.1 模块定位

本模块承担 design11 下正式的 preference memory / recall / slot recommendation 能力。它当前拥有：

1. structured user preference memory。
2. interaction bias memory。
3. direct / recent / semantic / sibling recall。
4. sibling expansion 与 derived hypothesis 生成。
5. recommended detail slots 与 mapping trace。
6. 面向 settings、LLM debug sidecar 和 execution evidence drawer 的 preference observability contract。

本模块当前不拥有 discovery / extraction 调度入口、`PromptCenter` owner 权限、execution authority 或 route authority。

### 1.2 正式数据面

`MemoryState` 在 preference 相关部分的正式数据面固定为：

| 子 store | 作用 |
| --- | --- |
| `structuredPreferenceMemory` | 用户长期/近期偏好 facts、recent facts、semantic chunks |
| `interactionBiasMemory` | `preferred_process_id`、`preferred_page_signature`、`preferred_shortcut_screen` 等交互偏置 |
| `preferenceDebugStore` | projection / recall / sibling expansion / slot mapping 诊断快照 |

旧 `preferenceEvidenceStore` 与 `preferenceSummaryCards` 只保留迁移兼容角色，不再是 live consumer 面。

### 1.3 Recall 与 sibling expansion 主链

当前 recall 主链固定为：

1. 当前用户输入先构造 `PreferenceRecallRequest`。
2. `PreferenceRecallResolver` 先执行 current-domain direct recall。
3. direct evidence 偏弱时，允许进入 sibling expansion。
4. sibling expansion 必须受 `domainRoot`、sibling distance、transferable facets 和 live gating 约束。
5. 最终输出 `PreferenceRecallBundle`，显式区分 direct preferences、recent facts、semantic evidence、neighbor evidence 与 derived hypotheses。

正式约束为：

1. sibling expansion 只能发生在共享同一个 `domainRoot` 的 sibling domains 内。
2. derived hypothesis 永远低于 direct evidence。
3. derived hypothesis 可以进入 live 主链，但必须显式标记为推断结果。
4. derived hypothesis 不得直接回写为 durable preference fact。

### 1.4 Slot recommendation 与 canonical mapping

`PreferenceSlotRecommendationEngine` 把 recall bundle 映射为 `RecommendedDetailSlotBundle`。当前映射优先级固定为：

1. 用户显式输入。
2. 当前 authoritative topic/task/boundary slots。
3. 订单 / 服务 direct facts。
4. 对话 direct facts。
5. recent evidence。
6. semantic evidence。
7. neighbor evidence。
8. derived hypotheses。

recommended detail slots 只服务 semantic / plan / reuse 弱提示，不自动推进 execution，也不覆盖 authoritative boundary slot。

### 1.5 可见面与调试合同

当前正式 observability surface 固定为三处：

1. settings 页：展示最近一次 projection、recall 和 mapping trace 摘要，服务于 one-off 调试。
2. `CONVERSATION` debug sidecar：展示当前 turn 的 recall bundle、recommended slots、sibling expansion 状态和来源层级。
3. `EXECUTION` evidence drawer：展示 execution start 之前消费的 frozen preference snapshot，说明哪些推荐进入了 boundary，哪些被拒绝、覆盖或要求确认。

### 1.6 设计约束

1. 本模块只能提供 evidence、debug trace 和 recommendation，不能生成 execution authority。
2. UI debug 面只能展示 recall / inference / mapping 结果，不能绕过本地 resolver 直接写 store。
3. sibling expansion 不能退化成无约束跨域联想。
4. interaction bias memory 不得重新污染用户偏好 recall 结果。
5. prompt contract 必须区分 direct / neighbor / derived 三个层级，不能重新合成模糊 preference 文本块。

## 2. 待开发项目

1. 继续收缩旧 preference 兼容面，直到完全删除旧字段 live 依赖。
2. 扩展 settings、`CONVERSATION` debug sidecar 与 `EXECUTION` evidence drawer 的 projection / recall / mapping diagnostics 粒度。
3. 继续完善 multi-source weighting、domain-specific promotion gate 和 sibling expansion 的可解释性。
4. 扩展 canonical facet-to-slot mapping，尤其是 transport、food、local service 与更多 commerce 外 domain。
5. 继续验证 recommended slots 在 semantic / plan / reuse 中只作为 evidence 与 weak hints，不会绕过 task authority。
