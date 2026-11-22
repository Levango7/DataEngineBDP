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
# 离线自检（不需要 Keycloak / kubectl / 凭据，CI 可跑）：
#   bash scripts/import-keycloak-realm.sh --verify-only      # 只校验 realm 片段与代码侧契约
#
# 可选命名空间/realm：KEYCLOAK_NAMESPACE=sq-platform KEYCLOAK_REALM=sq
# 给用户授角色：--grant <用户名> <角色名>
#   bash scripts/import-keycloak-realm.sh --grant platform-root SUPER_ADMIN
#
# MFA realm 片段落地（台账 #131）：
#   片段来源 = chart 渲染的 MFA 片段 ConfigMap（design/deploy/charts/keycloak/templates/
#   realm-mfa-fragment.yaml，键名取 values.mfa.fragment.fileName，默认 sq-realm-mfa.json）：
#     kubectl get cm -n <ns> -l app.kubernetes.io/name=keycloak   # 找 *-mfa-fragment
#   取到片段后与**现有 realm** 合并，产出**最小更新体**（只含 MFA 相关字段 + 合并后的
#   requiredActions），再 kcadm update realms/<realm> -b <体> ⇒ 不整表覆盖 realm
#   （整表覆盖会把 UPDATE_PASSWORD / UPDATE_PROFILE 等既有 required action 一并抹掉）。
#   合并器：scripts/keycloak-mfa-merge.py（片段合法性由它 validate_fragment 判定）。
#
#   用法：
#     bash scripts/import-keycloak-realm.sh                            # 自动查集群 ConfigMap
#     bash scripts/import-keycloak-realm.sh --mfa-fragment ./frag.json  # 用本地片段文件
#     bash scripts/import-keycloak-realm.sh --skip-mfa                 # 只导角色，不碰 MFA
#     bash scripts/import-keycloak-realm.sh --require-mfa              # 片段拿不到即失败
#   片段拿不到（chart 未装 / mfa.fragment.enabled=false）⇒ 默认**警告并跳过**，不阻断角色导入；
#   要强制 ⇒ 加 --require-mfa。
#
# 离线自测（不需要 Keycloak / kubectl / 凭据，CI 可跑）：
#   bash scripts/import-keycloak-realm.sh --self-test
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REALM_FILE="$REPO_ROOT/design/deploy/keycloak/sq-realm-roles.json"
NAMESPACE="${KEYCLOAK_NAMESPACE:-sq-platform}"
REALM="${KEYCLOAK_REALM:-sq}"
SERVER=""
GRANT_USER=""
GRANT_ROLE=""
VERIFY_ONLY=0
SELF_TEST=0
SKIP_MFA=0
REQUIRE_MFA=0
MFA_FRAGMENT_FILE="${KEYCLOAK_MFA_FRAGMENT_FILE:-}"
MFA_FRAGMENT_KEY="${KEYCLOAK_MFA_FRAGMENT_KEY:-sq-realm-mfa.json}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --server) SERVER="${2:?--server 需要地址}"; shift 2 ;;
    --grant)
      GRANT_USER="${2:?--grant 需要用户名}"; GRANT_ROLE="${3:?--grant 需要角色名}"; shift 3 ;;
    --verify-only) VERIFY_ONLY=1; shift ;;
    --self-test) SELF_TEST=1; shift ;;
    --skip-mfa) SKIP_MFA=1; shift ;;
    --require-mfa) REQUIRE_MFA=1; shift ;;
    --mfa-fragment)
      MFA_FRAGMENT_FILE="${2:?--mfa-fragment 需要片段文件路径}"; shift 2 ;;
    -h|--help) sed -n '1,58p' "$0"; exit 0 ;;
    *) echo "未知参数: $1（见 --help）" >&2; exit 2 ;;
  esac
done

if [[ ! -f "$REALM_FILE" ]]; then
  echo "ERROR: 找不到 realm 片段 $REALM_FILE" >&2
  exit 2
fi

# 离线自检：不起服务、不连集群、不需要凭据。校验 realm 片段自身正确性
# 以及它与代码侧 @PreAuthorize 角色引用的一致性——这些错误在真机导入成功之后
# 才会以 403 形式暴露，届时很难归因到 realm 文件，故先离线拦一道。
if [[ "$VERIFY_ONLY" -eq 1 ]]; then
  exec python3 "$REPO_ROOT/scripts/check-keycloak-realm-fragment.py" "$@"
fi

# 离线自测（台账 #131 消费链）：合成 realm + **权威片段** → 跑真实合并器 → 断言最小更新体。
# 覆盖的是消费链的「片段 → 最小更新体」这一半；kcadm update 那一半需要真集群，
# 本仓无运行环境 ⇒ 属未验证边界，不在自测覆盖内（如实登记，不假装覆盖）。
if [[ "$SELF_TEST" -eq 1 ]]; then
  SELFTEST_DIR="$(mktemp -d)"
  trap 'rm -rf "$SELFTEST_DIR"' EXIT
  cat > "$SELFTEST_DIR/current.json" <<'JSON'
{
  "realm": "sq",
  "requiredActions": [
    {"id": "UPDATE_PASSWORD", "name": "Update Password", "enabled": true, "defaultAction": false},
    {"id": "CONFIGURE_TOTP", "name": "旧名", "enabled": false, "defaultAction": false}
  ]
}
JSON
  cat > "$SELFTEST_DIR/bad-fragment.json" <<'JSON'
{
  "otpPolicy": {"type": "totp", "algorithm": "HmacSHA1", "digits": 6, "period": 30},
  "usersProfile": {"unmanagedAttributePolicy": "ENABLED"}
}
JSON
  selftest_fail=0
  # 正对照：用权威片段合并，必须成功且产出正确的最小更新体
  if python3 "$REPO_ROOT/scripts/keycloak-mfa-merge.py" \
        "$SELFTEST_DIR/current.json" "$REPO_ROOT/design/deploy/keycloak/sq-realm-mfa.json" \
        > "$SELFTEST_DIR/merged.json"; then
    python3 - "$SELFTEST_DIR/merged.json" <<'PY' || selftest_fail=1
import json
import sys

with open(sys.argv[1], encoding="utf-8") as fh:
    d = json.load(fh)
actions = d.get("requiredActions", [])
ids = [a.get("id") for a in actions]
problems = []
if "UPDATE_PASSWORD" not in ids:
    problems.append("合并后丢了 realm 原有的 required action（UPDATE_PASSWORD）")
totp = [a for a in actions if a.get("id") == "CONFIGURE_TOTP"]
if not totp:
    problems.append("合并后没有 CONFIGURE_TOTP")
elif totp[0].get("defaultAction") is not True:
    problems.append("CONFIGURE_TOTP.defaultAction 不是布尔 true：%r" % (totp[0].get("defaultAction"),))
if d.get("otpPolicy", {}).get("type") != "totp":
    problems.append("otpPolicy.type 不是 totp")
if problems:
    for p in problems:
        print("[FAIL]", p)
    sys.exit(1)
print("[PASS] 最小更新体保留既有 required action + 覆盖 CONFIGURE_TOTP + otpPolicy 生效")
PY
  else
    echo "[FAIL] 权威片段被合并器拒绝" >&2
    selftest_fail=1
  fi
  # 负对照：片段含禁用键 usersProfile ⇒ 合并器必须拒绝（否则非法片段会被静默写进 realm）
  if python3 "$REPO_ROOT/scripts/keycloak-mfa-merge.py" \
        "$SELFTEST_DIR/current.json" "$SELFTEST_DIR/bad-fragment.json" >/dev/null 2>&1; then
    echo "[FAIL] 负对照：含 usersProfile 的片段被接受（判据失灵）" >&2
    selftest_fail=1
  else
    echo "[PASS] 负对照：含 usersProfile 的片段被合并器拒绝"
  fi
  if [[ "$selftest_fail" -eq 0 ]]; then
    echo "OK self-test: MFA 片段消费链（片段 → 最小更新体）"
  else
    echo "ERROR: MFA 片段消费链自测失败" >&2
  fi
  exit "$selftest_fail"
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
# -s 传 partial import 参数；-f 提供 realm 片段 JSON。
# 注：曾写成 `-o resource=import`，但 kcadm 的 -o 是**无值开关**（"创建后把新资源打印到
# stdout"），`resource=import` 会被当成非法选项直接报错——该脚本此前从未实跑，故没人发现。
# （2026-10-13 首次实跑 Keycloak 24.0 时暴露并修正。）
run_kcadm create partialImport -r "$REALM" \
  -s ifResourceExistsAction=OVERWRITE -f "$SRC"

# ---- MFA realm 片段落地（台账 #131）----
# 片段与角色声明是**两条独立的生效路径**：partialImport 只认它认识的资源类型，
# otpPolicy / webAuthnPolicy / requiredActions 不在其中 ⇒ 必须走 kcadm update 单独 PUT。
# 取片段的来源（优先级从高到低）：
#   ① --mfa-fragment <本地文件> / KEYCLOAK_MFA_FRAGMENT_FILE
#   ② 集群里的 MFA 片段 ConfigMap（chart 渲染物，键名 = values.mfa.fragment.fileName）
#   ③ 取不到 ⇒ 警告并跳过（--require-mfa 时改为失败）
if [[ "$SKIP_MFA" -eq 1 ]]; then
  echo "==> 跳过 MFA 片段落地（--skip-mfa）"
else
  MFA_FRAGMENT_PATH=""
  MFA_FRAGMENT_OWNED=0
  if [[ -n "$MFA_FRAGMENT_FILE" ]]; then
    if [[ ! -f "$MFA_FRAGMENT_FILE" ]]; then
      echo "ERROR: --mfa-fragment 指定的文件不存在：$MFA_FRAGMENT_FILE" >&2
      exit 2
    fi
    MFA_FRAGMENT_PATH="$MFA_FRAGMENT_FILE"
  else
    MFA_CM=""
    if command -v kubectl >/dev/null 2>&1; then
      MFA_CM="$(kubectl get cm -n "$NAMESPACE" -l app.kubernetes.io/name=keycloak \
                -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null \
                | grep -- '-mfa-fragment$' | head -n 1 || true)"
    fi
    if [[ -n "$MFA_CM" ]]; then
      MFA_FRAGMENT_PATH="$(mktemp)"
      MFA_FRAGMENT_OWNED=1
      # 键名含点号 ⇒ jsonpath 里要转义（.data.sq-realm-mfa\.json）
      if ! kubectl get cm "$MFA_CM" -n "$NAMESPACE" \
              -o jsonpath="{.data['${MFA_FRAGMENT_KEY}']}" > "$MFA_FRAGMENT_PATH" 2>/dev/null; then
        rm -f "$MFA_FRAGMENT_PATH"
        MFA_FRAGMENT_PATH=""
        MFA_FRAGMENT_OWNED=0
      fi
    fi
  fi

  if [[ -z "$MFA_FRAGMENT_PATH" || ! -s "$MFA_FRAGMENT_PATH" ]]; then
    if [[ "$REQUIRE_MFA" -eq 1 ]]; then
      echo "ERROR: 取不到 MFA realm 片段（--require-mfa）：chart 未安装或 mfa.fragment.enabled=false" >&2
      exit 1
    fi
    echo "    ⚠️ 取不到 MFA realm 片段（chart 未安装 / mfa.fragment.enabled=false / 无 kubectl 访问）"
    echo "       ⇒ 跳过 MFA 落地；需要强制请加 --require-mfa"
  else
    MFA_CURRENT_REALM="$(mktemp)"
    MFA_UPDATE_BODY="$(mktemp)"
    run_kcadm get "realms/$REALM" > "$MFA_CURRENT_REALM"
    if python3 "$REPO_ROOT/scripts/keycloak-mfa-merge.py" \
          "$MFA_CURRENT_REALM" "$MFA_FRAGMENT_PATH" > "$MFA_UPDATE_BODY"; then
      run_kcadm update "realms/$REALM" -b "$(cat "$MFA_UPDATE_BODY")" >/dev/null
      echo "    ✓ MFA 片段已合并进 realm（otpPolicy / webAuthnPolicy / requiredActions）"
    else
      echo "ERROR: MFA 片段不合法，合并器拒绝（片段来源：${MFA_FRAGMENT_PATH}）" >&2
      exit 1
    fi
    rm -f "$MFA_CURRENT_REALM" "$MFA_UPDATE_BODY"
    if [[ "$MFA_FRAGMENT_OWNED" -eq 1 ]]; then
      rm -f "$MFA_FRAGMENT_PATH"
    fi
  fi
fi

echo "==> 放开 UserProfile 未声明属性（Keycloak 24 必需，否则 tenantId 写不进用户）"
# 为什么必须单独这一步：Keycloak 24 启用 declarative UserProfile，默认只声明
# username/email/firstName/lastName 四个属性，其余属性（含 tenant-id mapper 依赖的
# `tenantId`）**在写入时被静默丢弃**——API 返回 201、GET 用户却看不到 attributes，
# 服务端也不记错误。后果：token 里 tenantId 恒空 → TenantContext 为空 →
# 所有受保护端点 401 Missing tenant identity。
# 且 `usersProfile` **不是 partialImport 支持的资源类型**（放在 realm JSON 里会被忽略，
# 实测 added=8 但 unmanagedAttributePolicy 仍为 null），必须导入后单独 PUT。
# 实测（Keycloak 24.0）：PUT users/profile 设 ENABLED 后，attributes.tenantId 立即落盘，
# 重新签发的 token 里 tenantId 正常出现。
if run_kcadm get "users/profile" -r "$REALM" >/dev/null 2>&1; then
  run_kcadm update "users/profile" -r "$REALM" \
    -s unmanagedAttributePolicy=ENABLED >/dev/null
  echo "    已设置 unmanagedAttributePolicy=ENABLED"
else
  echo "    ⚠️ 取不到 users/profile（Keycloak 版本可能不同），跳过；"
  echo "       若用户 tenantId 写不进去，请手动确认该设置"
fi

echo "==> 校验 realm 角色已落地"
# 角色清单与 design/deploy/keycloak/sq-realm-roles.json 保持一致，
# 并由 scripts/check-keycloak-realm-fragment.py 与代码侧 @PreAuthorize 引用交叉核对
# （缺角色的症状是运行期 403，而导入时毫无报错——所以这一步是必要的）。
missing=0
for want in SUPER_ADMIN TENANT_ADMIN USER ADMIN GOVERNANCE_WRITER INFRA_ADMIN SQL_GATEWAY_WRITER; do
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
  # 注：曾用 `-t` 传 body，但 kcadm 的 create **没有 -t 选项**（会报
  # "Invalid option: -t"）——传 JSON body 的正确选项是 `-b`（或 -f - 走 stdin）。
  # 该脚本此前从未实跑过 --grant 路径，所以这个错一直藏着。
  # （2026-10-13 首次实跑授权时暴露并修正。）
  run_kcadm create "users/$UID_HEX/role-mappings/realm" \
    -b '[{"id":"'"$ROLE_ID"'","name":"'"$GRANT_ROLE"'","composite":false,"clientRole":true}]' \
    >/dev/null
  echo "    已授予 $GRANT_ROLE"
fi

echo "完成。验证方式：登录取 access token，确认 realm_access.roles 与 tenantId 两个声明都在 ——"
echo "  见 docs/Keycloak角色与登录配置.md §4"
