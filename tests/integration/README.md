# 数据引擎大数据平台 · 集成测试

> 基于 pytest 的 REST API 集成测试框架，验证 9 个自研组件的端点正确性。
> 其中 4 个 Java/Go 组件通过 Docker Compose 运行，5 个 Python 组件通过本地 Python 直接运行。

## 集成测试概述

本目录包含数据引擎大数据平台（DataEngineBDP）9 个自研组件的集成测试：

### Java/Go 组件（Docker Compose 方式）

| 组件 | 技术栈 | 端口 | 测试文件 | 主要端点 |
|------|--------|------|----------|----------|
| 封装层 encaps-layer | Java/Spring Boot | 18080 | `test_encaps.py` | `/api/v1/tenants` CRUD |
| 统一 SQL 网关 sql-gateway | Java/Spring Boot | 18081 | `test_sql_gateway.py` | `/api/v1/sql/execute`、`/api/v1/sql/routes`、`/api/v1/sql/engines` |
| 元数据目录 catalog | Go/Gin | 18082 | `test_catalog.py` | `/api/v1/catalog/databases`、`/api/v1/catalog/tables` CRUD |
| 规则引擎 rule-engine | Java/Spring Boot | 18083 | `test_rule_engine.py` | `/api/v1/rules` CRUD + `/api/v1/rules/execute` |

### Python 组件（本地 Python 直接运行）

| 组件 | 技术栈 | 端口 | 测试文件 | 健康检查 | 主要端点 |
|------|--------|------|----------|----------|----------|
| 数据资产流通 asset-exchange | Python/FastAPI | 8087 | `test_asset_exchange.py` | `/api/v1/health` | `/api/v1/assets` CRUD + 订费/订阅 |
| 业务线门户 business-portal | Python/FastAPI | 8088 | `test_business_portal.py` | `/api/v1/health` | `/api/v1/business-lines` CRUD |
| 开放 API 目录 open-api-catalog | Python/FastAPI | 8090 | `test_open_api_catalog.py` | `/api/v1/health` | `/api/v1/apis` CRUD + 订阅/调用 |
| 行业模板 industry-templates | Python/FastAPI | 8091 | `test_industry_templates.py` | `/api/v1/health` | `/api/v1/templates` 列表/详情/部署 |
| 知识引擎 knowledge-engine | Python/FastAPI | 8092 | `test_knowledge_engine.py` | `/health` | `/api/v1/spaces` 知识空间 CRUD + 图查询 |

> **注意**：knowledge-engine 健康检查路径为 `/health`（无 `/api/v1` 前缀），返回 `status=ok`；
> 其余 Python 组件健康检查路径为 `/api/v1/health`，返回 `status=UP`。

## 端口分配表

| 端口 | 组件 | 运行方式 | 说明 |
|------|------|----------|------|
| 18080 | encaps-layer | Docker | 映射到容器内 8080，避开本机 8080 占用 |
| 18081 | sql-gateway | Docker | 映射到容器内 8081 |
| 18082 | catalog | Docker | 映射到容器内 8082 |
| 18083 | rule-engine | Docker | 映射到容器内 8083 |
| 8087 | asset-exchange | 本地 Python | FastAPI 原生端口 |
| 8088 | business-portal | 本地 Python | FastAPI 原生端口 |
| 8090 | open-api-catalog | 本地 Python | FastAPI 原生端口 |
| 8091 | industry-templates | 本地 Python | FastAPI 原生端口 |
| 8092 | knowledge-engine | 本地 Python | FastAPI（测试端口，避开本机 8080 占用） |
| 19090 | prometheus | Docker | Prometheus 监控 |
| 13000 | grafana | Docker | Grafana 可视化 |
| 16686 | jaeger | Docker | Jaeger UI |

## 目录结构

```
tests/integration/
├── conftest.py                       # pytest 配置与 fixtures（Java/Go URL + Python 组件自动启动）
├── test_encaps.py                    # 封装层集成测试（8 个用例）
├── test_sql_gateway.py               # SQL 网关集成测试（6 个用例）
├── test_catalog.py                   # Catalog 集成测试（10 个用例）
├── test_rule_engine.py               # 规则引擎集成测试（9 个用例）
├── test_asset_exchange.py            # Asset Exchange 集成测试（11 个用例）
├── test_business_portal.py           # Business Portal 集成测试（9 个用例）
├── test_open_api_catalog.py          # Open API Catalog 集成测试（8 个用例）
├── test_industry_templates.py        # Industry Templates 集成测试（7 个用例）
├── test_knowledge_engine.py          # Knowledge Engine 集成测试（7 个用例）
├── docker-compose.yml                # Java/Go 组件测试环境编排
├── run_python_tests.ps1              # Python 组件测试运行脚本（Windows）
├── run_python_tests.sh               # Python 组件测试运行脚本（Linux/macOS）
├── requirements.txt                  # Python 依赖
└── README.md                         # 本文档
```

## 前置条件

- **Python**：3.10 及以上（推荐 3.11+，已验证 3.14）
- **pip**：最新版本
- **Docker**：20.10+（仅 Java/Go 组件需要）
- **docker-compose**：v2 或 `docker compose` 子命令（仅 Java/Go 组件需要）
- **curl**：在容器内可用（healthcheck 依赖）

## 快速开始

### 方式一：运行 Java/Go 组件测试（Docker Compose 方式）

适用于 encaps-layer / sql-gateway / catalog / rule-engine 4 个组件。

#### 1. 安装 Python 依赖

```bash
pip install -r tests/integration/requirements.txt
```

#### 2. 启动被测组件（Docker 方式）

```bash
# 构建并后台启动 4 个组件
docker-compose -f tests/integration/docker-compose.yml up -d --build

# 等待所有服务健康检查通过（Compose v2 支持 --wait）
docker-compose -f tests/integration/docker-compose.yml up -d --wait

# 查看状态
docker-compose -f tests/integration/docker-compose.yml ps
```

#### 3. 运行集成测试

```bash
# 运行 Java/Go 组件测试（4 个文件）
pytest tests/integration/test_encaps.py tests/integration/test_sql_gateway.py \
       tests/integration/test_catalog.py tests/integration/test_rule_engine.py

# 运行单个组件测试
pytest tests/integration/test_encaps.py
```

#### 4. 销毁测试环境

```bash
docker-compose -f tests/integration/docker-compose.yml down -v
```

### 方式二：运行 Python 组件测试（本地 Python 方式）

适用于 asset-exchange / business-portal / open-api-catalog / industry-templates / knowledge-engine 5 个组件。
**无需 Docker**，组件由 conftest.py 中的 fixture 自动启动。

#### 1. 安装集成测试依赖

```bash
pip install -r tests/integration/requirements.txt
pip install httpx  # Python 组件测试使用 httpx 发送请求
```

#### 2. 安装各 Python 组件依赖

```bash
# 各组件目录下执行（按需）
pip install -r platform/asset-exchange/requirements.txt
pip install -r platform/business-portal/requirements.txt
pip install -r platform/open-api-catalog/requirements.txt
pip install -r platform/industry-templates/requirements.txt
pip install -r platform/knowledge-engine/requirements.txt
```

#### 3. 运行 Python 组件测试

**Windows PowerShell：**

```powershell
# 运行全部 5 个 Python 组件测试（自动启动/停止组件）
.\tests\integration\run_python_tests.ps1

# 运行单个组件测试
.\tests\integration\run_python_tests.ps1 -TestFile test_asset_exchange.py

# 详细日志
.\tests\integration\run_python_tests.ps1 -Verbose
```

**Linux/macOS：**

```bash
# 运行全部 5 个 Python 组件测试
./tests/integration/run_python_tests.sh

# 运行单个组件测试
./tests/integration/run_python_tests.sh test_knowledge_engine.py
```

**直接用 pytest：**

```bash
# conftest.py 中的 fixture 会自动启动 Python 组件
pytest tests/integration/test_asset_exchange.py tests/integration/test_business_portal.py \
       tests/integration/test_open_api_catalog.py tests/integration/test_industry_templates.py \
       tests/integration/test_knowledge_engine.py -v

# 运行单个组件
pytest tests/integration/test_asset_exchange.py -v
```

#### 4. 手动启动组件（可选）

若想手动启动组件而非由 fixture 自动启动，设置环境变量 `SQ_IT_SKIP_PYTHON_START=1`：

```bash
# 手动启动 asset-exchange
export SQ_IT_SKIP_PYTHON_START=1
cd platform/asset-exchange && ASSET_EXCHANGE_PORT=8087 ASSET_EXCHANGE_STORE_TYPE=mock python main.py &

# 然后运行测试（fixture 检测到端口已占用则直接复用）
pytest tests/integration/test_asset_exchange.py -v
```

各组件启动命令与端口/环境变量对应关系：

| 组件 | 启动命令 | 端口环境变量 | 存储环境变量 |
|------|----------|-------------|-------------|
| asset-exchange | `cd platform/asset-exchange && python main.py` | `ASSET_EXCHANGE_PORT=8087` | `ASSET_EXCHANGE_STORE_TYPE=mock` |
| business-portal | `cd platform/business-portal && python main.py` | `BP_PORT=8088` | `BP_STORE_TYPE=mock` |
| open-api-catalog | `cd platform/open-api-catalog && python main.py` | `OPENAPI_CATALOG_PORT=8090` | `OPENAPI_CATALOG_STORE_TYPE=mock` |
| industry-templates | `cd platform/industry-templates && python main.py` | `INDUSTRY_TEMPLATES_PORT=8091` | `INDUSTRY_TEMPLATES_DEPLOY_MODE=mock` |
| knowledge-engine | `cd platform/knowledge-engine && python main.py` | `KE_PORT=8092` | `KE_STORE_TYPE=mock` `KE_EXTRACTOR_TYPE=mock` |

### 方式三：运行全部组件测试

```bash
# 先启动 Java/Go 组件（Docker）
docker-compose -f tests/integration/docker-compose.yml up -d --wait

# 运行全部 9 个组件测试（Python 组件由 fixture 自动启动）
pytest tests/integration/ -v

# 销毁 Docker 环境
docker-compose -f tests/integration/docker-compose.yml down -v
```

## 测试用例说明

### 封装层 `test_encaps.py`

| 用例 | 说明 |
|------|------|
| `test_health_check` | 健康检查返回 200 且 status=UP |
| `test_create_tenant` | 创建租户返回 201，响应含 id |
| `test_list_tenants` | 列出租户返回 200，含已创建租户 |
| `test_get_tenant` | 获取单个租户返回 200，字段一致 |
| `test_get_tenant_not_found` | 不存在的 id 返回 404 |
| `test_update_tenant` | 更新租户返回 200，字段已更新 |
| `test_delete_tenant` | 删除租户返回 204，再次删除 404 |
| `test_tenant_crud_flow` | 端到端 CRUD 流程 |

### SQL 网关 `test_sql_gateway.py`

| 用例 | 说明 |
|------|------|
| `test_health_check` | 健康检查 |
| `test_list_engines` | 引擎列表为 `["trino","doris"]` |
| `test_list_routes` | 路由规则列表返回 200 |
| `test_add_route` | 添加路由规则成功且可查到 |
| `test_execute_sql` | 执行 SQL 返回 200，含 queryId/status/engine |
| `test_execute_sql_with_doris` | 显式指定 doris 引擎 |
| `test_sql_execution_flow` | 端到端：添加路由 → 执行命中路由的 SQL |

### Catalog `test_catalog.py`

| 用例 | 说明 |
|------|------|
| `test_health_check` | 健康检查，额外校验 component=catalog |
| `test_create_database` | 创建数据库 |
| `test_list_databases` | 列出数据库 |
| `test_create_table` | 创建表（含列定义与分区键） |
| `test_list_tables` | 列出表 |
| `test_list_tables_by_database` | 按库名过滤表 |
| `test_get_table` | 获取单个表 |
| `test_get_table_not_found` | 不存在的 id 返回 404 |
| `test_update_table` | 更新表 |
| `test_delete_table` | 删除表返回 204 |
| `test_catalog_crud_flow` | 端到端：建库 → 建表 → 获取 → 更新 → 删表 → 删库 |

### 规则引擎 `test_rule_engine.py`

| 用例 | 说明 |
|------|------|
| `test_health_check` | 健康检查 |
| `test_list_rule_types` | 规则类型为 `["DQ","MASK","ALERT"]` |
| `test_create_rule` | 创建规则 |
| `test_list_rules` | 列出规则 |
| `test_get_rule` | 获取单个规则 |
| `test_get_rule_not_found` | 不存在的 id 返回 404 |
| `test_update_rule` | 更新规则 |
| `test_delete_rule` | 删除规则返回 204 |
| `test_execute_rule` | 执行规则返回 200，含 ruleId/status |
| `test_execute_rule_not_found` | 执行不存在的规则返回 404 |
| `test_rule_crud_and_execute_flow` | 端到端：创建 → 获取 → 更新 → 执行 → 删除 |

### Asset Exchange `test_asset_exchange.py`（Python, port 8087）

| 用例 | 说明 |
|------|------|
| `test_health_check` | 健康检查返回 200 且 status=UP |
| `test_openapi_schema` | OpenAPI schema 可访问 |
| `test_list_assets_empty` | 浏览资产市场返回 200 且为列表 |
| `test_create_asset` | 上架资产返回 201，含 id 与 name |
| `test_get_asset` | 获取资产详情返回 200，字段一致 |
| `test_get_asset_not_found` | 不存在的 id 返回 404 |
| `test_update_asset` | 更新资产返回 200，字段已更新 |
| `test_delete_asset` | 下架资产返回 204 |
| `test_asset_usage` | 获取资产使用统计返回 200 |
| `test_asset_billing` | 获取资产计费记录返回 200 |
| `test_asset_subscriptions_empty` | 新上架资产的订阅列表为空 |
| `test_asset_crud_flow` | 端到端：上架 → 获取 → 更新 → 列表 → 下架 |

### Business Portal `test_business_portal.py`（Python, port 8088）

| 用例 | 说明 |
|------|------|
| `test_health_check` | 健康检查返回 200，module=business-portal |
| `test_openapi_schema` | OpenAPI schema 可访问 |
| `test_list_business_lines` | 列出业务线返回 200 且为列表 |
| `test_create_business_line` | 创建业务线返回 201，含 id 与 name |
| `test_get_business_line` | 获取业务线详情返回 200 |
| `test_get_business_line_not_found` | 不存在的 id 返回 404 |
| `test_update_business_line` | 更新业务线返回 200，字段已更新 |
| `test_delete_business_line` | 删除业务线返回 204 |
| `test_business_line_crud_flow` | 端到端：创建 → 获取 → 更新 → 列表 → 删除 |

### Open API Catalog `test_open_api_catalog.py`（Python, port 8090）

| 用例 | 说明 |
|------|------|
| `test_health_check` | 健康检查返回 200，module=open-api-catalog |
| `test_openapi_schema` | OpenAPI schema 可访问 |
| `test_list_apis` | 浏览 API 目录返回 200 且为列表 |
| `test_register_api` | 注册 API 返回 201，含 id 与 name |
| `test_get_api` | 获取 API 详情返回 200 |
| `test_get_api_not_found` | 不存在的 id 返回 404 |
| `test_update_api` | 更新 API 返回 200，字段已更新 |
| `test_delete_api` | 下线 API 返回 204 |
| `test_api_crud_flow` | 端到端：注册 → 获取 → 更新 → 列表 → 下线 |

### Industry Templates `test_industry_templates.py`（Python, port 8091）

| 用例 | 说明 |
|------|------|
| `test_health_check` | 健康检查返回 200，含 deployMode 与 templateCount |
| `test_openapi_schema` | OpenAPI schema 可访问 |
| `test_list_templates` | 列出所有模板返回 200 且为列表 |
| `test_list_templates_with_filter` | 按行业过滤模板返回 200 |
| `test_list_categories` | 列出行业分类返回 200 |
| `test_get_template_not_found` | 不存在的模板 id 返回 404 |
| `test_template_detail_and_deploy_flow` | 端到端：列出 → 详情 → 部署（mock 模式） |

### Knowledge Engine `test_knowledge_engine.py`（Python, port 8080）

| 用例 | 说明 |
|------|------|
| `test_health_check` | 健康检查返回 200 且 status=ok |
| `test_openapi_schema` | OpenAPI schema 可访问 |
| `test_list_spaces` | 列出知识空间返回 200 且为列表 |
| `test_create_space` | 创建知识空间返回 201，含 name |
| `test_delete_space` | 删除知识空间返回 200/204 |
| `test_extract_from_text` | 从文本抽取知识返回 200，含实体/关系 |
| `test_build_and_query_flow` | 端到端：建空间 → 插入实体 → 查询顶点 → 图查询 → 删空间 |

## 测试报告生成

### HTML 报告

```bash
pytest tests/integration/ --html=tests/integration/report.html --self-contained-html
```

生成的 `report.html` 可直接在浏览器打开，包含用例列表、耗时、失败堆栈。

### JUnit XML（CI 集成用）

```bash
pytest tests/integration/ --junitxml=tests/integration/junit.xml
```

### 详细日志

```bash
pytest tests/integration/ -v --log-cli-level=INFO
```

## 并行执行

使用 `pytest-xdist` 加速：

```bash
# 自动按 CPU 核数并行
pytest tests/integration/ -n auto

# 指定 4 个进程
pytest tests/integration/ -n 4

# 并行 + HTML 报告
pytest tests/integration/ -n auto --html=report.html
```

> 注意：并行执行时，各组件的内存存储（如 encaps-layer 的租户表）是共享的，
> 测试用例已设计为相互独立（每个用例自管理数据），可安全并行。

## 服务不可用时的行为

`conftest.py` 中的 `pytest_collection_modifyitems` 钩子会在收集阶段探测各服务健康检查：
- **Java/Go 组件**：若某服务不可用，对应测试文件的所有用例自动 **跳过**（而非失败）；
- **Python 组件**：默认由 fixture 自动启动，**不会因端口未占用而跳过**；
  若设置 `SQ_IT_SKIP_PYTHON_START=1` 且组件未外部启动，则跳过对应测试。
- 跳过原因写入 `pytest.mark.skip` 的 reason 字段，报告中可见。

这意味着：在仅启动部分组件时，仍可运行可用组件的测试，其余自动跳过。

## Python 组件自动启动机制

`conftest.py` 为 5 个 Python 组件提供了 session 级别的 fixture，自动管理组件生命周期：

1. **首次调用** fixture 时，通过 `subprocess.Popen` 启动 `python main.py`；
2. **等待健康检查** 通过后（最多 30 秒），返回基础 URL；
3. **session 期间** 组件保持运行，所有测试共享同一进程；
4. **session 结束** 时，`_python_components_finalizer` 自动终止所有子进程。

环境变量自动设置（各组件 `settings.py` 读取）：

| 组件 | 端口变量 | 存储变量 | 健康检查 |
|------|----------|----------|----------|
| asset-exchange | `ASSET_EXCHANGE_PORT=8087` | `ASSET_EXCHANGE_STORE_TYPE=mock` | `/api/v1/health` |
| business-portal | `BP_PORT=8088` | `BP_STORE_TYPE=mock` | `/api/v1/health` |
| open-api-catalog | `OPENAPI_CATALOG_PORT=8090` | `OPENAPI_CATALOG_STORE_TYPE=mock` | `/api/v1/health` |
| industry-templates | `INDUSTRY_TEMPLATES_PORT=8091` | `INDUSTRY_TEMPLATES_DEPLOY_MODE=mock` | `/api/v1/health` |
| knowledge-engine | `KE_PORT=8092` | `KE_STORE_TYPE=mock` `KE_EXTRACTOR_TYPE=mock` | `/health` |

## CI 集成说明

### GitHub Actions 示例

```yaml
name: Integration Tests
on: [push, pull_request]
jobs:
  it:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.11'
      - run: pip install -r tests/integration/requirements.txt
      - run: docker-compose -f tests/integration/docker-compose.yml up -d --build --wait
      - run: pytest tests/integration/ -n auto --html=report.html --junitxml=junit.xml
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: it-reports
          path: |
            report.html
            junit.xml
      - run: docker-compose -f tests/integration/docker-compose.yml down -v
        if: always()
```

### 关键 CI 建议

1. **服务就绪等待**：使用 `--wait` 或在 pytest 前加 `sleep 10`，确保 healthcheck 通过；
2. **失败时保留报告**：`if: always()` 上传 artifact，便于排查；
3. **资源清理**：`if: always()` 执行 `down -v`，避免容器泄漏；
4. **并行度**：CI 上建议 `-n 4` 而非 `-n auto`，避免资源争抢。

## 设计约束

- 使用 **pytest** 框架，所有用例为函数式（非 unittest.TestCase）；
- **Java/Go 组件测试** 使用 `requests` 库调用 HTTP API，通过 Docker Compose 运行被测组件；
- **Python 组件测试** 使用 `httpx` 库调用 HTTP API，通过 `subprocess.Popen` 本地启动被测组件；
- 每个测试函数有清晰 **docstring**，说明被测端点与期望；
- 使用 `pytest.mark.skip`（通过钩子自动添加）在服务不可用时跳过；
- 测试之间 **相互独立**，不依赖执行顺序；
- 使用 **pytest fixtures** 管理测试数据（`sample_tenant`、`sample_database`、`sample_table`、`sample_rule` 在测试后自动清理）；
- `docker-compose.yml` 中所有 Java/Go 服务都配置 **healthcheck**，确保就绪后再执行测试；
- Python 组件由 session 级 fixture 自动启动/停止，**无需 Docker**，适合网络受限环境。