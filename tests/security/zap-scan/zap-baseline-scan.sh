#!/usr/bin/env bash
# =============================================================================
# ZAP 基线扫描（被动扫描）—— DataEngineBDP
# -----------------------------------------------------------------------------
# 仅对代理/爬虫捕获的流量做被动扫描，不发送任何攻击载荷，适合 CI 流水线快速检查。
#
# 用法：
#   ./zap-baseline-scan.sh [-t target] [-r report.html] [-j report.json]
#
# 默认目标：http://localhost:18086
# 报告输出：../reports/zap-baseline-{html,json,md}
# =============================================================================
set -euo pipefail

TARGET="${TARGET:-http://localhost:18086}"
REPORT_DIR="$(cd "$(dirname "$0")/.." && pwd)/reports"
mkdir -p "$REPORT_DIR"
TS="$(date +%Y%m%d-%H%M%S)"

HTML_OUT="${REPORT_DIR}/zap-baseline-${TS}.html"
JSON_OUT="${REPORT_DIR}/zap-baseline-${TS}.json"
MD_OUT="${REPORT_DIR}/zap-baseline-${TS}.md"

# ZAP 容器镜像（官方 weekly）
ZAP_IMAGE="${ZAP_IMAGE:-ghcr.io/zaproxy/zaproxy:weekly}"

echo "========================================"
echo " ZAP 基线扫描（被动）"
echo " 目标: $TARGET"
echo " 报告: $REPORT_DIR"
echo "========================================"

# 通过 Docker 运行 ZAP，使用 -cmd 一次性模式
docker run --rm -t \
  -v "$REPORT_DIR:/zap/reports:rw" \
  "$ZAP_IMAGE" \
  zap-baseline.py \
    -t "$TARGET" \
    -h "report-baseline.html" \
    -j "report-baseline.json" \
    -r "report-baseline.md" \
    -c "$(cd "$(dirname "$0")" && pwd)/zap-policy/baseline.policy" \
    --context "$(cd "$(dirname "$0")" && pwd)/zap-context.xml" \
    -I || true

# 重命名输出（追加时间戳）
[ -f "$REPORT_DIR/report-baseline.html" ] && mv "$REPORT_DIR/report-baseline.html" "$HTML_OUT"
[ -f "$REPORT_DIR/report-baseline.json" ] && mv "$REPORT_DIR/report-baseline.json" "$JSON_OUT"
[ -f "$REPORT_DIR/report-baseline.md" ]   && mv "$REPORT_DIR/report-baseline.md"   "$MD_OUT"

echo "----------------------------------------"
echo " 扫描完成"
echo " HTML: $HTML_OUT"
echo " JSON: $JSON_OUT"
echo " MD  : $MD_OUT"
echo "----------------------------------------"

# 解析 JSON 报告，提取告警数
if command -v jq >/dev/null 2>&1 && [ -f "$JSON_OUT" ]; then
  HIGH=$(jq '.site[0].alerts | map(select(.riskcode == "3")) | length' "$JSON_OUT" 2>/dev/null || echo "?")
  MED=$(jq  '.site[0].alerts | map(select(.riskcode == "2")) | length' "$JSON_OUT" 2>/dev/null || echo "?")
  LOW=$(jq  '.site[0].alerts | map(select(.riskcode == "1")) | length' "$JSON_OUT" 2>/dev/null || echo "?")
  echo " 风险统计: HIGH=$HIGH  MEDIUM=$MED  LOW=$LOW"
  if [ "$HIGH" != "0" ] && [ "$HIGH" != "?" ]; then
    echo "❌ 发现 HIGH 风险告警，请检查报告"
    exit 2
  fi
fi

exit 0