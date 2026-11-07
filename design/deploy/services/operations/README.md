# 数擎运营后台 operations-api · 部署副本

> **双实现并存（2026-11-06 审计标注）：本目录之外另有一份同域实现**
> `platform/operations-api/`（FastAPI 结构化正版骨架：app.py / api / jwt_auth /
> repositories，路由 `/api/v1/contracts/*` 合同 CRUD，附 tests 与 pyproject），
> 但**无 Dockerfile、不被 compose/chart/k8s 引用**。
>
> 本目录是实际被部署的那一份：`k8s.yaml:32` 以 `${REGISTRY}/operations-api:${TAG}`
> 引用本目录 `main.py` 构建的镜像，路由 `/api/v1/admin/*`（租户生命周期 / 套餐 /
> 计量 / 账单 / 看板），当前为演示态（TENANTS 内存态 `# 生产用 DB`、PACKAGES 硬编码
> `# 生产走 ConfigMap`，两处注释按裁决前处理口径保留不动）。
>
> 两边路由不相交（`/api/v1/admin/*` vs `/api/v1/contracts/*`）、各自演进。
> 哪份为正版、是否合并属**产品裁决**，见 `docs/KNOWN-FAILURES.md` #117 待裁项；
> 在裁决前请勿只改一边而不同步另一边。反向标注见
> `platform/operations-api/README.md`。

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
