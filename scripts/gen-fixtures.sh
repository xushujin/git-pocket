#!/usr/bin/env bash
# 生成一个用于手工验证的示例仓库（含合并历史、二进制文件、大文件）。
# 用法：./scripts/gen-fixtures.sh [目标目录]
set -euo pipefail

TARGET="${1:-/tmp/gitpocket-fixtures}"
rm -rf "$TARGET"
mkdir -p "$TARGET"
cd "$TARGET"

git init -q -b main repo
cd repo
git config user.name "Fixture"
git config user.email "fixture@example.com"

printf '# Fixture\n\n用于验证 GitPocket 的解析与 diff。\n' > README.md
mkdir -p src docs
printf 'fun main() {\n    println("v1")\n}\n' > src/main.kt
printf 'first\nsecond\nthird\n' > docs/notes.md
git add -A && git commit -q -m "chore: 初始提交"

printf 'fun main() {\n    println("v2")\n}\n' > src/main.kt
git add -A && git commit -q -m "feat: 修改输出"

git checkout -q -b feature/branch
printf 'feature line\n' >> docs/notes.md
git add -A && git commit -q -m "feat: 分支上的改动"

git checkout -q main
printf 'main line\n' >> docs/notes.md
git add -A && git commit -q -m "fix: 主线上的改动"

git merge feature/branch -m "merge: 合并 feature/branch" || true

head -c 4096 /dev/urandom > assets.bin 2>/dev/null || true
git add -A 2>/dev/null || true
git commit -q -m "chore: 添加二进制文件" 2>/dev/null || true

git gc --aggressive --prune=now

echo "示例仓库已生成：$TARGET/repo"
echo "验证：git -C $TARGET/repo fsck --strict && git -C $TARGET/repo log --oneline"
