"""TemplateEngine - 模板解析 + 参数注入 + 一键部署.

核心职责：
    1. 模板解析：从模板库加载模板，校验完整性
    2. 参数注入：将租户 values 渲染到模板占位符（${...}）
    3. 一键部署：参数校验 → 渲染 → helm install（mock 或真实）→ instantiate

对齐设计文档第 5 节"安装与实例化"。

部署模式：
    - mock: 不真正调用 helm，仅生成部署记录（开发/测试默认）
    - helm: 通过 HelmExecutor 调用真实 helm CLI（生产/集成环境）
"""

from __future__ import annotations

import asyncio
from datetime import datetime, timezone
import os
import re
from typing import Any, Literal, Optional
import uuid

from industry_templates.models import (
    DeploymentRecord,
    DeploymentRequest,
    DeploymentStatus,
    Template,
    TemplatePreview,
    TemplateStatus,
)
from industry_templates.services.exceptions import (
    DeploymentNotFoundError,
    NamespaceValidationError,
    ParameterValidationError,
    RenderError,
    TemplateError,
    TemplateNotDeployableError,
    TemplateNotFoundError,
)

# 占位符正则：${param.name} 或 ${param.name:default}
_PLACEHOLDER_RE = re.compile(r"\$\{([a-zA-Z0-9_.]+)(?::([^}]*))?\}")

# K8s DNS 标签（namespace）合法字符约束
_NAMESPACE_RE = re.compile(r"^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$")

# 部署失败信息安全截断长度（避免异常消息外泄堆栈/超长内容）
_ERROR_MESSAGE_MAX_LEN = 500


class TemplateEngine:
    """模板引擎：解析 + 参数注入 + 一键部署.

    Attributes:
        templates:   模板库 dict[id -> Template]
        deployments: 部署记录 dict[deploymentId -> DeploymentRecord]
        deployMode:  部署模式: mock / helm
        helmExecutor: Helm 执行器（deployMode=helm 时使用）
        chartBase:   Chart 查找基础路径
    """

    def __init__(
        self,
        templates: Optional[list[Template]] = None,
        deployMode: Literal["mock", "helm"] = "mock",
        helmExecutor: Optional[Any] = None,
        chartBase: str = "./charts",
    ) -> None:
        """初始化模板引擎.

        Args:
            templates:    初始模板列表，不传则空
            deployMode:   部署模式: mock / helm
            helmExecutor: HelmExecutor 实例（helm 模式下使用，不传则惰性构建）
            chartBase:    Chart 查找基础路径
        """
        self.templates: dict[str, Template] = {}
        self.deployments: dict[str, DeploymentRecord] = {}
        self.deployMode: Literal["mock", "helm"] = deployMode
        self.chartBase = chartBase
        self._helmExecutor = helmExecutor
        if templates:
            for t in templates:
                self.register_template(t)

    # ---------- Helm 执行器 ----------

    @property
    def helmExecutor(self):
        """惰性构建 HelmExecutor（helm 模式下首次访问时构建）."""
        if self._helmExecutor is None:
            from industry_templates.services.helm_executor import HelmExecutor

            self._helmExecutor = HelmExecutor()
        return self._helmExecutor

    def _resolve_chart_path(self, templateId: str) -> str:
        """解析模板对应的 Chart 路径（三级回退）.

        约定（Sprint 4.2 修复：此前仅按 templateId 查找，与
        {industry}-template 命名的 chart 目录永远对不上，helm 模式部署必失败）：
        1. meta.chartRef 显式指定（TemplateMeta.chartRef，对应 Chart.yaml name）；
        2. {chartBase}/{templateId}（templateId 与 chart 目录同名时）；
        3. {chartBase}/{industry}-template（内置行业 chart 的统一命名）。
        三级均未命中时回退为模板 ID，由 helm 报"chart not found"（保留原语义）。
        """
        template = self.templates.get(templateId)
        if template is not None and template.meta.chartRef:
            candidate = os.path.join(self.chartBase, template.meta.chartRef)
            if os.path.isdir(candidate):
                return candidate
        candidate = os.path.join(self.chartBase, templateId)
        if os.path.isdir(candidate):
            return candidate
        if template is not None:
            candidate = os.path.join(self.chartBase, f"{template.meta.industry.value}-template")
            if os.path.isdir(candidate):
                return candidate
        return templateId

    # ---------- 模板注册 ----------

    def register_template(self, template: Template) -> None:
        """注册一个模板到模板库."""
        self.templates[template.id] = template

    def list_templates(
        self,
        industry: Optional[str] = None,
        keyword: Optional[str] = None,
        status: Optional[str] = None,
    ) -> list[Template]:
        """列出模板，支持按行业/关键字/状态过滤.

        Args:
            industry: 行业过滤（finance/retail/manufacturing/...）
            keyword: 名称或描述关键字
            status: 模板状态过滤

        Returns:
            过滤后的模板列表。
        """
        result = list(self.templates.values())
        if industry:
            result = [t for t in result if t.meta.industry.value == industry]
        if status:
            result = [t for t in result if t.meta.status.value == status]
        if keyword:
            kw = keyword.lower()
            result = [
                t
                for t in result
                if kw in t.meta.name.lower()
                or kw in t.meta.description.lower()
                or any(kw in tag.lower() for tag in t.meta.tags)
            ]
        return result

    def get_template(self, templateId: str) -> Template:
        """获取模板详情.

        Args:
            templateId: 模板 ID

        Returns:
            模板对象

        Raises:
            TemplateNotFoundError: 模板不存在
        """
        if templateId not in self.templates:
            raise TemplateNotFoundError(templateId)
        return self.templates[templateId]

    def list_categories(self) -> list[dict[str, Any]]:
        """列出模板分类（按行业聚合）.

        Returns:
            [{"industry": "finance", "name": "金融", "count": 1, "templates": [...]}]
        """
        industry_names = {
            "finance": "金融",
            "retail": "零售",
            "manufacturing": "制造",
            "government": "政务",
            "iot": "物联网",
        }
        groups: dict[str, list[Template]] = {}
        for t in self.templates.values():
            ind = t.meta.industry.value
            groups.setdefault(ind, []).append(t)
        return [
            {
                "industry": ind,
                "name": industry_names.get(ind, ind),
                "count": len(items),
                "templates": [{"id": t.id, "name": t.meta.name, "version": t.meta.version} for t in items],
            }
            for ind, items in groups.items()
        ]

    # ---------- 参数注入（渲染） ----------

    @staticmethod
    def render_value(value: Any, values: dict[str, Any]) -> Any:
        """递归渲染值中的占位符 ${...}.

        Args:
            value: 待渲染的值（str/dict/list/其他）
            values: 参数取值字典

        Returns:
            渲染后的值。占位符未提供时保留原占位符。
        """
        if isinstance(value, str):

            def _replace(match: re.Match[str]) -> str:
                key = match.group(1)
                default = match.group(2)
                if key in values:
                    return str(values[key])
                if default is not None:
                    return default
                return match.group(0)  # 保留原占位符

            return _PLACEHOLDER_RE.sub(_replace, value)
        if isinstance(value, dict):
            return {k: TemplateEngine.render_value(v, values) for k, v in value.items()}
        if isinstance(value, list):
            return [TemplateEngine.render_value(v, values) for v in value]
        return value

    def render_template(
        self,
        template: Template,
        values: dict[str, Any],
    ) -> dict[str, Any]:
        """渲染整个模板（参数注入）.

        Args:
            template: 模板对象
            values: 参数取值

        Returns:
            渲染后的模板 dict（含 dataFlow/computeLogic/visualization）
        """
        rendered = template.model_dump()
        return self.render_value(rendered, values)

    # ---------- 参数校验 ----------

    @staticmethod
    def validate_parameters(
        template: Template,
        values: dict[str, Any],
    ) -> None:
        """校验参数：必填检查 + 类型检查.

        Args:
            template: 模板对象
            values: 参数取值

        Raises:
            ParameterValidationError: 校验失败
        """
        missing: list[str] = []
        for p in template.parameters:
            if p.required and p.name not in values:
                # 检查是否有默认值
                if p.defaultValue is None:
                    missing.append(p.name)
                    continue
            if p.name in values:
                v = values[p.name]
                # 类型检查（轻量）；bool 是 int 子类，需先行排除
                if p.type.value == "integer" and (isinstance(v, bool) or not isinstance(v, int)):
                    raise ParameterValidationError(
                        f"参数 {p.name} 应为整数，得到 {type(v).__name__}",
                        missing=[p.name],
                    )
                if p.type.value == "float" and (isinstance(v, bool) or not isinstance(v, (int, float))):
                    raise ParameterValidationError(
                        f"参数 {p.name} 应为浮点数，得到 {type(v).__name__}",
                        missing=[p.name],
                    )
                if p.type.value == "boolean" and not isinstance(v, bool):
                    raise ParameterValidationError(
                        f"参数 {p.name} 应为布尔，得到 {type(v).__name__}",
                        missing=[p.name],
                    )
                if p.type.value == "enum" and p.enumOptions and v not in p.enumOptions:
                    raise ParameterValidationError(
                        f"参数 {p.name} 必须为 {p.enumOptions} 之一，得到 {v}",
                        missing=[p.name],
                    )
        if missing:
            raise ParameterValidationError(
                f"缺少必填参数: {missing}",
                missing=missing,
            )

    @staticmethod
    def merge_default_values(
        template: Template,
        values: dict[str, Any],
    ) -> dict[str, Any]:
        """合并默认值（用户值优先）."""
        merged = {}
        for p in template.parameters:
            if p.defaultValue is not None:
                merged[p.name] = p.defaultValue
        merged.update(values)
        return merged

    # ---------- 一键部署 ----------

    def deploy(
        self,
        templateId: str,
        request: DeploymentRequest,
    ) -> DeploymentRecord:
        """一键部署模板：参数校验 → 渲染 → install → instantiate.

        对齐设计文档第 5 节"安装与实例化"。

        Args:
            templateId: 模板 ID
            request: 部署请求（含租户/参数/数据源绑定）

        Returns:
            部署记录

        Raises:
            TemplateNotFoundError: 模板不存在
            TemplateNotDeployableError: 模板不可部署
            ParameterValidationError: 参数校验失败
        """
        # 1. 加载模板
        template = self.get_template(templateId)

        # 2. 校验模板状态
        if template.meta.status != TemplateStatus.CATALOG:
            raise TemplateNotDeployableError(templateId, template.meta.status.value)

        # 3. 合并默认值
        merged_values = self.merge_default_values(template, request.values)

        # 4. 参数校验
        self.validate_parameters(template, merged_values)

        # 5. 渲染模板
        try:
            self.render_template(template, merged_values)
        except Exception as e:
            raise RenderError(f"模板渲染失败: {e}") from e

        # 6. 生成部署记录
        deployment_id = f"dep-{uuid.uuid4().hex[:12]}"
        namespace = request.namespace or f"tenant-{request.tenantId}"
        if not _NAMESPACE_RE.match(namespace):
            raise NamespaceValidationError(namespace)
        record = DeploymentRecord(
            deploymentId=deployment_id,
            templateId=templateId,
            templateVersion=template.meta.version,
            tenantId=request.tenantId,
            releaseName=request.releaseName,
            namespace=namespace,
            status=DeploymentStatus.INSTALLING,
            renderedValues=merged_values,
        )
        self.deployments[deployment_id] = record

        # 7. 执行部署：mock 模式模拟，helm 模式真实调用 helm CLI
        try:
            if self.deployMode == "helm":
                self._helm_deploy(record, template, merged_values)
            else:
                self._mock_deploy(record)
        except TemplateError:
            record.status = DeploymentStatus.FAILED
            record.finishedAt = datetime.now(timezone.utc)
            raise
        except Exception as exc:
            record.status = DeploymentStatus.FAILED
            record.errorMessage = str(exc)[:_ERROR_MESSAGE_MAX_LEN]
            record.finishedAt = datetime.now(timezone.utc)
            raise

        # 8. 模板安装计数 +1
        template.meta.installCount += 1

        return record

    async def deploy_async(
        self,
        templateId: str,
        request: DeploymentRequest,
    ) -> DeploymentRecord:
        """deploy 的异步门面：将同步部署流程（含 helm 子进程）卸载到工作线程.

        避免阻塞事件循环（helm --wait 最长可阻塞 timeout 秒）。

        Args:
            templateId: 模板 ID
            request:    部署请求（含租户/参数/数据源绑定）

        Returns:
            部署记录
        """
        return await asyncio.to_thread(self.deploy, templateId, request)

    def _mock_deploy(
        self,
        record: DeploymentRecord,
    ) -> None:
        """Mock 部署：模拟 helm install + instantiate（不真正调用 helm）."""
        record.status = DeploymentStatus.INSTANTIATING
        record.jobRunId = f"job-{uuid.uuid4().hex[:12]}"
        record.status = DeploymentStatus.RUNNING
        record.dashboardSnapshotUrl = f"/dashboards/{record.deploymentId}/snapshot.png"
        record.finishedAt = datetime.now(timezone.utc)

    def _helm_deploy(
        self,
        record: DeploymentRecord,
        template: Template,
        values: dict[str, Any],
    ) -> None:
        """真实 Helm 部署：调用 helm upgrade --install + 模拟 instantiate.

        Args:
            record:  部署记录（已生成 deploymentId/namespace/releaseName）
            template: 模板对象（用于定位 Chart）
            values:   渲染后的 values 字典

        Raises:
            HelmCommandError: helm 命令失败。
        """
        chart = self._resolve_chart_path(template.id)
        # 真实 helm install / upgrade
        self.helmExecutor.install_or_upgrade(
            releaseName=record.releaseName,
            chart=chart,
            namespace=record.namespace,
            values=values,
        )
        # helm 成功后进入 instantiate（首次作业 + 仪表盘快照）
        # instantiate 仍走平台内部逻辑（非 helm 范畴）
        record.status = DeploymentStatus.INSTANTIATING
        record.jobRunId = f"job-{uuid.uuid4().hex[:12]}"
        record.status = DeploymentStatus.RUNNING
        record.dashboardSnapshotUrl = f"/dashboards/{record.deploymentId}/snapshot.png"
        record.finishedAt = datetime.now(timezone.utc)

    def get_deployment(self, deploymentId: str) -> DeploymentRecord:
        """获取部署记录.

        Raises:
            DeploymentNotFoundError: 部署记录不存在
        """
        if deploymentId not in self.deployments:
            raise DeploymentNotFoundError(deploymentId)
        return self.deployments[deploymentId]

    def list_deployments(
        self,
        tenantId: Optional[str] = None,
    ) -> list[DeploymentRecord]:
        """列出部署记录，可按租户过滤."""
        result = list(self.deployments.values())
        if tenantId:
            result = [r for r in result if r.tenantId == tenantId]
        return result

    # ---------- 模板预览 ----------

    def preview_template(self, templateId: str) -> TemplatePreview:
        """预览模板架构（不部署）.

        Args:
            templateId: 模板 ID

        Returns:
            模板预览：架构图 + 参数摘要 + 统计

        Raises:
            TemplateNotFoundError: 模板不存在
        """
        template = self.get_template(templateId)

        # 架构图：节点 + 边
        nodes = [
            {
                "id": n.id,
                "name": n.name,
                "nodeType": n.nodeType,
                "layer": n.layer,
            }
            for n in template.dataFlow.nodes
        ]
        edges: list[dict[str, str]] = []
        for n in template.dataFlow.nodes:
            for src in n.inputs:
                edges.append({"source": src, "target": n.id})

        # 参数摘要
        param_summary = [
            {
                "name": p.name,
                "type": p.type.value,
                "required": p.required,
                "defaultValue": p.defaultValue,
                "description": p.description,
            }
            for p in template.parameters
        ]

        # 统计
        stats = {
            "dataFlowNodes": len(template.dataFlow.nodes),
            "computeSteps": len(template.computeLogic.steps),
            "visualizationPanels": len(template.visualization.panels),
            "parameters": len(template.parameters),
        }

        return TemplatePreview(
            templateId=templateId,
            templateName=template.meta.name,
            industry=template.meta.industry,
            architecture={"nodes": nodes, "edges": edges},
            parameterSummary=param_summary,
            stats=stats,
        )

    # ---------- 卸载 ----------

    def undeploy(
        self,
        deploymentId: str,
        purgeData: bool = False,
    ) -> DeploymentRecord:
        """卸载部署.

        Args:
            deploymentId: 部署 ID
            purgeData: 是否物理删除数据（默认 False，仅标记下线）

        Returns:
            更新后的部署记录

        Raises:
            DeploymentNotFoundError: 部署记录不存在
        """
        record = self.get_deployment(deploymentId)
        # helm 模式：真实调用 helm uninstall
        if self.deployMode == "helm":
            try:
                self.helmExecutor.uninstall(
                    releaseName=record.releaseName,
                    namespace=record.namespace,
                )
            except TemplateError as e:
                # release 不存在不视为致命错误，继续标记为 STOPPED
                record.errorMessage = f"helm uninstall 警告: {e}"
        record.status = DeploymentStatus.STOPPED
        record.finishedAt = datetime.now(timezone.utc)
        if purgeData:
            # 物理删除：清除渲染值
            record.renderedValues = {}
        return record
