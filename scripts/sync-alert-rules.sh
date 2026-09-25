#!/usr/bin/env bash
# 同步告警规则到 Prometheus Chart（单一来源 → 部署产物）
#
# 为什么需要：规则的事实来源是 platform/observability/rules/*.yaml，但 Helm Chart
# 只能读取自身目录内的文件（.Files 不支持 chart 外路径），因此需要一份副本；
# 本脚本负责复制，--check 模式供 CI 校验两处不漂移。
#
# 用法：
#   bash scripts/sync-alert-rules.sh            # 同步（覆盖 chart 内副本）
#   bash scripts/sync-alert-rules.sh --check    # 只校验一致性，有漂移则退出 1
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="$REPO_ROOT/platform/observability/rules"
DST_DIR="$REPO_ROOT/design/deploy/charts/prometheus/files/rules"

MODE="${1:---sync}"
if [[ "$MODE" != "--sync" && "$MODE" != "--check" ]]; then
  echo "用法: bash scripts/sync-alert-rules.sh [--sync|--check]" >&2
  exit 2
fi

if [[ ! -d "$SRC_DIR" ]]; then
  echo "ERROR: 规则目录不存在: $SRC_DIR" >&2
  exit 2
fi

# 只同步生效规则（rules/ 根下的 *.yaml），pending/ 子目录不加载
mapfile -t SRC_FILES < <(find "$SRC_DIR" -maxdepth 1 -name '*.yaml' -type f | sort)

if [[ ${#SRC_FILES[@]} -eq 0 ]]; then
  echo "ERROR: $SRC_DIR 下没有规则文件" >&2
  exit 2
fi

fail=0
if [[ "$MODE" == "--check" ]]; then
  for src in "${SRC_FILES[@]}"; do
    name="$(basename "$src")"
    dst="$DST_DIR/$name"
    if [[ ! -f "$dst" ]]; then
      echo "FAIL: chart 内缺少规则副本: files/rules/$name（运行 bash scripts/sync-alert-rules.sh 同步）"
      fail=1
    elif ! diff -q "$src" "$dst" >/dev/null; then
      echo "FAIL: 规则与 chart 副本不一致: $name（运行 bash scripts/sync-alert-rules.sh 同步）"
      fail=1
    fi
  done
  # chart 内多余文件（源已删但副本还在）
  while IFS= read -r dst; do
    name="$(basename "$dst")"
    if [[ ! -f "$SRC_DIR/$name" ]]; then
      echo "FAIL: chart 内存在源目录已删除的规则副本: files/rules/$name"
      fail=1
    fi
  done < <(find "$DST_DIR" -maxdepth 1 -name '*.yaml' -type f 2>/dev/null | sort)

  if [[ "$fail" -eq 0 ]]; then
    echo "OK: ${#SRC_FILES[@]} 个规则文件与 chart 副本一致"
  fi
  exit "$fail"
fi

mkdir -p "$DST_DIR"
# 清理源目录已删除的旧副本，保证 chart 不加载已废弃规则
for dst in "$DST_DIR"/*.yaml; do
  [[ -e "$dst" ]] || continue
  name="$(basename "$dst")"
  if [[ ! -f "$SRC_DIR/$name" ]]; then
    rm -f "$dst"
    echo "removed  files/rules/$name"
  fi
done

for src in "${SRC_FILES[@]}"; do
  name="$(basename "$src")"
  cp -f "$src" "$DST_DIR/$name"
  echo "synced   files/rules/$name"
done

echo "完成：$((${#SRC_FILES[@]})) 个规则文件已同步到 chart"
