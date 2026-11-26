#!/usr/bin/env bash
# 把 backend/web 同步到 android/app/src/main/assets。
#
# ## 为什么需要这个脚本
# 网页控制台有**两份物理副本**：
#   · backend/web/                     — 后端和 iOS 用
#   · android/app/src/main/assets/     — Android App 用（WebViewAssetLoader 从 assets 读）
#
# 之前靠人工 cp，两边一旦不同步就会分叉：Android 用户和电脑端看到不同的界面。
# 这个脚本是唯一的同步入口，CI 和本地都跑它。
#
# 用法：
#   scripts/sync_web.sh           # 后端 → Android
#   scripts/sync_web.sh --check   # 只检查是否一致，不一致就非零退出（CI 用）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/backend/web"
DST="$ROOT/android/app/src/main/assets"

# 同步范围：backend/web 下的**全部文件**。原来是硬编码 8 个，
# 新加静态文件时既不同步也不检测，等于悄悄分叉。
# 例外：什么都不排除 —— 后端 web 目录本来就只放前端资源。
# 兼容 macOS：bash 3.2 没有 mapfile，BSD find 没有 -printf。
# 用 while read + find -type f 的 POSIX 写法。
FILES=()
while IFS= read -r f; do
  [[ -n "$f" ]] && FILES+=("$f")
done < <(cd "$SRC" && find . -type f | sed 's|^\./||' | sort)

check=0
[[ "${1:-}" == "--check" ]] && check=1

diffs=0
missing=0
for f in "${FILES[@]}"; do
  if [[ ! -f "$SRC/$f" ]]; then
    echo "  [缺] backend/web/$f 不存在"
    missing=1
    continue
  fi
  if [[ $check -eq 1 ]]; then
    if ! cmp -s "$SRC/$f" "$DST/$f"; then
      echo "  [分叉] $f"
      diffs=1
    fi
  else
    mkdir -p "$(dirname "$DST/$f")"
    cp -f "$SRC/$f" "$DST/$f"
  fi
done


# 反向检查：Android assets 里有、backend/web 里没有的文件也算分叉
if [[ $check -eq 1 && -d "$DST" ]]; then
  while IFS= read -r f; do
    [[ -e "$SRC/$f" ]] || { echo "  [多余] android/assets/$f"; diffs=1; }
  done < <(cd "$DST" && find . -type f | sed 's|^\./||' | sort)
fi

if [[ $check -eq 1 ]]; then
  if [[ $missing -eq 1 || $diffs -eq 1 ]]; then
    echo ""
    echo "网页资源已分叉：先跑 scripts/sync_web.sh 再提交。"
    exit 1
  fi
  echo "网页资源一致（${#FILES[@]} 个文件）"
else
  echo "已同步 backend/web → android/app/src/main/assets"
fi
