"""数擎运营后台 · operations-api
提供 /api/v1/admin/* 端点: 租户生命周期 / 套餐 / 计量 / 账单 / 看板。
计量实时聚合 Prometheus, 账单实时计算, 套餐经封装层落地 ResourceQuota。
仅平台方可见, 客户不可见 (L5 平台侧隔离)。

安全说明:
- 所有 /api/v1/admin/* 端点强制 Bearer token 鉴权 (fail-fast, 无默认 token)。
- 租户名经正则白名单校验, 防止 PromQL 注入。
- Prometheus 查询异常不再静默吞错, 记入 logger 并向上抛出。
"""
import logging
import os
import re
from datetime import datetime

import httpx
from fastapi import Depends, FastAPI, HTTPException, Query, Security, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from prometheus_api_client import PrometheusConnect
from pydantic import BaseModel

logger = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO)

app = FastAPI(title="数擎运营后台 operations-api", version="0.1")

PROM_URL = os.getenv("PROMETHEUS_URL", "http://prometheus.monitoring:9090")
ENCAPS_URL = os.getenv("ENCAPS_URL", "http://encaps-layer:8080")

# 安全止血: ADMIN_TOKEN 必须显式配置, 不再提供 "change-me" 默认值 (fail-fast)
ADMIN_TOKEN = os.getenv("ADMIN_TOKEN")
if not ADMIN_TOKEN:
    raise RuntimeError(
        "ADMIN_TOKEN environment variable is required; refusing to start with no admin token"
    )

prom = PrometheusConnect(url=PROM_URL, disable_ssl=True)

# 套餐档位 (生产走 ConfigMap, 此处内置; 与产品文档 §11.5.2 对齐)
#   basic    : 8 核  / 16 GB / 5 TB
#   standard : 16 核 / 64 GB / 20 TB
#   flagship : 32 核 / 128 GB / 50 TB
# 存储单位 Gi: 1 TB = 1024 Gi
PACKAGES = {
    "basic":    {"cpu": 8,  "mem": 16,  "storage": 5 * 1024,   "price": 0},
    "standard": {"cpu": 16, "mem": 64,  "storage": 20 * 1024,  "price": 9800},
    "flagship": {"cpu": 32, "mem": 128, "storage": 50 * 1024,  "price": 39800},
}
CPU_UNIT_PRICE = 2.0      # 元 / CPU·小时 (溢出)
STORAGE_UNIT_PRICE = 0.1  # 元 / GB·月 (溢出, 与产品文档 §11.5.2 对齐)

# tenant 内存态 (生产用 DB; 此处演示)
TENANTS: dict[str, dict] = {}

# 租户名白名单: 仅允许小写字母 / 数字 / 连字符, 长度 1-32
# 防止 PromQL 标签注入 (namespace="{ws}") 与路径注入
TENANT_PATTERN = re.compile(r"^[a-z0-9-]{1,32}$")

# ---------------------------------------------------------------------------
# 鉴权与校验
# ---------------------------------------------------------------------------
security = HTTPBearer(auto_error=True)


async def verify_admin_token(
    credentials: HTTPAuthorizationCredentials = Security(security),
) -> HTTPAuthorizationCredentials:
    """全局管理端点鉴权: fail-fast 校验 Bearer token。"""
    if credentials.scheme.lower() != "bearer":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid authentication scheme, Bearer required",
        )
    if credentials.credentials != ADMIN_TOKEN:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid admin token",
        )
    return credentials


def validate_tenant(ws: str) -> str:
    """租户名白名单校验, 防止 PromQL 注入。"""
    if not TENANT_PATTERN.match(ws):
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Invalid tenant name: only [a-z0-9-]{1,32} allowed",
        )
    return ws


# ---------------------------------------------------------------------------
# 数据模型
# ---------------------------------------------------------------------------
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


# ---------------------------------------------------------------------------
# 管理端点 (全部强制 Bearer 鉴权)
# ---------------------------------------------------------------------------
@app.post("/api/v1/admin/tenants")
def create_tenant(req: TenantReq, _=Depends(verify_admin_token)):
    validate_tenant(req.name)
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
def list_tenants(_=Depends(verify_admin_token)):
    return [{"name": t, **v} for t, v in TENANTS.items()]


@app.get("/api/v1/admin/packages")
def list_packages(_=Depends(verify_admin_token)):
    return [{"id": k, **v} for k, v in PACKAGES.items()]


# ---------------------------------------------------------------------------
# Prometheus 计量查询 (异常不再静默吞错)
# ---------------------------------------------------------------------------
def _query_cpu_hours(ws: str, days: int = 30) -> float:
    q = f'sum(increase(container_cpu_usage_seconds_total{{namespace="{ws}"}}[{days}d]))'
    try:
        return float(prom.custom_query(q)[0]["value"][1])
    except Exception as e:
        logger.error("Prometheus cpu_hours query failed for ws=%s days=%s: %s", ws, days, e)
        raise


def _query_mem_gb_hours(ws: str, days: int = 30) -> float:
    q = f'sum(increase(container_memory_usage_bytes{{namespace="{ws}"}}[{days}d]))/1e9'
    try:
        return float(prom.custom_query(q)[0]["value"][1])
    except Exception as e:
        logger.error("Prometheus mem_gb_hours query failed for ws=%s days=%s: %s", ws, days, e)
        raise


def _query_storage_gb_days(ws: str) -> float:
    q = f'sum(kube_persistentvolumeclaim_resource_requests_storage_bytes{{namespace="{ws}"}})/1e9'
    try:
        return float(prom.custom_query(q)[0]["value"][1])
    except Exception as e:
        logger.error("Prometheus storage_gb_days query failed for ws=%s: %s", ws, e)
        raise


@app.get("/api/v1/admin/metering")
def metering(
    tenant: str = Query(...),
    days: int = 30,
    _=Depends(verify_admin_token),
):
    validate_tenant(tenant)
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
def bills(
    tenant: str = Query(...),
    month: str = Query(datetime.now().strftime("%Y-%m")),
    _=Depends(verify_admin_token),
):
    validate_tenant(tenant)
    if tenant not in TENANTS:
        raise HTTPException(404, "tenant not found")
    pkg_id = TENANTS[tenant]["package"]
    pkg = PACKAGES[pkg_id]
    m = metering(tenant, days=30)
    cpu_base = pkg["cpu"] * 720          # 720h/月基线
    # 存储按月计费: 配额单位 GB, 月基线即 pkg["storage"]
    stor_base = pkg["storage"]           # 月基线 (GB)
    storage_gb_month = m["storageGBDays"] / 30  # 日累计转月
    overflow = 0.0
    if m["cpuHours"] > cpu_base:
        overflow += (m["cpuHours"] - cpu_base) * CPU_UNIT_PRICE
    if storage_gb_month > stor_base:
        overflow += (storage_gb_month - stor_base) * STORAGE_UNIT_PRICE
    return {
        "tenantId": tenant, "package": pkg_id, "month": month,
        "base": pkg["price"], "overflow": round(overflow, 2),
        "total": round(pkg["price"] + overflow, 2), "status": "unpaid",
    }


@app.get("/api/v1/admin/dashboard")
def dashboard(_=Depends(verify_admin_token)):
    active = sum(1 for v in TENANTS.values() if v["status"] == "active")
    mrr = sum(PACKAGES[v["package"]]["price"] for v in TENANTS.values())
    return {
        "tenants": len(TENANTS), "active": active,
        "paused": len(TENANTS) - active, "mrr": mrr,
        "clusterCpuUsage": 0.62, "clusterMemUsage": 0.55, "health": "green",
    }


# ---------------------------------------------------------------------------
# 健康检查 (无需鉴权, 供 kubelet / Dockerfile HEALTHCHECK 使用)
# ---------------------------------------------------------------------------
@app.get("/healthz")
def healthz():
    return {"status": "ok"}
