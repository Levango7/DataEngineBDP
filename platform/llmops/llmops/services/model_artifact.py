"""模型 artifact 解析：按模型名+版本从模型仓取推理可下载 URL（多模型发布）.

背景：B 线真推理编排此前所有推理服务共用全局单一 ``LLMOPS_INFERENCE_MODEL_URL``，
生产上等于「平台只能服务一个模型」。本模块把「用哪个模型」上移到模型注册表：
deploy 时按 ``modelName`` + ``modelVersion`` 查注册表版本的 ``artifactUri``，
是 http(s) 直链则作为该服务独立的模型下载地址；解析不到则返回 None，
调用方回退全局兜底 URL（保留单模型部署形态，不破坏既有行为）。

边界（v1）：
- 只接受 http(s) artifactUri（模型仓/对象存储直链，含 presigned URL）；
- MLflow 内部 URI（runs:/…、models:/…）不在此解析，返回 None 由兜底覆盖
  （生产建议注册版本时填模型仓直链；MLflow URI 自动解析登记为后续项）。
"""

from __future__ import annotations

import logging
from typing import Optional

from llmops.services.registry import ServiceRegistry

logger = logging.getLogger(__name__)


def _pick_version(versions: list, requested: Optional[str]) -> Optional[object]:
    """版本选择：显式版本 → 生产版本 → 最大版本（与前端契约 modelVersion 字符串兼容）."""
    if not versions:
        return None
    if requested is not None and str(requested).strip():
        try:
            want = int(str(requested).strip())
        except ValueError:
            return None
        return next((v for v in versions if int(getattr(v, "version", 0)) == want), None)
    prod = next((v for v in versions if getattr(v, "isProduction", False)), None)
    if prod is not None:
        return prod
    return max(versions, key=lambda v: int(getattr(v, "version", 0)))


async def resolve_model_artifact_url(
    registry: ServiceRegistry, model_name: str, model_version: Optional[str] = None
) -> Optional[str]:
    """按模型名+版本解析 artifact 下载 URL；解析不到返回 None（调用方回退全局兜底）."""
    name = (model_name or "").strip()
    if not name:
        return None
    try:
        model = await registry.modelService.find_model_by_name(name)
        if model is None:
            return None
        versions = await registry.modelService.get_model_versions(model.id)
        picked = _pick_version(versions, model_version)
        if picked is None:
            return None
        uri = (getattr(picked, "artifactUri", None) or "").strip()
        if not uri:
            return None
        if not uri.lower().startswith(("http://", "https://")):
            # MLflow 内部 URI（runs:/ / models:/ / s3:// 等）：v1 不解析，回退兜底并留日志。
            logger.info(
                "模型 %s v%s 的 artifactUri 非 http(s)（%s），回退全局兜底模型 URL",
                name,
                getattr(picked, "version", "?"),
                uri[:80],
            )
            return None
        return uri
    except Exception as exc:  # noqa: BLE001  模型仓查询失败不阻断部署，回退兜底
        logger.warning("解析模型 %s 的 artifact URL 失败（回退全局兜底）: %s", name, exc)
        return None
