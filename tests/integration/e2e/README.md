# 数擎大数据平台 E2E 跨领域端到端集成测试

本目录包含数擎大数据平台（ShuqingBigDataPlatform）的跨领域端到端（E2E）集成测试套件，
覆盖 28 项需求的 E2E 验收以及 10 个跨领域全链路场景验证。

## 目录结构

```
tests/integration/e2e/
├── __init__.py                      # 包标识
├── conftest.py                      # E2E 专用 conftest（复用 docker/conftest）
├── requirements.txt                 # E2E 测试依赖
├── README.md                        # 本文件
├── test_e2e_cross_domain.py         # 10 个跨领域全链路场景测试
├── test_e2e_all_requirements.py     # 28 项需求 E2E 验收测试
├── e2e_report.py                    # E2E 测试报告生成器（HTML + JSON）
└── run_e2e.sh                       # 一键运行脚本
```

## 测试覆盖

### 1. 跨领域全链路测试（10 个场景）

| # | 测试 | 链路 |
|---|------|------|
| 1 | `test_nl2sql_to_federated_query` | NL2SQL → 联邦查询（自然语言 → SQL → 跨集群查询 → 结果） |
| 2 | `test_federated_query_to_materialized_view` | 联邦查询 → 物化视图（查询 → 物化加速 → 重写） |
| 3 | `test_materialized_view_to_ai_interpretation` | 物化视图 → AI 解读（结果 → AI 分析 → 报告） |
| 4 | `test_finetuning_to_evaluation_to_deployment` | 微调 → 评测 → 部署闭环 |
| 5 | `test_data_governance_to_quality_check` | 数据治理 → 质量检查（规则 → 检测 → 报告） |
| 6 | `test_cost_collection_to_finops_dashboard` | 成本采集 → FinOps 看板（采集 → 看板 → 建议） |
| 7 | `test_multi_cluster_failover_to_query_recovery` | 多集群故障迁移 → 查询恢复 |
| 8 | `test_stream_batch_unified_scheduling` | 流批一体调度（批 → 流 → 统一状态） |
| 9 | `test_asset_registration_to_exchange` | 资产注册 → 交易 → 授权 |
| 10 | `test_open_api_subscription_to_billing` | API 订阅 → 调用 → 计量 → 计费 |

### 2. 28 项需求 E2E 验收测试

| 优先级 | 数量 | 覆盖需求 |
|--------|------|----------|
| P0 | 11 项 | 云原生多租户、AI 推理、微调、数据联邦、实时数仓、行业模板、安全合规、统一可观测、成本管理、多集群管理、Serverless |
| P1 | 14 项 | Serverless 运行时、故障迁移、FinOps 看板、模型评测、跨集群查询、微调闭环、流批一体、实时治理、制造模板、零售模板、资产流通、开放 API、Grafana 双视图、多模态 |
| P2 | 3 项 | 数据虚拟化、能源模板、政务模板（骨架，标记 skip，Phase 3 实现） |

**总计：38 个测试用例**（10 跨领域 + 28 需求）。

## 运行方式

### 一键运行

```bash
# 启动 Docker 服务 → 运行 E2E 测试 → 生成报告 → 清理
./run_e2e.sh

# 不清理 Docker 服务（便于排查）
./run_e2e.sh --no-cleanup

# 跳过 Docker 启动（服务已外部启动）
./run_e2e.sh --no-docker

# 指定 HTML 报告路径
./run_e2e.sh --html custom_report.html
```

### 直接运行 pytest

```bash
# 安装依赖
pip install -r requirements.txt

# 运行全部 E2E 测试
python -m pytest tests/integration/e2e/ -v

# 仅运行跨领域测试
python -m pytest tests/integration/e2e/test_e2e_cross_domain.py -v

# 仅运行 28 项需求验收
python -m pytest tests/integration/e2e/test_e2e_all_requirements.py -v

# 生成 junit-xml 报告
python -m pytest tests/integration/e2e/ -v --junitxml=e2e-junit.xml
```

### 生成报告

```bash
# 从 junit-xml 生成 HTML + JSON 报告
python e2e_report.py --junit e2e-junit.xml --output e2e_report.html
```

## 服务依赖

E2E 测试依赖以下 Docker 服务（端口可通过环境变量覆盖）：

| 服务 | 默认端口 | 环境变量 | 健康检查 |
|------|----------|----------|----------|
| 封装层 | 18080 | `ENCAPS_URL` | `/actuator/health` |
| SQL 网关 | 18081 | `SQL_GATEWAY_URL` | `/actuator/health` |
| Catalog | 18082 | `CATALOG_URL` | `/api/v1/health` |
| 规则引擎 | 18083 | `RULE_ENGINE_URL` | `/actuator/health` |
| FinOps | 18084 | `FINOPS_URL` | `/api/v1/health` |
| LLM 网关 | 18085 | `LLM_GATEWAY_URL` | `/health` |
| 评测平台 | 18086 | `EVALUATION_URL` | `/health` |
| FinOps 看板 | 18087 | `FINOPS_DASHBOARD_URL` | `/api/v1/health` |
| 闭环编排 | 18088 | `FINETUNING_LOOP_URL` | `/health` |
| 模型仓库 | 18089 | `MODEL_REGISTRY_URL` | `/health` |
| Karmada | 18090 | `KARMADA_URL` | `/api/v1/health` |
| Knative | 18091 | `KNATIVE_URL` | `/api/v1/health` |
| 治理 | 18092 | `GOVERNANCE_URL` | `/api/v1/health` |
| 可观测 | 18093 | `OBSERVABILITY_URL` | `/api/v1/health` |
| 资产流通 | 18094 | `ASSET_EXCHANGE_URL` | `/api/v1/health` |
| 开放 API | 18095 | `OPEN_API_CATALOG_URL` | `/api/v1/health` |
| 行业模板 | 18096 | `INDUSTRY_TEMPLATES_URL` | `/api/v1/health` |
| 流批调度 | 18097 | `STREAM_BATCH_URL` | `/actuator/health` |
| NL2SQL | 18098 | `NL2SQL_URL` | `/health` |
| 微调 | 18099 | `FINETUNING_URL` | `/health` |
| 物化视图 | 18100 | `MATERIALIZED_VIEW_URL` | `/api/v1/health` |

**服务不可用时的行为**：测试用例自动 `skip`，不会报错。跨领域测试在关键服务（封装层/SQL 网关/Catalog/规则引擎/LLM 网关/NL2SQL/Karmada/FinOps/微调/评测）可用数 < 4 时整体跳过。

## 报告说明

### HTML 报告

包含：
- 汇总卡片（总数/通过/失败/跳过/错误）
- 需求覆盖矩阵（每项需求的用例数与通过/失败/跳过统计）
- 跨领域场景汇总
- 用例明细表（含优先级徽章、覆盖需求、耗时、失败消息）

### JSON 报告

结构化数据，便于 CI 系统解析与二次加工。

## 设计要点

1. **复用 docker/conftest**：E2E conftest 显式导入 `tests/integration/docker/conftest.py` 的公共能力（api_client、*_url、*_available fixtures），避免重复实现。
2. **自动跳过**：通过 `pytest_collection_modifyitems` 钩子在收集阶段判断服务可用性，对不可用的测试自动标记 skip，避免在无 Docker 环境中产生大量连接错误。
3. **租户隔离**：所有 E2E 测试使用统一的 `e2e-tenant` 租户上下文，JWT token 由 conftest 统一签发。
4. **资源清理**：每个创建资源的测试在 `finally` 块中清理，避免污染后续测试。
5. **P2 骨架**：数据虚拟化、能源模板、政务模板三项 P2 需求在 Phase 3 实现，当前测试标记 `@pytest.mark.skip`，实现后取消 skip 即可。
6. **自定义标记**：注册了 `cross_domain`、`requirement`、`p0`、`p1`、`p2` 五个标记，便于筛选与报告统计。

## 验证步骤

```bash
# 1. Python 语法检查
python -m py_compile tests/integration/e2e/*.py

# 2. pytest 测试收集（不执行）
python -m pytest tests/integration/e2e/ --noconftest --collect-only -q

# 3. 确认用例数 >= 38
python -m pytest tests/integration/e2e/ --collect-only -q | tail -1
```