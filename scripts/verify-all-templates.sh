#!/usr/bin/env bash
# ============================================================================
# verify-all-templates.sh — 9 套行业模板 Helm Chart 全量验证
# ----------------------------------------------------------------------------
# 用途：对 platform/industry-templates/charts/ 下全部行业模板 chart 执行：
#   1) helm lint  —— Chart 语法/结构校验
#   2) helm template —— 模板渲染校验（不依赖集群，纯本地渲染）
# 并输出验证结果汇总（PASS/FAIL 计数 + 失败明细）。
#
# 触发场景：
#   - 本地开发：./scripts/verify-all-templates.sh
#   - CI 流水线：在 helm-lint job 之后追加本脚本作为行业模板专属门禁
#
# 退出码：
#   0 = 全部通过
#   1 = 存在失败（lint 或 template 任一失败）
#   2 = 环境错误（helm 未安装 / charts 目录不存在）
#
# 依赖：helm >= 3.10
# ============================================================================
set -uo pipefail

# ---------- 定位仓库根（兼容任意调用 cwd） ----------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CHARTS_DIR="$REPO_ROOT/platform/industry-templates/charts"

# ---------- 前置检查 ----------
if ! command -v helm >/dev/null 2>&1; then
  echo "::error::helm 未安装，请先安装 Helm 3.10+"
  exit 2
fi

if [ ! -d "$CHARTS_DIR" ]; then
  echo "::error::charts 目录不存在：$CHARTS_DIR"
  exit 2
fi

# ---------- 初始化计数 ----------
LINT_PASS=0
LINT_FAIL=0
RENDER_PASS=0
RENDER_FAIL=0
FAIL_LOG="$(mktemp)"
trap 'rm -f "$FAIL_LOG"' EXIT

# 期望的 9 套行业模板（用于完整性校验）
EXPECTED_TEMPLATES=(
  agriculture-template
  education-template
  energy-template
  finance-template
  government-template
  manufacturing-template
  medical-template
  retail-template
  transportation-template
)

echo "============================================================"
echo " 行业模板 Helm Chart 全量验证"
echo " charts 目录: $CHARTS_DIR"
echo " helm 版本:   $(helm version --short 2>/dev/null || echo 'unknown')"
echo "============================================================"
echo ""

# ---------- 完整性校验：确认 9 套模板齐全 ----------
echo "[1/3] 模板完整性校验"
echo "------------------------------------------------------------"
MISSING=0
for expected in "${EXPECTED_TEMPLATES[@]}"; do
  if [ ! -f "$CHARTS_DIR/$expected/Chart.yaml" ]; then
    echo "  ✗ 缺失模板: $expected"
    MISSING=$((MISSING + 1))
  else
    echo "  ✓ $expected"
  fi
done
echo ""

if [ "$MISSING" -gt 0 ]; then
  echo "::error::缺失 $MISSING 套行业模板（期望 9 套）"
  exit 1
fi

echo "  全部 9 套行业模板齐全 ✓"
echo ""

# ---------- helm lint 阶段 ----------
echo "[2/3] helm lint —— Chart 语法校验"
echo "------------------------------------------------------------"
for chart_dir in "$CHARTS_DIR"/*/; do
  if [ ! -f "${chart_dir}Chart.yaml" ]; then
    continue
  fi
  chart_name="$(basename "$chart_dir")"
  if helm lint "$chart_dir" -q >/dev/null 2>&1; then
    echo "  ✓ $chart_name lint 通过"
    LINT_PASS=$((LINT_PASS + 1))
  else
    echo "  ✗ $chart_name lint 失败"
    helm lint "$chart_dir" 2>&1 | sed 's/^/      /'
    LINT_FAIL=$((LINT_FAIL + 1))
    echo "LINT-FAIL :: $chart_name" >> "$FAIL_LOG"
  fi
done
echo ""

# ---------- helm template 阶段 ----------
echo "[3/3] helm template —— 模板渲染校验"
echo "------------------------------------------------------------"
for chart_dir in "$CHARTS_DIR"/*/; do
  if [ ! -f "${chart_dir}Chart.yaml" ]; then
    continue
  fi
  chart_name="$(basename "$chart_dir")"
  # 渲染到临时文件，避免空输出误判
  render_out="$(mktemp)"
  if helm template "$chart_name" "$chart_dir" --namespace template-verify >"$render_out" 2>/dev/null; then
    if [ -s "$render_out" ]; then
      echo "  ✓ $chart_name 渲染通过"
      RENDER_PASS=$((RENDER_PASS + 1))
    else
      echo "  ✗ $chart_name 渲染输出为空"
      RENDER_FAIL=$((RENDER_FAIL + 1))
      echo "RENDER-EMPTY :: $chart_name" >> "$FAIL_LOG"
    fi
  else
    echo "  ✗ $chart_name 渲染失败"
    helm template "$chart_name" "$chart_dir" --namespace template-verify 2>&1 | head -5 | sed 's/^/      /'
    RENDER_FAIL=$((RENDER_FAIL + 1))
    echo "RENDER-FAIL :: $chart_name" >> "$FAIL_LOG"
  fi
  rm -f "$render_out"
done
echo ""

# ---------- 汇总 ----------
TOTAL_CHARTS=$((LINT_PASS + LINT_FAIL))
TOTAL_FAIL=$((LINT_FAIL + RENDER_FAIL))

echo "============================================================"
echo " 验证结果汇总"
echo "============================================================"
echo "  模板总数:     $TOTAL_CHARTS"
echo "  helm lint:    $LINT_PASS 通过 / $LINT_FAIL 失败"
echo "  helm template: $RENDER_PASS 通过 / $RENDER_FAIL 失败"
echo "  总失败数:     $TOTAL_FAIL"
echo ""

if [ "$TOTAL_FAIL" -gt 0 ]; then
  echo " 失败明细:"
  sed 's/^/   - /' "$FAIL_LOG"
  echo ""
  echo "::error::共有 $TOTAL_FAIL 项验证失败，请修复后重试"
  exit 1
fi

echo " ✓ 全部 $TOTAL_CHARTS 套行业模板验证通过"
exit 0