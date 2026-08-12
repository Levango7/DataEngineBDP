#!/bin/bash
# 版本对账脚本：检查项目中各组件版本一致性
# 检查范围：
#   - Go 版本：所有 platform/*/go.mod 中的 go 版本应统一为 go 1.26
#   - Java 版本：所有 platform/*/pom.xml 中的 java.version 应为 17
#   - Node 版本：frontend/package.json 的 engines.node 应包含 22
# 退出码：0 表示全部一致，1 表示存在不一致
set -e
ERRORS=0

# 期望版本
EXPECTED_GO="1.26"
EXPECTED_JAVA="17"
EXPECTED_NODE="22"

echo "=== 版本对账检查 ==="

# 检查 Go 版本
echo "检查 Go 版本（期望 go $EXPECTED_GO）..."
while IFS= read -r f; do
    # 仅匹配以 "go " 开头的行（跳过注释行）
    ver=$(grep "^go " "$f" | head -1 | awk '{print $2}')
    if [ -n "$ver" ] && [ "$ver" != "$EXPECTED_GO" ]; then
        echo "::error file=$f::go $ver (期望 $EXPECTED_GO)"
        ERRORS=$((ERRORS + 1))
    fi
done < <(find platform -name "go.mod" -not -path "*/target/*" 2>/dev/null)

# 检查 Java 版本
echo "检查 Java 版本（期望 java.version=$EXPECTED_JAVA）..."
while IFS= read -r f; do
    ver=$(grep "<java.version>" "$f" | head -1 | sed 's/.*<java.version>\(.*\)<\/java.version>.*/\1/')
    if [ -n "$ver" ] && [ "$ver" != "$EXPECTED_JAVA" ]; then
        echo "::error file=$f::java $ver (期望 $EXPECTED_JAVA)"
        ERRORS=$((ERRORS + 1))
    fi
done < <(find platform -name "pom.xml" -not -path "*/target/*" 2>/dev/null)

# 检查 Node 版本（package.json engines.node）
echo "检查 Node 版本（期望 engines.node 包含 $EXPECTED_NODE）..."
if [ -f frontend/package.json ]; then
    # 提取 engines.node 中的主版本号
    node_ver=$(grep -o '"node": *"[^"]*"' frontend/package.json | grep -o '[0-9]*' | head -1)
    if [ -n "$node_ver" ] && [ "$node_ver" != "$EXPECTED_NODE" ]; then
        echo "::error file=frontend/package.json::node $node_ver (期望 $EXPECTED_NODE)"
        ERRORS=$((ERRORS + 1))
    fi
fi

# 检查 CI 中 node-version（ci.yml）
echo "检查 CI node-version（期望 $EXPECTED_NODE）..."
ci_yml=".github/workflows/ci.yml"
if [ -f "$ci_yml" ]; then
    # 提取所有 node-version 配置，检查是否都为期望值
    while IFS= read -r line; do
        ver=$(echo "$line" | grep -o "'[^']*'" | tr -d "'" | head -1)
        if [ -n "$ver" ] && [ "$ver" != "$EXPECTED_NODE" ]; then
            echo "::error file=$ci_yml::node-version $ver (期望 $EXPECTED_NODE)"
            ERRORS=$((ERRORS + 1))
        fi
    done < <(grep "node-version:" "$ci_yml")
fi

# 汇总
echo "=== 检查完成 ==="
if [ $ERRORS -eq 0 ]; then
    echo "✓ 所有版本一致"
    exit 0
else
    echo "::error::发现 $ERRORS 个版本不一致"
    exit 1
fi