# evaluation（llm-gateway）Chart

LLM 评测服务（`platform/llm-gateway/evaluation`：数据集与评测任务执行，依赖上游 llm-gateway）的部署单元。

- **台账背景**：#2 部署单元缺口之一。
- **端口**：8086（`EVAL_PORT`）；探针 `/health`（与镜像 HEALTHCHECK 同路径）；uvicorn 启动快 ⇒ 不需要 startup 探针。
- **Secret（两路，`valueFrom.secretKeyRef`）**：

  | values 键 | 注入环境变量 | 说明 |
  |---|---|---|
  | `auth.jwtSecretName` / `auth.jwtSecretKey` | `JWT_SECRET` | 须与签发端（encaps-layer / Keycloak）一致 |
  | `llmGateway.apiKeySecret` / `llmGateway.apiKeyKey` | `LLM_GATEWAY_API_KEY` | 调用上游 llm-gateway 的 API Key（敏感） |

- **环境变量**（非敏感，进 ConfigMap）：`LLM_GATEWAY_URL`（默认 `http://llm-gateway:8084`，Service 名带
  release 前缀、按实际覆盖）、`LLM_GATEWAY_TIMEOUT`、`EVAL_REQUEST_TIMEOUT`、`EVAL_MAX_CONCURRENCY`、
  `JWT_ISSUER` / `JWT_EXPECTED_ISSUER`、`AUTH_MODE`、`DATASET_CACHE_DIR`（默认 `/app/data`，只读根下由 chart 挂 emptyDir）。
- **注意**：本服务当前未暴露 `/metrics`，`serviceMonitor` 默认关；chart 目录名 `evaluation` 取自模块路径
  basename（部署单元门禁口径），与 open-api-catalog 的 evaluation 无关。

```bash
helm template evaluation design/deploy/charts/evaluation
helm upgrade --install evaluation design/deploy/charts/evaluation   --set auth.jwtSecretName=sq-jwt --set llmGateway.apiKeySecret=sq-llm-gateway-key   -n sq-llm
```
