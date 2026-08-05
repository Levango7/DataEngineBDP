"""API 文档自动生成服务（OpenAPI 3.0 规范）.

对应详细设计 §4 契约 URL：OpenAPI Spec / Proto 托管地址。
"""
from __future__ import annotations

from typing import Any

from openapi_catalog.models import (
    APIDefinition,
    APIParam,
    APIResponse,
    ParamLocation,
    ParamType,
)
from openapi_catalog.repositories.mock import MockCatalogStore


# 参数类型映射到 OpenAPI 3.0 type
_PARAM_TYPE_MAP: dict[ParamType, str] = {
    ParamType.STRING: "string",
    ParamType.INTEGER: "integer",
    ParamType.NUMBER: "number",
    ParamType.BOOLEAN: "boolean",
    ParamType.ARRAY: "array",
    ParamType.OBJECT: "object",
}

# 参数位置映射到 OpenAPI 3.0 in
_PARAM_LOCATION_MAP: dict[ParamLocation, str] = {
    ParamLocation.PATH: "path",
    ParamLocation.QUERY: "query",
    ParamLocation.HEADER: "header",
    ParamLocation.BODY: "body",
}


class DocGeneratorService:
    """API 文档生成服务（OpenAPI 3.0）."""

    def __init__(self, store: MockCatalogStore) -> None:
        self.store = store

    async def generate_openapi_spec(self, api_id: str) -> dict[str, Any]:
        """生成 OpenAPI 3.0 Spec.

        Args:
            api_id: API ID.

        Returns:
            OpenAPI 3.0 Spec 字典.
        """
        api = await self.store.get_api(api_id)

        # 构建 parameters
        parameters = [
            self._build_parameter(p) for p in api.params
            if p.location != ParamLocation.BODY
        ]

        # 构建 request body
        request_body = self._build_request_body(api.params)

        # 构建 responses
        responses = self._build_responses(api.responses)

        # 构建 operation
        operation: dict[str, Any] = {
            "summary": api.name,
            "description": api.description or "",
            "operationId": f"{api.name}_{api.method.value.lower()}_{api.id[:8]}",
            "tags": [api.category],
            "parameters": parameters,
            "responses": responses,
        }
        if request_body is not None:
            operation["requestBody"] = request_body

        # 构建 path item
        path_item = {api.method.value.lower(): operation}

        # 构建 spec
        spec: dict[str, Any] = {
            "openapi": "3.0.3",
            "info": {
                "title": api.name,
                "description": api.description or "",
                "version": api.version,
                "contact": {
                    "name": "Shuqing Big Data Platform",
                    "url": "https://shuqing.example.com",
                },
                "license": {
                    "name": "Apache 2.0",
                    "url": "https://www.apache.org/licenses/LICENSE-2.0.html",
                },
            },
            "servers": [
                {
                    "url": f"/{api.path.lstrip('/')}",
                    "description": "API endpoint",
                }
            ],
            "paths": {
                api.path: path_item,
            },
            "components": {
                "schemas": self._build_schemas(api),
                "securitySchemes": self._build_security_schemes(api),
            },
            "security": self._build_security(api),
            "tags": [
                {
                    "name": api.category,
                    "description": f"分类: {api.category}",
                }
            ],
            "externalDocs": {
                "description": "API 文档",
                "url": f"/api/v1/apis/{api.id}/docs",
            },
            "x-api-metadata": {
                "apiId": api.id,
                "status": api.status.value,
                "sla": api.sla.value,
                "costStrategy": api.costStrategy.value,
                "costUnitPrice": api.costUnitPrice,
                "providerTenantId": api.providerTenantId,
                "tags": api.tags,
                "upstream": {
                    "type": api.upstream.type,
                    "url": api.upstream.url,
                },
            },
        }

        return spec

    def _build_parameter(self, param: APIParam) -> dict[str, Any]:
        """构建 OpenAPI parameter."""
        schema: dict[str, Any] = {
            "type": _PARAM_TYPE_MAP.get(param.type, "string"),
        }
        if param.description:
            schema["description"] = param.description
        if param.default is not None:
            schema["default"] = param.default
        if param.enum:
            schema["enum"] = param.enum
        if param.example is not None:
            schema["example"] = param.example

        result: dict[str, Any] = {
            "name": param.name,
            "in": _PARAM_LOCATION_MAP.get(param.location, "query"),
            "required": param.required,
            "schema": schema,
        }
        if param.description:
            result["description"] = param.description
        if param.example is not None:
            result["example"] = param.example
        return result

    def _build_request_body(
        self, params: list[APIParam]
    ) -> dict[str, Any] | None:
        """构建 request body（仅 body 参数）."""
        body_params = [p for p in params if p.location == ParamLocation.BODY]
        if not body_params:
            return None

        properties = {}
        required = []
        for p in body_params:
            prop: dict[str, Any] = {
                "type": _PARAM_TYPE_MAP.get(p.type, "string"),
            }
            if p.description:
                prop["description"] = p.description
            if p.default is not None:
                prop["default"] = p.default
            if p.enum:
                prop["enum"] = p.enum
            if p.example is not None:
                prop["example"] = p.example
            properties[p.name] = prop
            if p.required:
                required.append(p.name)

        return {
            "required": bool(required),
            "content": {
                "application/json": {
                    "schema": {
                        "type": "object",
                        "properties": properties,
                        "required": required,
                    }
                }
            },
        }

    def _build_responses(
        self, responses: list[APIResponse]
    ) -> dict[str, Any]:
        """构建 OpenAPI responses."""
        result = {}
        for r in responses:
            resp: dict[str, Any] = {
                "description": r.description or f"Status {r.statusCode}",
            }
            if r.schema:
                resp["content"] = {
                    "application/json": {
                        "schema": r.schema,
                    }
                }
            if r.example is not None:
                if "content" not in resp:
                    resp["content"] = {}
                resp["content"]["application/json"] = {
                    **resp["content"].get("application/json", {}),
                    "example": r.example,
                }
            result[str(r.statusCode)] = resp
        return result

    def _build_schemas(self, api: APIDefinition) -> dict[str, Any]:
        """构建 components.schemas."""
        schemas = {}

        # 请求 Schema
        body_params = [p for p in api.params if p.location == ParamLocation.BODY]
        if body_params:
            properties = {}
            required = []
            for p in body_params:
                properties[p.name] = {
                    "type": _PARAM_TYPE_MAP.get(p.type, "string"),
                    "description": p.description or "",
                }
                if p.required:
                    required.append(p.name)
            schemas[f"{api.name}Request"] = {
                "type": "object",
                "properties": properties,
                "required": required,
            }

        # 响应 Schema
        for r in api.responses:
            if r.schema:
                schemas[f"{api.name}Response{r.statusCode}"] = r.schema

        return schemas

    def _build_security_schemes(self, api: APIDefinition) -> dict[str, Any]:
        """构建 securitySchemes."""
        from openapi_catalog.models import AuthType

        if api.authType == AuthType.API_KEY:
            return {
                "ApiKeyAuth": {
                    "type": "apiKey",
                    "in": "header",
                    "name": "X-API-Key",
                }
            }
        elif api.authType == AuthType.JWT:
            return {
                "BearerAuth": {
                    "type": "http",
                    "scheme": "bearer",
                    "bearerFormat": "JWT",
                }
            }
        elif api.authType == AuthType.OAUTH2:
            return {
                "OAuth2": {
                    "type": "oauth2",
                    "flows": {
                        "clientCredentials": {
                            "tokenUrl": "/oauth2/token",
                            "scopes": {
                                "api.read": "读取权限",
                                "api.write": "写入权限",
                            },
                        }
                    },
                }
            }
        return {}

    def _build_security(self, api: APIDefinition) -> list[dict[str, list[str]]]:
        """构建 security."""
        from openapi_catalog.models import AuthType

        if api.authType == AuthType.API_KEY:
            return [{"ApiKeyAuth": []}]
        elif api.authType == AuthType.JWT:
            return [{"BearerAuth": []}]
        elif api.authType == AuthType.OAUTH2:
            return [{"OAuth2": ["api.read"]}]
        return []

    async def generate_markdown_doc(self, api_id: str) -> str:
        """生成 Markdown 格式 API 文档.

        Args:
            api_id: API ID.

        Returns:
            Markdown 文档字符串.
        """
        api = await self.store.get_api(api_id)

        lines = [
            f"# {api.name}",
            "",
            f"> 版本: `{api.version}`  |  状态: `{api.status.value}`  |  SLA: `{api.sla.value}`",
            "",
            "## 描述",
            "",
            api.description or "（无描述）",
            "",
            "## 调用方式",
            "",
            f"```http",
            f"{api.method.value} {api.path} HTTP/1.1",
            f"Host: api.shuqing.example.com",
        ]

        # 认证头
        from openapi_catalog.models import AuthType
        if api.authType == AuthType.API_KEY:
            lines.append("X-API-Key: <your-access-key>")
        elif api.authType == AuthType.JWT:
            lines.append("Authorization: Bearer <your-jwt-token>")
        lines.append("```")
        lines.append("")

        # 参数
        if api.params:
            lines.append("## 参数")
            lines.append("")
            lines.append("| 名称 | 位置 | 类型 | 必填 | 描述 |")
            lines.append("| --- | --- | --- | --- | --- |")
            for p in api.params:
                lines.append(
                    f"| `{p.name}` | {p.location.value} | {p.type.value} | "
                    f"{'是' if p.required else '否'} | {p.description or ''} |"
                )
            lines.append("")

        # 响应
        if api.responses:
            lines.append("## 响应")
            lines.append("")
            for r in api.responses:
                lines.append(f"### {r.statusCode} {r.description or ''}")
                lines.append("")
                if r.example is not None:
                    lines.append("```json")
                    import json
                    lines.append(json.dumps(r.example, ensure_ascii=False, indent=2))
                    lines.append("```")
                    lines.append("")

        # 计费
        lines.append("## 计费")
        lines.append("")
        lines.append(f"- 策略: `{api.costStrategy.value}`")
        lines.append(f"- 单价: `{api.costUnitPrice}`")
        if api.costStrategy.value == "monthly_package":
            lines.append(f"- 月配额: `{api.monthlyQuota}` 次")
        lines.append("")

        # 上游
        lines.append("## 后端上游")
        lines.append("")
        lines.append(f"- 类型: `{api.upstream.type}`")
        lines.append(f"- URL: `{api.upstream.url}`")
        lines.append(f"- 超时: `{api.upstream.timeout} ms`")
        lines.append("")

        return "\n".join(lines)