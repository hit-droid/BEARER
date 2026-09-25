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

## 离线保证
- `AndroidManifest.xml` 不声明 `INTERNET` 权限；
- 模型文件通过 ADB 推送到私有目录，不联网下载；
- 推理与规划全部在端侧完成。
