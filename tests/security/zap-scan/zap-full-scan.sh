#!/usr/bin/env bash
# =============================================================================
# ZAP 完整扫描（主动 + 被动）—— DataEngineBDP
# -----------------------------------------------------------------------------
# 1. 蜘蛛（spider）爬取所有 API 路由
# 2. 主动扫描：对每个参数注入攻击载荷（SQL/XSS/路径遍历/命令注入等）
# 3. 被动扫描：对爬取流量做规则匹配
# 4. 输出 HTML/JSON/MD 三种格式报告
#
# 注意：主动扫描会向目标发送攻击载荷，仅在测试环境运行！
#
# 用法：
#   ./zap-full-scan.sh [-t target] [-u user] [-p pass]
# =============================================================================
set -euo pipefail

TARGET="${TARGET:-http://localhost:18086}"
USERNAME="${USERNAME:-admin}"
PASSWORD="${PASSWORD:-admin}"
REPORT_DIR="$(cd "$(dirname "$0")/.." && pwd)/reports"
mkdir -p "$REPORT_DIR"
TS="$(date +%Y%m%d-%H%M%S)"

HTML_OUT="$REPORT_DIR/zap-full-${TS}.html"
JSON_OUT="$REPORT_DIR/zap-full-${TS}.json"
MD_OUT="$REPORT_DIR/zap-full-${TS}.md"

ZAP_IMAGE="${ZAP_IMAGE:-ghcr.io/zaproxy/zaproxy:weekly}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "========================================"
echo " ZAP 完整扫描（主动 + 被动）"
echo " 目标: $TARGET"
echo " 账号: $USERNAME"
echo " 报告: $REPORT_DIR"
echo "========================================"

# 警告确认
if [ "${FORCE:-0}" != "1" ]; then
  echo "⚠️  主动扫描将向目标发送攻击载荷，仅在测试环境运行！"
  read -r -p "确认继续？[y/N] " ans
  case "$ans" in
    y|Y|yes|YES) ;;
    *) echo "已取消"; exit 1 ;;
  esac
fi

# 通过 Docker 运行 ZAP 完整扫描
docker run --rm -t \
  -v "$REPORT_DIR:/zap/reports:rw" \
  -v "$SCRIPT_DIR:/zap/wrk:ro" \
  "$ZAP_IMAGE" \
  zap-full-scan.py \
    -t "$TARGET" \
    -h "report-full.html" \
    -j "report-full.json" \
    -r "report-full.md" \
    -c "$SCRIPT_DIR/zap-policy/full.policy" \
    --context "$SCRIPT_DIR/zap-context.xml" \
    --user "$USERNAME" \
    --pass "$PASSWORD" \
    -I \
    -T 5 || true  # 5 分钟超时

# 重命名输出
[ -f "$REPORT_DIR/report-full.html" ] && mv "$REPORT_DIR/report-full.html" "$HTML_OUT"
[ -f "$REPORT_DIR/report-full.json" ] && mv "$REPORT_DIR/report-full.json" "$JSON_OUT"
[ -f "$REPORT_DIR/report-full.md" ]   && mv "$REPORT_DIR/report-full.md"   "$MD_OUT"

echo "----------------------------------------"
echo " 扫描完成"
echo " HTML: $HTML_OUT"
echo " JSON: $JSON_OUT"
echo " MD  : $MD_OUT"
echo "----------------------------------------"

# 风险统计
if command -v jq >/dev/null 2>&1 && [ -f "$JSON_OUT" ]; then
  HIGH=$(jq '.site[0].alerts | map(select(.riskcode == "3")) | length' "$JSON_OUT" 2>/dev/null || echo "?")
  MED=$(jq  '.site[0].alerts | map(select(.riskcode == "2")) | length' "$JSON_OUT" 2>/dev/null || echo "?")
  LOW=$(jq  '.site[0].alerts | map(select(.riskcode == "1")) | length' "$JSON_OUT" 2>/dev/null || echo "?")
  INFO=$(jq '.site[0].alerts | map(select(.riskcode == "0")) | length' "$JSON_OUT" 2>/dev/null || echo "?")
  echo " 风险统计: HIGH=$HIGH  MEDIUM=$MED  LOW=$LOW  INFO=$INFO"
  if [ "$HIGH" != "0" ] && [ "$HIGH" != "?" ]; then
    echo "❌ 发现 HIGH 风险漏洞，请检查报告并修复"
    exit 2
  fi
fi

exit 0