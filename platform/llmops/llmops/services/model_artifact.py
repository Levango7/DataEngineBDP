"""模型 artifact 解析：按模型名+版本从模型仓取推理可下载 URL（多模型发布）.

背景：B 线真推理编排此前所有推理服务共用全局单一 ``LLMOPS_INFERENCE_MODEL_URL``，
生产上等于「平台只能服务一个模型」。本模块把「用哪个模型」上移到模型注册表：
deploy 时按 ``modelName`` + ``modelVersion`` 查注册表版本的 ``artifactUri``，
是 http(s) 直链则作为该服务独立的模型下载地址；解析不到则返回 None，
调用方回退全局兜底 URL（保留单模型部署形态，不破坏既有行为）。

支持的 artifactUri 形态：
1. http(s) 直链 —— 原样返回（含操作者提供的 presigned URL）；
2. MLflow 内部 URI（``runs:/`` / ``mlflow-artifacts:/``）→ tracking server 下载 URL；
3. ``models:/<name>/<version>`` → 递归解析（深度 1，防环）；
4. ``s3://bucket/key``（G 线）→ 平台自持 SigV4 query presign 签出可直接 curl 的
   https URL。需要配置 S3 端点与凭据（``LLMOPS_ARTIFACT_S3_*``；凭据只从
   Secret env 注入）；未配置时保持回退兜底契约（不阻断部署）。

安全边界（本模块）：
- s3:// 形态的 presigned URL host 逐字节等于操作者配置的 endpoint host
  （单元素白名单；bucket/key 来自注册表输入但只影响 path 与签名，不改变 host）；
- 对象键白名单：ASCII 可打印、禁 ``?`` ``#`` 空白与控制字符、禁前导/尾随
  ``/``、禁空段与 ``..`` 段（fail-closed，非 ASCII 显式拒绝而非签出错签名）；
- presigned URL 等效短期凭据：本模块任何日志不记录签名 URL，异常消息不含
  URL（签名 URL 只经内存传给编排器落 per-service Secret）。
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import hmac
import logging
import re
from typing import Optional
from urllib.parse import quote, urlparse

from llmops.services.registry import ServiceRegistry

logger = logging.getLogger(__name__)

_S3_BUCKET_RE = re.compile(r"^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
_LOG_UNSAFE_RE = re.compile(r"[\r\n\x00-\x1f\x7f]")


def _log_safe(value: object, limit: int = 120) -> str:
    """日志净化（CodeQL py/log-injection 修复）：模型名/服务 id 等用户可控值进
    日志前剥控制字符与换行（防日志行伪造/注入），并截断。"""
    return _LOG_UNSAFE_RE.sub("_", str(value))[:limit]


# 对象键白名单（fail-closed）：ASCII 可打印且非空白（0x21-0x7E 即排除空格与
# 全部控制字符），并禁 ? 与 #（query/fragment 语义字符）。
_S3_KEY_RE = re.compile(r"^[!-~]+$")


def _uri_encode(value: str, *, encode_slash: bool = False) -> str:
    """SigV4 严格编码：仅 ``A-Za-z0-9-._~`` 不编码；``/`` 默认保留（canonical URI 用）.

    注意：S3 是 SigV4 家族里**不做二次编码**的例外服务（其他 AWS 服务对
    canonical URI double-encode）——本函数必须只编码一次。
    """
    safe = "-._~" + ("" if encode_slash else "/")
    return quote(value, safe=safe)


@dataclass(frozen=True)
class S3PresignConfig:
    """S3 presign 配置（由路由层从 settings 构造；凭据不散落为裸参数）.

    ``endpoint_url`` 已由 settings 校验（http/https、无路径后缀、无尾斜杠）；
    ``access_key_id``/``secret_access_key`` 任一为空时调用方保持回退兜底契约。
    """

    endpoint_url: str
    region: str
    access_key_id: str
    secret_access_key: str
    ttl_sec: int
    path_style: bool = True
    allowed_buckets: str = ""  # 逗号分隔白名单；空=不限制

    @property
    def allowed_bucket_set(self) -> frozenset[str]:
        """bucket 白名单集合（空串 ⇒ 空集，语义「空=不限制」）."""
        return frozenset(b.strip() for b in self.allowed_buckets.split(",") if b.strip())

    def is_configured(self) -> bool:
        """端点与凭据齐备才可签 URL（缺任一保持回退兜底）."""
        return bool(self.endpoint_url.strip() and self.access_key_id.strip() and self.secret_access_key.strip())


@dataclass(frozen=True)
class _S3Location:
    """s3:// 解析结果（bucket/key），纯值对象."""

    bucket: str
    key: str


def _parse_s3_uri(uri: str) -> Optional[_S3Location]:
    """``s3://bucket/key`` → 位置对象；非法形态返回 None（fail-closed 白名单）.

    规则：bucket 段匹配 S3 命名约束（小写字母数字与 ``.`` ``-``，3-63 字符）；
    key 段 ASCII 可打印非空白、禁 ``?`` ``#``、禁前导/尾随 ``/``、禁空段与
    ``..`` 段。非 ASCII / ``s3a://`` 等其他 scheme 一律返回 None——显式拒绝，
    而不是生成一个「看起来合法」的错误签名。
    """
    text = (uri or "").strip()
    if not text.startswith("s3://"):
        return None
    rest = text[len("s3://") :]
    bucket, sep, key = rest.partition("/")
    if not sep or not bucket or not key:
        return None
    if not _S3_BUCKET_RE.match(bucket):
        return None
    if not _S3_KEY_RE.match(key):
        return None
    if "?" in key or "#" in key:
        return None
    if key.startswith("/") or key.endswith("/") or "//" in key:
        return None
    if any(seg in ("..", "") for seg in key.split("/")):
        return None
    return _S3Location(bucket=bucket, key=key)


def _presign_s3_get_object(loc: _S3Location, cfg: S3PresignConfig, now: Optional[datetime] = None) -> str:
    """SigV4 query presign（GET object）——零依赖纯函数，返回可直接 curl 的 URL.

    实现规范（每条都对应已知签名错误模式）：
    - canonical URI 对 key **单次编码**（S3 例外，不做 double-encode），path-style
      为 ``/bucket/key``，virtual-host 为 ``/key``（bucket 进 host）；
    - canonical query **先编码后按字节序排序**（key 升序，同 key 比 value），
      不含 ``X-Amz-Signature`` 自身；
    - signed headers 仅 ``host``，payload hash 固定 ``UNSIGNED-PAYLOAD``
      （boto3 presign 形态，兼容性最稳）；
    - scope 日期与 ``X-Amz-Date`` 同源且 UTC（naive local time 是最常见错误）；
    - 派生链四轮 HMAC：date → region → ``s3`` → ``aws4_request``，种子
      ``"AWS4" + secret``；
    - ``X-Amz-Expires`` 限 1..604800（协议硬界；配置层另限 1800 下限）；
    - 本函数不抛异常不打日志：非法输入由 ``_parse_s3_uri`` 拒绝；签名 URL
      只经内存传递（不进日志/异常消息）。
    """
    dt = (now or datetime.now(timezone.utc)).astimezone(timezone.utc)
    amz_date = dt.strftime("%Y%m%dT%H%M%SZ")
    date_stamp = dt.strftime("%Y%m%d")
    parsed = urlparse(cfg.endpoint_url)
    if cfg.path_style:
        host = parsed.netloc
        canon_uri = f"/{loc.bucket}/{_uri_encode(loc.key)}"
        base_url = f"{cfg.endpoint_url}/{loc.bucket}/{_uri_encode(loc.key)}"
    else:
        host = f"{loc.bucket}.{parsed.netloc}"
        canon_uri = f"/{_uri_encode(loc.key)}"
        base_url = f"{parsed.scheme}://{host}{canon_uri}"
    scope = f"{date_stamp}/{cfg.region}/s3/aws4_request"
    canonical_query = "&".join(
        f"{_uri_encode(k, encode_slash=True)}={_uri_encode(v, encode_slash=True)}"
        for k, v in sorted(
            {
                "X-Amz-Algorithm": "AWS4-HMAC-SHA256",
                "X-Amz-Credential": f"{cfg.access_key_id}/{scope}",
                "X-Amz-Date": amz_date,
                "X-Amz-Expires": str(int(cfg.ttl_sec)),
                "X-Amz-SignedHeaders": "host",
            }.items()
        )
    )
    canonical_request = f"GET\n{canon_uri}\n{canonical_query}\nhost:{host}\n\nhost\nUNSIGNED-PAYLOAD"
    string_to_sign = (
        f"AWS4-HMAC-SHA256\n{amz_date}\n{scope}\n" + hashlib.sha256(canonical_request.encode("utf-8")).hexdigest()
    )

    def _hmac(key: bytes, msg: str) -> bytes:
        return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()

    signing_key = _hmac(("AWS4" + cfg.secret_access_key).encode("utf-8"), date_stamp)
    signing_key = _hmac(signing_key, cfg.region)
    signing_key = _hmac(signing_key, "s3")
    signing_key = _hmac(signing_key, "aws4_request")
    signature = hmac.new(signing_key, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
    return f"{base_url}?{canonical_query}&X-Amz-Signature={signature}"


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


def _to_mlflow_download_url(artifact_uri: str, tracking_uri: str) -> Optional[str]:
    """MLflow 内部 artifact URI → tracking server 可下载的 HTTP URL（纯函数）.

    - ``runs:/<run_id>/<path>`` → ``<tracking_uri>/get-artifact?path=<path>&run_id=<run_id>``
      （tracking server 的 artifact 代理端点，本地/S3 artifact store 都由 server 转出）
    - ``mlflow-artifacts:/<path>`` → ``<tracking_uri>/api/2.0/mlflow-artifacts/artifacts/<path>``
    - 其他（``s3://`` / ``file://`` / ``models:/`` 等）：返回 None 由调用方处理或回退
    """
    base = (tracking_uri or "").strip().rstrip("/")
    if not base or not base.lower().startswith(("http://", "https://")):
        return None
    uri = (artifact_uri or "").strip()
    if uri.startswith("runs:/"):
        rest = uri[len("runs:/") :]
        run_id, _, path = rest.partition("/")
        if not run_id or not path:
            return None
        return f"{base}/get-artifact?path={quote(path, safe='')}&run_id={quote(run_id, safe='')}"
    if uri.startswith("mlflow-artifacts:/"):
        path = uri[len("mlflow-artifacts:/") :].lstrip("/")
        if not path:
            return None
        return f"{base}/api/2.0/mlflow-artifacts/artifacts/{quote(path, safe='/')}"
    return None


async def resolve_model_artifact_url(
    registry: ServiceRegistry,
    model_name: str,
    model_version: Optional[str] = None,
    *,
    tracking_uri: str = "",
    s3: Optional[S3PresignConfig] = None,
    _depth: int = 0,
) -> Optional[str]:
    """按模型名+版本解析 artifact 下载 URL；解析不到返回 None（调用方回退全局兜底）.

    artifactUri 支持的可部署形态：
    1. http(s) 直链 —— 原样返回；
    2. MLflow 内部 URI（``runs:/`` / ``mlflow-artifacts:/``）—— 转成 tracking server
       的下载 URL（``tracking_uri`` 为 MLflow tracking server 地址，来自配置）；
    3. ``models:/<name>/<version>`` —— 解析为注册表中该模型版本的 artifactUri 后递归
       （深度 1，防环）；
    4. ``s3://bucket/key`` —— 用 ``s3`` 配置（``S3PresignConfig``）做 SigV4 presign，
       签出可直接 curl 的 URL；``s3`` 未传或配置不完整时保持回退兜底契约（与既有
       ``test_resolve_*`` 行为一致），bucket 白名单不匹配同样回退并留日志。
    其他形态（file:// 等）返回 None 回退兜底并留日志。

    日志纪律：s3:// 形态日志只记 bucket/key（artifactUri 本身不含签名）；签名
    URL 不进任何日志。注意 ``uri[:80]`` 截断方向是**从头截**——对 http(s)
    presigned URL 签名在尾部 query，截断输出不含签名（有意依赖）。
    """
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
        if uri.lower().startswith(("http://", "https://")):
            return uri
        mlflow_url = _to_mlflow_download_url(uri, tracking_uri)
        if mlflow_url is not None:
            return mlflow_url
        if uri.startswith("s3://") and s3 is not None and s3.is_configured():
            loc = _parse_s3_uri(uri)
            if loc is None:
                logger.info(
                    "模型 %s v%s 的 s3:// artifactUri 未通过对象键白名单校验，回退全局兜底模型 URL",
                    _log_safe(name),
                    _log_safe(getattr(picked, "version", "?")),
                )
                return None
            allowed = s3.allowed_bucket_set
            if allowed and loc.bucket not in allowed:
                # 白名单「空=不限制」；非空才过滤（空集一律拒绝是错误语义）
                logger.info(
                    "模型 %s v%s 的 s3 bucket 不在白名单内，回退全局兜底模型 URL",
                    _log_safe(name),
                    _log_safe(getattr(picked, "version", "?")),
                )
                return None
            return _presign_s3_get_object(loc, s3)
        if uri.startswith("models:/") and _depth < 1:
            rest = uri[len("models:/") :]
            parts = [p for p in rest.split("/") if p]
            if len(parts) >= 2:
                return await resolve_model_artifact_url(
                    registry, parts[0], parts[1], tracking_uri=tracking_uri, s3=s3, _depth=_depth + 1
                )
        # 不支持的形态（file:// 等，或 s3:// 未配置 presign）：回退兜底并留日志，
        # 不阻断部署。日志只记 uri 前 80 字符（s3:// 形态不含签名，见 docstring）。
        logger.info(
            "模型 %s v%s 的 artifactUri 无法转为下载 URL（%s），回退全局兜底模型 URL",
            _log_safe(name),
            _log_safe(getattr(picked, "version", "?")),
            _log_safe(uri, 80),
        )
        return None
    except Exception as exc:  # noqa: BLE001  模型仓查询失败不阻断部署，回退兜底
        # 日志纪律：只记异常类别名——K8s/HTTP 异常消息可能内嵌请求体（含签名 URL），
        # 不进日志（security 复审 P1：泄漏面硬规则）。
        logger.warning("解析模型 %s 的 artifact URL 失败（回退全局兜底）: %s", _log_safe(name), type(exc).__name__)
        return None
