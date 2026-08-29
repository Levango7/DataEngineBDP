#!/usr/bin/env bash
# 生产交付物 Mock 烘焙校验：拦截"镜像/生产 chart 默认 Mock"回归。
#
# 背景（2026-08-29 P0-2）：knowledge-engine Dockerfile 曾烘焙 KE_STORE_TYPE=mock、
# registry chart 未覆盖组件代码默认的 mock_mode=true 等，导致生产部署静默拿到假实现。
# 规则：
#   1. 运行时 Dockerfile 禁止烘焙 *MOCK* / mock 类环境变量默认值（显式标注允许清单除外）
#   2. 生产 chart values 默认 env 禁止出现 mock 取值（REGISTRY_MOCK_MODE=true、
#      KE_STORE_TYPE=mock、LOOP_DEV_MODE=true 等）
#   3. 集成测试/本地开发配置（tests/、deploy/local/、design/deploy/values/env/dev）
#      显式声明 mock 属预期，不在本门禁范围。
set -euo pipefail

errors=0

# ---- 规则 1：运行时 Dockerfile 不得烘焙 mock 环境变量 ----
while IFS= read -r -d '' dockerfile; do
  # 只检查 ENV 行里的 mock 取值（RUN 阶段/参数化构建不算）
  if grep -nE '^[[:space:]]*ENV.*\b(mock|MOCK_MODE=true|DEV_MODE=true)\b' "$dockerfile" 2>/dev/null \
     | grep -viE 'comment|说明|注释' >/dev/null; then
    echo "::error file=$dockerfile::Dockerfile ENV 烘焙了 mock/开发模式默认值（生产交付物禁止；本地降级由部署环境显式注入）"
    grep -nE '^[[:space:]]*ENV.*\b(mock|MOCK_MODE=true|DEV_MODE=true)\b' "$dockerfile"
    errors=$((errors + 1))
  fi
done < <(find platform -name Dockerfile -print0 2>/dev/null)

# ---- 规则 2：生产 chart values 默认 env 禁止 mock 取值 ----
# 受检开关清单：键 → 允许的生产取值（除注释行外出现其他取值即报错）
check_chart() {
  local chart="$1" key="$2" bad_value_regex="$3"
  local file="design/deploy/charts/${chart}/values.yaml"
  [ -f "$file" ] || return 0
  if grep -vE '^\s*#' "$file" | grep -E "${key}:[[:space:]]*${bad_value_regex}" >/dev/null 2>&1; then
    echo "::error file=$file::生产 chart ${chart} 的 ${key} 出现 mock 取值"
    errors=$((errors + 1))
  fi
}

check_chart registry        'REGISTRY_MOCK_MODE' '"?(true|True)"?'
check_chart model-finetuning 'LOOP_MOCK_MODE'   '"?(true|True)"?'
check_chart model-finetuning 'LOOP_DEV_MODE'    '"?(true|True)"?'
check_chart knowledge-engine 'KE_STORE_TYPE'    'mock'
check_chart knowledge-engine 'KE_EXTRACTOR_TYPE' 'mock'
check_chart vector-engine   'STORE_TYPE'        'mock'

if [ "$errors" -gt 0 ]; then
  echo "::error::共 $errors 处生产交付物 Mock 烘焙违规"
  exit 1
fi
echo "生产交付物 Mock 烘焙检查通过（Dockerfile + 生产 chart values）"
