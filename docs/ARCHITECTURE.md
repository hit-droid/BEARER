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
`AccessibilityAutomator` 按屏幕尺寸换算单元格中心坐标为像素坐标后派发点击；设置中开启"网格点放模式"后，
主页显示网格参考覆盖层，便于对照单元格坐标下达 `tap_grid` 动作。

### 8. 动作录制与回放（Record & Replay，对标 AppAgent 演示学习）
让"一次成功的自动化"可复用，是离线智能体从"演示"走向"工具"的关键一环。

- **录制**：`AgentLoop.run(record = true)` 每步把动作经 `Action.toScriptAction()` 转成可序列化 `ScriptAction`，连同 `UiSnapshot.pageKey` 记录的**前后页面**与成败，写入 `ScriptStep`；循环结束（或因 `Done`/`Ask` 退出）时打包成 `ActionScript` 经 `Event.Recorded` 发出。`MainViewModel` 收到后落盘到 `ScriptStore`（应用私有目录下的 `<id>.json`）。
- **回放 `ReplayRunner`**：给定一个 `ActionScript`，用 `ScriptAction.toAction()` 还原动作，**不再调用规划器**，逐步 `device.execute`，速度更快、确定性更高；回放前读屏做"页面漂移"诊断（页面与录制时不同则提示但仍执行），回放成功会调用 `MemoryStore.success` 强化知识库。
- **UI**：主页"录制本次任务为脚本"开关控制 `record`；新增「我的脚本」页列出脚本、支持一键回放与删除。

### 9. 自动探索器 `Explorer` + 语义标注器 `ElementAnnotator`（AppAgent 两阶段）

至此，AppAgent 的核心机制已完整离线落地，且比原版更适合"纯端侧"：

| AppAgent 原版 | 本工程（离线版） |
|---|---|
| 阶段一：人工/随机**探索** App 页面 | `Explorer` 自动广度优先遍历页面 |
| 阶段二：GPT-4V 看截图**生成元素文档** | `ElementAnnotator` 用**本地 LLM** 读无障碍文本生成功能描述 |
| 需要云端多模态模型 | 全程离线，无需模型也能探索（描述退化为启发式） |

**探索**：从当前前台 App 出发，每到达一页就登记页面与其元素；挑选**未探索过且安全**的可点击控件试探，
点击后若页面切换即记录一条导航边（ `点 A → 页面 B` ）；本页无可探元素则先滚动、再回溯，
受限于步数与页面数双上限。

**标注**：探索结束后把尚无语义描述的元素分批（每批 8 个，低温短输出）交给本地模型，
得到"这个控件是干嘛的"并写回知识库。描述来源由 `ElementKnowledge.source` 区分
`heuristic` / `llm`，语义标注只升级、不覆盖。

**能力降级纪律**：不是所有引擎都能听懂任意指令。因此 `LlmEngine` 提供
`canFollowArbitraryInstructions`，规则式的 `StubEngine` 声明为 `false`，
标注器据此判断 `[available]` 并自动放弃 LLM 标注——保证无模型时探索流程依然完整可用。

**探索安全约束**（真实操控必须谨慎）：只点击不输入；危险标签黑名单
（卸载/删除/清除/支付/登出等）直接跳过；限域在目标包名内，越界立即返回。

### 10. 确定性路线规划 `RoutePlanner`（知识优先、LLM 兜底）

**动机**：既然已经把 App 的导航关系学成了图，就不该再把整份地图塞进 prompt、指望本地小模型
现场推理出"先点哪个、再点哪个"——这对 1B 级模型既慢又不可靠。

因此在大模型之前加一道更廉价的关卡：**把导航图当真正的图，跑 BFS 最短路径**。

```
目标 → 关键词提取（剔除"打开/进入/帮我"等交互词）
     → 在当前应用导航图上 BFS 搜索命中页面
     → 命中：返回"依次点击哪些元素"，直接 TapText 执行（零推理）
     → 未命中：检查其它已探索应用是否更匹配 → 先 OpenApp 切换，再规划
     → 仍无命中：回退本地 LLM 规划，并提示"建议先探索"
```

闭环中的三级降级（[AgentLoop]）：

| 级别 | 条件 | 行为 |
|---|---|---|
| ① 路线 | 队列非空 | 逐步 `TapText`，不调用模型 |
| ② 模型 | 路线失败/知识不足 | `Planner` 规划，并对该元素 `fail()` 降级 |
| ③ 提示 | 压根查不到路径 | `RouteAbort` 建议先探索，仍由模型接管 |

路线走到最后一步后自动交回模型处理**剩余细节**（例如到了 WLAN 页还要点开关），
所以路线与模型是**接力**关系，不是二选一。

> 实现要点：点击必须使用**原始大小写文本**（`WLAN`），而知识库的元素 key 是归一化小写，
> 因此 `MemoryStore.labelOf()` 负责 key → 原始文本的还原。

## 离线保证
- `AndroidManifest.xml` 不声明 `INTERNET` 权限；
- 模型文件通过 ADB 推送到私有目录，不联网下载；
- 推理与规划全部在端侧完成。
