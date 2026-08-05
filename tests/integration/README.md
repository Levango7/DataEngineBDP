# 数擎大数据平台 · 集成测试

> 基于 pytest 的 REST API 集成测试框架，验证 4 个自研组件的端点正确性。

## 集成测试概述

本目录包含数擎大数据平台（ShuqingBigDataPlatform）4 个自研组件的集成测试：

| 组件 | 技术栈 | 端口 | 测试文件 | 主要端点 |
|------|--------|------|----------|----------|
| 封装层 encaps-layer | Java/Spring Boot | 8080 | `test_encaps.py` | `/api/v1/tenants` CRUD |
| 统一 SQL 网关 sql-gateway | Java/Spring Boot | 8081 | `test_sql_gateway.py` | `/api/v1/sql/execute`、`/api/v1/sql/routes`、`/api/v1/sql/engines` |
| 元数据目录 catalog | Go/Gin | 8082 | `test_catalog.py` | `/api/v1/catalog/databases`、`/api/v1/catalog/tables` CRUD |
| 规则引擎 rule-engine | Java/Spring Boot | 8083 | `test_rule_engine.py` | `/api/v1/rules` CRUD + `/api/v1/rules/execute` |

所有组件均暴露 `GET /api/v1/health` 健康检查端点，返回 `{"status": "UP", ...}`。

## 目录结构

```
tests/integration/
├── conftest.py              # pytest 配置与 fixtures（API 客户端、URL、测试数据管理）
├── test_encaps.py           # 封装层集成测试（8 个用例）
├── test_sql_gateway.py      # SQL 网关集成测试（6 个用例）
├── test_catalog.py          # Catalog 集成测试（10 个用例）
├── test_rule_engine.py      # 规则引擎集成测试（9 个用例）
├── docker-compose.yml       # 测试环境编排（一键拉起 4 个组件）
├── requirements.txt         # Python 依赖
└── README.md                # 本文档
```

## 前置条件

- **Python**：3.10 及以上
- **pip**：最新版本
- **Docker**：20.10+（用于启动被测组件）
- **docker-compose**：v2 或 `docker compose` 子命令
- **curl**：在容器内可用（healthcheck 依赖）

## 快速开始

### 1. 安装 Python 依赖

```bash
pip install -r tests/integration/requirements.txt
```

### 2. 启动被测组件（Docker 方式）

```bash
# 构建并后台启动 4 个组件
docker-compose -f tests/integration/docker-compose.yml up -d --build

# 等待所有服务健康检查通过（Compose v2 支持 --wait）
docker-compose -f tests/integration/docker-compose.yml up -d --wait

# 查看状态
docker-compose -f tests/integration/docker-compose.yml ps
```

### 3. 运行集成测试

```bash
# 运行全部集成测试
pytest tests/integration/

# 运行单个组件测试
pytest tests/integration/test_encaps.py
pytest tests/integration/test_sql_gateway.py
pytest tests/integration/test_catalog.py
pytest tests/integration/test_rule_engine.py

# 运行单个测试用例
pytest tests/integration/test_encaps.py::test_tenant_crud_flow
```

### 4. 销毁测试环境

```bash
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
- 若某服务不可用，对应测试文件的所有用例自动 **跳过**（而非失败）；
- 跳过原因写入 `pytest.mark.skip` 的 reason 字段，报告中可见。

这意味着：在仅启动部分组件时，仍可运行可用组件的测试，其余自动跳过。

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
- 使用 **requests** 库调用 HTTP API，无浏览器依赖；
- 每个测试函数有清晰 **docstring**，说明被测端点与期望；
- 使用 `pytest.mark.skip`（通过钩子自动添加）在服务不可用时跳过；
- 测试之间 **相互独立**，不依赖执行顺序；
- 使用 **pytest fixtures** 管理测试数据（`sample_tenant`、`sample_database`、`sample_table`、`sample_rule` 在测试后自动清理）；
- `docker-compose.yml` 中所有服务都配置 **healthcheck**，确保就绪后再执行测试。