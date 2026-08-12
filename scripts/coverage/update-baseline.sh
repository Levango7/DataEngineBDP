#!/usr/bin/env bash
# 覆盖率基线更新脚本
# 用法：bash scripts/coverage/update-baseline.sh [java|go|python|all]
# 功能：从当前构建结果读取各模块覆盖率，写入 docs/coverage-baseline/{lang}.json
# 注意：仅在覆盖率提升后执行此脚本更新基线，切勿在覆盖率下降时更新
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASELINE_DIR="$PROJECT_ROOT/docs/coverage-baseline"

mkdir -p "$BASELINE_DIR"

LANG="${1:-all}"

update_java() {
    echo "更新 Java 覆盖率基线..."
    local out="$BASELINE_DIR/java.json"
    echo "{" > "$out"
    echo "  \"_comment\": \"Java 模块覆盖率基线（自动生成 $(date -u +%Y-%m-%dT%H:%M:%SZ)）\"," >> "$out"
    local first=1
    while IFS= read -r pom; do
        local mod_dir mod_name jacoco_xml
        mod_dir=$(dirname "$pom")
        mod_name=$(basename "$mod_dir")
        jacoco_xml="$mod_dir/target/site/jacoco/jacoco.xml"
        if [ ! -f "$jacoco_xml" ]; then
            continue
        fi
        local line_missed line_covered line_total line_pct
        line_missed=$(grep -oP '<counter type="LINE"[^>]*missed="\K[0-9]+' "$jacoco_xml" | tail -1)
        line_covered=$(grep -oP '<counter type="LINE"[^>]*covered="\K[0-9]+' "$jacoco_xml" | tail -1)
        line_total=$((line_missed + line_covered))
        if [ "$line_total" -gt 0 ]; then
            line_pct=$((line_covered * 100 / line_total))
        else
            line_pct=0
        fi
        if [ "$first" -eq 1 ]; then
            first=0
        else
            echo "," >> "$out"
        fi
        printf '  "%s": %s' "$mod_name" "$line_pct" >> "$out"
    done < <(find "$PROJECT_ROOT/platform" -name pom.xml -not -path "*/target/*")
    echo "" >> "$out"
    echo "}" >> "$out"
    echo "✓ Java 基线已写入 $out"
}

update_go() {
    echo "更新 Go 覆盖率基线..."
    local out="$BASELINE_DIR/go.json"
    echo "{" > "$out"
    echo "  \"_comment\": \"Go 模块覆盖率基线（自动生成 $(date -u +%Y-%m-%dT%H:%M:%SZ)）\"," >> "$out"
    local first=1
    while IFS= read -r gomod; do
        local mod_dir mod_name cov
        mod_dir=$(dirname "$gomod")
        mod_name=$(basename "$mod_dir")
        cov=$(cd "$mod_dir" && go test -cover ./... 2>/dev/null | grep "^total:" | awk '{print $NF}' | tr -d '%' || echo "0")
        if [ -z "$cov" ]; then
            cov="0"
        fi
        if [ "$first" -eq 1 ]; then
            first=0
        else
            echo "," >> "$out"
        fi
        printf '  "%s": %s' "$mod_name" "$cov" >> "$out"
    done < <(find "$PROJECT_ROOT/platform" -name go.mod -not -path "*/vendor/*")
    echo "" >> "$out"
    echo "}" >> "$out"
    echo "✓ Go 基线已写入 $out"
}

update_python() {
    echo "更新 Python 覆盖率基线..."
    local out="$BASELINE_DIR/python.json"
    echo "{" > "$out"
    echo "  \"_comment\": \"Python 模块覆盖率基线（自动生成 $(date -u +%Y-%m-%dT%H:%M:%SZ)）\"," >> "$out"
    local first=1
    while IFS= read -r mod_dir; do
        local mod_name cov
        mod_name=$(basename "$mod_dir")
        cov=$(cd "$mod_dir" && python -m pytest tests/ --cov=. --cov-report=term-missing --no-header -q 2>&1 | grep -E "^TOTAL" | awk '{print $NF}' | tr -d '%' || echo "0")
        if [ -z "$cov" ]; then
            cov="0"
        fi
        if [ "$first" -eq 1 ]; then
            first=0
        else
            echo "," >> "$out"
        fi
        printf '  "%s": %s' "$mod_name" "$cov" >> "$out"
    done < <(find "$PROJECT_ROOT/platform" -name pyproject.toml -not -path "*/node_modules/*")
    echo "" >> "$out"
    echo "}" >> "$out"
    echo "✓ Python 基线已写入 $out"
}

case "$LANG" in
    java)   update_java ;;
    go)     update_go ;;
    python) update_python ;;
    all)
        update_java
        update_go
        update_python
        ;;
    *)
        echo "用法：bash $0 [java|go|python|all]"
        exit 1
        ;;
esac

echo "完成。提交基线更新：git add docs/coverage-baseline/ && git commit -m 'chore(coverage): update baseline'"