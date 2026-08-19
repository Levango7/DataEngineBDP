# JWT 密钥轮换指南

> 适用范围：DataEngineBDP 平台 Keycloak Realm + Spring Security JWT 验签 + APISIX jwt-auth 插件
> 维护者：平台安全团队
> 更新日期：2026-08-20
> 状态：生产可用
> 关联文档：`SECRETS-MANAGEMENT-GUIDE.md`、`design/deploy/charts/finance-template/values.yaml`

---

## 1. 轮换必要性

### 1.1 合规与安全驱动

| 驱动项 | 说明 | 来源 |
|--------|------|------|
| 等保三级 | 「身份鉴别凭证应具有有限生命周期，定期更换」 | GB/T 22239-2019 8.1.4.1 |
| 金融审计 | 「关键密钥轮换周期不超过 90 天」 | JR/T 0071-2012 |
| 密评要求 | 「签名密钥与加密密钥分离，且均需轮换」 | GM/T 0054-2018 |
| OWASP API Top10 | API2:2023 Broken Authentication 要求密钥可轮换 | OWASP 2023 |
| 内部基线 | DataEngineBDP `SECURITY.md` §3.2 要求 90 天轮换 | 项目安全策略 |

### 1.2 风险场景

- **密钥泄漏**：开发人员误将 `jwt-signing-key` 提交到 Git（GitHub Secret Scanning 报警）；
- **内部威胁**：离职员工持有旧密钥可伪造任意租户的 JWT；
- **算法淘汰**：HS256 已被多国标准建议弃用，需切换到 RS256 / EdDSA；
- **签名降级**：攻击者通过 `alg: none` 绕过验签，需密钥版本号强制校验。

### 1.3 不轮换的代价

| 时间窗口 | 风险等级 | 典型事件 |
|----------|----------|----------|
| 0-30 天 | 低 | 正常运行 |
| 30-90 天 | 中 | 依赖人员流动，泄漏概率上升 |
| 90-180 天 | 高 | 离职人员持有有效密钥，可伪造 token |
| > 180 天 | 严重 | 多次人员变动后密钥分布不可控，需全平台停机轮换 |

---

## 2. 双密钥过渡期方案

### 2.1 设计原则

采用 **双密钥并行验签 + 渐进式发布** 策略，确保轮换期间零停机、零用户掉线。

```
时间轴 ──────────────────────────────────────────────────────►

阶段 1: 仅旧密钥 K1 签发 + 验签
        ──────────────────── T0

阶段 2: 新密钥 K2 签发 + K1/K2 双验签（过渡期）
        ──────────────────── T1（轮换开始）

阶段 3: K2 签发 + K2 验签（旧 token 自然过期）
        ──────────────────── T2（旧 token 全部过期）

阶段 4: K2 签发 + K2 验签（稳态）
        ──────────────────── T3（清理 K1）
```

### 2.2 时间窗口规划

| 阶段 | 持续时间 | 操作 | 验签密钥集 |
|------|----------|------|-----------|
| T0 → T1 | 0 | 触发轮换：生成 K2，发布到 Keycloak/Spring/APISIX | {K1} |
| T1 → T2 | max(token TTL) = 24h | 双密钥并行验签，新 token 由 K2 签发 | {K1, K2} |
| T2 → T3 | 7d（观察期） | 仅 K2 验签，监控异常 | {K2} |
| T3 | - | 归档 K1，更新审计记录 | {K2} |

> **关键约束**：过渡期时长 ≥ 最长 token TTL。本平台 access_token TTL=2h、refresh_token TTL=24h，故过渡期至少 24h。

### 2.3 双密钥验签逻辑（Spring Security）

```java
/**
 * 双密钥验签：轮换过渡期同时接受 K1（旧）与 K2（新）签发的 token。
 * 通过 JWT claim "kid"（key id）路由到对应密钥。
 */
@Component
public class RotatingJwtDecoder implements JwtDecoder {

    private final Map<String, JwtDecoder> decodersByKeyId;
    private final String activeKid;          // 当前签发密钥 ID
    private final Set<String> acceptedKids;  // 验签接受的密钥 ID 集合

    public RotatingJwtDecoder(JwtKeyProperties props) {
        this.activeKid = props.getActiveKid();
        this.acceptedKids = props.getAcceptedKids();
        this.decodersByKeyId = new HashMap<>();
        props.getKeys().forEach(k ->
            decodersByKeyId.put(k.getKid(),
                NimbusJwtDecoder.withPublicKey(k.getPublicKey()).build()));
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        // 1. 解析 header 获取 kid（不验签，仅读 header）
        Jwt header = JwtDecoders.decodeHeader(token);
        String kid = (String) header.getHeaders().get("kid");

        // 2. 校验 kid 在白名单内（防降级攻击）
        if (!acceptedKids.contains(kid)) {
            throw new BadJwtException("Unknown key id: " + kid);
        }

        // 3. 路由到对应密钥验签
        return decodersByKeyId.get(kid).decode(token);
    }
}
```

### 2.4 token 中携带 kid

签发端（Keycloak）在 JWT header 注入 `kid`，便于验签路由：

```json
{
  "alg": "RS256",
  "typ": "JWT",
  "kid": "k2-2026w34"   // 密钥版本号
}
```

---

## 3. Keycloak Realm 配置

### 3.1 Realm JSON 配置（轮换前）

```json
{
  "realm": "dataenginebdp",
  "enabled": true,
  "sslRequired": "external",
  "accessTokenLifespan": 7200,
  "refreshTokenLifespan": 86400,
  "components": {
    "org.keycloak.keys.KeyProvider": [
      {
        "name": "rsa-active",
        "providerId": "rsa",
        "providerType": "org.keycloak.keys.KeyProvider",
        "config": {
          "priority": ["100"],
          "enabled": ["true"],
          "active": ["true"],
          "algorithm": ["RS256"],
          "keySize": ["2048"],
          "kid": ["k1-2026w20"]
        }
      }
    ]
  }
}
```

### 3.2 轮换操作（添加新密钥 + 标记旧密钥）

通过 Keycloak Admin REST API 执行：

```bash
# 1. 生成新 RSA 密钥对（K2）
NEW_KID="k2-$(date +%Yw%V)"
NEW_KEY_JSON=$(jq -n --arg kid "$NEW_KID" '{
  "name": "rsa-active-new",
  "providerId": "rsa",
  "providerType": "org.keycloak.keys.KeyProvider",
  "config": {
    "priority": ["200"],
    "enabled": ["true"],
    "active": ["true"],
    "algorithm": ["RS256"],
    "keySize": ["2048"],
    "kid": $kid
  }
}')

# 2. 添加新密钥（priority 200 > 100，新 token 由 K2 签发）
curl -X POST \
  "http://keycloak:8080/admin/realms/dataenginebdp/components" \
  -H "Authorization: Bearer ${KC_ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "$NEW_KEY_JSON"

# 3. 将旧密钥 K1 标记为非 active（仍可验签，priority 100）
curl -X PUT \
  "http://keycloak:8080/admin/realms/dataenginebdp/components/${OLD_K1_UUID}" \
  -H "Authorization: Bearer ${KC_ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"config":{"active":["false"],"enabled":["true"],"priority":["100"]}}'

# 4. 等待过渡期（24h）后，禁用旧密钥
sleep 86400
curl -X PUT \
  "http://keycloak:8080/admin/realms/dataenginebdp/components/${OLD_K1_UUID}" \
  -H "Authorization: Bearer ${KC_ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"config":{"enabled":["false"]}}'
```

### 3.3 Keycloak Realm Export（轮换后归档）

轮换完成后导出 Realm JSON 归档至 `design/deploy/keycloak/realm-dataenginebdp-${NEW_KID}.json`，便于审计追溯。

---

## 4. Spring Security 配置

### 4.1 application.yml（双密钥配置）

```yaml
app:
  jwt:
    # 当前签发密钥 ID（与 Keycloak active 密钥一致）
    active-kid: k2-2026w34
    # 验签接受的密钥 ID 集合（过渡期包含新旧两个）
    accepted-kids:
      - k2-2026w34
      - k1-2026w20
    # 密钥详情（公钥从 Keycloak JWKS 端点拉取，或本地配置）
    keys:
      - kid: k2-2026w34
        algorithm: RS256
        jwks-uri: http://keycloak:8080/realms/dataenginebdp/protocol/openid-connect/certs
        public-key: |
          -----BEGIN PUBLIC KEY-----
          MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAyY7...K2...
          -----END PUBLIC KEY-----
      - kid: k1-2026w20
        algorithm: RS256
        public-key: |
          -----BEGIN PUBLIC KEY-----
          MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxL3...K1...
          -----END PUBLIC KEY-----
    # 轮换监控
    rotation:
      warning-days: 80    # 距轮换 80 天开始告警
      max-age-days: 90    # 强制轮换阈值
```

### 4.2 SecurityFilterChain 配置

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                     RotatingJwtDecoder jwtDecoder) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/v1/auth/**").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.decoder(jwtDecoder)))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS));
        return http.build();
    }
}
```

### 4.3 轮换健康检查端点

```java
@RestController
@RequestMapping("/actuator")
public class JwtKeyHealthIndicator {

    @GetMapping("/jwt-key")
    public Map<String, Object> jwtKeyHealth() {
        return Map.of(
            "activeKid", jwtDecoder.getActiveKid(),
            "acceptedKids", jwtDecoder.getAcceptedKids(),
            "oldestKeyAgeDays", keyAgeService.oldestKeyAgeDays(),
            "rotationDue", keyAgeService.oldestKeyAgeDays() >= 90
        );
    }
}
```

---

## 5. APISIX jwt-auth 插件配置

APISIX 的 `jwt-auth` 插件支持多密钥验签，通过 `key_claim_name` 路由 kid。

```json
{
  "plugins": {
    "jwt-auth": {
      "key_claim_name": "kid",
      "algorithm": "RS256",
      "public_key_set": [
        {
          "kid": "k2-2026w34",
          "public_key": "-----BEGIN PUBLIC KEY-----\nMIIBIjAN...K2...\n-----END PUBLIC KEY-----"
        },
        {
          "kid": "k1-2026w20",
          "public_key": "-----BEGIN PUBLIC KEY-----\nMIIBIjAN...K1...\n-----END PUBLIC KEY-----"
        }
      ]
    }
  }
}
```

> **注意**：APISIX 配置通过 Admin API 下发，禁止明文密钥入 Git。生产环境通过 `design/deploy/apisix/jwt-auth-secret.yaml`（ExternalSecret 引用 Vault）注入。

---

## 6. 自动化轮换 CronJob

### 6.1 Kubernetes CronJob（每 90 天触发）

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: jwt-key-rotation
  namespace: platform-security
  labels:
    app.kubernetes.io/name: jwt-key-rotation
    app.kubernetes.io/part-of: dataenginebdp
spec:
  schedule: "0 2 1 */3 *"  # 每季度 1 号 02:00 执行（90 天周期）
  concurrencyPolicy: Forbid
  successfulJobsHistoryLimit: 3
  failedJobsHistoryLimit: 5
  jobTemplate:
    spec:
      backoffLimit: 2
      template:
        spec:
          restartPolicy: OnFailure
          serviceAccountName: jwt-rotator
          containers:
            - name: rotator
              image: nexus/dataenginebdp-jwt-rotator:1.0.0
              imagePullPolicy: IfNotPresent
              env:
                - name: KEYCLOAK_URL
                  value: "http://keycloak:8080"
                - name: REALM
                  value: "dataenginebdp"
                - name: TRANSITION_HOURS
                  value: "24"
                - name: KEY_ALGORITHM
                  value: "RS256"
                - name: KEY_SIZE
                  value: "2048"
                - name: SLACK_WEBHOOK
                  valueFrom:
                    secretKeyRef:
                      name: rotation-alerts
                      key: slack-webhook
              command:
                - /bin/sh
                - -c
                - |
                  set -euo pipefail
                  NEW_KID="k2-$(date +%Yw%V)"
                  echo "[1/5] 生成新密钥 ${NEW_KID}"
                  /app/generate-key.sh "${NEW_KID}"
                  echo "[2/5] 推送到 Keycloak（priority 200，active=true）"
                  /app/push-keycloak.sh "${NEW_KID}"
                  echo "[3/5] 更新 Spring Security 配置（accepted-kids += ${NEW_KID}）"
                  /app/update-spring-config.sh "${NEW_KID}"
                  echo "[4/5] 更新 APISIX jwt-auth 插件配置"
                  /app/update-apisix.sh "${NEW_KID}"
                  echo "[5/5] 等待过渡期 ${TRANSITION_HOURS}h 后禁用旧密钥"
                  /app/schedule-decommission.sh "${TRANSITION_HOURS}"
                  echo "轮换触发完成，过渡期监控中"
              resources:
                requests:
                  cpu: 100m
                  memory: 128Mi
                limits:
                  cpu: 500m
                  memory: 256Mi
```

### 6.2 RBAC（最小权限）

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: jwt-rotator
  namespace: platform-security
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: jwt-rotator
  namespace: platform-security
rules:
  - apiGroups: [""]
    resources: ["secrets"]
    resourceNames: ["jwt-keys-active", "jwt-keys-accepted"]
    verbs: ["get", "update", "patch"]
  - apiGroups: ["apps"]
    resources: ["deployments"]
    resourceNames: ["encaps-layer", "sql-gateway", "rule-engine"]
    verbs: ["get", "patch"]  # 仅允许滚动重启以加载新密钥
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: jwt-rotator
  namespace: platform-security
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: Role
  name: jwt-rotator
subjects:
  - kind: ServiceAccount
    name: jwt-rotator
    namespace: platform-security
```

### 6.3 轮换脚本核心逻辑（伪代码）

```bash
#!/bin/bash
# /app/generate-key.sh - 生成新 RSA 密钥对
KID=$1
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "/tmp/${KID}.pem"
openssl rsa -pubout -in "/tmp/${KID}.pem" -out "/tmp/${KID}.pub"

# 上传私钥到 Vault（仅 Keycloak 可读）
vault kv put "secret/data/jwt/${KID}" private_key=@/tmp/${KID}.pem

# 公钥可公开（写入 K8s Secret 供 Spring/APISIX 读取）
kubectl create secret generic "jwt-pubkey-${KID}" \
  --from-file=public-key=/tmp/${KID}.pub \
  --namespace=platform-security

# 清理本地临时文件
shred -u /tmp/${KID}.pem /tmp/${KID}.pub
```

---

## 7. 90 天轮换频率建议

### 7.1 频率选择依据

| 频率 | 优点 | 缺点 | 适用场景 |
|------|------|------|----------|
| 30 天 | 风险暴露窗口最短 | 运维成本高，过渡期占比大 | 高安全要求（金融核心） |
| **90 天** | **合规达标 + 运维可控** | **泄漏窗口 90 天** | **本平台推荐** |
| 180 天 | 运维成本最低 | 不满足等保三级 | 仅开发/测试环境 |
| 365 天 | 几乎免维护 | 严重违规 | 禁止 |

### 7.2 本平台推荐策略

| 环境 | 轮换周期 | 过渡期 | 触发方式 |
|------|----------|--------|----------|
| 生产（prod） | **90 天** | 24h | CronJob 季度执行 |
| 预发（staging） | 90 天 | 12h | CronJob 季度执行 |
| 测试（test） | 180 天 | 6h | 手动触发 |
| 开发（dev） | 不轮换 | - | 固定密钥 |

### 7.3 监控与告警

```yaml
# PrometheusRule：距轮换 80 天告警
apiVersion: monitoring.coreos.com/v1
kind: PrometheusRule
metadata:
  name: jwt-key-rotation-alert
  namespace: platform-security
spec:
  groups:
    - name: jwt-key
      rules:
        - alert: JwtKeyRotationDueSoon
          expr: jwt_key_oldest_age_days > 80
          for: 1h
          labels:
            severity: warning
            team: security
          annotations:
            summary: "JWT 密钥即将到达轮换阈值（>80 天）"
            description: "当前最旧密钥已使用 {{ $value }} 天，请在 10 天内完成轮换"
        - alert: JwtKeyRotationOverdue
          expr: jwt_key_oldest_age_days > 90
          for: 5m
          labels:
            severity: critical
            team: security
          annotations:
            summary: "JWT 密钥已超期未轮换（>90 天）"
            description: "当前最旧密钥已使用 {{ $value }} 天，违反等保三级要求，立即轮换"
        - alert: JwtKeyTransitionStuck
          expr: jwt_key_transition_active == 1 and on() time() - jwt_key_transition_start_time > 86400 * 2
          for: 5m
          labels:
            severity: critical
            team: security
          annotations:
            summary: "JWT 密钥过渡期异常延长（>48h）"
            description: "过渡期已超过 48h，可能旧 token 未正常过期或验签配置错误"
```

---

## 8. 轮换操作 Runbook（应急手动）

### 8.1 触发条件

- 安全事件（密钥疑似泄漏）：立即触发，不等 CronJob；
- CronJob 失败超过 3 次：人工介入；
- 监控告警 `JwtKeyRotationOverdue`：24h 内必须完成。

### 8.2 操作步骤

```bash
# 0. 准备：登录跳板机，获取 Keycloak admin token
KC_ADMIN_TOKEN=$(curl -s -X POST \
  "http://keycloak:8080/realms/master/protocol/openid-connect/token" \
  -d "grant_type=password" \
  -d "client_id=admin-cli" \
  -d "username=${KC_ADMIN_USER}" \
  -d "password=${KC_ADMIN_PASS}" | jq -r .access_token)

# 1. 生成新密钥
NEW_KID="k2-$(date +%Yw%V)"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out /tmp/${NEW_KID}.pem

# 2. 推送 Keycloak（见 §3.2）

# 3. 更新 Spring Security ConfigMap
kubectl patch configmap encaps-layer-config -n platform \
  --type merge -p "{\"data\":{\"jwt.active-kid\":\"${NEW_KID}\",\"jwt.accepted-kids\":\"${NEW_KID},k1-2026w20\"}}"

# 4. 滚动重启 encaps-layer / sql-gateway / rule-engine
kubectl rollout restart deployment/encaps-layer -n platform
kubectl rollout restart deployment/sql-gateway -n platform
kubectl rollout restart deployment/rule-engine -n platform

# 5. 验证：用新 token 访问 API
NEW_TOKEN=$(curl -s -X POST \
  "http://keycloak:8080/realms/dataenginebdp/protocol/openid-connect/token" \
  -d "grant_type=password" \
  -d "client_id=dataenginebdp" \
  -d "username=test" -d "password=test" | jq -r .access_token)
curl -H "Authorization: Bearer ${NEW_TOKEN}" http://encaps-layer:8080/api/v1/tenants

# 6. 等待 24h 过渡期后，禁用旧密钥（见 §3.2 步骤 4）

# 7. 归档：将旧密钥 kid 记录到审计日志
echo "$(date -Iseconds) JWT key rotated: k1-2026w20 -> ${NEW_KID} by ${USER}" \
  >> /var/log/jwt-rotation-audit.log
```

### 8.3 回滚

若新密钥导致验签失败：

```bash
# 立即将 active-kid 切回旧密钥
kubectl patch configmap encaps-layer-config -n platform \
  --type merge -p '{"data":{"jwt.active-kid":"k1-2026w20"}}'
kubectl rollout restart deployment/encaps-layer -n platform

# 排查新密钥配置（公钥格式、kid 不匹配等）
```

---

## 9. 审计与合规检查清单

- [ ] 每次轮换记录：时间、操作人、旧 kid、新 kid、过渡期开始/结束时间
- [ ] 旧密钥归档至 Vault `secret/data/jwt-archive/` 路径，保留 1 年
- [ ] 季度合规自检：`kubectl get secret jwt-keys-active -o yaml | yq '.metadata.annotations.last-rotated'`
- [ ] 年度审计：导出 Keycloak Realm JSON + Spring Security 配置快照，提交安全部评审
- [ ] 密钥泄漏应急流程：发现泄漏后 1h 内启动轮换，4h 内完成新密钥签发

---

## 10. 相关文档

| 文档 | 说明 |
|------|------|
| `SECRETS-MANAGEMENT-GUIDE.md` | 平台 Secrets 管理总体方案（Vault / SealedSecret） |
| `SECURITY.md` | 项目安全策略与基线 |
| `design/deploy/charts/finance-template/values.yaml` | Helm Chart 中 Keycloak 凭证占位符 |
| `design/deploy/keycloak/realm-dataenginebdp.json` | Keycloak Realm 配置模板 |
| `ROADMAP.md` §v1.1 | Keycloak Realm 配置落地路线图项 |

---

## 变更记录

| 日期 | 变更 | 作者 |
|------|------|------|
| 2026-08-20 | 初始版本，覆盖双密钥过渡、Keycloak/Spring/APISIX 配置、CronJob、90 天策略 | 平台安全团队 |