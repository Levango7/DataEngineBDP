"""APISIX 路由配置模型.

对应详细设计 §3 网关下发：
    APISIX 路由 + 插件链（限流/熔断/计量/日志/重写）一次性下发。
"""
from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from openapi_catalog.models.base import HttpMethod


class APISIXUpstream(BaseModel):
    """APISIX upstream 配置."""

    type: str = Field(default="roundrobin", description="负载均衡类型")
    nodes: dict[str, int] = Field(
        ..., description="节点列表 {url: weight}"
    )
    timeout: dict[str, int] = Field(
        default_factory=lambda: {"connect": 6, "send": 6, "read": 6},
        description="超时配置(s)",
    )
    retries: int = Field(default=0, ge=0, description="重试次数")


class APISIXPlugin(BaseModel):
    """APISIX 插件配置（通用 wrapper）."""

    name: str = Field(..., description="插件名")
    config: dict[str, Any] = Field(
        default_factory=dict, description="插件配置"
    )


class APISIXRoute(BaseModel):
    """APISIX 路由规则.

    对应 APISIX Route 资源：uri/method/upstream/plugins
    """

    id: str = Field(..., description="路由 ID（与 apiId 一致）")
    name: str = Field(..., description="路由名称")
    uri: str = Field(..., description="匹配 URI")
    methods: list[HttpMethod] = Field(..., description="HTTP 方法列表")
    upstream: APISIXUpstream = Field(..., description="上游配置")
    plugins: dict[str, dict[str, Any]] = Field(
        default_factory=dict, description="插件配置 {pluginName: config}"
    )
    labels: dict[str, str] = Field(
        default_factory=dict, description="标签"
    )
    enableWebsocket: bool = Field(default=False, description="启用 WebSocket")
    priority: int = Field(default=0, description="路由优先级")

    def to_apisix_payload(self) -> dict[str, Any]:
        """转换为 APISIX Admin API 提交格式."""
        return {
            "name": self.name,
            "uri": self.uri,
            "methods": [m.value for m in self.methods],
            "upstream": {
                "type": self.upstream.type,
                "nodes": self.upstream.nodes,
                "timeout": self.upstream.timeout,
                "retries": self.upstream.retries,
            },
            "plugins": self.plugins,
            "labels": self.labels,
            "enable_websocket": self.enableWebsocket,
            "priority": self.priority,
        }


class APISIXConsumer(BaseModel):
    """APISIX Consumer（绑定 API Key 凭证）."""

    username: str = Field(..., description="消费者用户名")
    plugins: dict[str, dict[str, Any]] = Field(
        default_factory=dict, description="插件配置"
    )

    def to_apisix_payload(self) -> dict[str, Any]:
        """转换为 APISIX Consumer 提交格式."""
        return {
            "username": self.username,
            "plugins": self.plugins,
        }