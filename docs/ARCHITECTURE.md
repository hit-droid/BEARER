# 架构说明

离线智能体由四层组成，彼此通过接口解耦：

```
┌─────────────────────────────────────────────┐
│  UI 层 (Compose)                             │
│  MainScreen / SettingsScreen / MainViewModel  │
└───────────────┬─────────────────────────────┘
                │ 驱动
┌───────────────▼─────────────────────────────┐
│  智能体闭环 (AgentLoop)                       │
│  规划 → 执行 → 观察，产出 Event 流给 UI        │
└───────┬───────────────────────┬──────────────┘
        │ 决策                   │ 执行
┌───────▼────────┐      ┌────────▼──────────────┐
│  Planner       │      │  DeviceController      │
│  (LLM 规划器)  │      │  ← AccessibilityAutomator│
└───────┬────────┘      └────────┬──────────────┘
        │                        │
┌───────▼────────┐      ┌────────▼──────────────┐
│  LlmEngine     │      │  AgentAccessibilityService│
│  LlamaCppEngine│      │  (读取屏幕/派发手势)    │
│  StubEngine    │      └─────────────────────────┘
└───────┬────────┘
        │ JNI
┌───────▼────────┐
│  llama.cpp (.so)│   ← 端侧 GGUF 模型
└────────────────┘
```

## 关键设计

### 1. 推理层抽象 `LlmEngine`
统一接口 `load / generate / unload`，`generate` 返回 `Flow<String>` 以支持流式输出。
- `LlamaCppEngine`：真实离线推理，通过 `LlamaJni` 调原生库。
- `StubEngine`：规则式规划器，在无模型时降级运行，保证闭环可演示。

### 2. 规划器 `Planner`
把「目标 + 历史步骤 + 当前界面快照」拼成强约束 prompt，要求模型只输出一个动作 JSON
（见 `ActionPlan` schema）。解析失败安全回退为 `Wait`，避免循环崩溃。

### 3. 界面向文本 `UiSnapshot`
`AccessibilityNodeInfo` 遍历后只保留有文本/可交互的节点（上限 60），序列化为紧凑文本喂给模型，
控制小模型上下文长度。坐标类动作直接使用屏幕像素；文本类动作在运行时由执行器按可见节点匹配，更鲁棒。

### 4. 执行器 `AccessibilityAutomator`
实现 `DeviceController`：
- 点击/滑动：`dispatchGesture`（API 24+）；
- 输入：`performAction(ACTION_SET_TEXT)`，无需调起软键盘；
- 启动应用：`PackageManager.getLaunchIntentForPackage`；
- 返回/桌面：`performGlobalAction`。

### 5. 闭环 `AgentLoop`
`repeat(maxSteps)` 内：读屏 → 规划 → 执行 → 记录历史 → 延迟。遇到 `Done`/`Ask` 或错误即终止，
通过 `Flow<Event>` 把每步实时暴露给 UI。

### 6. 文档型知识库 `MemoryStore`（对标 AppAgent 探索机制）
灵感来自 [Tencent AppAgent](https://github.com/TencentQQGYLab/AppAgent) 的"先探索 App、积累 UI 知识"思路，
但完全离线落地，且从"元素清单"升级为**带功能描述的文档型知识库**：
- `UiSnapshot` 在 `fromRoot()` 时计算 `pageKey`（包名 + 界面显著文本签名，稳定区分页面）与 `title`（尽力提取的页面标题），作为"页面锚点"（无需截图）；
- 每步读屏后，`AgentLoop` 登记当前**页面**与可见**元素**（元素带 `function` 功能描述、`role` 角色、`pageKey` 归属页面）；
- 点击文本后若 `pageKey` 变化，即记录一条**导航边**（`NavEdge`：从某页点某元素到达某页）；
- `Planner` 调用 `MemoryStore.describe(pkg, pageKey)` 把"当前页面可交互元素（含用途）＋ 从本页可直达的页面"整份注入 prompt，让本地模型理解"点 X 去 Y 页"；
- `MemoryStore.dump()` 导出全量可读知识库，设置页"预览内容"直接展示；也可一键清空。

数据模型：`AppKnowledgeDocument`（按包名聚合）→ `PageKnowledge` / `ElementKnowledge` / `NavEdge`，统一序列化为应用私有目录的 `offline_agent_memory.json`（`version=2`）。

### 7. 网格点按 `Action.TapGrid`
对无文字标签、仅靠坐标难以描述的控件，提供按网格单元（默认 10×6）点按的能力。
`AccessibilityAutomator` 按屏幕尺寸换算单元格中心坐标为像素坐标后派发点击；设置中开启"网格点按模式"后，
主页显示网格参考覆盖层，便于对照单元格坐标下达 `tap_grid` 动作。

## 离线保证
- `AndroidManifest.xml` 不声明 `INTERNET` 权限；
- 模型文件通过 ADB 推送到私有目录，不联网下载；
- 推理与规划全部在端侧完成。
