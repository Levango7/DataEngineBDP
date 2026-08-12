"""Catalog（资产目录）Docker 集成测试。

被测对象：Docker 容器 ``it-catalog``（镜像 ``sq/catalog:0.1.0``），
Go/Gin，主机端口 18082 → 容器 8082。

覆盖端点：
- GET  /api/v1/health                  （健康检查，无需认证）
- GET  /api/v1/catalog/databases       （列出数据库，需认证）
- POST /api/v1/catalog/databases       （创建数据库，需认证）
- GET  /api/v1/catalog/tables          （列出表，需认证）
- POST /api/v1/catalog/tables          （创建表，需认证）
- GET  /api/v1/catalog/tables/{id}     （获取单个表，需认证）
- DELETE /api/v1/catalog/tables/{id}   （删除表，需认证）

设计要点：
- Catalog 是 Go/Gin 实现，健康检查路径为 /api/v1/health（非 /actuator/health）；
- Table 模型需要 databaseName、tableName、columns 字段；
- Database 模型需要 name 字段；
- 验证无认证请求返回 401（认证机制正常）。
"""

from __future__ import annotations

import pytest


# ---------------------------------------------------------------------------
# 健康检查
# ---------------------------------------------------------------------------
def test_health_check(catalog_url):
    """验证 Catalog 健康检查端点返回 200 且 status=UP。

    Catalog 是 Go 实现，健康检查路径为 /api/v1/health。
    """
    import requests

    resp = requests.get(catalog_url + "/api/v1/health", timeout=10)
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("status") == "UP"


def test_health_check_version(catalog_url):
    """验证 Catalog 健康检查包含版本信息。"""
    import requests

    resp = requests.get(catalog_url + "/api/v1/health", timeout=10)
    assert resp.status_code == 200
    body = resp.json()
    assert "version" in body
    assert body["version"] == "0.1.0"


# ---------------------------------------------------------------------------
# 认证机制验证
# ---------------------------------------------------------------------------
def test_unauthorized_without_token(catalog_url):
    """验证无 Bearer token 访问受保护端点返回 401。"""
    import requests

    resp = requests.get(catalog_url + "/api/v1/catalog/tables", timeout=10)
    assert resp.status_code == 401


def test_unauthorized_with_invalid_token(catalog_url):
    """验证无效 Bearer token 访问受保护端点返回 401。"""
    import requests

    resp = requests.get(
        catalog_url + "/api/v1/catalog/tables",
        headers={"Authorization": "Bearer invalid-token-xxx"},
        timeout=10,
    )
    assert resp.status_code == 401


# ---------------------------------------------------------------------------
# 数据库管理
# ---------------------------------------------------------------------------
def test_list_databases(api_client, catalog_url):
    """验证 GET /api/v1/catalog/databases 返回 200 且含 data 与 total 字段。"""
    resp = api_client.get(catalog_url + "/api/v1/catalog/databases")
    assert resp.status_code == 200
    body = resp.json()
    assert "data" in body
    assert "total" in body
    assert isinstance(body["data"], list)


def test_create_database(api_client, catalog_url):
    """验证 POST /api/v1/catalog/databases 创建数据库返回 201。"""
    payload = {"name": "docker_it_db", "description": "Docker集成测试数据库"}
    resp = api_client.post(catalog_url + "/api/v1/catalog/databases", json=payload)
    assert resp.status_code == 201
    body = resp.json()
    assert "id" in body
    assert body.get("name") == payload["name"]

    # 清理。
    try:
        api_client.delete(catalog_url + f"/api/v1/catalog/databases/{body['id']}")
    except Exception:
        pass


# ---------------------------------------------------------------------------
# 表管理
# ---------------------------------------------------------------------------
def test_list_tables(api_client, catalog_url):
    """验证 GET /api/v1/catalog/tables 返回 200 且含 data 与 total 字段。"""
    resp = api_client.get(catalog_url + "/api/v1/catalog/tables")
    assert resp.status_code == 200
    body = resp.json()
    assert "data" in body
    assert "total" in body
    assert isinstance(body["data"], list)


def test_create_table(api_client, catalog_url):
    """验证 POST /api/v1/catalog/tables 创建表返回 201。"""
    payload = {
        "databaseName": "docker_it_db",
        "tableName": "test_table_create",
        "description": "Docker集成测试表",
        "columns": [
            {"name": "id", "type": "BIGINT", "nullable": False},
            {"name": "name", "type": "VARCHAR", "nullable": True},
        ],
    }
    resp = api_client.post(catalog_url + "/api/v1/catalog/tables", json=payload)
    assert resp.status_code == 201
    body = resp.json()
    assert "id" in body
    assert body.get("databaseName") == payload["databaseName"]
    assert body.get("tableName") == payload["tableName"]

    # 清理。
    try:
        api_client.delete(catalog_url + f"/api/v1/catalog/tables/{body['id']}")
    except Exception:
        pass


def test_create_table_missing_database(api_client, catalog_url):
    """验证 POST /api/v1/catalog/tables 缺少 databaseName 返回 400。"""
    payload = {"tableName": "test_table_no_db", "columns": [{"name": "id", "type": "BIGINT"}]}
    resp = api_client.post(catalog_url + "/api/v1/catalog/tables", json=payload)
    assert resp.status_code == 400


def test_create_table_missing_columns(api_client, catalog_url):
    """验证 POST /api/v1/catalog/tables 缺少 columns 返回 400。"""
    payload = {"databaseName": "docker_it_db", "tableName": "test_table_no_cols"}
    resp = api_client.post(catalog_url + "/api/v1/catalog/tables", json=payload)
    assert resp.status_code == 400


def test_get_table(api_client, catalog_url, sample_table):
    """验证 GET /api/v1/catalog/tables/{id} 返回 200 且字段一致。"""
    table_id = sample_table["id"]
    resp = api_client.get(catalog_url + f"/api/v1/catalog/tables/{table_id}")
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("id") == table_id
    assert body.get("tableName") == sample_table["tableName"]


def test_delete_table(api_client, catalog_url):
    """验证 DELETE /api/v1/catalog/tables/{id} 删除表返回 204 或 200。"""
    # 先创建一个待删除的表。
    payload = {
        "databaseName": "docker_it_db",
        "tableName": "test_table_delete",
        "columns": [{"name": "id", "type": "BIGINT"}],
    }
    create_resp = api_client.post(catalog_url + "/api/v1/catalog/tables", json=payload)
    assert create_resp.status_code == 201
    table_id = create_resp.json()["id"]

    # 删除。
    resp = api_client.delete(catalog_url + f"/api/v1/catalog/tables/{table_id}")
    # Gin 对 204 的处理可能返回 200，两种都接受。
    assert resp.status_code in (200, 204)


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------
@pytest.fixture
def sample_table(api_client, catalog_url):
    """创建一个示例表，测试结束后自动删除。

    Yields:
        创建后的表字典（含 id 等字段）。
    """
    payload = {
        "databaseName": "docker_it_db",
        "tableName": "sample_table_fixture",
        "description": "fixture示例表",
        "columns": [
            {"name": "id", "type": "BIGINT", "nullable": False},
            {"name": "name", "type": "VARCHAR", "nullable": True},
        ],
    }
    resp = api_client.post(catalog_url + "/api/v1/catalog/tables", json=payload)
    assert resp.status_code == 201
    table = resp.json()

    yield table

    # 清理。
    try:
        api_client.delete(catalog_url + f"/api/v1/catalog/tables/{table['id']}")
    except Exception:
        pass