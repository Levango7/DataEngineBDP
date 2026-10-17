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

# 失败必须自带线索：子命令输出先落文件，成功即删，失败打退出码 + [ERROR] 摘要 + 尾部若干行。
# 起因：2026-10-16 run 38874594043 / job 116886838575 报了 8 条 "FAIL: mvn …" 而 CI 日志里
# 一条 mvn 输出都没有（旧写法一律 >/dev/null 2>&1），红了也无法归因，只能靠"同一 job 相邻
# 两 head 一红一绿"反推是瞬态——那是运气，不是证据。
LOGDIR="$(mktemp -d "${TMPDIR:-/tmp}/smoke-local.XXXXXX")"
trap 'rm -rf "$LOGDIR"' EXIT

# run_logged <标签> <工作目录> <命令...>：非零退出时打印摘要，返回原退出码。
# CI 以 `bash -e` 执行本脚本 ⇒ 这里显式 set +e 跑子命令，跑完按原状态恢复；
# 否则"命令替换赋值即失败"会把整段裁决块掐死，只剩零输出红。
run_logged() {
  local label="$1" dir="$2"; shift 2
  local log
  log="$LOGDIR/$(printf '%s' "$label" | tr ' /' '__').log"
  local had_e=0
  case "$-" in *e*) had_e=1 ;; esac
  set +e
  ( cd "$dir" && "$@" ) >"$log" 2>&1
  local rc=$?
  if [ "$rc" -ne 0 ]; then
    log "  ↳ [$label] 退出码 $rc（完整日志：$log）"
    local hits
    hits="$(grep -E '^\[ERROR\]|BUILD FAILURE|Could not (resolve|find|transfer)|status code: |Connection refused|TLS handshake|UnknownHost' "$log" 2>/dev/null | head -n 15)"
    if [ -n "$hits" ]; then
      printf '%s\n' "$hits" | sed 's/^/      /'
    fi
    log "  ↳ 尾部 25 行："
    tail -n 25 "$log" | sed 's/^/      /'
  else
    rm -f "$log"
  fi
  if [ "$had_e" -eq 1 ]; then
    set -e
  fi
  return "$rc"
}

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
    if run_logged "go build $mod" "$ROOT/$mod" go build ./...; then
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
  if run_logged "mvn install 父POM" "$ROOT" mvn install -B -N -q; then
    pass "mvn install 父POM"
  else
    fail "mvn install 父POM（治理模块前置）"
  fi
  for lib_pom in platform/common-security platform/encaps-shared-model platform/encaps-layer platform/sql-gateway; do
    if run_logged "mvn install $lib_pom" "$ROOT/$lib_pom" mvn install -B -q -DskipTests; then
      pass "mvn install $lib_pom"
    else
      fail "mvn install $lib_pom（治理模块前置）"
    fi
  done
  for mod in platform/governance/real-time-pipeline platform/governance/metadata-collector platform/governance/lineage-analyzer; do
    if run_logged "mvn -o compile $mod" "$ROOT/$mod" mvn -q -o compile \
      || run_logged "mvn compile $mod" "$ROOT/$mod" mvn -q compile; then
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