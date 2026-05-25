# 海外/国内多提供商接入任务清单（temp 执行回写）

> 回写时间：2026-05-25（按仓库当前实现状态）

## 0. 已实现结果摘要

1. 已落地三轴 provider runtime：semantic / vision / search。
2. 已落地 profile 维度：`DOMESTIC_DEFAULT` / `GLOBAL_DEFAULT` / `CUSTOM`，并带 schema 版本与迁移入口。
3. 搜索链路已支持 Aliyun OpenSearch 与 Google CSE 两路动态切换。
4. 语义链路已切到 profile 驱动 runtime config，不再写死 DeepSeek 配置。
5. 视觉链路已切到中性入口 `ProviderRuntimeConfigs.vision`；Qwen 模型设置仅在 Qwen provider 下可编辑。
6. settings 已支持 provider profile 与 search provider 操作，视觉模型展示改为读取当前 profile 的视觉配置。

## 1. 阶段回写状态

### 阶段 A：Schema 与 Runtime 基座

- [x] A1. Provider Profile 配置模型与存储
  - 已有 `ProviderProfileRuntimeConfig`、三轴 runtime config、schemaVersion、settings store、迁移入口。
- [x] A2. BuildConfig 键扩展
  - 已扩展 OPENAI / GEMINI / GOOGLE_SEARCH 键族，保留 DEEPSEEK / QWEN_VISION / OPENSEARCH。
- [x] A3. 启动加载与迁移
  - 启动路径会应用已存 profile；legacy schema 走迁移写回。

### 阶段 B：语义 Provider 抽象

- [x] B1. 语义 runtime 选择改造
  - `IntentGateway` 与 `ChatReplyGateway` 改为按 `ProviderRuntimeConfigs.semanticRuntimeConfig(...)` 取配置。
- [x] B2. DeepSeek 路径兼容
  - `DeepSeekPrototypeClient` 默认配置改为动态语义配置入口，保留现有 packet/parse 合同。
- [~] B3. OpenAI/Gemini 专有 adapter
  - 当前为统一 OpenAI-compatible request body 通道；尚未拆成 provider 专有 adapter 类。

### 阶段 C：视觉 Provider 抽象

- [x] C1. 视觉 runtime 选择改造
  - 主要视觉调用点已从 `qwenVision` 切到 `vision` 中性入口。
- [x] C2. Qwen 行为兼容
  - 保留 Qwen 模型选项及 `QwenVisionModelRuntime` 行为。
- [~] C3. Gemini/OpenAI Vision 深化
  - 配置与运行时入口已具备；专有解析 adapter 尚未拆分类。

### 阶段 D：搜索 Provider 抽象

- [x] D1. 搜索 provider 动态化
- [x] D2. Aliyun 迁移
- [x] D3. Google CSE 新增
- [x] D4. provider 写死清理
  - `ChatTurnOrchestrator` 的 search attribution provider 已由 client 动态输出。

### 阶段 E：Settings 页面改造

- [x] E1. Provider Profile / Search Provider 操作入口
- [x] E2. 模型显示与 profile 对齐
  - semantic 模式与 runtime model 显示已接入；vision 显示改为读取当前 provider profile。
- [~] E3. 视觉模型编辑权限细化
  - 已实现：非 Qwen provider 下视觉模型项只读提示。
  - 待做：如果需要，可补完整的 Gemini/OpenAI 视觉模型编辑器。

### 阶段 F：测试与回归

- [x] F1. 新增关键单测
  - `ProviderRoutingRuntimeTest` 覆盖语义网关 profile 切换路由与 vision runtime 解析。
- [x] F2. 构建回归
  - 多轮 `:app:assembleDebug` 通过，已真机安装启动验证。

## 2. 已落地文件映射（关键项）

1. `app/src/main/java/com/atombits/pocopaw/ProviderRuntimeConfig.kt`
   - profile schema、三轴配置、动态 semantic/vision/search runtime 解析。
2. `app/src/main/java/com/atombits/pocopaw/intent/IntentGateway.kt`
   - 语义请求改为 profile 驱动配置。
3. `app/src/main/java/com/atombits/pocopaw/reply/ChatReplyGateway.kt`
   - 执行回复请求改为 profile 驱动配置。
4. `app/src/main/java/com/atombits/pocopaw/DeepSeekPrototypeClient.kt`
   - 默认 runtime config 动态化，错误提示 provider 中性化。
5. `app/src/main/java/com/atombits/pocopaw/SearchAugmentationClient.kt`
   - Aliyun + Google CSE 双 provider。
6. `app/src/main/java/com/atombits/pocopaw/orchestration/ChatTurnOrchestrator.kt`
   - 搜索 provider attribution 动态化。
7. `app/src/main/java/com/atombits/pocopaw/ui/ConsoleRenderAdapter.kt`
   - 视觉模型显示读取当前 profile 视觉配置。
8. `app/src/main/java/com/atombits/pocopaw/MainActivity.kt`
   - provider profile / search provider 入口；非 Qwen 视觉模型只读提示。
9. `app/src/test/java/com/atombits/pocopaw/ProviderRoutingRuntimeTest.kt`
   - profile 切换与 vision 解析单测。

## 3. 当前遗留（仅记录，不扩张范围）

1. semantic / vision provider 仍是统一客户端通道，尚未拆分 provider 专有 adapter 类。
2. settings 的视觉模型编辑器目前仅支持 Qwen 编辑路径，Gemini/OpenAI 为只读显示。
