# owasp-crs chart

台账 **#132**「可部署面零 WAF」的部署面落地形态。

## 形态

| 项 | 内容 |
| --- | --- |
| chart | `design/deploy/charts/owasp-crs/`（name `owasp-crs`，appVersion `4.8.0`） |
| 镜像 | `docker.m.daocloud.io/owasp/modsecurity-crs:4.8.0-nginx-202411071011`（nginx flavor） |
| 上游 | `coreruleset/modsecurity-crs-docker`（ModSecurity v3 + OWASP Core Rule Set） |
| 形态 | **独立边缘 WAF**：nginx + ModSecurity 反代到 APISIX 网关，APISIX 不感知 WAF 存在 |
| 阻断开关 | `MODSEC_DEFAULT_PHASE1_ACTION` / `PHASE2_ACTION`（上游默认 **pass**，本 chart 默认改 **deny**） |
| 误报处置 | CRS 排除规则（`REQUEST-900` / `RESPONSE-999`），以 ConfigMap + `subPath` 挂载覆盖镜像内置同名文件 |

### 为什么不在 APISIX 里做

仓库既有 `design/deploy/charts/apisix/values.yaml:86-91` 的插件清单里**没有 WAF 类插件**，
APISIX core 也没有 OWASP CRS 型 WAF 插件。为避免在 APISIX 里伪造一个不存在的插件配置，
WAF 独立成 chart：外部流量先进 WAF（`Service` 8080/8443），WAF 再反代 APISIX
（`BACKEND=http://apisix.apisix.svc.cluster.local:9080`）。

## 关键口径（均来自上游源码/文档，非臆测）

- **反代必须给 `BACKEND`**：上游 nginx 的 `proxy_pass ${BACKEND};`
  （`nginx/templates/includes/proxy_backend.conf.template:18`），不给就是 502。
- **上游默认不拦**：`MODSEC_DEFAULT_PHASE1_ACTION="phase:1,pass,log,tag:'\${MODSEC_TAG}'"`
  （`nginx/Dockerfile:163-164`）⇒ 只记日志。本 chart 默认改成
  `phase:1,log,auditlog,deny,tag:'${MODSEC_TAG}'`（显式阻断），可用 `--set` 调回。
- **动作串整体替换**：这两个变量整体替换 ModSecurity 的 `SecDefaultAction`
  （`src/opt/modsecurity/configure-rules.sh:141-164`）⇒ 必须写完整动作列表，不能只写 `deny`。
- **`PROXY_HOST_HEADER` 不能置空**：上游默认 `'$host'`（`nginx/Dockerfile:193`），
  置空会渲染出 `proxy_set_header Host ;`，nginx 配置非法。
- **CRS v4 分级**：`BLOCKING_PARANOIA` / `DETECTION_PARANOIA`
  （`src/opt/modsecurity/configure-rules.v4.conf`），默认 1；调高会显著增加误报。
- **排除规则路径**：上游 README 口径为
  `/etc/modsecurity.d/owasp-crs/rules/REQUEST-900-EXCLUSION-RULES-BEFORE-CRS.conf` 与
  `RESPONSE-999-EXCLUSION-RULES-AFTER-CRS.conf`；本 chart 用 `subPath` 只覆盖这两个文件，
  不遮蔽镜像内置的 CRS 规则目录。
- **镜像以 `USER nginx` 运行**（`nginx/Dockerfile` 末段），且 `/etc/nginx/conf`、
  `/opt/owasp-crs`、`/etc/modsecurity.d` 已 chown 给 nginx ⇒ `runAsUser: 101` 与镜像一致。

## 安装

```bash
helm install owasp-crs design/deploy/charts/owasp-crs \
  --namespace apisix --create-namespace
```

APISIX 以 umbrella 方式部署时（release 名不含 `apisix`），Service 名为 `<release>-apisix`：

```bash
helm install owasp-crs design/deploy/charts/owasp-crs \
  --namespace apisix --create-namespace \
  --set backend.serviceName=<umbrella-release>-apisix
```

## 密钥口径

本 chart **不含任何明文口令/密钥**。需要用平台证书替换镜像自签证书时走引用，不落明文：

```bash
kubectl -n apisix create secret tls owasp-crs-tls --cert=server.crt --key=server.key
helm install owasp-crs design/deploy/charts/owasp-crs -n apisix \
  --set tls.existingSecret=owasp-crs-tls
```

挂载路径按上游口径 `/etc/nginx/conf/server.crt` 与 `server.key`（`nginx/Dockerfile:213-214`）。

## 边界与未验证

- **未验证**：本机无 K8s 集群，本 chart 未做真机部署与 WAF 拦截验证；
  渲染层证据为 `helm lint` / `helm template`（rc=0）。
- **未验证**：镜像 tag 是否在 `docker.m.daocloud.io` 实际存在——本机 registry API 不可达
  （401/超时），由一次性探针 workflow 在 GitHub Actions runner 上判定。
- **未验证**：镜像内 nginx 用户的 uid/gid 取 101（Debian nginx 基础镜像惯例），
  未在真机核对；不匹配时用 `--set podSecurityContext.runAsUser=<uid>` 覆盖。
- **未验证**：`BLOCKING_PARANOIA=1` 下的实际误报率与拦截效果；生产前需按业务流量回归。
- **边界**：滚动 tag `nginx` 始终存在（上游 `docker-bake.hcl` 的 latest 条目产出
  `tag_base=nginx`），但滚动 tag 不可复现，故仅作探针对照，不作默认值。
