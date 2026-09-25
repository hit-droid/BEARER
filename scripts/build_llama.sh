#!/usr/bin/env bash
# 拉取并准备 llama.cpp 源码，供 app/src/main/jni/CMakeLists.txt 编译使用。
#
# 用法（在仓库根目录执行）：
#   bash scripts/build_llama.sh
#
# 前置条件：已安装 git。NDK 由 Android Gradle Plugin 在构建 APK 时自动调用，
# 无需在此处手动编译原生库——本脚本只负责把源码放到正确位置。
#
# 说明：llama.cpp 迭代很快，其 C API 偶有变动。本工程的 jni/llama_jni.cpp
# 面向"经典采样循环"API。若你克隆的版本已切换到新的 sampler 链 / llama_vocab，
# 请参考 llama_jni.cpp 末尾的"新 API 适配提示"做少量调整。

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET_DIR="$ROOT/app/src/main/jni/llama.cpp"

# 锁定一个较稳定的提交，避免上游频繁变动导致 JNI 编译失败。
# 如需最新特性，可改为 master 或指定其它提交。
LLAMA_COMMIT="${LLAMA_COMMIT:-b3746}"

if [ -d "$TARGET_DIR/.git" ]; then
    echo "[build_llama] 已存在 llama.cpp，跳过克隆（如需更新请删除该目录）。"
    exit 0
fi

if [ -d "$TARGET_DIR" ]; then
    echo "[build_llama] 目标目录已存在但非 git 仓库：$TARGET_DIR"
    exit 1
fi

echo "[build_llama] 克隆 llama.cpp 并检出 $LLAMA_COMMIT ..."
git clone --depth 1 https://github.com/ggerganov/llama.cpp.git "$TARGET_DIR"
git -C "$TARGET_DIR" fetch --depth 1 origin "$LLAMA_COMMIT"
git -C "$TARGET_DIR" checkout "$LLAMA_COMMIT"

echo "[build_llama] 完成。现在可在 Android Studio 中构建 APK（需要 NDK）。"
echo "[build_llama] 若仅想体验 UI 与自动化闭环而不编译原生库，"
echo "              可注释掉 app/build.gradle.kts 中的 externalNativeBuild 与 ndk 块，"
echo "              应用会自动使用内置的 StubEngine。"
