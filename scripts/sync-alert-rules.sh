#!/usr/bin/env bash
# 同步告警规则到 Prometheus Chart（模板 → 部署产物）
#
# 为什么需要：规则的事实来源是 platform/observability/rules/*.yaml，但 Helm Chart
# 只能读取自身目录内的文件（.Files 不支持 chart 外路径），因此需要一份副本；
#
# 为什么是"渲染"而不是"复制"：源文件是带 {{tenant_id}} / {{fail_threshold}} 等契约
# 变量的**模板**。占位符原样进 Prometheus 时是合法的 PromQL 字符串字面量，语法检查过
# 得去、但匹配不到任何序列 → 规则永久静默（本仓此前有 20 处占位符随副本进 chart）。
# 故 chart 副本一律存 scripts/render-tenant-alert-rules.py 的平台侧渲染产物
# （tenant_id=~".+"，按 tenant_id 分组对全部租户生效）。
#
# 用法：
#   bash scripts/sync-alert-rules.sh            # 渲染并覆盖 chart 内副本
#   bash scripts/sync-alert-rules.sh --check    # 只校验一致性，有漂移则退出 1
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="$REPO_ROOT/platform/observability/rules"
DST_DIR="$REPO_ROOT/design/deploy/charts/prometheus/files/rules"
RENDERER="$REPO_ROOT/scripts/render-tenant-alert-rules.py"

MODE="${1:---sync}"
if [[ "$MODE" != "--sync" && "$MODE" != "--check" ]]; then
  echo "用法: bash scripts/sync-alert-rules.sh [--sync|--check]" >&2
  exit 2
fi

if [[ ! -d "$SRC_DIR" ]]; then
  echo "ERROR: 规则目录不存在: $SRC_DIR" >&2
  exit 2
fi

# 只渲染生效规则（rules/ 根下的 *.yaml），pending/ 子目录不加载
mapfile -t SRC_FILES < <(find "$SRC_DIR" -maxdepth 1 -name '*.yaml' -type f | sort)

if [[ ${#SRC_FILES[@]} -eq 0 ]]; then
  echo "ERROR: $SRC_DIR 下没有规则文件" >&2
  exit 2
fi

# 先把模板渲染到临时目录，chart 侧比对/落盘的基准都是渲染产物
# （Windows 开发机常只有 `python`，CI 只有 `python3`，故两者都试）
PY="$(command -v python3 || command -v python || true)"
if [[ -z "$PY" ]]; then
  echo "ERROR: 未找到 python3/python，无法渲染告警规则模板" >&2
  exit 2
fi
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
if ! "$PY" "$RENDERER" --rules-dir "$SRC_DIR" --out-dir "$TMP_DIR"; then
  echo "ERROR: 模板渲染失败，chart 副本未更新" >&2
  exit 1
fi
mapfile -t RENDERED < <(find "$TMP_DIR" -maxdepth 1 -name '*.yaml' -type f | sort)

fail=0
if [[ "$MODE" == "--check" ]]; then
  for src in "${RENDERED[@]}"; do
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
  # 反向不变量：chart 副本的**数据字段**里不允许残留任何契约占位符（否则又是一批死规则）。
  # 注释里保留 {{tenant_id}} 是刻意的（渲染器不改注释，便于人读），故先剔除注释行。
  bad_placeholder=0
  for dst in "$DST_DIR"/*.yaml; do
    [[ -e "$dst" ]] || continue
    if awk '!/^[[:space:]]*#/' "$dst" | grep -qE '\{\{[a-z_][a-z0-9_]*\}\}'; then
      echo "FAIL: $(basename "$dst") 数据字段含未渲染占位符，Prometheus 加载后相关规则永不触发："
      awk '!/^[[:space:]]*#/' "$dst" | grep -oE '\{\{[a-z_][a-z0-9_]*\}\}' | sort -u | sed 's/^/        /'
      bad_placeholder=1
    fi
  done
  if [[ "$bad_placeholder" -eq 1 ]]; then
    fail=1
  fi

  if [[ "$fail" -eq 0 ]]; then
    echo "OK: ${#RENDERED[@]} 个规则文件与 chart 渲染副本一致（且无残留占位符）"
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

for src in "${RENDERED[@]}"; do
  name="$(basename "$src")"
  cp -f "$src" "$DST_DIR/$name"
  echo "synced   files/rules/$name"
done

echo "完成：${#RENDERED[@]} 个规则文件已渲染并同步到 chart"
