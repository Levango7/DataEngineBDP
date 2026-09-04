#!/usr/bin/env bash
# 本地快速冒烟门禁（无需 K8s/Docker 集群）
# 与 scripts/smoke-test.sh（K8s 冒烟）互补，本脚本只做本地静态+编译+单测门禁。
# 用法：bash scripts/smoke-local.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

FAILS=0
log()  { echo "[smoke-local] $*"; }
pass() { log "PASS: $*"; }
fail() { log "FAIL: $*"; FAILS=$((FAILS+1)); }

# ---------------------------------------------------------------
# 1. Mock 生产卫生门禁（P0 回归拦截）
# ---------------------------------------------------------------
log "1/5 运行 Mock 生产卫生检查 ..."
if bash scripts/check-prod-mock-hygiene.sh; then
  pass "Mock 卫生检查"
else
  fail "Mock 卫生检查（见上方 ::error::）"
fi

# ---------------------------------------------------------------
# 2. Go 编译 + 单测（catalog / vector-engine / observability）
# ---------------------------------------------------------------
log "2/5 Go 模块编译检查 ..."
if command -v go >/dev/null 2>&1; then
  # 仅对含 go.mod 的 Go 模块执行编译（observability 等纯配置目录自动跳过）
  for mod in platform/catalog platform/vector-engine platform/observability/query-api; do
    if [ ! -f "$ROOT/$mod/go.mod" ]; then
      log "SKIP: $mod（无 go.mod，非 Go 模块）"
      continue
    fi
    if (cd "$ROOT/$mod" && go build ./... >/dev/null 2>&1); then
      pass "go build $mod"
    else
      fail "go build $mod"
    fi
  done
else
  log "go 未安装，跳过 Go 检查"
fi

# ---------------------------------------------------------------
# 3. Java 治理模块编译（governance 三件套）
# ---------------------------------------------------------------
log "3/5 Java 治理模块编译 ..."
if command -v mvn >/dev/null 2>&1; then
  # 预构建：治理模块依赖内部 SNAPSHOT（父POM / common-security，lineage-analyzer←sql-gateway），
  # 全新环境（CI runner）本地仓库无缓存，须按依赖序先 install，否则依赖解析直接失败
  # （与 ci.yml java-build-test 的预构建序列保持一致）
  if (cd "$ROOT" && mvn install -B -N -q >/dev/null 2>&1); then
    pass "mvn install 父POM"
  else
    fail "mvn install 父POM（治理模块前置）"
  fi
  for lib_pom in platform/common-security platform/encaps-layer platform/sql-gateway; do
    if (cd "$ROOT/$lib_pom" && mvn install -B -q -DskipTests >/dev/null 2>&1); then
      pass "mvn install $lib_pom"
    else
      fail "mvn install $lib_pom（治理模块前置）"
    fi
  done
  for mod in platform/governance/real-time-pipeline platform/governance/metadata-collector platform/governance/lineage-analyzer; do
    if (cd "$ROOT/$mod" && mvn -q -o compile >/dev/null 2>&1) || (cd "$ROOT/$mod" && mvn -q compile >/dev/null 2>&1); then
      pass "mvn compile $mod"
    else
      fail "mvn compile $mod"
    fi
  done
else
  log "mvn 未安装，跳过 Java 编译"
fi

# ---------------------------------------------------------------
# 4. Doris 链路探测（可选，缺失不阻断）
# ---------------------------------------------------------------
log "4/5 Doris Lite 探测 ..."
bash scripts/doris-lite-verify.sh || log "WARN: Doris 未就绪（设计上可选，不阻断）"

# ---------------------------------------------------------------
# 5. 治理层健康端点契约抽查（源码层）
# ---------------------------------------------------------------
log "5/5 健康端点契约抽查 ..."
for hc in \
  platform/governance/real-time-pipeline/src/main/java/com/levango7/dataenginebdp/governance/realtime/controller/HealthController.java \
  platform/governance/metadata-collector/src/main/java/com/levango7/dataenginebdp/governance/collector/controller/HealthController.java \
  platform/governance/lineage-analyzer/src/main/java/com/levango7/dataenginebdp/governance/lineage/controller/HealthController.java; do
  # 兼容两种注解写法：方法级 @GetMapping("/api/v1/health") 与类级 @RequestMapping("/api/v1/health")
  grep -qE '@(Get|Request)Mapping\("/api/v1/health"\)' "$hc" 2>/dev/null && pass "health 端点 $(basename "$hc")" || fail "health 端点缺失: $hc"
done

# ---------------------------------------------------------------
echo ""
if [ "$FAILS" -eq 0 ]; then
  log "=== smoke-local 全部通过 ==="
  exit 0
else
  log "=== smoke-local 失败项: ${FAILS} ==="
  exit 1
fi