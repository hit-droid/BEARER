# 更新日志

本文件遵循 [Keep a Changelog](https://keepachangelog.com/) 约定，版本号采用 [语义化版本](https://semver.org/lang/zh-CN/)。

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
