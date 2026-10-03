# 更新日志

本文件遵循 [Keep a Changelog](https://keepachangelog.com/) 约定，版本号采用 [语义化版本](https://semver.org/lang/zh-CN/)。

## [0.2.0] - 2026-10-03

对标 [Tencent AppAgent](https://github.com/TencentQQGYLab/AppAgent) 的"探索→知识库"思路，补强离线成功率。

### 新增
- **本地记忆库（知识库）** `MemoryStore`：运行中自动登记各 App 的可交互元素到应用私有目录（JSON），下次执行同类任务时作为规划参考，减少对模型的盲目试探。支持探索登记、成功操作加权、清空。
- **网格点按** `Action.TapGrid` + 执行器坐标换算：对无文字标签、仅靠坐标难以描述的控件按网格单元点按（对标 AppAgent 网格覆盖）。设置页可开启"网格点按模式"，主页显示网格参考覆盖层。
- 规划器 `Planner` 新增"本地记忆"上下文注入；`AgentLoop` 在每步探索时登记可见可交互元素、成功操作后强化记忆。
- 设置页新增记忆库状态展示与"清空本地记忆"。

### 修复
- 修正 `StubEngine` 的节点解析正则：原正则与 `UiSnapshot.toPromptText()` 实际输出格式不匹配，导致规则规划器无法读取界面节点；现改为稳健的多正则逐行解析，并支持利用本地记忆做兜底匹配。

### 变更
- 版本号升至 0.2.0（versionCode 2）。

## [0.1.0] - 2026-09-25

首个可构建发行版。

### 新增
- 离线安卓任务自动化智能体主体工程（Kotlin + Jetpack Compose）。
- 推理层 `LlmEngine` 接口，含 `LlamaCppEngine`（llama.cpp JNI 真实推理）与 `StubEngine`（规则式规划器，无需模型即可运行）。
- 智能体核心：`Action` 动作定义、`UiSnapshot` 界面快照、`Planner`（目标+界面→下一步动作 JSON）、`AgentLoop`（ReAct 规划→执行→观察闭环）。
- 自动化执行引擎：`AgentAccessibilityService` + `AccessibilityAutomator`（读取屏幕、派发手势、设置文本、启动应用、返回/桌面）。
- Compose 主页 / 设置页 / `MainViewModel`，实时展示目标、计划步骤、日志与界面观察。
- `scripts/build_llama.sh`：固定提交拉取并编译 llama.cpp 为 `.so`。
- Gradle Wrapper（gradle-8.9）与 `.github/workflows/ci.yml` 自动构建 Debug APK。

### 变更
- 默认构建关闭原生推理层（`offlineagent.native=false`），**无需 NDK 即可编译运行**；接入真实模型时置 `true`（或 `-Pofflineagent.native=true`）。
- 通过 `BuildConfig.USE_NATIVE_LLM` 在代码层区分构建类型，原生库缺失时安全降级为 `StubEngine`。

### 已知限制
- 仓库未声明 `INTERNET` 权限，运行时完全离线。
- `llama_jni.cpp` 面向 llama.cpp 经典采样循环 API；升级上游后如需适配，改动集中在文件末尾注释处。
