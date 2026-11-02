#!/usr/bin/env bash
# ============================================================================
# env-profile-consistency-check.sh — 四环境 Profile 一致性与承诺兑现检查
# ----------------------------------------------------------------------------
# 本脚本是「四环境交付」在纯模拟层（无需任何真机 / 客户环境）可闭环的
# 校验。它不替代真机验证，而是把"不用真机也能钉死的那部分"钉死。
#
# 两种运行模式：
#   gate   （默认）只对「零改动即可判定」的事实失败；用于 CI 阻断。
#          通过前提：渲染成功 + 已存在的部署环境 values 齐全 +
#          kubeconform 判出无效资源（若 kubeconform 可用）。
#   audit  全部发现都计失败（含四环境交付轴的内容级不一致），用于
#          夜间/手动审计，输出完整清单，不进阻断。
#
#   阶段1  Profile Schema 自洽（仅 4 个交付环境强制；local 为开发者
#         PoC Profile，文件头明示不参与交付，结构差异只报告不失败）
#   阶段2  双副本漂移检测
#         design/deploy/profiles/<env>.yaml 与 ske/profiles/<env>.yaml 的
#         YAML 语义深比对（信创 TLCP 配置曾仅在 design 副本存在——
#         SKE 发行版层拿不到国密传输差异，"零改动交付"在抽象层即裂开）
#   阶段3  文档承诺兑现
#         docs/环境验证状态.md 声明的四环境 values 骨架目录存在性校验
#   阶段4  环境渲染可部署性 + 差异透明化
#         对 13 个核心 chart 用真实存在的 dev/staging/prod 三套 values
#         渲染，断言全部渲染成功；并输出三环境资源拓扑差异清单——
#         这是 ga-checklist 1.7.6「四环境共用同一 Chart」的静态等价证据：
#         环境差异全部由 values 解释，chart 模板零环境硬编码。
#         kind 差异本身不是失败条件（dev 无 PDB/HPA、prod 有，是
#         values/env/README.md 明文设计意图）；失败条件是渲染失败
#         或 kubeconform 判出的无效资源。
#
# 触发：本地 bash 直接运行 / .github/workflows/env-profile-gate.yml
# 退出码：0 = 全部通过；1 = 存在失败；2 = 环境错误（依赖 / 目录缺失）
# 依赖：helm >= 3.10、python3 + PyYAML；kubeconform 可选
#       （未安装或 schema 源不可达时自动降级为 warn，不计失败）
# ============================================================================
# 注意：不带 set -e —— 各失败路径用显式 if 分支处理（与
# chart-render-check.sh 同口径），grep 无匹配返回 1 也不应中断。
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT" || exit 2

MODE="${1:-gate}"
case "$MODE" in
  gate|audit) ;;
  *) echo "用法: $0 [gate|audit]" >&2; exit 2 ;;
esac
export MODE
export ENVS DELIVERY_ENVS PROMISED_DIRS

# 交付轴四环境（docs/环境验证状态.md 口径）；local 是开发者 PoC Profile
DELIVERY_ENVS="onprem xinchuang publiccloud privatecloud"
ENVS="local $DELIVERY_ENVS"
# 文档声明的四环境 values 骨架目录（docs/环境验证状态.md「新增配置」节）
PROMISED_DIRS="staging-xinchuang staging-publiccloud staging-privatecloud"
# 阶段4：真实存在的三套部署环境 values（部署环境轴，非四环境交付轴）
DEPLOY_ENVS="dev staging prod"
VALUES_CHARTS="catalog doris encaps-layer failover-api failover-engine federated-query flink kafka rule-engine spark sql-gateway superset trino"

# 与 chart-render-check.sh 同口径的 CI 冒烟占位值（chart 含 fail-fast：
# catalog/nacos 的 jwtSigningKey 为空时拒绝渲染）
SMOKE_JWT=ci-smoke-only-value-0123456789abcdef

FAIL_LOG="$(mktemp)"
WARN_LOG="$(mktemp)"
export FAIL_LOG WARN_LOG
trap 'rm -f "$FAIL_LOG" "$WARN_LOG"' EXIT

# 失败计数：阶段 1-3 由 python 写入，前缀区分严重级别（GATE: 零改动事实 /
# AUDIT: 内容级不一致）；阶段 4 的 fail() 一律 GATE。
fail() { echo "GATE:$1" >> "$FAIL_LOG"; echo "::error::$1" >&2; }
warn() { echo "$1" >> "$WARN_LOG"; echo "  [warn] $1"; }

echo "############################################################"
echo "# 四环境 Profile 一致性检查（模式: $MODE）"
echo "# 仓库根: $REPO_ROOT"
echo "############################################################"
echo ""

for d in design/deploy/profiles ske/profiles design/deploy/values/env; do
  if [ ! -d "$d" ]; then
    echo "::error::必需目录不存在: $d"
    exit 2
  fi
done

# ============================================================================
# 阶段1 + 阶段2 + 阶段3：纯 YAML 分析（内嵌 python，不调外部命令）
# ============================================================================
echo "============================================================"
echo "[阶段1/2/3] Profile Schema 自洽 / 双副本漂移 / 文档承诺兑现"
echo "============================================================"
python3 - <<'PYEOF'
import os, sys, yaml

MODE = os.environ.get("MODE", "gate")
ENVS = os.environ.get("ENVS", "local onprem xinchuang publiccloud privatecloud").split()
DELIVERY = os.environ.get("DELIVERY_ENVS", "onprem xinchuang publiccloud privatecloud").split()
PROMISED = os.environ.get("PROMISED_DIRS", "staging-xinchuang staging-publiccloud staging-privatecloud").split()
DEPLOY_ENVS = "dev staging prod".split()
VALUES_CHARTS = "catalog doris encaps-layer failover-api failover-engine federated-query flink kafka rule-engine spark sql-gateway superset trino".split()

FAIL_LOG = os.environ["FAIL_LOG"]
WARN_LOG = os.environ["WARN_LOG"]

def err(sev, msg):
    with open(FAIL_LOG, "a", encoding="utf-8") as fh:
        fh.write(f"{sev}:{msg}\n")

def warn(msg):
    with open(WARN_LOG, "a", encoding="utf-8") as fh:
        fh.write(msg + "\n")
    print(f"  [warn] {msg}")

def _deep_diff(a, b, path=""):
    out = []
    if isinstance(a, dict) and isinstance(b, dict):
        for k in sorted(set(a) | set(b)):
            if k not in a:
                out.append(f"{path}.{k}: ske-only={str(b[k])[:90]}")
            elif k not in b:
                out.append(f"{path}.{k}: design-only={str(a[k])[:90]}")
            else:
                out += _deep_diff(a[k], b[k], f"{path}.{k}")
    elif a != b:
        out.append(f"{path}: design={str(a)[:90]} | ske={str(b)[:90]}")
    return out

def algo_has_sm4(algo):
    if algo is None:
        return False
    if isinstance(algo, str):
        return "SM4" in algo
    return "SM4" in algo

# ---- 阶段1：Schema 自洽（仅交付环境强制；local 只查 YAML 可解析）----
print("--- 阶段1: Profile Schema 自洽（交付环境 %d 份 + local PoC）---" % (len(DELIVERY) * 2))
n_schema = 0
n_checked = 0
for env in ENVS:
    for base in ("design/deploy/profiles", "ske/profiles"):
        path = f"{base}/{env}.yaml"
        tag = f"{env}@{base}"
        if not os.path.isfile(path):
            err("AUDIT", f"PROFILE-MISSING {path}")
            continue
        try:
            p = yaml.safe_load(open(path, encoding="utf-8"))
        except yaml.YAMLError as e:
            err("AUDIT", f"PROFILE-YAML-INVALID {path} :: {str(e)[:200]}")
            continue
        if env not in DELIVERY:
            warn(f"LOCAL-POC {tag} :: local 为开发者 PoC Profile（文件头明示不参与交付），仅校验 YAML 可解析，结构差异不强制")
            continue
        n_checked += 1
        for key in ("meta", "compute", "images", "global", "storage", "network", "kms", "identity"):
            if key not in p:
                err("AUDIT", f"SCHEMA-FIELD-MISSING {tag} :: 缺少顶层字段 {key}")
        meta = p.get("meta") or {}
        if meta.get("name") != env:
            err("AUDIT", f"SCHEMA-META-NAME {tag} :: meta.name={meta.get('name')!r} 与文件名 {env!r} 不符")
        if meta.get("k8s") != "self-built":
            err("AUDIT", f"SCHEMA-META-K8S {tag} :: meta.k8s={meta.get('k8s')!r} 应为 self-built（四环境承诺：K8s 一律自建）")
        arch = (p.get("compute") or {}).get("arch")
        if not isinstance(arch, list) or not arch:
            err("AUDIT", f"SCHEMA-COMPUTE-ARCH {tag} :: compute.arch 应为非空列表")
        else:
            bad = [a for a in arch if a not in ("x86", "arm64")]
            if bad:
                err("AUDIT", f"SCHEMA-COMPUTE-ARCH {tag} :: 非法值 {bad}（允许 x86/arm64）")
        if not (p.get("compute") or {}).get("os"):
            err("AUDIT", f"SCHEMA-COMPUTE-OS {tag} :: compute.os 应为非空列表")
        g = (p.get("global") or {}).get("guomiEnabled")
        if not isinstance(g, bool):
            err("AUDIT", f"SCHEMA-GUOMI-BOOL {tag} :: global.guomiEnabled 应为布尔值（true/false 均合法）")
        elif g is True and not algo_has_sm4((p.get("kms") or {}).get("algo")):
            err("AUDIT", f"SCHEMA-GUOMI-ALGO {tag} :: guomiEnabled=true 但 kms.algo 不含 SM4")
        obj = (p.get("storage") or {}).get("objectStore") or {}
        for key in ("driver", "endpoint"):
            if not obj.get(key):
                err("AUDIT", f"SCHEMA-OBJSTORE {tag} :: storage.objectStore.{key} 缺失或为空")
with open(FAIL_LOG, encoding="utf-8") as fh:
    n_schema = sum(1 for line in fh if line.startswith("AUDIT:SCHEMA") or line.startswith("AUDIT:PROFILE"))
print(f"  交付 Profile schema 校验 {n_checked} 份，{n_schema} 个 schema 错误")

# ---- 阶段2：双副本漂移 ----
print("--- 阶段2: 双副本漂移检测（design/deploy/profiles vs ske/profiles）---")
drift_total = 0
drift_envs = 0
for env in ENVS:
    try:
        a = yaml.safe_load(open(f"design/deploy/profiles/{env}.yaml", encoding="utf-8"))
        b = yaml.safe_load(open(f"ske/profiles/{env}.yaml", encoding="utf-8"))
    except (OSError, yaml.YAMLError) as e:
        err("AUDIT", f"DRIFT-LOAD-FAIL {env} :: {str(e)[:200]}")
        continue
    if a == b:
        print(f"  ✓ {env}: 双副本语义一致")
        continue
    diffs = _deep_diff(a, b, env)
    drift_total += len(diffs)
    drift_envs += 1
    print(f"  ✗ {env}: 双副本漂移（{len(diffs)} 处差异）")
    for d in diffs:
        print(f"      - {d}")
    err("AUDIT", f"DRIFT {env} :: 双副本语义不一致（{len(diffs)} 处）：" + "; ".join(diffs[:3])[:280])
print(f"  漂移环境 {drift_envs}/{len(ENVS)}，总差异 {drift_total} 处")

# ---- 阶段3：文档承诺兑现 + 已落地 values 齐全性 ----
print("--- 阶段3: 文档承诺兑现（docs/环境验证状态.md 声称的 values 骨架）---")
for name in PROMISED:
    d = f"design/deploy/values/env/{name}"
    if os.path.isdir(d):
        n = len([f for f in os.listdir(d) if f.endswith(".yaml")])
        print(f"  ✓ {name}: 存在（{n} 个 values 文件）")
    else:
        err("AUDIT", f"PROMISE-BROKEN {name} :: 环境验证状态文档声称为「staging 配置骨架」，仓库实际不存在")
        print(f"  ✗ {name}: 目录缺失（文档声称的骨架未落地）")
for env in DEPLOY_ENVS:
    d = f"design/deploy/values/env/{env}"
    have = {f.rsplit("-", 1)[0] for f in os.listdir(d)} if os.path.isdir(d) else set()
    absent = sorted(set(VALUES_CHARTS) - have)
    if absent:
        # 已落地部署环境的组件 values 齐全性是零改动事实：缺失即失败
        err("GATE", f"VALUES-INCOMPLETE {env} :: 缺 {absent}")
        print(f"  ✗ {env}: {len(have)}/{len(VALUES_CHARTS)} 组件 values（缺 {absent}）")
    else:
        print(f"  ✓ {env}: {len(have)}/{len(VALUES_CHARTS)} 组件 values 齐全")

with open(FAIL_LOG, encoding="utf-8") as fh:
    lines = [l.rstrip("\n") for l in fh if l.strip()]
n_gate = sum(1 for l in lines if l.startswith("GATE:"))
n_audit = sum(1 for l in lines if l.startswith("AUDIT:"))
print(f"\n  阶段1-3: GATE 级失败 {n_gate}，AUDIT 级发现 {n_audit}")
for l in lines:
    sev, _, msg = l.partition(":")
    tag = "[FAIL]" if (sev == "GATE" or MODE == "audit") else "[adv]"
    print(f"  {tag} {msg}")
PYEOF
rc123=$?
if [ "$rc123" -ne 0 ]; then
  fail "ANALYSIS-CRASH :: 阶段1-3 YAML 分析异常退出（rc=$rc123），阶段1-3 结果不可信"
  echo "  ✗ 阶段1-3 YAML 分析异常退出（rc=$rc123）"
fi
echo ""

# ============================================================================
# 阶段4：三套部署环境 values 渲染 + 拓扑差异透明化（bash 调 helm）
# ============================================================================
echo "============================================================"
echo "[阶段4] 环境渲染可部署性 + 差异透明化（dev/staging/prod 部署环境轴）"
echo "============================================================"
TMPDIR_R="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_R"; rm -f "$FAIL_LOG" "$WARN_LOG"' EXIT

# kubeconform 可用性探测：用一个已知合法的 Service 探针判定 schema 源是否可达。
# 探针失败（未安装 / 无法下载 schema）时降级 warn，绝不因为基础设施问题判失败。
KUBECONFORM=""
if command -v kubeconform >/dev/null 2>&1; then KUBECONFORM=kubeconform
elif [ -x /usr/local/bin/kubeconform ]; then KUBECONFORM=/usr/local/bin/kubeconform
fi
if [ -n "$KUBECONFORM" ]; then
  cat > "$TMPDIR_R/probe.yaml" <<'PROBE'
apiVersion: v1
kind: Service
metadata:
  name: env-gate-probe
  namespace: env-gate
spec:
  ports:
    - port: 80
      targetPort: 80
  selector:
    app: env-gate-probe
PROBE
  if ! "$KUBECONFORM" -strict -ignore-missing-schemas -kubernetes-version 1.29.0 -summary \
        "$TMPDIR_R/probe.yaml" > "$TMPDIR_R/probe.out" 2>&1; then
    warn "KUBECONFORM-UNAVAILABLE :: 探针校验失败（kubeconform 未安装或 schema 源不可达），kubeconform 校验降级跳过 :: $(head -1 "$TMPDIR_R/probe.out" | cut -c1-140)"
    KUBECONFORM=""
  fi
fi
[ -z "$KUBECONFORM" ] && echo "  [info] kubeconform 不可用，kubeconform 校验降级跳过（见上 warn）"

render_fail=0
render_ok=0
schema_invalid=0
schema_error=0
for name in $VALUES_CHARTS; do
  chart_dir="design/deploy/charts/$name"
  if [ ! -f "$chart_dir/Chart.yaml" ]; then
    warn "CHART-MISSING $name :: 无对应 chart 目录，跳过"
    continue
  fi
  renders=""
  for env in $DEPLOY_ENVS; do
    vf="design/deploy/values/env/$env/$name-values.yaml"
    out="$TMPDIR_R/$name-$env.yaml"
    extra_args=()
    case "$name" in
      catalog|nacos) extra_args=(--set "auth.jwtSigningKey=${SMOKE_JWT}") ;;
    esac
    # errexit 豁免写法（同 chart-render-check.sh）：失败进 if 分支而非直接退出
    if ! out_txt=$(helm template "$name" "$chart_dir" --namespace env-gate -f "$vf" ${extra_args[@]+"${extra_args[@]}"} 2>"$TMPDIR_R/err.txt"); then
      rerr=$(head -3 "$TMPDIR_R/err.txt" 2>/dev/null | tr '\n' ' ' | cut -c1-200)
      fail "RENDER-FAIL $name/$env :: $rerr"
      echo "  ✗ $name/$env: 渲染失败 :: $rerr"
      render_fail=$((render_fail+1))
      continue
    fi
    if [ -z "$out_txt" ]; then
      fail "RENDER-EMPTY $name/$env :: helm 退出码 0 但无输出"
      render_fail=$((render_fail+1))
      continue
    fi
    printf '%s' "$out_txt" > "$out"
    renders="$renders $out"
    if [ -n "$KUBECONFORM" ]; then
      if ! "$KUBECONFORM" -strict -ignore-missing-schemas -kubernetes-version 1.29.0 -summary \
            "$out" > "$TMPDIR_R/kc.out" 2>&1; then
        # Invalid: N=内容问题（失败）；Invalid: 0=基础设施错误（warn）
        if grep -qE "Invalid: [1-9]" "$TMPDIR_R/kc.out"; then
          # v0.6.7 文本输出：违规为 "<path> - <Kind> <name> is invalid: ..."，
          # 基础设施错误为 "... failed validation: ..."；两者都捞并去掉路径前缀。
          det=$(grep -E "is invalid|failed validation" "$TMPDIR_R/kc.out" \
                 | sed 's/^[^ ]* - //' | head -3 | tr '\n' ' ' | cut -c1-220)
          fail "SCHEMA-INVALID $name/$env :: kubeconform 判出无效资源 :: $det"
          echo "  ✗ $name/$env: kubeconform 判出无效资源"
          schema_invalid=$((schema_invalid+1))
        else
          warn "SCHEMA-ERROR $name/$env :: kubeconform 出错但无无效资源（疑似 schema 不可达）:: $(head -1 "$TMPDIR_R/kc.out" | cut -c1-140)"
          schema_error=$((schema_error+1))
        fi
      fi
    fi
  done
  # 三个环境都渲染成功时输出拓扑指纹差异
  if [ "$(echo $renders | wc -w)" -eq 3 ]; then
    render_ok=$((render_ok+1))
    f1=$(echo $renders | awk '{print $1}')
    python3 - "$f1" "$(echo $renders | awk '{print $2}')" "$(echo $renders | awk '{print $3}')" <<'PYEOF'
import re, sys
from collections import Counter
sets = []
for p in sys.argv[1:4]:
    txt = open(p, encoding="utf-8").read()
    sets.append(Counter(re.findall(r"^kind:\s*(\S+)", txt, re.M)))
names = ["dev", "staging", "prod"]
allk = sorted(set().union(*sets))
deltas = [f"{k}: " + " ".join(f"{n}={s.get(k,0)}" for n, s in zip(names, sets))
          for k in allk if len({s.get(k,0) for s in sets}) > 1]
chart = sys.argv[1].rsplit("/", 1)[-1].rsplit("-", 1)[0]
if deltas:
    print(f"  ~ {chart:18s}: 三套渲染成功；拓扑差异（应为 values/env/README.md 声明的设计意图）: {'; '.join(deltas)}")
else:
    print(f"  ✓ {chart:18s}: 三套渲染成功，拓扑一致")
PYEOF
  fi
done
echo ""
echo "  渲染: $render_ok/$(echo $VALUES_CHARTS | wc -w) chart 三套环境全部成功；渲染失败 $render_fail；kubeconform 无效资源 $schema_invalid（降级/错误 $schema_error）"

# ============================================================================
# 汇总
# ============================================================================
n_gate=$(grep -c '^GATE:' "$FAIL_LOG" 2>/dev/null || true)
n_audit=$(grep -c '^AUDIT:' "$FAIL_LOG" 2>/dev/null || true)
fatal=$n_gate
[ "$MODE" = "audit" ] && fatal=$((n_gate + n_audit))

echo ""
echo "============================================================"
echo "汇总（模式: $MODE）"
echo "============================================================"
if [ -s "$WARN_LOG" ]; then
  echo "  警告 $(wc -l < "$WARN_LOG") 条:"
  sed 's/^/    [warn] /' "$WARN_LOG"
fi
if [ "$n_audit" -gt 0 ] && [ "$MODE" = "gate" ]; then
  echo "  AUDIT 级发现 $n_audit 条（非阻断，内容级不一致，详见阶段1-3明细；audit 模式将计失败）:"
  grep '^AUDIT:' "$FAIL_LOG" | sed 's/^AUDIT:/    [adv] /'
fi
if [ "$fatal" -gt 0 ]; then
  echo "  失败 $fatal 条:"
  grep '^GATE:' "$FAIL_LOG" | sed 's/^GATE:/    [FAIL] /'
  if [ "$MODE" = "audit" ]; then
    grep '^AUDIT:' "$FAIL_LOG" | sed 's/^AUDIT:/    [FAIL] /'
  fi
  echo ""
  echo "::error::四环境 Profile 一致性检查未通过（模式 $MODE，$fatal 条失败），详见上方明细"
  exit 1
fi
if [ "$n_audit" -gt 0 ]; then
  echo "  ✓ gate 模式通过（AUDIT 级发现 $n_audit 条已列为待裁决项，不阻断）"
else
  echo "  ✓ 全部阶段通过"
fi
exit 0
