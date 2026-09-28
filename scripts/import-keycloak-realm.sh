#!/usr/bin/env bash
# 导入 Keycloak realm 角色与客户端声明（sq-realm-roles.json）
#
# 为什么需要：平台鉴权只认 JWT 的 realm_access.roles（JwtAuthFilter.extractAuthorities
# 把它转成 ROLE_*），而本仓此前没有任何 realm 导入物，Keycloak Chart 也不消费 values 里的
# realms 段 —— 结果 SUPER_ADMIN / TENANT_ADMIN 无处授予，所有 @PreAuthorize 端点必然 403。
#
# 用法（凭据只从环境变量读，脚本不落盘不打印）：
#   export KEYCLOAK_ADMIN=admin KEYCLOAK_ADMIN_PASSWORD='<从密钥系统取>'
#   bash scripts/import-keycloak-realm.sh                    # kubectl exec 进 Pod 执行 kcadm
#   bash scripts/import-keycloak-realm.sh --server http://127.0.0.1:8081   # 本地 port-forward
#
# 可选命名空间/realm：KEYCLOAK_NAMESPACE=sq-platform KEYCLOAK_REALM=sq
# 给用户授角色：--grant <用户名> <角色名>
#   bash scripts/import-keycloak-realm.sh --grant platform-root SUPER_ADMIN
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REALM_FILE="$REPO_ROOT/design/deploy/keycloak/sq-realm-roles.json"
NAMESPACE="${KEYCLOAK_NAMESPACE:-sq-platform}"
REALM="${KEYCLOAK_REALM:-sq}"
SERVER=""
GRANT_USER=""
GRANT_ROLE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --server) SERVER="${2:?--server 需要地址}"; shift 2 ;;
    --grant)
      GRANT_USER="${2:?--grant 需要用户名}"; GRANT_ROLE="${3:?--grant 需要角色名}"; shift 3 ;;
    -h|--help) sed -n '1,22p' "$0"; exit 0 ;;
    *) echo "未知参数: $1（见 --help）" >&2; exit 2 ;;
  esac
done

if [[ ! -f "$REALM_FILE" ]]; then
  echo "ERROR: 找不到 realm 片段 $REALM_FILE" >&2
  exit 2
fi
if [[ -z "${KEYCLOAK_ADMIN:-}" || -z "${KEYCLOAK_ADMIN_PASSWORD:-}" ]]; then
  echo "ERROR: 需先 export KEYCLOAK_ADMIN 与 KEYCLOAK_ADMIN_PASSWORD" >&2
  exit 2
fi

# kcadm 命令前缀：远程模式走 kubectl exec，直连模式走本机 kcadm.sh
if [[ -z "$SERVER" ]]; then
  POD="$(kubectl get pods -n "$NAMESPACE" -l app.kubernetes.io/name=keycloak \
          -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
  if [[ -z "$POD" ]]; then
    echo "ERROR: 命名空间 $NAMESPACE 内没找到 keycloak Pod（可用 --server 直连或改 KEYCLOAK_NAMESPACE）" >&2
    exit 1
  fi
  echo "==> 目标 Pod: $NAMESPACE/$POD"
  kubectl cp "$REALM_FILE" "$NAMESPACE/$POD:/tmp/sq-realm-roles.json"
  run_kcadm() {
    kubectl exec -n "$NAMESPACE" "$POD" -- /opt/keycloak/bin/kcadm.sh "$@"
  }
  SRC="/tmp/sq-realm-roles.json"
else
  command -v kcadm.sh >/dev/null 2>&1 || {
    echo "ERROR: --server 模式需要本机可用 kcadm.sh（Keycloak 发行包 bin 目录）" >&2; exit 2; }
  run_kcadm() { kcadm.sh "$@"; }
  SRC="$REALM_FILE"
fi

echo "==> 登录 master 域"
run_kcadm config credentials --server "${SERVER:-http://localhost:8080}" \
  --realm master --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD"

echo "==> 确保 realm '$REALM' 存在"
if ! run_kcadm get "realms/$REALM" >/dev/null 2>&1; then
  run_kcadm create realms -s realm="$REALM" -s enabled=true >/dev/null
  echo "    已创建 realm $REALM"
else
  echo "    realm $REALM 已存在"
fi

echo "==> 部分导入角色与客户端声明（同名对象按 OVERWRITE 处理）"
# -s 传 partial import 参数，-o resource=import 让 kcadm 输出导入摘要而非整个实体
run_kcadm create partialImport -r "$REALM" \
  -s ifResourceExistsAction=OVERWRITE -o resource=import -f "$SRC"

echo "==> 校验 realm 角色已落地"
missing=0
for want in SUPER_ADMIN TENANT_ADMIN USER; do
  if run_kcadm get "roles/$want" -r "$REALM" >/dev/null 2>&1; then
    echo "    ✓ $want"
  else
    echo "    ✗ $want 缺失"
    missing=1
  fi
done
[[ "$missing" -eq 0 ]] || { echo "ERROR: 角色导入不完整" >&2; exit 1; }

if [[ -n "$GRANT_USER" ]]; then
  echo "==> 给用户 $GRANT_USER 授予 realm 角色 $GRANT_ROLE"
  UID_HEX="$(run_kcadm get users -r "$REALM" -q search="$GRANT_USER" -q exact=true \
    | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d[0]["id"] if d else "")')"
  if [[ -z "$UID_HEX" ]]; then
    echo "ERROR: realm $REALM 中找不到用户 $GRANT_USER（先在 Keycloak 建用户再授权）" >&2
    exit 1
  fi
  ROLE_ID="$(run_kcadm get "roles/$GRANT_ROLE" -r "$REALM" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')"
  # role-mappings/realm 接受的是 JSON 数组
  run_kcadm create "users/$UID_HEX/role-mappings/realm" \
    -t '[{"id":"'"$ROLE_ID"'","name":"'"$GRANT_ROLE"'","composite":false,"clientRole":true}]' \
    >/dev/null
  echo "    已授予 $GRANT_ROLE"
fi

echo "完成。验证方式：登录取 access token，确认 realm_access.roles 与 tenantId 两个声明都在 ——"
echo "  见 docs/Keycloak角色与登录配置.md §4"
