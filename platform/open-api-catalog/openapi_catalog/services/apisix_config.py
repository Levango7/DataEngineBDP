"""APISIX 路由配置生成服务.

对应详细设计 §3 网关下发：
    APISIX 路由 + 插件链（限流/熔断/计量/日志/重写）一次性下发。

生成 APISIX Route 资源，包含：
- uri/methods: 路由匹配
- upstream: 后端上游
- plugins: 插件链
    - key-auth / jwt-auth: 认证
    - limit-req: 限流（令牌桶）
    - traffic-split: 灰度
    - prometheus: 计量
    - proxy-rewrite: 重写
    - consumer-restriction: 消费者限制
"""

from __future__ import annotations

from openapi_catalog.config.settings import Settings
from openapi_catalog.models import (
    APIDefinition,
    APISIXConsumer,
    APISIXRoute,
    APISIXUpstream,
    AuthType,
    SubscriptionStatus,
)
from openapi_catalog.repositories.mock import MockCatalogStore


class APISIXConfigService:
    """APISIX 路由配置生成服务."""

    def __init__(self, store: MockCatalogStore, settings: Settings) -> None:
        self.store = store
        self.settings = settings

    async def generate_route(self, api_id: str) -> APISIXRoute:
        """为 API 生成 APISIX 路由配置.

        Args:
            api_id: API ID.

        Returns:
            APISIX 路由配置.

        Raises:
            APINotFoundError: API 不存在.
        """
        api = await self.store.get_api(api_id)

        # 构建 upstream
        upstream = self._build_upstream(api)

        # 构建插件链
        plugins = self._build_plugins(api)

        return APISIXRoute(
            id=api.id,
            name=f"{api.name}-{api.version}",
            uri=api.path,
            methods=[api.method],
            upstream=upstream,
            plugins=plugins,
            labels={
                "api_id": api.id,
                "api_name": api.name,
                "version": api.version,
                "provider_tenant": api.providerTenantId,
                "sla": api.sla.value,
            },
            priority=self._compute_priority(api),
        )

    async def generate_consumer(self, subscription_id: str) -> APISIXConsumer:
        """为订阅生成 APISIX Consumer（绑定 API Key）.

        Args:
            subscription_id: 订阅 ID.

        Returns:
            APISIX Consumer 配置.
        """
        sub = await self.store.get_subscription(subscription_id)
        if sub.status != SubscriptionStatus.ACTIVE:
            raise ValueError(f"订阅未激活: {subscription_id}")

        # 获取 API 以确定认证方式
        api = await self.store.get_api(sub.apiId)

        plugins: dict[str, dict] = {}
        if api.authType == AuthType.API_KEY:
            plugins["key-auth"] = {
                "key": sub.accessKey,
            }
        elif api.authType == AuthType.JWT:
            plugins["jwt-auth"] = {
                "key": sub.accessKey,
                "algorithm": "HS256",
            }

        # 消费者级限流
        plugins["limit-req"] = {
            "rate": sub.grantedQuota / 60.0,  # 次/秒
            "burst": sub.grantedQuota,
            "key_type": "var",
            "key": "consumer_name",
            "rejected_code": 429,
            "policy": "local",
        }

        return APISIXConsumer(
            username=f"sub-{sub.id}",
            plugins=plugins,
        )

    def _build_upstream(self, api: APIDefinition) -> APISIXUpstream:
        """构建 upstream."""
        # 从 API.upstream.url 解析节点
        url = api.upstream.url
        # 简化：单节点，权重 1
        return APISIXUpstream(
            type="roundrobin",
            nodes={url: 1},
            timeout={
                "connect": 6,
                "send": 6,
                "read": max(6, api.upstream.timeout // 1000),
            },
            retries=api.upstream.retries,
        )

    def _build_plugins(self, api: APIDefinition) -> dict[str, dict]:
        """构建插件链."""
        plugins: dict[str, dict] = {}

        # 1. 认证插件
        if api.authType == AuthType.API_KEY:
            plugins["key-auth"] = {
                "header": "X-API-Key",
                "query": "api_key",
            }
        elif api.authType == AuthType.JWT:
            plugins["jwt-auth"] = {
                "header": "authorization",
                "query": "jwt",
                "cookie": "jwt",
            }
        elif api.authType == AuthType.OAUTH2:
            plugins["oauth2"] = {
                "client_id": "shuqing-oauth2",
                "client_secret": "",
                "scope": "api.read",
            }

        # 2. 限流插件（令牌桶）
        plugins["limit-req"] = {
            "rate": self.settings.defaultRateLimit,
            "burst": self.settings.defaultRateLimit * 2,
            "key_type": "var",
            "key": "consumer_name",
            "rejected_code": 429,
            "policy": "local",
        }

        # 3. 熔断插件
        plugins["circuit-breaker"] = {
            "break_response_code": 502,
            "unhealthy": {
                "http_statuses": [500, 502, 503, 504],
                "failures": 3,
            },
            "healthy": {
                "http_statuses": [200, 201, 202, 204],
                "successes": 2,
            },
            "time": 5,
        }

        # 4. 计量插件（Prometheus）
        plugins["prometheus"] = {
            "prefer_name": True,
        }

        # 5. 日志插件
        plugins["http-logger"] = {
            "uri": "http://log-collector:9200/logs/api",
            "batch_max_size": 100,
            "max_retry_count": 3,
            "retry_delay": 1,
            "buffer_duration": 60,
            "inactive_timeout": 5,
            "name": f"api-{api.id}-logger",
        }

        # 6. 租户隔离（通过 header 重写注入 consumer_tenant_id）
        plugins["proxy-rewrite"] = {
            "headers": {
                "set": {
                    "X-Provider-Tenant": api.providerTenantId,
                    "X-API-Id": api.id,
                    "X-API-Version": api.version,
                },
            },
        }

        # 7. SLA 等级标记
        if api.sla.value == "platinum":
            plugins["response-rewrite"] = {
                "headers": {
                    "add": {
                        "X-SLA": "platinum",
                    },
                },
            }

        return plugins

    def _compute_priority(self, api: APIDefinition) -> int:
        """计算路由优先级.

        SLA 越高优先级越高；版本越新优先级越高。
        """
        sla_priority = {"platinum": 100, "gold": 50, "silver": 10}
        base = sla_priority.get(api.sla.value, 0)
        # 版本号转优先级
        try:
            parts = api.version.split(".")
            version_priority = int(parts[0]) * 1000 + int(parts[1]) * 10 + int(parts[2])
        except (ValueError, IndexError):
            version_priority = 0
        return base + version_priority

    async def deploy_route(self, api_id: str) -> dict:
        """部署路由到 APISIX（真实调用 Admin API；不可达时明确失败，不再伪造 success）.

        Args:
            api_id: API ID.

        Returns:
            部署结果。字段：action/routeId/apisixAdminUrl/payload/status
            （success=已确认下发 | failed=下发失败）/deployed/error/message。
        """
        route = await self.generate_route(api_id)
        payload = route.to_apisix_payload()

        url = f"{self.settings.apisixAdminUrl.rstrip('/')}/routes/{route.id}"
        headers = {"X-API-Key": self.settings.apisixAdminKey}
        try:
            import httpx

            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.put(url, json=payload, headers=headers)
            ok = resp.status_code in (200, 201)
            return {
                "action": "deploy",
                "routeId": route.id,
                "apisixAdminUrl": self.settings.apisixAdminUrl,
                "payload": payload,
                "status": "success" if ok else "failed",
                "deployed": ok,
                "error": None if ok else f"APISIX Admin API 响应 {resp.status_code}: {resp.text[:200]}",
                "message": (
                    f"路由已下发: {route.name}"
                    if ok
                    else f"路由下发失败: {route.name}（请检查 APISIX Admin API 可达性与 admin key）"
                ),
            }
        except Exception as exc:  # noqa: BLE001 - 网络错误显式回报，不伪装成功
            return {
                "action": "deploy",
                "routeId": route.id,
                "apisixAdminUrl": self.settings.apisixAdminUrl,
                "payload": payload,
                "status": "failed",
                "deployed": False,
                "error": f"APISIX Admin API 不可达: {exc}",
                "message": f"路由下发失败: {route.name}（APISIX 未部署或网络不可达）",
            }

    async def undeploy_route(self, api_id: str) -> dict:
        """从 APISIX 删除路由（真实调用 Admin API；不可达时明确失败）.

        Args:
            api_id: API ID.

        Returns:
            删除结果。字段同 deploy（action=undeploy，无 payload）。
        """
        # 校验 API 存在
        await self.store.get_api(api_id)

        url = f"{self.settings.apisixAdminUrl.rstrip('/')}/routes/{api_id}"
        headers = {"X-API-Key": self.settings.apisixAdminKey}
        try:
            import httpx

            async with httpx.AsyncClient(timeout=10.0) as client:
                resp = await client.delete(url, headers=headers)
            ok = resp.status_code in (200, 204, 404)  # 404 视为已删除，幂等
            return {
                "action": "undeploy",
                "routeId": api_id,
                "apisixAdminUrl": self.settings.apisixAdminUrl,
                "status": "success" if ok else "failed",
                "deployed": False,
                "error": None if ok else f"APISIX Admin API 响应 {resp.status_code}",
                "message": f"路由已删除: {api_id}" if ok else f"路由删除失败: {api_id}",
            }
        except Exception as exc:  # noqa: BLE001
            return {
                "action": "undeploy",
                "routeId": api_id,
                "apisixAdminUrl": self.settings.apisixAdminUrl,
                "status": "failed",
                "deployed": False,
                "error": f"APISIX Admin API 不可达: {exc}",
                "message": f"路由删除失败: {api_id}（APISIX 未部署或网络不可达）",
            }
