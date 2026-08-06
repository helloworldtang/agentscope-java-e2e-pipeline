#!/usr/bin/env bash
# 初始化本地 git skill 仓（模拟「agent 管理平台」维护的 skill 存储）。
# WeChatPublisher 通过 SKILL_GIT_URL=file://<本仓> 用 GitSkillRepository 从它加载 skill，
# 演示「平台下发 skill → agent 拉取」。
set -euo pipefail

# 平台仓放在项目的同级目录（项目外，不污染 demo 仓库）
STORE_DIR="${SKILL_STORE_DIR:-$(cd "$(dirname "$0")/.." && pwd)/agentscope-skill-store}"

rm -rf "$STORE_DIR"
mkdir -p "$STORE_DIR"
cd "$STORE_DIR"
git init -q
git config user.email "platform@local" 2>/dev/null || true
git config user.name "skill-platform" 2>/dev/null || true

# 平台下发初始 skill A：platform-greet
mkdir -p skills/platform-greet
cat > skills/platform-greet/SKILL.md <<'EOF'
---
name: platform-greet
description: Use this skill when you need a short WeChat-style greeting line. 平台下发示例 skill A。
---
# 平台问候语 skill（示例 A）

生成一句简短、有温度的公众号问候语。

## 要领
- 用「你」称呼读者；
- 1 句话，不超过 20 字；
- 点出一个与正文主题相关的钩子。
EOF

git add -A
git commit -qm "platform: 下发初始 skill platform-greet"

URL="file://$STORE_DIR"
echo "✓ 平台 skill 仓已初始化: $STORE_DIR"
echo "  当前已下发 skill: platform-greet"
echo ""
echo "下一步（把这行加到 .env 或直接 export）："
echo "  export SKILL_GIT_URL=$URL"
echo ""
echo "快速验证平台下发（不跑 agent）："
echo "  PRINT_SKILLS_ONLY=1 mvn -q exec:java"
