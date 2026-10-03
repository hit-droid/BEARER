# 更新日志

本文件遵循 [Keep a Changelog](https://keepachangelog.com/) 约定，版本号采用 [语义化版本](https://semver.org/lang/zh-CN/)。

## [0.5.0] - 2026-10-03

落地 [AppAgent](https://github.com/TencentQQGYLab/AppAgent) 的完整**两阶段**（自主探索 → 知识文档生成），把离线智能体从"边做边学"升级为"先摸清地形再办事"。

### 新增
- **自动探索器 `Explorer`**：广度优先遍历目标 App 的页面，登记页面元素并积累"点某元素 → 到达某页"的导航边。主页新增「探索当前应用」按钮。
  - 安全约束：只点击、**永不输入文本**；内置危险标签黑名单（卸载/删除/支付/登出等）一律跳过；限制在目标包名范围内活动、越界即返回；步数与页面数双上限。
- **元素语义标注器 `ElementAnnotator`**：探索结束后调用本地 LLM，把元素清单升级为"带功能描述的文档"（如"查看 WLAN 列表"），替代此前的启发式猜测。分批、低温、短输出，适配本地小模型。
- **知识描述溯源**：`ElementKnowledge.source` 区分 `heuristic`（规则推断）/`llm`（模型生成），语义标注只升级不覆盖已有 LLM 描述；知识库统计与预览中可见标注比例。

### 变更
- `LlmEngine` 新增 `canFollowArbitraryInstructions`：规则式 `StubEngine` 声明为 false，使语义标注等"非规划任务"能正确降级（无模型时探索仍可用，仅描述退化为启发式）。

### 修复
- **真实编译错误**：`AccessibilityAutomator.tapByText` 为普通函数却调用了 `suspend fun dispatchTap`，会导致 Android Studio 编译失败；已改为 `suspend fun`。该问题由本轮引入的 Kotlin 编译器静态检查发现。

### 工程
- 版本号升至 0.5.0（versionCode 5）。

## [0.4.0] - 2026-10-03

新增 **动作录制与回放（Record & Replay）** 能力，把离线智能体从"一次性规划执行"升级为"可复用自动化工具"。

### 新增
- **录制**：`AgentLoop` 开启 `record` 后，每步动作连同"执行前后页面指纹"与成败写入 `ScriptStep`，循环结束时打包成 `ActionScript` 经 `Event.Recorded` 发出，由 `MainViewModel` 落盘到 `ScriptStore`（应用私有目录下的 JSON）。
- **回放 `ReplayRunner`**：给定一个 `ActionScript` 逐步执行，**不再调用规划器**，速度更快、确定性更高；回放前读取当前界面做"页面漂移"诊断，回放成功仍会强化本地知识库。
- **可序列化动作 `ScriptAction`**：`Action` 密封类的扁平 JSON 载体，提供 `toScriptAction()` / `toAction()` 双向转换。
- **脚本库 UI**：新增「我的脚本」页，列出已录制脚本，支持一键回放与删除；主页新增"录制本次任务为脚本"开关。

### 工程
- 版本号升至 0.4.0（versionCode 4）。

## [0.3.0] - 2026-10-03

将"本地记忆库"升级为 AppAgent 式的**文档型知识库**，进一步补强离线成功率。

### 新增
- **文档型知识库 `MemoryStore`**：元素记录**功能描述**、**角色**（按钮/输入框/开关/列表项）、**所属页面**；并自动积累**页面导航图**（点 A 元素到达 B 页面）。
- **页面指纹识别** `UiSnapshot.pageKey` / `title`：由包名 + 界面显著文本签名得到稳定页面标识与可读标题，无需截图即可近似 AppAgent 的"页面锚点"。
- **规划器接入导航图**：`Planner` 把"当前页面可交互元素（含用途）＋ 从本页可直达的页面"整份注入 prompt，让本地模型理解"点 X 去 Y 页"。
- 设置页知识库卡片新增"预览内容"按钮，展示全量知识库（页面/元素功能/导航边）的可读导出。

### 变更
- `AgentLoop` 每步探索登记页面与元素（含功能描述）；点击文本后若页面切换即记录导航边；成功/失败分别加权或降级。
- `Planner` 上下文从"元素清单"升级为"文档型知识库导出"。
- 版本号升至 0.3.0（versionCode 3）。

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
