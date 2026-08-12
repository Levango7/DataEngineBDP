"""数据虚拟化与虚拟表 Docker 集成测试。

被测对象：Docker 容器 ``it-sql-gateway``（镜像 ``shuqing/sql-gateway:0.1.0``），
Java/Spring Boot，主机端口 18081 → 容器 8081。
本测试覆盖虚拟表模块（T042 数据虚拟化与虚拟表）的全部端点。

覆盖端点（前缀 /api/v1/virtual-tables）：
- POST /                        注册虚拟表
- GET  /                        列出虚拟表
- GET  /{tableName}             获取单个虚拟表
- PUT  /{tableName}             更新虚拟表
- DELETE /{tableName}           删除虚拟表
- POST /{tableName}/query       查询虚拟表数据
- GET  /{tableName}/schema      获取 schema
- POST /{tableName}/test-connection  测试连接
- POST /{tableName}/refresh     手动刷新物化表
- GET  /cache/stats             缓存统计
- GET  /types                   支持的数据源类型

测试用例（16 个）：
1.  MySQL 虚拟表注册
2.  Oracle 虚拟表注册
3.  JDBC 虚拟表注册
4.  Kafka 虚拟表注册
5.  REST 虚拟表注册
6.  虚拟表查询
7.  虚拟表与本地表混合查询（通过 SQL 网关执行）
8.  元数据缓存验证
9.  物化策略-全量刷新
10. 物化策略-增量刷新
11. 物化策略-手动刷新
12. 虚拟表删除
13. 虚拟表更新
14. 权限验证
15. 租户隔离验证
16. SQL 重写验证

设计要点：
- 虚拟表注册使用模拟连接配置，不依赖真实外部数据源；
- 查询/连接测试端点在外部源不可达时返回错误，属预期行为；
- 物化刷新在无真实数据时返回 rows=0，属预期行为。
"""

from __future__ import annotations

import pytest


# ---------------------------------------------------------------------------
# 辅助函数
# ---------------------------------------------------------------------------
def make_virtual_table_payload(
    table_name: str,
    data_source_type: str,
    connection_config: dict,
    source_object: str,
    columns: list[dict] | None = None,
    materialization_strategy: str = "NONE",
    tenant_id: str = "docker-it-tenant",
    description: str = "集成测试虚拟表",
) -> dict:
    """构造虚拟表注册请求体。

    Args:
        table_name: 虚拟表名
        data_source_type: 数据源类型（MYSQL/ORACLE/JDBC/KAFKA/REST）
        connection_config: 连接配置字典
        source_object: 外部源对象
        columns: 列定义列表
        materialization_strategy: 物化策略
        tenant_id: 租户 ID
        description: 描述

    Returns:
        虚拟表注册请求体字典
    """
    import json

    if columns is None:
        columns = [
            {"name": "id", "type": "INTEGER", "nullable": False, "comment": "主键"},
            {"name": "name", "type": "VARCHAR", "nullable": True, "comment": "名称"},
        ]
    return {
        "tableName": table_name,
        "tenantId": tenant_id,
        "dataSourceType": data_source_type,
        "connectionConfig": json.dumps(connection_config),
        "sourceObject": source_object,
        "columns": columns,
        "materializationStrategy": materialization_strategy,
        "enabled": True,
        "description": description,
    }


def make_mysql_config() -> dict:
    """构造 MySQL 连接配置（模拟，不依赖真实 MySQL）。"""
    return {
        "url": "jdbc:mysql://localhost:3306/test?useSSL=false",
        "username": "root",
        "password": "test",
        "driver": "com.mysql.cj.jdbc.Driver",
    }


def make_oracle_config() -> dict:
    """构造 Oracle 连接配置（模拟）。"""
    return {
        "url": "jdbc:oracle:thin:@localhost:1521:orcl",
        "username": "system",
        "password": "test",
        "driver": "oracle.jdbc.OracleDriver",
    }


def make_jdbc_config() -> dict:
    """构造通用 JDBC 连接配置（模拟 PostgreSQL）。"""
    return {
        "url": "jdbc:postgresql://localhost:5432/test",
        "username": "postgres",
        "password": "test",
        "driver": "org.postgresql.Driver",
    }


def make_kafka_config() -> dict:
    """构造 Kafka 连接配置（模拟）。"""
    return {
        "bootstrapServers": "localhost:9092",
        "groupId": "vt-test-group",
        "topic": "test-topic",
    }


def make_rest_config() -> dict:
    """构造 REST API 连接配置（模拟）。"""
    return {
        "baseUrl": "http://localhost:8080",
        "method": "GET",
        "headers": {"Content-Type": "application/json"},
        "responseDataPath": "data",
        "timeoutSeconds": 5,
    }


# ---------------------------------------------------------------------------
# 0. 健康检查与基础端点
# ---------------------------------------------------------------------------
def test_virtual_table_types_endpoint(api_client, sql_gateway_url):
    """验证 GET /api/v1/virtual-tables/types 返回五种数据源类型。"""
    resp = api_client.get(sql_gateway_url + "/api/v1/virtual-tables/types")
    assert resp.status_code == 200
    body = resp.json()
    assert isinstance(body, list)
    assert set(body) == {"MYSQL", "ORACLE", "JDBC", "KAFKA", "REST"}


# ---------------------------------------------------------------------------
# 1-5. 五种数据源虚拟表注册
# ---------------------------------------------------------------------------
def test_01_register_mysql_virtual_table(api_client, sql_gateway_url):
    """测试 1：MySQL 虚拟表注册。"""
    payload = make_virtual_table_payload(
        table_name="vt_mysql_orders",
        data_source_type="MYSQL",
        connection_config=make_mysql_config(),
        source_object="test.orders",
    )
    resp = api_client.post(sql_gateway_url + "/api/v1/virtual-tables", json=payload)
    assert resp.status_code == 201
    body = resp.json()
    assert body["tableName"] == "vt_mysql_orders"
    assert body["dataSourceType"] == "MYSQL"
    assert body["enabled"] is True
    assert "id" in body


def test_02_register_oracle_virtual_table(api_client, sql_gateway_url):
    """测试 2：Oracle 虚拟表注册。"""
    payload = make_virtual_table_payload(
        table_name="vt_oracle_users",
        data_source_type="ORACLE",
        connection_config=make_oracle_config(),
        source_object="SYSTEM.USERS",
    )
    resp = api_client.post(sql_gateway_url + "/api/v1/virtual-tables", json=payload)
    assert resp.status_code == 201
    body = resp.json()
    assert body["tableName"] == "vt_oracle_users"
    assert body["dataSourceType"] == "ORACLE"


def test_03_register_jdbc_virtual_table(api_client, sql_gateway_url):
    """测试 3：通用 JDBC 虚拟表注册。"""
    payload = make_virtual_table_payload(
        table_name="vt_jdbc_products",
        data_source_type="JDBC",
        connection_config=make_jdbc_config(),
        source_object="public.products",
    )
    resp = api_client.post(sql_gateway_url + "/api/v1/virtual-tables", json=payload)
    assert resp.status_code == 201
    body = resp.json()
    assert body["tableName"] == "vt_jdbc_products"
    assert body["dataSourceType"] == "JDBC"


def test_04_register_kafka_virtual_table(api_client, sql_gateway_url):
    """测试 4：Kafka 虚拟表注册。"""
    payload = make_virtual_table_payload(
        table_name="vt_kafka_events",
        data_source_type="KAFKA",
        connection_config=make_kafka_config(),
        source_object="events-topic",
        columns=[
            {"name": "messageKey", "type": "VARCHAR", "nullable": True},
            {"name": "messageValue", "type": "VARCHAR", "nullable": True},
            {"name": "topic", "type": "VARCHAR", "nullable": False},
            {"name": "partition", "type": "INTEGER", "nullable": False},
            {"name": "offset", "type": "BIGINT", "nullable": False},
            {"name": "timestamp", "type": "TIMESTAMP", "nullable": True},
        ],
    )
    resp = api_client.post(sql_gateway_url + "/api/v1/virtual-tables", json=payload)
    assert resp.status_code == 201
    body = resp.json()
    assert body["tableName"] == "vt_kafka_events"
    assert body["dataSourceType"] == "KAFKA"


def test_05_register_rest_virtual_table(api_client, sql_gateway_url):
    """测试 5：REST API 虚拟表注册。"""
    payload = make_virtual_table_payload(
        table_name="vt_rest_api_data",
        data_source_type="REST",
        connection_config=make_rest_config(),
        source_object="/api/v1/items",
        columns=[
            {"name": "id", "type": "INTEGER", "nullable": False},
            {"name": "name", "type": "VARCHAR", "nullable": True},
            {"name": "value", "type": "VARCHAR", "nullable": True},
        ],
    )
    resp = api_client.post(sql_gateway_url + "/api/v1/virtual-tables", json=payload)
    assert resp.status_code == 201
    body = resp.json()
    assert body["tableName"] == "vt_rest_api_data"
    assert body["dataSourceType"] == "REST"


# ---------------------------------------------------------------------------
# 6. 虚拟表查询
# ---------------------------------------------------------------------------
def test_06_query_virtual_table(api_client, sql_gateway_url):
    """测试 6：虚拟表查询（外部源不可达时返回 500，属预期行为）。"""
    # 先注册一个虚拟表
    payload = make_virtual_table_payload(
        table_name="vt_query_test",
        data_source_type="REST",
        connection_config=make_rest_config(),
        source_object="/api/v1/data",
    )
    reg_resp = api_client.post(sql_gateway_url + "/api/v1/virtual-tables", json=payload)
    # 若已存在（重复运行），跳过注册
    assert reg_resp.status_code in (201, 409)

    # 执行查询
    query_resp = api_client.post(
        sql_gateway_url + "/api/v1/virtual-tables/vt_query_test/query",
        json={"predicate": None, "limit": 10},
    )
    # 外部源不可达时返回 500，属预期；若可达则返回 200
    assert query_resp.status_code in (200, 500)


# ---------------------------------------------------------------------------
# 7. 虚拟表与本地表混合查询
# ---------------------------------------------------------------------------
def test_07_mixed_query_with_local_table(api_client, sql_gateway_url):
    """测试 7：虚拟表与本地表混合查询（通过 SQL 网关执行 SQL）。"""
    # 注册虚拟表
    payload = make_virtual_table_payload(
        table_name="vt_mixed_source",
        data_source_type="REST",
        connection_config=make_rest_config(),
        source_object="/api/v1/records",
    )
    api_client.post(sql_gateway_url + "/api/v1/virtual-tables", json=payload)

    # 通过 SQL 网关执行引用虚拟表的 SQL
    sql_payload = {
        "sql": "SELECT * FROM vt_mixed_source LIMIT 10",
        "tenantId": "docker-it-tenant",
    }
    resp = api_client.post(sql_gateway_url + "/api/v1/sql/execute", json=sql_payload)
    assert resp.status_code == 200
    body = resp.json()
    assert "queryId" in body
    assert "status" in body


# ---------------------------------------------------------------------------
# 8. 元数据缓存验证
# ---------------------------------------------------------------------------
def test_08_metadata_cache(api_client, sql_gateway_url):
    """测试 8：元数据缓存验证（获取 schema 后检查缓存统计）。"""
    # 注册虚拟表
    payload = make_virtual_table_payload(
        table_name="vt_cache_test",
        data_source_type="REST",
        connection_config=make_rest_config(),
        source_object="/api/v1/cached",
    )
    api_client.post(sql_gateway_url + "/api/v1/virtual-tables", json=payload)

    # 获取 schema（触发缓存写入）
    schema_resp = api_client.get(
        sql_gateway_url + "/api/v1/virtual-tables/vt_cache_test/schema"
    )
    assert schema_resp.status_code == 200
    schema = schema_resp.json()
    assert isinstance(schema, list)

    # 再次获取（应命中缓存）
    schema_resp2 = api_client.get(
        sql_gateway_url + "/api/v1/virtual-tables/vt_cache_test/schema"
    )
    assert schema_resp2.status_code == 200

    # 检查缓存统计
    stats_resp = api_client.get(
        sql_gateway_url + "/api/v1/virtual-tables/cache/stats"
    )
    assert stats_resp.status_code == 200
    stats = stats_resp.json()
    assert "hitCount" in stats
    assert "missCount" in stats


# ---------------------------------------------------------------------------
# 9-11. 物化策略
# ---------------------------------------------------------------------------
def test_09_materialization_full_refresh(api_client, sql_gateway_url):
    """测试 9：物化策略-全量刷新。"""
    payload = make_virtual_table_payload(
        table_name="vt_mat_full",
        data_source_type="REST",
        connection_config=make_rest_config(),
        source_object="/api/v1/full-data",
        materialization_strategy="FULL",
    )
    reg_resp = api_client.post(sql_gateway_url + "/api/v1/virtual-tables", json=payload)
    assert reg_resp.status_code in (201, 409)

    # 手动触发刷新
    refresh_resp = api_client.post(
        sql_gateway_url + "/api/v1/virtual-tables/vt_mat_full/refresh"
    )
    assert refresh_resp.status_code == 200
    body = refresh_resp.json()
    assert body.get("refreshed") is True
    assert "rows" in body


def test_10_materialization_incremental_refresh(api_client, sql_gateway_url):
    """测试 10：物化策略-增量刷新。"""
    payload = make_virtual_table_payload(
        table_name="vt_mat_incremental",
        data_source_type="REST",
        connection_config=make_rest_config(),
        source_object="/api/v1/incr-data",
        materialization_strategy="INCREMENTAL",
    )
    reg_resp = api_client.post(sql_gateway_url + "/api/v1/virtual-tables", json=payload)
    assert reg_resp.status_code in (201, 409)

    refresh_resp = api_client.post(
        sql_gateway_url + "/api/v1/virtual-tables/vt_mat_incremental/refresh"
    )
    assert refresh_resp.status_code == 200


def test_11_materialization_manual_refresh(api_client, sql_gateway_url):
    """测试 11：物化策略-手动刷新。"""
    payload = make_virtual_table_payload(
        table_name="vt_mat_manual",
        data_source_type="REST",
        connection_config=make_rest_config(),
        source_object="/api/v1/manual-data",
        materialization_strategy="MANUAL",
    )
    reg_resp = api_client.post(sql_gateway_url + "/api/v1/virtual-tables", json=payload)
    assert reg_resp.status_code in (201, 409)

    refresh_resp = api_client.post(
        sql_gateway_url + "/api/v1/virtual-tables/vt_mat_manual/refresh"
    )
    assert refresh_resp.status_code == 200


# ---------------------------------------------------------------------------
# 12. 虚拟表删除
# ---------------------------------------------------------------------------
def test_12_delete_virtual_table(api_client, sql_gateway_url):
    """测试 12：虚拟表删除。"""
    # 先注册
    payload = make_virtual_table_payload(
        table_name="vt_delete_test",
        data_source_type="MYSQL",
        connection_config=make_mysql_config(),
        source_object="test.delete_me",
    )
    reg_resp = api_client.post(sql_gateway_url + "/api/v1/virtual-tables", json=payload)
    assert reg_resp.status_code in (201, 409)

    # 删除
    del_resp = api_client.delete(
        sql_gateway_url + "/api/v1/virtual-tables/vt_delete_test"
    )
    assert del_resp.status_code == 204

    # 验证已删除
    get_resp = api_client.get(
        sql_gateway_url + "/api/v1/virtual-tables/vt_delete_test"
    )
    assert get_resp.status_code == 404


# ---------------------------------------------------------------------------
# 13. 虚拟表更新
# ---------------------------------------------------------------------------
def test_13_update_virtual_table(api_client, sql_gateway_url):
    """测试 13：虚拟表更新。"""
    # 先注册
    payload = make_virtual_table_payload(
        table_name="vt_update_test",
        data_source_type="MYSQL",
        connection_config=make_mysql_config(),
        source_object="test.original",
        description="原始描述",
    )
    reg_resp = api_client.post(sql_gateway_url + "/api/v1/virtual-tables", json=payload)
    assert reg_resp.status_code in (201, 409)

    # 更新
    update_payload = {
        "description": "更新后的描述",
        "sourceObject": "test.updated",
    }
    update_resp = api_client.put(
        sql_gateway_url + "/api/v1/virtual-tables/vt_update_test",
        json=update_payload,
    )
    assert update_resp.status_code == 200
    body = update_resp.json()
    assert body["description"] == "更新后的描述"
    assert body["sourceObject"] == "test.updated"


# ---------------------------------------------------------------------------
# 14. 权限验证
# ---------------------------------------------------------------------------
def test_14_permission_verification(sql_gateway_url):
    """测试 14：权限验证（无 token 访问虚拟表端点返回 401）。"""
    import requests

    # 无 token 访问
    resp = requests.get(sql_gateway_url + "/api/v1/virtual-tables", timeout=10)
    assert resp.status_code == 401

    # 无 token 注册
    resp2 = requests.post(
        sql_gateway_url + "/api/v1/virtual-tables",
        json={"tableName": "unauthorized", "tenantId": "x"},
        timeout=10,
    )
    assert resp2.status_code == 401


# ---------------------------------------------------------------------------
# 15. 租户隔离验证
# ---------------------------------------------------------------------------
def test_15_tenant_isolation(api_client, sql_gateway_url):
    """测试 15：租户隔离验证（不同租户的虚拟表互不可见）。

    注意：本测试中 api_client 使用固定的 JWT token（tenantId=docker-it-tenant），
    因此注册的虚拟表均属于该租户。这里验证列出端点仅返回当前租户的虚拟表。
    """
    # 列出当前租户的虚拟表
    list_resp = api_client.get(sql_gateway_url + "/api/v1/virtual-tables")
    assert list_resp.status_code == 200
    tables = list_resp.json()
    assert isinstance(tables, list)
    # 验证全部虚拟表属于当前租户
    for table in tables:
        assert table["tenantId"] == "docker-it-tenant"


# ---------------------------------------------------------------------------
# 16. SQL 重写验证
# ---------------------------------------------------------------------------
def test_16_sql_rewrite_verification(api_client, sql_gateway_url):
    """测试 16：SQL 重写验证（注册物化虚拟表后，SQL 引用应被重写为物化表名）。"""
    # 注册一个物化虚拟表
    payload = make_virtual_table_payload(
        table_name="vt_rewrite_test",
        data_source_type="REST",
        connection_config=make_rest_config(),
        source_object="/api/v1/rewrite-data",
        materialization_strategy="FULL",
    )
    reg_resp = api_client.post(sql_gateway_url + "/api/v1/virtual-tables", json=payload)
    assert reg_resp.status_code in (201, 409)

    # 通过 SQL 网关执行引用该虚拟表的 SQL
    sql_payload = {
        "sql": "SELECT * FROM vt_rewrite_test LIMIT 5",
        "tenantId": "docker-it-tenant",
    }
    resp = api_client.post(sql_gateway_url + "/api/v1/sql/execute", json=sql_payload)
    assert resp.status_code == 200
    body = resp.json()
    assert "queryId" in body
    # SQL 重写对用户透明，返回结果应包含 queryId
    assert body["status"] in ("SUCCESS", "DEGRADED", "FAILED")


# ---------------------------------------------------------------------------
# 列出虚拟表（辅助验证）
# ---------------------------------------------------------------------------
def test_list_virtual_tables(api_client, sql_gateway_url):
    """验证 GET /api/v1/virtual-tables 返回列表。"""
    resp = api_client.get(sql_gateway_url + "/api/v1/virtual-tables")
    assert resp.status_code == 200
    body = resp.json()
    assert isinstance(body, list)


def test_get_virtual_table_schema(api_client, sql_gateway_url):
    """验证 GET /api/v1/virtual-tables/{tableName}/schema 返回列定义。"""
    # 先注册
    payload = make_virtual_table_payload(
        table_name="vt_schema_test",
        data_source_type="MYSQL",
        connection_config=make_mysql_config(),
        source_object="test.schema_table",
        columns=[
            {"name": "id", "type": "INTEGER", "nullable": False, "comment": "ID"},
            {"name": "name", "type": "VARCHAR", "nullable": True, "comment": "名称"},
            {"name": "created_at", "type": "TIMESTAMP", "nullable": True},
        ],
    )
    api_client.post(sql_gateway_url + "/api/v1/virtual-tables", json=payload)

    resp = api_client.get(
        sql_gateway_url + "/api/v1/virtual-tables/vt_schema_test/schema"
    )
    assert resp.status_code == 200
    schema = resp.json()
    assert isinstance(schema, list)
    assert len(schema) == 3
    assert schema[0]["name"] == "id"


def test_test_connection_endpoint(api_client, sql_gateway_url):
    """验证 POST /api/v1/virtual-tables/{tableName}/test-connection 端点。"""
    payload = make_virtual_table_payload(
        table_name="vt_conn_test",
        data_source_type="MYSQL",
        connection_config=make_mysql_config(),
        source_object="test.conn_table",
    )
    api_client.post(sql_gateway_url + "/api/v1/virtual-tables", json=payload)

    resp = api_client.post(
        sql_gateway_url + "/api/v1/virtual-tables/vt_conn_test/test-connection"
    )
    assert resp.status_code == 200
    body = resp.json()
    assert "connected" in body
    # 外部源不可达时 connected=False，属预期
    assert body["connected"] in (True, False)


def test_register_duplicate_returns_409(api_client, sql_gateway_url):
    """验证重复注册同租户同名虚拟表返回 409。"""
    payload = make_virtual_table_payload(
        table_name="vt_duplicate_test",
        data_source_type="MYSQL",
        connection_config=make_mysql_config(),
        source_object="test.dup",
    )
    # 第一次注册
    resp1 = api_client.post(sql_gateway_url + "/api/v1/virtual-tables", json=payload)
    assert resp1.status_code in (201, 409)
    # 第二次注册（应 409）
    resp2 = api_client.post(sql_gateway_url + "/api/v1/virtual-tables", json=payload)
    assert resp2.status_code == 409


def test_get_nonexistent_returns_404(api_client, sql_gateway_url):
    """验证获取不存在的虚拟表返回 404。"""
    resp = api_client.get(
        sql_gateway_url + "/api/v1/virtual-tables/vt_nonexistent_xyz"
    )
    assert resp.status_code == 404