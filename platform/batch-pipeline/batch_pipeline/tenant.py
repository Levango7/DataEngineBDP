"""M1 多租户化：租户上下文解析与 config 路径分区.

设计（AutoBatch 归并设计 §3.3）：
- tenant_id 只从可信入口提取：配置 ``tenant.id``（tenant.enabled=true 时）或
  环境变量 ``BATCH_PIPELINE_TENANT_ID``（K8s Job / 调度器注入，优先级更高）；
  M2 API 层从 JWT/header 提取后直接调用 apply_tenant，绝不信任请求体。
- 租户作为 config 组装参数传入内核：apply_tenant 在进程入口把租户分区写进
  cfg 的路径字段后返回新 cfg——内核各模块仍只读 cfg，不引入全局变量，
  既有单租户用例零改动。租户 id 写回 cfg["tenant"] 后进入 config_digest，
  续跑校验天然按租户隔离。
- 未启用租户时（tenant.enabled=false 且环境变量未设）cfg 原样使用，
  行为与单租户 100% 一致。

路径分区规则：
    run_dir / state_dir   容器根：追加租户段  → run/<tenant>/<batch>/、state/<tenant>/
    storage.prefix        S3 key 布局前置租户 → s3://<bucket>/<tenant>/<warehouse>/...
    storage.warehouse     湖位置：URI 在 authority 后插入（s3://b/<tenant>/w）；
                          iceberg 本地路径在首段后插入（state/<tenant>/warehouse）；
                          parquet 后端不改（segment 由 prefix 承担分区）
    iceberg catalog_uri   仅改写 sqlite:/// 文件路径（首段后插入），
                          REST/HTTP catalog 原样保留（隔离点在 warehouse URI）
    openlineage.namespace 追加 ".<tenant>" 后缀，血缘事件携带租户维度
"""

from __future__ import annotations

import copy
import os
import re
from typing import Any, Optional

TENANT_ENV_VAR = "BATCH_PIPELINE_TENANT_ID"
DEFAULT_TENANT_ID = "default"

# 小写字母/数字/连字符，1-63 字符，首尾必须是字母数字——与 S3 key、文件路径、
# OpenLineage namespace 拼接、K8s label 取值兼容
_TENANT_RE = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

_URI_RE = re.compile(r"^([a-zA-Z][a-zA-Z0-9+.\-]*)://([^/]*)(/.*)?$")


class TenantError(ValueError):
    """租户 id 非法或缺失时抛出."""


def validate_tenant_id(tenant_id: str) -> str:
    """校验租户 id 并返回去空白后的规范形式，非法值抛 TenantError."""
    tid = (tenant_id or "").strip()
    if not _TENANT_RE.match(tid):
        raise TenantError(
            f"invalid tenant id {tenant_id!r}: expected 1-63 chars of "
            "[a-z0-9-], starting/ending alphanumeric"
        )
    return tid


def resolve_tenant(cfg: dict[str, Any]) -> Optional[str]:
    """解析本次运行的租户 id；未启用租户时返回 None.

    优先级：环境变量 ``BATCH_PIPELINE_TENANT_ID`` > 配置 ``tenant.id``
    （需 ``tenant.enabled=true``）。环境变量非空即生效（调度器注入场景
    配置里可能没有 tenant 段），空串视为未设置。
    """
    env = os.environ.get(TENANT_ENV_VAR, "").strip()
    if env:
        return validate_tenant_id(env)
    tenant_cfg = cfg.get("tenant")
    if isinstance(tenant_cfg, dict) and bool(tenant_cfg.get("enabled", False)):
        return validate_tenant_id(str(tenant_cfg.get("id") or DEFAULT_TENANT_ID))
    return None


def _append_segment(path: str, tenant_id: str) -> str:
    """容器根路径追加租户段（run_dir/state_dir 用）.

    统一用正斜杠拼接（配置字段约定）；所有消费方（abs_path /
    os.path.join / relpath）在 Windows 上对两种分隔符等价处理。
    """
    trimmed = path.rstrip("/\\")
    if not trimmed:
        return tenant_id
    return trimmed + "/" + tenant_id


def _insert_path_segment(path: str, tenant_id: str) -> str:
    """本地路径首段后插入租户段（iceberg 本地 warehouse/sqlite 路径用）.

    ``state/warehouse`` → ``state/<tenant>/warehouse``；盘符与 Unix 根
    ``/`` 不计为段。
    """
    norm = path.replace("\\", "/")
    drive, rest = os.path.splitdrive(norm)
    is_abs = rest.startswith("/")
    segs = [s for s in rest.split("/") if s]
    if not segs:
        return (drive + "/" if drive or is_abs else "") + tenant_id
    joined = "/".join([segs[0], tenant_id] + segs[1:])
    if drive:
        return drive + "/" + joined
    return ("/" if is_abs else "") + joined


def _insert_uri_segment(uri: str, tenant_id: str) -> str:
    """对象存储 URI 在 authority 后插入租户段.

    ``s3://bucket/warehouse`` → ``s3://bucket/<tenant>/warehouse``；
    非 URI 输入抛 TenantError（调用方约定 warehouse 为 URI 时必须合法）。
    """
    m = _URI_RE.match(uri)
    if not m:
        raise TenantError(f"cannot partition non-URI path {uri!r} with tenant {tenant_id!r}")
    scheme, authority, rest = m.groups()
    return f"{scheme}://{authority}/{tenant_id}{rest or ''}"


def apply_tenant(cfg: dict[str, Any], tenant_id: str) -> dict[str, Any]:
    """把租户分区写入 cfg 的路径字段并返回新 cfg（深拷贝，不改入参）.

    调用点：pipeline.run_pipeline 入口（CLI / 编排器）与 M2 API 层
    （tenant_id 来自 JWT/header 校验后）。返回的 cfg 含规范化的
    ``tenant`` 段（enabled=true + id），随 config_digest 一起参与续跑校验。
    """
    tid = validate_tenant_id(tenant_id)
    out = copy.deepcopy(cfg)

    pipeline_cfg = out.setdefault("pipeline", {})
    pipeline_cfg["run_dir"] = _append_segment(str(pipeline_cfg.get("run_dir", "run") or "run"), tid)

    incremental_cfg = out.setdefault("incremental", {})
    incremental_cfg["state_dir"] = _append_segment(
        str(incremental_cfg.get("state_dir", "state") or "state"), tid
    )

    storage_cfg = out.setdefault("storage", {})
    prefix = str(storage_cfg.get("prefix", "") or "").strip("/")
    storage_cfg["prefix"] = f"{tid}/{prefix}" if prefix else tid

    backend = str(storage_cfg.get("backend", "local_csv"))
    warehouse = str(storage_cfg.get("warehouse", "") or "")
    if warehouse:
        if "://" in warehouse:
            storage_cfg["warehouse"] = _insert_uri_segment(warehouse, tid)
        elif backend == "iceberg":
            storage_cfg["warehouse"] = _insert_path_segment(warehouse, tid)

    iceberg_cfg = storage_cfg.setdefault("iceberg", {})
    if isinstance(iceberg_cfg, dict):
        catalog_uri = str(iceberg_cfg.get("catalog_uri", "") or "")
        if catalog_uri.startswith("sqlite:///"):
            rel = catalog_uri[len("sqlite:///") :]
            iceberg_cfg["catalog_uri"] = "sqlite:///" + _insert_path_segment(rel, tid)

    openlineage_cfg = out.setdefault("openlineage", {})
    namespace = str(openlineage_cfg.get("namespace", "batch-pipeline") or "batch-pipeline")
    openlineage_cfg["namespace"] = f"{namespace}.{tid}"

    tenant_cfg = out.get("tenant")
    out["tenant"] = {
        **(tenant_cfg if isinstance(tenant_cfg, dict) else {}),
        "enabled": True,
        "id": tid,
    }
    return out
