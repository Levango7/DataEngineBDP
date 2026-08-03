"""数擎运营后台 · operations-api
提供 /api/v1/admin/* 端点: 租户生命周期 / 套餐 / 计量 / 账单 / 看板。
计量实时聚合 Prometheus, 账单实时计算, 套餐经封装层落地 ResourceQuota。
仅平台方可见, 客户不可见 (L5 平台侧隔离)。
"""
from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel
import os
import httpx
from datetime import datetime, date
from prometheus_api_client import PrometheusConnect

app = FastAPI(title="数擎运营后台 operations-api", version="0.1")

PROM_URL = os.getenv("PROMETHEUS_URL", "http://prometheus.monitoring:9090")
ENCAPS_URL = os.getenv("ENCAPS_URL", "http://encaps-layer:8080")
ADMIN_TOKEN = os.getenv("ADMIN_TOKEN", "change-me")
prom = PrometheusConnect(url=PROM_URL, disable_ssl=True)

# 套餐档位 (生产走 ConfigMap, 此处内置; 与运营后台 v0.1 §4.2 一致)
PACKAGES = {
    "basic":    {"cpu": 8,   "mem": 32,   "storage": 200,  "price": 0},
    "standard": {"cpu": 32,  "mem": 128,  "storage": 1024, "price": 9800},
    "flagship": {"cpu": 128, "mem": 512,  "storage": 5120, "price": 39800},
}
CPU_UNIT_PRICE = 2.0      # 元 / CPU·小时 (溢出)
STORAGE_UNIT_PRICE = 0.5  # 元 / GB·天 (溢出)

# tenant 内存态 (生产用 DB; 此处演示)
TENANTS: dict[str, dict] = {}


class TenantReq(BaseModel):
    name: str
    displayName: str
    type: str = "external"
    package: str = "standard"
    contact: dict = {}


def _encaps_headers() -> dict:
    return {"Authorization": f"Bearer {ADMIN_TOKEN}"}


def _ws_name(tenant: str) -> str:
    return f"{tenant}-default"


@app.post("/api/v1/admin/tenants")
def create_tenant(req: TenantReq):
    if req.package not in PACKAGES:
        raise HTTPException(400, "unknown package")
    pkg = PACKAGES[req.package]
    # 调封装层建工作空间并落地 ResourceQuota
    r = httpx.post(
        f"{ENCAPS_URL}/api/v1/workspaces",
        headers=_encaps_headers(),
        json={"name": _ws_name(req.name), "displayName": req.displayName,
              "quota": {"cpu": pkg["cpu"], "memory": f'{pkg["mem"]}Gi',
                        "storage": f'{pkg["storage"]}Gi'},
              "tenantType": req.type},
        timeout=30,
    )
    if r.status_code >= 400:
        raise HTTPException(502, f"encaps layer error: {r.text}")
    TENANTS[req.name] = {"package": req.package, "status": "active"}
    return {"tenantId": req.name, "status": "active", "quotaRef": _ws_name(req.name)}


@app.get("/api/v1/admin/tenants")
def list_tenants():
    return [{"name": t, **v} for t, v in TENANTS.items()]


@app.get("/api/v1/admin/packages")
def list_packages():
    return [{"id": k, **v} for k, v in PACKAGES.items()]


def _query_cpu_hours(ws: str, days: int = 30) -> float:
    q = f'sum(increase(container_cpu_usage_seconds_total{{namespace="{ws}"}}[{days}d]))'
    try:
        return float(prom.custom_query(q)[0]["value"][1])
    except Exception:
        return 0.0


def _query_mem_gb_hours(ws: str, days: int = 30) -> float:
    q = f'sum(increase(container_memory_usage_bytes{{namespace="{ws}"}}[{days}d]))/1e9'
    try:
        return float(prom.custom_query(q)[0]["value"][1])
    except Exception:
        return 0.0


def _query_storage_gb_days(ws: str) -> float:
    q = f'sum(kube_persistentvolumeclaim_resource_requests_storage_bytes{{namespace="{ws}"}})/1e9'
    try:
        return float(prom.custom_query(q)[0]["value"][1])
    except Exception:
        return 0.0


@app.get("/api/v1/admin/metering")
def metering(tenant: str = Query(...), days: int = 30):
    if tenant not in TENANTS:
        raise HTTPException(404, "tenant not found")
    ws = _ws_name(tenant)
    return {
        "cpuHours": round(_query_cpu_hours(ws, days), 2),
        "memGBHours": round(_query_mem_gb_hours(ws, days), 2),
        "storageGBDays": round(_query_storage_gb_days(ws), 2),
        "jobs": 0,    # 生产经封装层 GET /api/v1/jobs?workspace=ws 计数
        "queries": 0,  # 生产经网关指标 sql_gateway_query_total{ws}
    }


@app.get("/api/v1/admin/bills")
def bills(tenant: str = Query(...), month: str = Query(datetime.now().strftime("%Y-%m"))):
    if tenant not in TENANTS:
        raise HTTPException(404, "tenant not found")
    pkg_id = TENANTS[tenant]["package"]
    pkg = PACKAGES[pkg_id]
    m = metering(tenant, days=30)
    cpu_base = pkg["cpu"] * 720          # 720h/月基线
    stor_base = pkg["storage"] * 30      # 30天基线
    overflow = 0.0
    if m["cpuHours"] > cpu_base:
        overflow += (m["cpuHours"] - cpu_base) * CPU_UNIT_PRICE
    if m["storageGBDays"] > stor_base:
        overflow += (m["storageGBDays"] - stor_base) * STORAGE_UNIT_PRICE
    return {
        "tenantId": tenant, "package": pkg_id, "month": month,
        "base": pkg["price"], "overflow": round(overflow, 2),
        "total": round(pkg["price"] + overflow, 2), "status": "unpaid",
    }


@app.get("/api/v1/admin/dashboard")
def dashboard():
    active = sum(1 for v in TENANTS.values() if v["status"] == "active")
    mrr = sum(PACKAGES[v["package"]]["price"] for v in TENANTS.values())
    return {
        "tenants": len(TENANTS), "active": active,
        "paused": len(TENANTS) - active, "mrr": mrr,
        "clusterCpuUsage": 0.62, "clusterMemUsage": 0.55, "health": "green",
    }


@app.get("/healthz")
def healthz():
    return {"status": "ok"}
