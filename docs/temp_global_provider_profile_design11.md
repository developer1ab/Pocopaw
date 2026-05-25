# 海外/国内多提供商运行时配置专题（temp）

## 0. 文档定位

1. 本文是 temp 专题设计稿，用于先完成方案评审。
2. 当前正式规范入口仍是 design11 系列；本稿通过后再回灌到对应模块文档。
3. 本稿只覆盖 provider/runtime/search 配置面，不改动 task authority、route authority、PromptCenter owner 边界。

## 1. 概要设计（Outline Design）

### 1.1 问题陈述

当前实现存在三类 provider 绑定：

1. 语义链路绑定 DeepSeek 命名配置。
2. 视觉链路绑定 Qwen 视觉配置和 Qwen thinking/search 请求字段。
3. 搜索增强绑定阿里 OpenSearch provider 标识和参数结构。

这导致“国内/海外”无法通过同一套运行时 profile 切换，只能做分散替换，风险高且不可观测。

### 1.2 设计目标

1. 维持双模型主结构：语义模型与视图模型分离。
2. 搜索引擎单独成为第三轴，支持 Google Search API 与阿里 OpenSearch 并存。
3. 把“国内/海外”收口成显式 profile，而不是隐式 fallback。
4. 保持 PromptCenter 唯一 prompt/contract owner，不新增平行 packet 或 schema owner。
5. 保持 execution runtime 无搜索权限的现有约束：搜索仅在 semantic/planning/pre-execution 生效。

### 1.3 非目标

1. 不在本专题改写 execution runtime 主链。
2. 不在本专题重构 capability catalog 或 app route 策略。
3. 不在本专题引入自动 fallback、灰度随机切换或不可见降级。

### 1.4 核心方案

采用三轴运行时配置模型：

1. Semantic Provider Axis：ChatGPT/OpenAI、Gemini、DeepSeek 等。
2. Vision Provider Axis：Qwen Vision、Gemini Vision、OpenAI Vision 等。
3. Search Provider Axis：Google Programmable Search JSON API、Aliyun OpenSearch。

并引入 Provider Profile：

1. `domestic_default`：保留现有国内主路径。
2. `global_default`：语义优先 OpenAI/Gemini，搜索优先 Google。
3. `custom`：用户可分别覆盖三轴。

## 2. 详细设计（Detailed Design）

### 2.1 运行时域模型

#### 2.1.1 枚举与能力声明

```json
{
  "SemanticProviderKind": ["OPENAI", "GEMINI", "DEEPSEEK"],
  "VisionProviderKind": ["QWEN_VISION", "GEMINI_VISION", "OPENAI_VISION"],
  "SearchProviderKind": ["GOOGLE_CSE", "ALIYUN_OPENSEARCH"]
}
```

每个 provider 需要 capability 声明：

1. 是否支持 reasoning channel。
2. 是否支持联网原生开关（如 `enable_search`）。
3. 是否支持 JSON schema/strict structured output。
4. 是否支持 streaming。

#### 2.1.2 统一配置对象

```json
{
  "profile_id": "global_default",
  "region_mode": "GLOBAL",
  "semantic": {
    "provider": "OPENAI",
    "model_tier_map": {
      "FAST": "gpt-4.1-mini",
      "EXPERT": "gpt-4.1"
    },
    "endpoint": "https://api.openai.com/v1/chat/completions",
    "api_key_ref": "OPENAI_API_KEY",
    "request_policy": {
      "timeout_ms": 90000,
      "max_retries": 1,
      "streaming_enabled": true
    }
  },
  "vision": {
    "provider": "GEMINI_VISION",
    "model": "gemini-2.5-flash",
    "endpoint": "https://generativelanguage.googleapis.com/v1beta/models",
    "api_key_ref": "GEMINI_API_KEY",
    "request_policy": {
      "timeout_ms": 90000,
      "max_retries": 1,
      "streaming_enabled": false
    },
    "feature_toggles": {
      "thinking_enabled": false,
      "search_enabled": false
    }
  },
  "search": {
    "provider": "GOOGLE_CSE",
    "endpoint": "https://www.googleapis.com/customsearch/v1",
    "api_key_ref": "GOOGLE_SEARCH_API_KEY",
    "engine_id_ref": "GOOGLE_SEARCH_ENGINE_ID",
    "request_policy": {
      "timeout_ms": 30000,
      "max_queries_per_turn": 3,
      "top_k_per_query": 5
    },
    "query_policy": {
      "safe": "active",
      "gl": "us",
      "hl": "en"
    }
  }
}
```

说明：

1. `api_key_ref` 只存引用，不存明文。
2. `region_mode` 只做预设选择，不干预 authority。
3. search 配置独立于语义和视觉，不复用其 endpoint/key。

### 2.2 存储 Schema（本地持久化）

#### 2.2.1 Settings Store Schema

建议新增 `provider_profile_settings`（SharedPreferences 或 JSON 持久化均可，先保持 SharedPreferences）。

```json
{
  "profile_id": "global_default",
  "region_mode": "GLOBAL",
  "semantic_provider": "OPENAI",
  "semantic_fast_model": "gpt-4.1-mini",
  "semantic_expert_model": "gpt-4.1",
  "semantic_endpoint": "https://api.openai.com/v1/chat/completions",
  "vision_provider": "GEMINI_VISION",
  "vision_model": "gemini-2.5-flash",
  "vision_endpoint": "https://generativelanguage.googleapis.com/v1beta/models",
  "search_provider": "GOOGLE_CSE",
  "search_endpoint": "https://www.googleapis.com/customsearch/v1",
  "search_query_gl": "us",
  "search_query_hl": "en",
  "search_safe": "active",
  "chat_thinking_enabled": false,
  "chat_search_enabled": false,
  "vision_thinking_enabled": false,
  "vision_search_enabled": false,
  "schema_version": 1
}
```

#### 2.2.2 BuildConfig 输入扩展

在保持现有键不删除的前提下，新增键族：

1. `OPENAI_API_KEY`、`OPENAI_ENDPOINT`、`OPENAI_MODEL_FAST`、`OPENAI_MODEL_EXPERT`。
2. `GEMINI_API_KEY`、`GEMINI_ENDPOINT`、`GEMINI_MODEL_TEXT_FAST`、`GEMINI_MODEL_TEXT_EXPERT`、`GEMINI_MODEL_VISION`。
3. `GOOGLE_SEARCH_API_KEY`、`GOOGLE_SEARCH_ENGINE_ID`、`GOOGLE_SEARCH_ENDPOINT`。
4. 保留 `DEEPSEEK_*`、`QWEN_VISION_*`、`OPENSEARCH_*` 作为兼容输入。

### 2.3 模块职责与接口

#### 2.3.1 Provider Runtime

新增统一入口：

1. `ProviderProfileRuntime`：解析当前 profile 与 override。
2. `SemanticProviderRegistry`：按 provider kind 返回语义客户端 adapter。
3. `VisionProviderRegistry`：按 provider kind 返回视觉客户端 adapter。
4. `SearchProviderRegistry`：按 provider kind 返回搜索客户端 adapter。

#### 2.3.2 适配器合同

语义 adapter 合同：

1. 输入：PromptPacket、SemanticRuntimePreferences、TurnOptions。
2. 输出：RawPromptExchange 或 Streaming Delta。
3. 约束：不能在 adapter 内修改 prompt contract；只能变更 provider request/response mapping。

视觉 adapter 合同：

1. 输入：视觉任务请求、截图/图像数据、视觉开关。
2. 输出：统一视觉解析对象。
3. 约束：Qwen 专有字段不外溢到通用接口。

搜索 adapter 合同：

1. 输入：queries 列表。
2. 输出：`SearchAugmentationResult(provider, queryResults, snippets)`。
3. 约束：provider 名由 adapter 注入，禁止在 orchestrator 写死。

### 2.4 Prompt 与 Contract 兼容策略

1. `PromptCenter` 不感知具体 provider brand。
2. `SEARCH_PLAN_QUERY -> 搜索聚合 -> SEMANTIC_TURN` 链路不变。
3. `search_summary`、`assistant_reply`、reasoning channel 的用户可见约束不变。
4. provider-specific 参数仅在 adapter 内映射。

### 2.5 Settings 页面结构（细化）

#### 2.5.1 信息架构

在现有 SETTINGS 页面新增一张卡片：`Provider Profile`，并保留现有 `Models` 卡片用于能力开关。

结构顺序：

1. Provider Profile（新增）。
2. Models（现有，重命名为 Turn Capability）。
3. One-off Diagnostics（现有）。
4. 其他已有卡片（保持）。

#### 2.5.2 Provider Profile 卡片字段

1. Region Preset
   - 控件：单选组（Domestic / Global / Custom）。
   - 行为：切换 preset 时，回填三轴默认值；不自动提交请求。
2. Semantic Provider
   - 控件：可点击选择（Dialog list）。
   - 字段：provider、fast model、expert model、endpoint。
3. Vision Provider
   - 控件：可点击选择（Dialog list）。
   - 字段：provider、model、endpoint。
4. Search Provider
   - 控件：可点击选择（Dialog list）。
   - 字段：provider、endpoint、query policy（gl/hl/safe）。
5. Credential Status
   - 控件：只读状态行。
   - 内容：三轴必需 key 的已配置/缺失摘要。
6. Validate Profile 按钮
   - 行为：执行轻量连通性与配置完整性校验，输出 diagnostics 文本。

#### 2.5.3 现有 Models 卡片调整

1. 保留对话回合开关：thinking/search。
2. 保留视觉回合开关：thinking/search。
3. 将“语义模型档位选择”改为读取 profile 的 fast/expert 映射，而不是 DeepSeek 专属命名。
4. 将“视觉模型选择”改为读取 vision provider 的 model 选项集合。

#### 2.5.4 页面交互规则

1. 设置更改先写本地 store，再刷新 runtime 缓存。
2. 对正在进行的 turn 不回溯改写；仅影响下一轮。
3. profile 不完整时，提交消息展示明确缺失项（按轴拆分）。
4. 禁止 silent fallback：缺 key 直接失败并提示补齐。

### 2.6 海外场景需要提前准备的一套配置

除 ChatGPT/Gemini/Google Search 外，建议同步预留：

1. 区域与语言：`gl`、`hl`、时区展示一致性。
2. 合规模块开关：是否允许把 query/screenshot 发到第三方 provider。
3. 请求可观测字段：provider、endpoint、model、status、latency、error class。
4. 配额保护：单回合搜索 query 上限、每日调用计数（本地诊断面）。

### 2.7 迁移与兼容

1. 迁移阶段保留旧键读取，优先读新 profile schema。
2. 若旧配置存在且新配置为空：生成一次 `domestic_default`。
3. 若用户手动切换 Global：不清空旧国内配置，保留可回切状态。
4. schema 版本升级必须带迁移函数，禁止破坏式覆盖。

## 3. 详细实施计划（Detailed Implementation Plan）

### 3.1 阶段 A：Schema 与 Runtime 基座

1. 新增 provider profile settings store。
2. 扩展 BuildConfig 键读取。
3. 引入 provider kind 枚举与三轴 runtime config。
4. 完成旧配置到新 schema 的迁移器。

验收：可在不改调用方的情况下读取并打印当前 profile。

### 3.2 阶段 B：语义链路 adapter 化

1. 抽取 `SemanticProviderAdapter` 接口。
2. 将当前 DeepSeek 请求逻辑放入 `DeepSeekSemanticAdapter`。
3. 新增 OpenAI/Gemini 语义 adapter（最小可用：非流式 + 流式）。
4. IntentGateway 改为通过 registry 选 adapter。

验收：语义回合可按 provider 切换并保持 contract 不变。

### 3.3 阶段 C：视觉链路 adapter 化

1. 抽取 `VisionProviderAdapter` 接口。
2. 迁移现有 Qwen 视觉实现。
3. 新增 Gemini/OpenAI Vision adapter 占位实现（可先单路径）。
4. 统一 thinking/search 开关注入策略。

验收：视觉请求不再依赖 Qwen 命名对象。

### 3.4 阶段 D：搜索 provider 抽象

1. 抽取 `SearchProviderAdapter` 接口。
2. 迁移 Aliyun OpenSearch 为 adapter。
3. 新增 Google CSE adapter。
4. 替换写死 provider 字符串逻辑。

验收：search prompt section 的 provider 字段随配置变化。

### 3.5 阶段 E：Settings 页面改造

1. 新增 Provider Profile 卡片与交互。
2. 现有 Models 卡片改为读取通用 runtime。
3. 增加配置完整性与连通性校验入口。
4. 增加缺失配置提示文案（按语义/视觉/搜索拆分）。

验收：用户可在页面完成国内/海外 preset 切换并即时生效到下一轮。

### 3.6 阶段 F：测试与可观测性

1. schema 迁移单测。
2. registry/provider 选择单测。
3. 搜索 provider prompt section 断言。
4. UI setting 交互测试与错误文案测试。

验收：核心路径回归通过，且无隐式 fallback。

## 4. 逻辑一致性审查（Design Consistency Review）

### 4.1 一致性检查结果

1. 与 PromptCenter owner 一致：未引入第二套 prompt owner。
2. 与 design11 搜索链一致：仍是 planner -> search -> semantic。
3. 与 UI 约束一致：开关与配置只影响下一轮，不越权启动执行。
4. 与无 fallback 规则一致：缺配置直接报错并提示修复。

### 4.2 风险与缓解

1. 风险：不同 provider 的 structured output 兼容性差。
   - 缓解：adapter 层做响应标准化，parser 仅消费统一对象。
2. 风险：页面复杂度上升。
   - 缓解：保留 preset 快捷路径，custom 仅在需要时展开。
3. 风险：旧配置迁移遗漏。
   - 缓解：迁移前后输出 diagnostics 对比，并提供一键回退到 domestic preset。

### 4.3 待确认决策点（评审项）

1. Global 语义默认优先 OpenAI 还是 Gemini。
2. Global 视觉默认优先 Gemini Vision 还是 OpenAI Vision。
3. Google Search 的默认 `gl/hl/safe` 组合。
4. 是否在首版就提供 provider 连通性探测按钮，还是第二阶段上线。
