#!/usr/bin/env bash
# ============================================================
# 覆盖率基线更新脚本（T-08 增强：只升不降保护 + 元数据保留）
# 用法：bash scripts/coverage/update-baseline.sh [java|go|python|all]
# 功能：
#   1. 从当前构建产物读取各模块覆盖率（JaCoCo XML / Go coverprofile / pytest-cov TOTAL）
#   2. 与现有基线比较，仅允许向上更新（新基线 ≥ 旧基线）
#   3. 向下更新直接 exit 1 并打印差异
#   4. 写回 JSON，保留 _comment/_source/_threshold_note 元数据，追加 _updated_at
# 注意：仅在覆盖率提升后执行此脚本更新基线，切勿在覆盖率下降时更新
# 来源：2026-09-10-jdk-version-mismatch-maven-multimodule-test-windows（bash 脚本在 Linux CI runner 运行）
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASELINE_DIR="$PROJECT_ROOT/docs/coverage-baseline"

mkdir -p "$BASELINE_DIR"

LANG="${1:-all}"
NOW_ISO="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# ------------------------------------------------------------
# 工具函数：从 JSON 文件读取指定 key 的值（python3 解析）
# ------------------------------------------------------------
json_get() {
    local file="$1"
    local key="$2"
    python3 -c "import json; d=json.load(open('$file')); print(d.get('$key', ''))" 2>/dev/null || echo ""
}

# ------------------------------------------------------------
# 工具函数：构造新 JSON 文件
# 保留 _comment / _source / _threshold_note 元数据，追加 _updated_at
# 入参：$1=输出文件  $2=语言名  $3=临时新数据文件（key=value 行）
# ------------------------------------------------------------
write_baseline_json() {
    local out="$1"
    local lang="$2"
    local data_file="$3"
    local existing="$out"

    # 提取既有元数据（若文件不存在则用默认值）
    local meta_comment="" meta_source="" meta_threshold=""
    if [ -f "$existing" ]; then
        meta_comment=$(json_get "$existing" "_comment")
        meta_source=$(json_get "$existing" "_source")
        meta_threshold=$(json_get "$existing" "_threshold_note")
    fi
    # 默认元数据
    if [ -z "$meta_comment" ]; then
        meta_comment="${lang} 模块覆盖率基线（自动生成 ${NOW_ISO}）"
    fi
    if [ -z "$meta_source" ]; then
        meta_source="update-baseline.sh 自动生成"
    fi
    if [ -z "$meta_threshold" ]; then
        meta_threshold="CI 趋势阻断：下降 > 2% 阻断"
    fi

    # 用 python3 安全构造 JSON（避免手拼引号转义问题）
    python3 - "$out" "$meta_comment" "$meta_source" "$meta_threshold" "$NOW_ISO" "$data_file" <<'PYEOF'
import json, sys
out = sys.argv[1]
meta_comment = sys.argv[2]
meta_source = sys.argv[3]
meta_threshold = sys.argv[4]
now = sys.argv[5]
data_file = sys.argv[6]

result = {
    "_comment": meta_comment,
    "_source": meta_source,
    "_threshold_note": meta_threshold,
    "_updated_at": now,
}
with open(data_file, 'r', encoding='utf-8') as f:
    for line in f:
        line = line.strip()
        if not line or '=' not in line:
            continue
        key, val = line.split('=', 1)
        key = key.strip()
        val = val.strip()
        try:
            result[key] = int(val)
        except ValueError:
            try:
                result[key] = float(val)
            except ValueError:
                result[key] = val

with open(out, 'w', encoding='utf-8') as f:
    json.dump(result, f, indent=2, ensure_ascii=False)
    f.write('\n')
PYEOF
}

# ------------------------------------------------------------
# 工具函数：比较新旧基线，仅允许向上更新
# 入参：$1=语言  $2=新数据文件(key=value)  $3=既有基线 JSON
# 返回：0=可更新（全部 ≥ 旧值）；1=存在下降（已打印差异）
# ------------------------------------------------------------
check_monotonic_up() {
    local lang="$1"
    local new_data="$2"
    local baseline="$3"

    if [ ! -f "$baseline" ]; then
        echo -e "${YELLOW}[INFO] $lang 基线文件不存在，首次创建无需比较${NC}"
        return 0
    fi

    local fail=0
    while IFS='=' read -r mod new_pct; do
        mod=$(echo "$mod" | xargs)
        new_pct=$(echo "$new_pct" | xargs)
        [ -z "$mod" ] || [ -z "$new_pct" ] && continue
        local old_pct
        old_pct=$(json_get "$baseline" "$mod")
        [ -z "$old_pct" ] && old_pct="0"
        # 浮点比较：new < old 则失败
        local lower
        lower=$(python3 -c "print(1 if float('$new_pct') < float('$old_pct') else 0)" 2>/dev/null || echo "0")
        if [ "$lower" = "1" ]; then
            local diff
            diff=$(python3 -c "print(round(float('$old_pct') - float('$new_pct'), 2))" 2>/dev/null || echo "?")
            echo -e "${RED}[FAIL] $mod 覆盖率下降：基线 ${old_pct}% → 新值 ${new_pct}%（下降 ${diff}%）${NC}"
            fail=1
        fi
    done < "$new_data"

    if [ "$fail" -eq 1 ]; then
        echo -e "${RED}========== $lang 基线更新被拒绝（存在下降项） ==========${NC}"
        echo -e "${YELLOW}提示：仅在覆盖率提升时更新基线；若下降为预期（重构/删测），请人工评审后手动编辑 $baseline${NC}"
        return 1
    fi
    echo -e "${GREEN}[OK] $lang 全部模块新基线 ≥ 旧基线，允许更新${NC}"
    return 0
}

# ------------------------------------------------------------
# Java 基线更新：从 JaCoCo XML 读取行覆盖率
# ------------------------------------------------------------
update_java() {
    echo "更新 Java 覆盖率基线..."
    local out="$BASELINE_DIR/java.json"
    local tmp_data
    tmp_data="$(mktemp)"
    trap "rm -f '$tmp_data'" RETURN

    while IFS= read -r pom; do
        local mod_dir mod_name jacoco_xml
        mod_dir=$(dirname "$pom")
        mod_name=$(basename "$mod_dir")
        jacoco_xml="$mod_dir/target/site/jacoco/jacoco.xml"
        if [ ! -f "$jacoco_xml" ]; then
            echo -e "${YELLOW}[SKIP] $mod_name 无 jacoco.xml，跳过${NC}"
            continue
        fi
        local line_missed line_covered line_total line_pct
        line_missed=$(grep -oP '<counter type="LINE"[^>]*missed="\K[0-9]+' "$jacoco_xml" | tail -1)
        line_covered=$(grep -oP '<counter type="LINE"[^>]*covered="\K[0-9]+' "$jacoco_xml" | tail -1)
        line_missed=${line_missed:-0}
        line_covered=${line_covered:-0}
        line_total=$((line_missed + line_covered))
        if [ "$line_total" -gt 0 ]; then
            line_pct=$((line_covered * 100 / line_total))
        else
            line_pct=0
        fi
        echo "${mod_name}=${line_pct}" >> "$tmp_data"
    done < <(find "$PROJECT_ROOT/platform" -name pom.xml -not -path "*/target/*")

    # 只升不降保护
    if ! check_monotonic_up "Java" "$tmp_data" "$out"; then
        return 1
    fi

    write_baseline_json "$out" "Java" "$tmp_data"
    echo -e "${GREEN}✓ Java 基线已写入 $out${NC}"
}

# ------------------------------------------------------------
# Go 基线更新：从 go test -coverprofile + go tool cover -func 读取总覆盖率
# T-08 修复：原用 go test -cover 不输出 total: 行，grep ^total: 恒为空，
# 导致所有模块基线被写为 0，趋势阻断形同虚设。
# 改为与 ci.yml 门禁/趋势检查一致的 -coverprofile + go tool cover -func 口径。
# 来源：2026-09-12-go-test-cover-no-total-line-trend-check-bypass
# ------------------------------------------------------------
update_go() {
    echo "更新 Go 覆盖率基线..."
    local out="$BASELINE_DIR/go.json"
    local tmp_data
    tmp_data="$(mktemp)"
    trap "rm -f '$tmp_data'" RETURN

    while IFS= read -r gomod; do
        local mod_dir mod_name cov cov_profile cov_output
        mod_dir=$(dirname "$gomod")
        # R12 修复：基线文件 go.json 的 key 为相对 platform/ 的模块路径
        # （如 observability/query-api），原用 basename 会得到 query-api，与基线 key 不匹配。
        # 改为去掉 $PROJECT_ROOT/platform/ 前缀，与 ci.yml:1129 趋势检查口径一致。
        mod_name=${mod_dir#"$PROJECT_ROOT/platform/"}
        # 使用 coverprofile + go tool cover -func 获取总覆盖率
        # （go test -cover 不输出 total: 行，无法直接 grep 解析）
        cov_profile="$mod_dir/.coverage.baseline.out"
        rm -f "$cov_profile"
        cov_output=$(cd "$mod_dir" && go test -count=1 -coverprofile=.coverage.baseline.out ./... 2>&1 || true)
        if [ ! -s "$cov_profile" ]; then
            echo -e "${YELLOW}[SKIP] $mod_name 未生成覆盖率数据（可能无测试或测试失败），跳过${NC}"
            echo "测试输出（末尾20行）:"
            echo "$cov_output" | tail -20
            continue
        fi
        cov=$(cd "$mod_dir" && go tool cover -func=.coverage.baseline.out | grep "^total:" | awk '{print $NF}' | tr -d '%' || echo "")
        rm -f "$cov_profile"
        if [ -z "$cov" ] || [ "$cov" = "0" ]; then
            echo -e "${RED}[FAIL] $mod_name 覆盖率解析失败或为 0%，无法更新基线${NC}"
            echo -e "${YELLOW}提示：请检查 $mod_dir 的测试是否正常运行${NC}"
            exit 1
        fi
        echo "${mod_name}=${cov}" >> "$tmp_data"
    done < <(find "$PROJECT_ROOT/platform" -name go.mod -not -path "*/vendor/*")

    if ! check_monotonic_up "Go" "$tmp_data" "$out"; then
        return 1
    fi

    write_baseline_json "$out" "Go" "$tmp_data"
    echo -e "${GREEN}✓ Go 基线已写入 $out${NC}"
}

# ------------------------------------------------------------
# Python 基线更新：从 pytest --cov 的 TOTAL 行读取总覆盖率
# ------------------------------------------------------------
update_python() {
    echo "更新 Python 覆盖率基线..."
    local out="$BASELINE_DIR/python.json"
    local tmp_data
    tmp_data="$(mktemp)"
    trap "rm -f '$tmp_data'" RETURN

    while IFS= read -r mod_dir; do
        local mod_name cov
        mod_name=${mod_dir#"$PROJECT_ROOT/platform/"}
        cov=$(cd "$mod_dir" && python -m pytest tests/ --cov=. --cov-report=term-missing --no-header -q 2>&1 | grep -E "^TOTAL" | awk '{print $NF}' | tr -d '%' || echo "0")
        if [ -z "$cov" ]; then
            cov="0"
        fi
        echo "${mod_name}=${cov}" >> "$tmp_data"
    done < <(find "$PROJECT_ROOT/platform" -name pyproject.toml -not -path "*/node_modules/*")

    if ! check_monotonic_up "Python" "$tmp_data" "$out"; then
        return 1
    fi

    write_baseline_json "$out" "Python" "$tmp_data"
    echo -e "${GREEN}✓ Python 基线已写入 $out${NC}"
}

# ------------------------------------------------------------
# 主入口
# ------------------------------------------------------------
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

echo -e "${GREEN}完成。提交基线更新：git add docs/coverage-baseline/ && git commit -m 'chore(coverage): update baseline'${NC}"
