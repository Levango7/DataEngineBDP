# 数擎运营后台 operations-api · 部署副本

> **SUPERSEDED（2026-11-10 裁决，KNOWN-FAILURES #119⑥）：本目录为存档副本，不再是部署单元。**
> umbrella 的 operations-api 现由 `design/deploy/charts/operations-api/`（v2.0.0）承载，
> 部署 `platform/operations-api` 正版镜像；APISIX 路由 `/api/v1/contracts/**` 已指向
> `operations-api:8081`。本目录保留不删：CI 仍编译其 `main.py`、`CONVENTIONS.md` 仍有引用。
>
> 本目录历史形态：`k8s.yaml:32` 以 `${REGISTRY}/operations-api:${TAG}` 引用本目录 `main.py`
> 构建的镜像，路由 `/api/v1/admin/*`（租户生命周期 / 套餐 / 计量 / 账单 / 看板），演示态
> （TENANTS 内存态 `# 生产用 DB`、PACKAGES 硬编码 `# 生产走 ConfigMap`，两处注释按裁决前
> 处理口径保留不动）。两边路由不相交（`/api/v1/admin/*` vs `/api/v1/contracts/*`）、
> 代码未合并、路由不共享。反向标注见 `platform/operations-api/README.md`。

## 本目录文件

| 文件 | 说明 |
|------|------|
| `main.py` | FastAPI 单文件实现（239 行）：`/api/v1/admin/*` + `/healthz`，Bearer token 强制鉴权，租户名正则白名单防 PromQL 注入 |
| `k8s.yaml` | K8s Deployment/Service（Deployment 名 `operations-api`，容器镜像 `${REGISTRY}/operations-api:${TAG}`） |
| `Dockerfile` | 本目录镜像构建 |
| `requirements.txt` | Python 依赖 |

## 安全说明（沿用 main.py 头部注释）

- 所有 `/api/v1/admin/*` 端点强制 Bearer token 鉴权（fail-fast，无默认 token）。
- 租户名经正则白名单校验，防止 PromQL 注入。
- Prometheus 查询异常记入 logger 并向上抛出，不静默吞错。
