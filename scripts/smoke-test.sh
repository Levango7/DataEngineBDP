#!/usr/bin/env bash
# ============================================================
# v2.1.0-RC 本地冒烟测试
# 配合 deploy/local-up.sh 运行，验证核心 5 服务健康
# ============================================================
set -euo pipefail

NS=dataengine
CLUSTER=dataengine-local

log() { echo "[$(date '+%H:%M:%S')] $*"; }
fail() { log "FAIL: $*"; exit 1; }
pass() { log "PASS: $*"; }

# 等待 Pod Ready
wait_pod_ready() {
  local label="$1" timeout="${2:-180}"
  log "等待 $label Ready (最多 ${timeout}s)..."
  kubectl wait --for=condition=Ready pod -l "$label" -n "$NS" --timeout="${timeout}s" >/dev/null 2>&1 || fail "$label 未就绪"
}

# HTTP 健康检查
check_health() {
  local svc="$1" port="$2" path="${3:-/health}"
  log "检查 $svc:$port$path ..."
  kubectl run -n "$NS" "curl-$svc-$$" --rm -i --restart=Never --image=curlimages/curl:8.9.1 -- \
    -fsS "http://$svc.$NS.svc.cluster.local:$port$path" >/dev/null 2>&1 || fail "$svc 健康检查失败"
  pass "$svc 健康"
}

echo "=== v2.1.0-RC 本地冒烟测试 ==="

# 1. 集群与命名空间
log "检查集群: $(kubectl config current-context)"
kubectl get ns "$NS" >/dev/null || fail "命名空间 $NS 不存在"

# 2. 核心 5 服务 Pod Ready
wait_pod_ready "app.kubernetes.io/name=encaps-layer" 180
wait_pod_ready "app.kubernetes.io/name=sql-gateway" 180
wait_pod_ready "app.kubernetes.io/name=catalog" 180
wait_pod_ready "app.kubernetes.io/name=rule-engine" 180
wait_pod_ready "app.kubernetes.io/name=open-api-catalog" 180

# 3. HTTP 健康检查（需 Service 端口正确）
log "启动临时 port-forward 进行健康检查..."
kubectl port-forward -n "$NS" svc/encaps-layer 8080:8080 >/dev/null 2>&1 &
PF1=$!
kubectl port-forward -n "$NS" svc/sql-gateway 8081:8080 >/dev/null 2>&1 &
PF2=$!
kubectl port-forward -n "$NS" svc/catalog 8082:8080 >/dev/null 2>&1 &
PF3=$!
kubectl port-forward -n "$NS" svc/rule-engine 8083:8080 >/dev/null 2>&1 &
PF4=$!
kubectl port-forward -n "$NS" svc/open-api-catalog 8084:8080 >/dev/null 2>&1 &
PF5=$!
sleep 3

curl -fsS http://localhost:8080/health >/dev/null && pass "encaps-layer" || fail "encaps-layer"
curl -fsS http://localhost:8081/health >/dev/null && pass "sql-gateway" || fail "sql-gateway"
curl -fsS http://localhost:8082/health >/dev/null && pass "catalog" || fail "catalog"
curl -fsS http://localhost:8083/health >/dev/null && pass "rule-engine" || fail "rule-engine"
curl -fsS http://localhost:8084/health >/dev/null && pass "open-api-catalog" || fail "open-api-catalog"

# 清理 port-forward
kill $PF1 $PF2 $PF3 $PF4 $PF5 2>/dev/null || true

# 4. 容器非 root 验证
log "验证容器非 root 用户..."
for svc in encaps-layer sql-gateway catalog rule-engine open-api-catalog; do
  uid=$(kubectl get pod -n "$NS" -l "app.kubernetes.io/name=$svc" -o jsonpath='{.items[0].spec.containers[0].securityContext.runAsUser}' 2>/dev/null || echo "")
  [[ "$uid" == "65532" || "$uid" == "1000" || "$uid" == "1001" ]] || log "WARN: $svc runAsUser=${uid:-未设置} (预期 65532/1000/1001)"
done

# 5. 镜像版本验证
log "验证镜像版本为 v2.1.0-RC..."
for svc in encaps-layer sql-gateway catalog rule-engine open-api-catalog; do
  img=$(kubectl get pod -n "$NS" -l "app.kubernetes.io/name=$svc" -o jsonpath='{.items[0].spec.containers[0].image}' 2>/dev/null || echo "")
  [[ "$img" == *"v2.1.0-RC"* ]] || log "WARN: $svc 镜像版本非 v2.1.0-RC: $img"
done

# 6. 关键配置验证
log "验证 catalog JWT 密钥已注入..."
kubectl get secret -n "$NS" catalog-auth >/dev/null 2>&1 || fail "catalog-auth Secret 缺失"
key_len=$(kubectl get secret -n "$NS" catalog-auth -o jsonpath='{.data.JWT_SIGNING_KEY}' | base64 -d | wc -c)
(( key_len >= 32 )) || fail "JWT_SIGNING_KEY 长度不足: $key_len 字符 (需 >=32)"
pass "catalog JWT 密钥长度: $key_len 字符"

# 7. Helm Chart 渲染验证（四环境 Profile）
log "验证四环境 Profile 渲染..."
for env in xinchuang onprem public-cloud private-cloud; do
  helm template test design/deploy/charts/dataenginebdp-umbrella \
    -f deploy/local/values-local-core.yaml \
    --set global.env="$env" >/dev/null 2>&1 || fail "Profile $env 渲染失败"
done
pass "四环境 Profile 渲染通过"

echo "=== 冒烟测试全部通过 ==="