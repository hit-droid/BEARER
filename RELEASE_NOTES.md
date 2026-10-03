# OfflineAgent v0.2.0

离线安卓任务自动化智能体：本地大模型负责"规划/决策"，无障碍服务负责在真机上执行点击、输入、滑动，全程不联网。本版本对标 [Tencent AppAgent](https://github.com/TencentQQGYLab/AppAgent) 的探索机制，补强离线成功率。

## 相对 v0.1.0 的变化

### 新增
- **本地记忆库（知识库）**：运行中自动把各 App 的可交互元素持久化到本机 JSON，下次执行同类任务时作为规划参考，显著减少盲目试探。设置页可查看统计并一键清空。
- **网格点按** `Action.TapGrid`：对无文字标签的控件按网格单元格坐标点按；设置开启"网格点按模式"后主页显示网格参考覆盖层。
- `Planner` 注入"本地记忆"上下文；`AgentLoop` 每步探索登记可见元素、成功操作后加权记忆。

### 修复
- 修正 `StubEngine` 节点解析正则（此前与 `UiSnapshot` 实际输出格式不匹配，导致规则规划器读不到界面节点）；现支持利用本地记忆兜底匹配。

### 工程
- 版本号升至 0.2.0（versionCode 2）。

## 使用方式
- 快速体验（无需模型/NDK）：Android Studio 打开 → 运行 `app` → 开启无障碍服务 → 输入"打开设置"即可看到完整闭环（走 `StubEngine`）。
- 接入真实推理：执行 `scripts/build_llama.sh` → 装 NDK 并设 `offlineagent.native=true` → `adb push model.gguf` 到应用私有目录 → 设置里填路径。

## 已知限制
- 仓库未声明 `INTERNET` 权限，运行时完全离线。
- `llama_jni.cpp` 面向 llama.cpp 经典采样循环 API；升级上游后适配点集中在文件末尾注释处。
