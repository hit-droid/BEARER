# OfflineAgent v0.1.0

首个可构建发行版。一个**完全离线运行**的安卓任务自动化智能体：本地大模型负责"规划/决策"，无障碍服务负责在真机上执行点击、输入、滑动等操作，全程不联网。

## 亮点
- 原生 **Kotlin + Jetpack Compose**，无第三方 DI 框架。
- 推理层 `LlmEngine` 接口：`LlamaCppEngine`（llama.cpp JNI 真实端侧推理）+ `StubEngine`（规则式规划器，无需模型即可运行）。
- ReAct 式闭环：`规划 → 执行 → 观察`，带步数与超时保护，动作解析失败安全回退为等待。
- 自动化执行引擎基于 `AccessibilityService`，读取屏幕、派发手势、设置文本、启动应用、返回/桌面。
- Compose 主页 / 设置页实时展示目标、计划步骤、日志与界面观察。

## 工程改进（相对初始版本）
- 默认构建关闭原生推理层（`offlineagent.native=false`），**无需 NDK 即可编译运行**。接入真实模型时置 `true`。
- 通过 `BuildConfig.USE_NATIVE_LLM` 在代码层区分构建类型，原生库缺失时安全降级为 `StubEngine`。
- 内置 Gradle Wrapper（gradle-8.9），开箱即编。
- 新增 GitHub Actions CI：自动构建 stub 版 Debug APK。

## 使用方式
- 快速体验（无需模型/NDK）：用 Android Studio 打开 → 运行 `app` → 开启无障碍服务 → 输入"打开设置"即可看到完整自动化闭环（走 `StubEngine`）。
- 接入真实推理：执行 `scripts/build_llama.sh` → 装 NDK 并设 `offlineagent.native=true` → `adb push model.gguf` 到应用私有目录 → 设置里填路径。

## 已知限制
- 仓库未声明 `INTERNET` 权限，运行时完全离线。
- `llama_jni.cpp` 面向 llama.cpp 经典采样循环 API；升级上游后适配点集中在文件末尾注释处。
