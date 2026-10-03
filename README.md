# 离线智能体（OfflineAgent）

一个**完全离线运行**的安卓任务自动化智能体。本地大模型负责"规划/决策"，无障碍服务负责在真机上执行点击、输入、滑动等操作——**全程不联网**。

- 技术栈：原生 **Kotlin + Jetpack Compose**
- 推理：端侧 **llama.cpp（GGUF 量化模型）**，通过 JNI 桥接
- 自动化：**AccessibilityService** 读取屏幕 + 派发手势
- 闭环：ReAct 风格 规划 → 执行 → 观察

> 当前仓库不含任何网络权限，运行时不会发起任何请求。模型文件需通过 USB/ADB 推送到设备。

---

## 1. 功能

- 用自然语言下达任务目标（如"打开设置并进入关于手机"），智能体自动分步完成。
- 端侧 LLM 把"目标 + 当前界面"映射为下一步动作（点击/输入/滑动/启动应用/返回/等待…）。
- **文档型本地知识库（对标 [AppAgent](https://github.com/TencentQQGYLab/AppAgent) 探索机制）**：运行中自动把各 App 的**可交互元素及其功能**、**所属页面**、以及"**点此元素→到达页面**"的导航关系记录到本机，下次执行同类任务时整份注入规划器，显著减少离线小模型的盲目试探。设置页可预览/清空知识库。
- **网格点按**：对无文字标签的控件，可按网格单元格坐标点按（设置中开启"网格点按模式"，主页显示网格参考）。
- 实时界面快照与执行日志可视化，便于观察与调试。
- 内置 `StubEngine`：**无需模型、无需 NDK** 即可跑通整个自动化闭环，方便先验证流程。

## 2. 环境要求

| 项目 | 版本 |
|------|------|
| Android Studio | Hedgehog / Iguana 及以上 |
| Android Gradle Plugin | 8.5.x（已锁定） |
| Kotlin | 2.0.x（已锁定） |
| NDK | 26.1.x（编译真实推理层时需要） |
| 设备 | Android 8.0+（API 26+），建议 6GB+ 内存跑 1~3B 模型 |

## 3. 快速开始（不编译原生层，先看 UI 与自动化）

默认构建**已关闭原生推理层**，无需 NDK、无需模型即可编译运行。适合先验证界面与无障碍自动化流程：

1. 用 Android Studio 打开本工程（仓库已自带 `gradlew` 与 Gradle Wrapper，无需手动配置 Gradle）。
2. 连接设备（或模拟器），运行 `app`。
3. 在「设置 → 自动化」里跳转系统无障碍设置，开启 **离线智能体 · 自动化服务**。
4. 回到主页输入目标（如"打开设置"），点击「开始执行」。此时使用内置 `StubEngine`：它会按规则解析界面并点击，让你看到完整闭环（无需下载模型）。

> 命令行构建：`bash ./gradlew assembleDebug`（默认走 StubEngine，不需要 NDK）。

## 4. 接入真实本地推理（llama.cpp）

### 4.1 准备原生库

```bash
bash scripts/build_llama.sh      # 克隆 llama.cpp 到 app/src/main/jni/llama.cpp
```

脚本会固定检出较稳定的 llama.cpp 提交，避免上游 API 漂移导致 JNI 编译失败。若你的 llama.cpp 版本已切换到新的 sampler 链 / `llama_vocab` API，请参考 `app/src/main/jni/llama_jni.cpp` 末尾的"新 API 适配提示"做少量修改。

随后开启原生构建开关（二选一）：

- 改 `gradle.properties`：`offlineagent.native=true`
- 或命令行：`./gradlew assembleDebug -Pofflineagent.native=true`

并确保已安装对应 NDK（Android Studio → SDK Manager → SDK Tools → NDK，版本见 `app/build.gradle.kts`）。开启后 `BuildConfig.USE_NATIVE_LLM=true`，App 会自动优先使用 `LlamaCppEngine`。

### 4.2 准备模型

下载一个 GGUF 量化模型（如 `qwen2.5-1.5b-instruct-q4_k_m.gguf` 或 `smollm2-1.7b` 系列），推送到应用私有目录：

```bash
adb push model.gguf /sdcard/Android/data/com.offlineagent/files/
```

### 4.3 配置并运行

1. 打开 App → 设置，填写模型路径（如 `/sdcard/Android/data/com.offlineagent/files/model.gguf`），按需调整温度（建议 0.3~0.5）与最大 token。
2. 开启无障碍服务（同 3.3）。
3. 主页输入自然语言目标，开始执行。

## 5. 已知限制与后续

- 当前 `llama_jni.cpp` 面向 llama.cpp 经典采样循环 API；升级 llama.cpp 后如需适配，改动集中在文件末尾注释处。
- 小模型在复杂多步任务上易"跑偏"，建议配合较短目标、及时用「停止」人工纠偏。
- 后续可扩展：RAG 本地知识库、多应用跨任务编排、动作回放与脚本导出、模型热切换。

## 6. 目录结构

```
OfflineAgent/
├── app/src/main/
│   ├── jni/                     # llama.cpp JNI 胶水层（CMake + C++）
│   ├── java/com/offlineagent/
│   │   ├── llm/                 # LlmEngine 接口 / LlamaJni / LlamaCppEngine / StubEngine
│   │   ├── core/                # Action / UiSnapshot / Planner / AgentLoop / DeviceController
│   │   ├── automation/          # 无障碍服务 + 执行器
│   │   ├── ui/                  # Compose 界面与 ViewModel
│   │   └── di/                  # Settings + AppContainer
│   ├── res/                     # 资源（含无障碍服务配置）
│   └── AndroidManifest.xml
├── scripts/build_llama.sh       # 拉取 llama.cpp
└── docs/ARCHITECTURE.md         # 架构详解
```
