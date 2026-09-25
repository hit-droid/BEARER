#!/usr/bin/env bash
#
# 一键发布脚本（在【能访问 GitHub】的机器上运行，例如你本机）。
#
# 作用：
#   1. 用你的 GitHub 令牌登录 gh
#   2. 新建（或复用）名为 BEARER 的公开仓库
#   3. 初始化 git、提交、推送到 main
#   4. 基于 tag v0.1.0 创建 GitHub Release（正文取自 RELEASE_NOTES.md）
#
# 用法：
#   GITHUB_TOKEN=ghp_xxx bash scripts/publish.sh
#   或
#   bash scripts/publish.sh ghp_xxx
#
# 注意：本脚本会把当前目录全部文件提交并推送，请确认在仓库根目录执行。
set -euo pipefail

TOKEN="${1:-${GITHUB_TOKEN:-}}"
REPO_NAME="BEARER"
TAG="v0.1.0"
VISIBILITY="--public"   # 改为 --private 可创建私有仓库

if [[ -z "$TOKEN" ]]; then
  echo "错误：未提供 GitHub 令牌。请用 GITHUB_TOKEN=... 或第一个参数传入。" >&2
  exit 1
fi

echo "==> 使用 gh 登录"
echo "$TOKEN" | gh auth login --with-token

echo "==> 确保 gh 已认证"
gh auth status

# 创建仓库（已存在则忽略错误）
echo "==> 创建仓库 $REPO_NAME"
gh repo create "$REPO_NAME" $VISIBILITY --description "离线安卓任务自动化智能体（本地 LLM + 无障碍服务，全程不联网）" || \
  echo "（仓库可能已存在，继续）"

# git 初始化与提交
echo "==> 初始化并提交"
if [[ ! -d .git ]]; then
  git init -q
  git branch -M main
fi

git add -A
if git diff --cached --quiet; then
  echo "（无新改动，跳过提交）"
else
  git commit -q -m "Release $TAG: 离线安卓任务自动化智能体"
fi

# 远程与推送
REMOTE_URL="https://github.com/$(gh api user --jq .login)/$REPO_NAME.git"
if ! git remote | grep -q '^origin$'; then
  git remote add origin "$REMOTE_URL"
fi
echo "==> 推送到 origin ($REMOTE_URL)"
git push -u origin main

# 创建 Release
echo "==> 创建 GitHub Release $TAG"
if gh release view "$TAG" >/dev/null 2>&1; then
  echo "（Release $TAG 已存在，跳过创建）"
else
  gh release create "$TAG" \
    --title "OfflineAgent $TAG" \
    --notes-file RELEASE_NOTES.md \
    --target main
fi

echo "==> 完成。仓库地址：https://github.com/$(gh api user --jq .login)/$REPO_NAME"
