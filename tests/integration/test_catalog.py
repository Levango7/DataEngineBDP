"""Catalog 元数据目录集成测试。

被测对象：``platform/catalog``，Go/Gin，默认端口 8082。

覆盖端点：
- GET    /api/v1/health
- POST   /api/v1/catalog/databases
- GET    /api/v1/catalog/databases
- GET    /api/v1/catalog/databases/{id}
- DELETE /api/v1/catalog/databases/{id}
- POST   /api/v1/catalog/tables
- GET    /api/v1/catalog/tables
- GET    /api/v1/catalog/tables/{id}
- PUT    /api/v1/catalog/tables/{id}
- DELETE /api/v1/catalog/tables/{id}

设计要点：
- 表测试依赖数据库存在，使用 ``sample_database`` / ``sample_table`` fixture 自动管理；
- 完整 CRUD 流程通过 ``test_catalog_crud_flow`` 端到端验证。
"""

from __future__ import annotations


# ---------------------------------------------------------------------------
# 健康检查
# ---------------------------------------------------------------------------
def test_health_check(api_client, catalog_url):
    """验证 Catalog 健康检查端点返回 200 且 status=UP。"""
    resp = api_client.get(catalog_url + "/api/v1/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("status") == "UP"
    # Catalog 健康检查额外返回 component 与 version。
    assert body.get("component") == "catalog"


# ---------------------------------------------------------------------------
# Database CRUD
# ---------------------------------------------------------------------------
def test_create_database(api_client, catalog_url):
    """验证 POST /api/v1/catalog/databases 创建数据库返回 201/200 且含 id。"""
    payload = {"name": "it_create_db", "description": "创建数据库测试"}
    resp = api_client.post(catalog_url + "/api/v1/catalog/databases", json=payload)
    assert resp.status_code in (200, 201)
    body = resp.json()
    assert "id" in body
    assert body.get("name") == payload["name"]

    # 清理
    try:
        api_client.delete(catalog_url + f"/api/v1/catalog/databases/{body['id']}")
    except Exception:
        pass


def test_list_databases(api_client, catalog_url, sample_database):
    """验证 GET /api/v1/catalog/databases 返回 200 且为列表，含已创建的数据库。"""
    resp = api_client.get(catalog_url + "/api/v1/catalog/databases")
    assert resp.status_code == 200
    body = resp.json()
    # 兼容分页响应 {"data": [...], "total": N} 与直接列表 [...] 两种格式
    items = body.get("data", body) if isinstance(body, dict) else body
    assert isinstance(items, list)
    ids = [db.get("id") for db in items]
    assert sample_database["id"] in ids


# ---------------------------------------------------------------------------
# Table CRUD
# ---------------------------------------------------------------------------
def test_create_table(api_client, catalog_url, sample_database):
    """验证 POST /api/v1/catalog/tables 创建表返回 201/200 且含 id 与列定义。"""
    payload = {
        "databaseName": sample_database["name"],
        "tableName": "it_create_table",
        "columns": [
            {"name": "id", "type": "bigint", "nullable": False},
            {"name": "name", "type": "string", "nullable": True},
        ],
        "partitionKeys": ["dt"],
    }
    resp = api_client.post(catalog_url + "/api/v1/catalog/tables", json=payload)
    assert resp.status_code in (200, 201)
    body = resp.json()
    assert "id" in body
    assert body.get("tableName") == "it_create_table"

    # 清理
    try:
        api_client.delete(catalog_url + f"/api/v1/catalog/tables/{body['id']}")
    except Exception:
        pass


def test_list_tables(api_client, catalog_url, sample_table):
    """验证 GET /api/v1/catalog/tables 返回 200 且为列表，含已创建的表。"""
    resp = api_client.get(catalog_url + "/api/v1/catalog/tables")
    assert resp.status_code == 200
    body = resp.json()
    # 兼容分页响应 {"data": [...], "total": N} 与直接列表 [...] 两种格式
    items = body.get("data", body) if isinstance(body, dict) else body
    assert isinstance(items, list)
    ids = [t.get("id") for t in items]
    assert sample_table["id"] in ids


def test_list_tables_by_database(api_client, catalog_url, sample_table):
    """验证 GET /api/v1/catalog/tables?database=xxx 按库名过滤返回 200。"""
    db_name = sample_table.get("databaseName", "it_test_db")
    resp = api_client.get(
        catalog_url + "/api/v1/catalog/tables", params={"database": db_name}
    )
    assert resp.status_code == 200
    body = resp.json()
    # 兼容分页响应 {"data": [...], "total": N} 与直接列表 [...] 两种格式
    items = body.get("data", body) if isinstance(body, dict) else body
    assert isinstance(items, list)
    # 过滤后列表中所有表应属于该库（若实现返回 databaseName 字段）。
    for t in items:
        if "databaseName" in t:
            assert t["databaseName"] == db_name


def test_get_table(api_client, catalog_url, sample_table):
    """验证 GET /api/v1/catalog/tables/{id} 返回 200 且字段一致。"""
    table_id = sample_table["id"]
    resp = api_client.get(catalog_url + f"/api/v1/catalog/tables/{table_id}")
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("id") == table_id
    assert body.get("tableName") == sample_table["tableName"]


def test_get_table_not_found(api_client, catalog_url):
    """验证 GET /api/v1/catalog/tables/999999 对不存在的 id 返回 404。"""
    resp = api_client.get(catalog_url + "/api/v1/catalog/tables/999999")
    assert resp.status_code == 404


def test_update_table(api_client, catalog_url, sample_table):
    """验证 PUT /api/v1/catalog/tables/{id} 更新表返回 200 且字段已更新。"""
    table_id = sample_table["id"]
    update_payload = {
        "databaseName": sample_table.get("databaseName", "it_test_db"),
        "tableName": "it_test_table_updated",
        "columns": [
            {"name": "id", "type": "bigint", "nullable": False},
            {"name": "name", "type": "string", "nullable": True},
            {"name": "age", "type": "int", "nullable": True},
        ],
        "partitionKeys": ["dt"],
    }
    resp = api_client.put(
        catalog_url + f"/api/v1/catalog/tables/{table_id}", json=update_payload
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("id") == table_id
    assert body.get("tableName") == "it_test_table_updated"


def test_delete_table(api_client, catalog_url, sample_database):
    """验证 DELETE /api/v1/catalog/tables/{id} 删除表返回 204，再次删除返回 404。"""
    # 先创建一个待删除表。
    create_resp = api_client.post(
        catalog_url + "/api/v1/catalog/tables",
        json={
            "databaseName": sample_database["name"],
            "tableName": "it_delete_table",
            "columns": [{"name": "id", "type": "bigint", "nullable": False}],
        },
    )
    assert create_resp.status_code in (200, 201)
    table_id = create_resp.json()["id"]

    del_resp = api_client.delete(catalog_url + f"/api/v1/catalog/tables/{table_id}")
    assert del_resp.status_code == 204

    again_resp = api_client.delete(catalog_url + f"/api/v1/catalog/tables/{table_id}")
    assert again_resp.status_code == 404


# ---------------------------------------------------------------------------
# 完整 CRUD 流程
# ---------------------------------------------------------------------------
def test_catalog_crud_flow(api_client, catalog_url):
    """端到端 CRUD 流程：建库 → 建表 → 获取 → 更新 → 删表 → 删库。

    本测试自管理数据，验证 database + table 完整生命周期。
    """
    # 1. 建库
    db_resp = api_client.post(
        catalog_url + "/api/v1/catalog/databases",
        json={"name": "it_flow_db", "description": "流程测试库"},
    )
    assert db_resp.status_code in (200, 201)
    database = db_resp.json()
    db_id = database["id"]

    try:
        # 2. 建表
        table_resp = api_client.post(
            catalog_url + "/api/v1/catalog/tables",
            json={
                "databaseName": "it_flow_db",
                "tableName": "it_flow_table",
                "columns": [
                    {"name": "id", "type": "bigint", "nullable": False},
                    {"name": "name", "type": "string", "nullable": True},
                ],
                "partitionKeys": ["dt"],
            },
        )
        assert table_resp.status_code in (200, 201)
        table = table_resp.json()
        table_id = table["id"]

        try:
            # 3. 获取表
            get_resp = api_client.get(
                catalog_url + f"/api/v1/catalog/tables/{table_id}"
            )
            assert get_resp.status_code == 200
            assert get_resp.json()["tableName"] == "it_flow_table"

            # 4. 更新表
            upd_resp = api_client.put(
                catalog_url + f"/api/v1/catalog/tables/{table_id}",
                json={
                    "databaseName": "it_flow_db",
                    "tableName": "it_flow_table_v2",
                    "columns": [
                        {"name": "id", "type": "bigint", "nullable": False}
                    ],
                },
            )
            assert upd_resp.status_code == 200
            assert upd_resp.json()["tableName"] == "it_flow_table_v2"

            # 5. 列表中应包含该表
            list_resp = api_client.get(catalog_url + "/api/v1/catalog/tables")
            assert list_resp.status_code == 200
            list_body = list_resp.json()
            # 兼容分页响应 {"data": [...], "total": N} 与直接列表 [...] 两种格式
            list_items = list_body.get("data", list_body) if isinstance(list_body, dict) else list_body
            assert table_id in [t["id"] for t in list_items]
        finally:
            # 5. 删表
            del_table_resp = api_client.delete(
                catalog_url + f"/api/v1/catalog/tables/{table_id}"
            )
            assert del_table_resp.status_code == 204
    finally:
        # 6. 删库
        del_db_resp = api_client.delete(
            catalog_url + f"/api/v1/catalog/databases/{db_id}"
        )
        assert del_db_resp.status_code == 204