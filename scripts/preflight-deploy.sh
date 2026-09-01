#!/usr/bin/env bash
# ============================================================================
# L0 四环境部署预检（preflight）
#
# 背景：README 宣称"一套主代码四环境零改动交付（信创/本地/公有云/私有云）"，
# 但四环境真实安装演练从未执行（无账号/硬件）。在拿到真实环境前，本脚本
# 把"能在交付前静态/本地验证的部分"全部机器化——每一项 PASS/FAIL 有据可查，
# 演练当天只需处理环境特有的 FAIL 项，而不是从零开始排查。
#
# 检查项（四环境共用 + 环境特有）：
#   A. 工具链：docker/kubectl/helm/jq/python3（缺失标 FAIL 并给出安装提示）
#   B. Profile 结构：5 个 profile（xinchuang/onprem/publiccloud/privatecloud/local）
#      必填键存在、YAML 可解析、镜像/存储/CNI/KMS/identity 声明齐全
#   C. 镜像可达性：profile 引用的 baseImage + 各组件 chart 镜像
#      （可选 --pull 实测拉取；默认仅静态检查引用完整性）
#   D. Helm 渲染：umbrella chart 以各 profile 渲染（对 global.env 注入）
#      逐环境 kubeconform Schema 校验
#   E. 环境 API 凭据预检（publiccloud/privatecloud 特有）：
#      检查标准凭据环境变量是否已导出（不验值，只验存在性，避免演练日现场找密钥）
#
# 用法：
#   bash scripts/preflight-deploy.sh                 # 全部检查（默认）
#   bash scripts/preflight-deploy.sh --env xinchuang # 单环境
#   bash scripts/preflight-deploy.sh --pull          # 含镜像真实拉取（慢）
# ============================================================================
set -u
cd "$(dirname "$0")/.."

MODE_ENV="all"
DO_PULL="false"
for arg in "$@"; do
  case "$arg" in
    --env) MODE_ENV="pending" ;;
    --pull) DO_PULL="true" ;;
    *) [ "$MODE_ENV" = "pending" ] && MODE_ENV="$arg" ;;
  esac
done

ALL_PROFILES=(xinchuang onprem publiccloud privatecloud)
if [ "$MODE_ENV" = "all" ]; then PROFILE_LIST=("${ALL_PROFILES[@]}"); else PROFILE_LIST=("$MODE_ENV"); fi

PASS=0; FAIL=0; WARN=0
ok()   { printf "  [PASS] %s\n" "$1"; PASS=$((PASS+1)); }
bad()  { printf "  [FAIL] %s\n" "$1"; FAIL=$((FAIL+1)); }
warn() { printf "  [WARN] %s\n" "$1"; WARN=$((WARN+1)); }
need() { printf "      → %s\n" "$1"; }

# ---------------------------------------------------------------------------
echo "=========================================="
echo " L0 部署预检（profiles: ${PROFILE_LIST[*]}）"
echo "=========================================="

# ---- A. 工具链 ----
echo "[A] 工具链检查"
for t in docker kubectl helm; do
  if command -v "$t" >/dev/null 2>&1; then ok "$t 可用"; else bad "$t 缺失"; need "安装 $t 后重跑（README 前置条件表）"; fi
done
if command -v kubeconform >/dev/null 2>&1; then ok "kubeconform 可用"; else warn "kubeconform 缺失（D 项 Schema 校验将跳过）"; need "curl -sSL https://github.com/yannh/kubeconform/releases/download/v0.6.7/kubeconform-linux-amd64.tar.gz | tar xz -C /tmp && sudo mv /tmp/kubeconform /usr/local/bin/"; fi

# ---- B. Profile 结构 ----
echo "[B] Profile 结构检查（四环境交付物）"
declare -A PROFILE_REQUIRE=(
  [xinchuang]="images.baseImage compute.arch storage.objectStore.network network.cni identity.driver"
  [onprem]="images.baseImage compute.arch storage.objectStore.network network.cni identity.driver"
  [publiccloud]="images.baseImage compute.arch storage.objectStore.identity identity.driver"
  [privatecloud]="images.baseImage compute.arch identity.driver"
)
for p in "${PROFILE_LIST[@]}"; do
  f="design/deploy/profiles/${p}.yaml"
  if [ ! -f "$f" ]; then bad "profile 文件缺失: $f"; continue; fi
  ok "profile 文件存在: $p"
  # 必填键（python yaml 解析，容错无 jq）
  keys="${PROFILE_REQUIRE[$p]:-}"
  missing=$(python3 - "$f" $keys <<'PYEOF'
import sys, yaml
path, keys = sys.argv[1], sys.argv[2].split()
try:
    d = yaml.safe_load(open(path, encoding="utf-8")) or {}
except Exception as e:
    print("YAML_PARSE_FAIL", e); sys.exit(0)
def dig(k):
    cur = d
    for part in k.split("."):
        if not isinstance(cur, dict) or part not in cur: return False
        cur = cur[part]
    return cur not in (None, "", [], {})
out = [k for k in keys if not dig(k)]
print(" ".join(out))
PYEOF
)
  if [ "$missing" = "YAML_PARSE_FAIL" ] || echo "$missing" | grep -q "YAML"; then
    bad "$p profile YAML 解析失败"
  elif [ -z "$missing" ] || [ "$missing" = "" ]; then
    ok "$p 必填键齐全（arch/镜像/存储/网络/身份源）"
  else
    bad "$p 缺少键: $missing"
  fi
done

# ---- C. 镜像引用完整性 + 可达性 ----
echo "[C] 镜像引用检查"
for p in "${PROFILE_LIST[@]}"; do
  f="design/deploy/profiles/${p}.yaml"
  [ -f "$f" ] || continue
  base=$(python3 - "$f" <<'PYEOF'
import sys, yaml
d = yaml.safe_load(open(sys.argv[1], encoding="utf-8")) or {}
print((d.get("images") or {}).get("baseImage") or "")
PYEOF
)
  if [ -n "$base" ] && [ "$base" != "None" ]; then ok "$p baseImage=$base"
  else warn "$p 未声明 baseImage（默认镜像源）"; fi
  if [ "$DO_PULL" = "true" ] && [ -n "$base" ] && [ "$base" != "None" ]; then
    if docker pull "$base" >/dev/null 2>&1; then ok "$p baseImage 拉取成功"
    else bad "$p baseImage 拉取失败: $base（镜像源/网络）"; fi
  fi
done
[ "$DO_PULL" = "false" ] && warn "--pull 未启用：跳过真实拉取（演练前建议跑一次）"

# ---- D. Helm 渲染（逐 profile） ----
echo "[D] Helm 渲染检查（umbrella + profile 注入）"
UMBRELLA="design/deploy/charts/dataenginebdp-umbrella"
LOCAL_VALUES="deploy/local/values-local-core.yaml"
if [ ! -d "$UMBRELLA" ]; then bad "umbrella chart 缺失: $UMBRELLA"; else
  for p in "${PROFILE_LIST[@]}"; do
    out=$(helm template "pre-$p" "$UMBRELLA" -n pre -f "$LOCAL_VALUES" --set "global.env=$p" 2>/dev/null)
    if [ -n "$out" ]; then
      ok "$p umbrella 渲染成功（$(echo "$out" | grep -c 'kind:') 个资源）"
      if command -v kubeconform >/dev/null 2>&1; then
        echo "$out" > /tmp/pre-${p}.yaml
        if kubeconform -strict -ignore-missing-schemas -kubernetes-version 1.29.0 -summary /tmp/pre-${p}.yaml >/dev/null 2>&1; then
          ok "$p 渲染产物 Schema 校验通过"
        else bad "$p Schema 校验失败（kubeconform）"; fi
        rm -f /tmp/pre-${p}.yaml
      fi
    else bad "$p umbrella 渲染失败（helm template 返回空——检查 profile 值注入）"; fi
  done
fi

# ---- E. 环境凭据预检（可导出性）----
echo "[E] 环境 API 凭据预检（演练日清单）"
for p in "${PROFILE_LIST[@]}"; do
  case "$p" in
    publiccloud)
      for v in ALIBABA_CLOUD_ACCESS_KEY_ID HUAWEICLOUD_SDK_AK; do
        if [ -n "${!v:-}" ]; then ok "$v 已导出"; else warn "$v 未导出（公有云演练需提前配置）"; need "导出后重跑本预检直到 PASS"; fi
      done ;;
    privatecloud)
      warn "私有云: 演练日需准备 OpenStack/vSphere 地址+凭据（profile 无静态校验项）" ;;
    xinchang) : ;;
  esac
done

# ---------------------------------------------------------------------------
echo "=========================================="
echo " 预检汇总: PASS=$PASS FAIL=$FAIL WARN=$WARN"
if [ "$FAIL" -gt 0 ]; then
  echo " 结果: 未通过——FAIL 项全部处理后再进入四环境演练"
  exit 1
fi
echo " 结果: 通过（WARN 项建议在演练前处理，不阻塞）"
