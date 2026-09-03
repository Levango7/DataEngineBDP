"""模板数据模型.

对齐设计文档第 2 节"模板结构"：
    一个模板 = 数据模型 + 作业流 + BI 仪表盘 + 权限模板 + README
"""

from __future__ import annotations

from datetime import datetime
from typing import Any, Optional

from pydantic import BaseModel, Field

from industry_templates.models.base import (
    DeploymentStatus,
    Industry,
    ParameterType,
    TemplateStatus,
    TimestampMixin,
)

# ---------- 模板参数 ----------


class TemplateParameter(BaseModel):
    """模板参数定义（对应 values.yaml 中一个字段）.

    Attributes:
        name: 参数名（如 datasource.order_db）
        type: 参数类型
        description: 业务说明
        defaultValue: 默认值
        required: 是否必填
        enumOptions: 枚举可选值（type=enum 时使用）
        placeholder: 占位符（如 ${ORDER_DB_JDBC}）
    """

    name: str = Field(..., description="参数名")
    type: ParameterType = Field(..., description="参数类型")
    description: str = Field(default="", description="业务说明")
    defaultValue: Any = Field(default=None, description="默认值")
    required: bool = Field(default=True, description="是否必填")
    enumOptions: Optional[list[str]] = Field(default=None, description="枚举可选值")
    placeholder: Optional[str] = Field(default=None, description="占位符")


# ---------- 数据流配置 ----------


class DataFlowNode(BaseModel):
    """数据流节点（对应 DAG 中的一个作业节点）."""

    id: str = Field(..., description="节点 ID")
    name: str = Field(..., description="节点名称")
    nodeType: str = Field(..., description="节点类型: source/transform/sink")
    layer: Optional[str] = Field(default=None, description="数据分层: ods/dwd/dws/ads")
    description: str = Field(default="", description="节点说明")
    inputs: list[str] = Field(default_factory=list, description="输入节点 ID 列表")
    outputs: list[str] = Field(default_factory=list, description="输出节点 ID 列表")
    config: dict[str, Any] = Field(default_factory=dict, description="节点配置（SQL/参数等）")


class DataFlowConfig(BaseModel):
    """数据流配置（对应 templates/dags/ 下的 DAG JSON）."""

    nodes: list[DataFlowNode] = Field(default_factory=list, description="数据流节点列表")
    schedule: Optional[str] = Field(default=None, description="调度周期（cron 表达式）")
    description: str = Field(default="", description="数据流说明")


# ---------- 计算逻辑配置 ----------


class ComputeLogicStep(BaseModel):
    """计算逻辑步骤（对应一个 SQL/Python 计算单元）."""

    id: str = Field(..., description="步骤 ID")
    name: str = Field(..., description="步骤名称")
    stepType: str = Field(
        ...,
        description="步骤类型: sql/feature/model/rule/scoring",
    )
    description: str = Field(default="", description="步骤说明")
    inputs: list[str] = Field(default_factory=list, description="输入表/字段")
    outputs: list[str] = Field(default_factory=list, description="输出表/字段")
    code: str = Field(default="", description="计算代码（SQL/Python）")
    params: dict[str, Any] = Field(default_factory=dict, description="步骤参数")


class ComputeLogicConfig(BaseModel):
    """计算逻辑配置."""

    steps: list[ComputeLogicStep] = Field(default_factory=list, description="计算步骤列表")
    description: str = Field(default="", description="计算逻辑说明")


# ---------- 可视化配置 ----------


class VisualizationPanel(BaseModel):
    """可视化面板（对应 BI 仪表盘中的一个 panel）."""

    id: str = Field(..., description="面板 ID")
    title: str = Field(..., description="面板标题")
    chartType: str = Field(
        ...,
        description="图表类型: table/line/bar/pie/gauge/map/scatter",
    )
    description: str = Field(default="", description="面板说明")
    dataSource: str = Field(default="", description="数据源表/SQL")
    config: dict[str, Any] = Field(default_factory=dict, description="ECharts option 配置")
    width: int = Field(default=6, ge=1, le=24, description="宽度（栅格）")
    height: int = Field(default=300, ge=100, description="高度（px）")


class VisualizationConfig(BaseModel):
    """可视化配置（对应 templates/dashboards/ 下的 dashboard JSON）."""

    title: str = Field(default="", description="仪表盘标题")
    panels: list[VisualizationPanel] = Field(default_factory=list, description="面板列表")
    description: str = Field(default="", description="仪表盘说明")


# ---------- 模板元信息 ----------


class TemplateMeta(BaseModel):
    """模板元信息（对应 Chart.yaml）."""

    id: str = Field(..., description="模板 ID（如 fin-risk-scorecard）")
    name: str = Field(..., description="模板名")
    industry: Industry = Field(..., description="行业")
    version: str = Field(default="0.1.0", description="模板版本")
    appVersion: str = Field(default="0.1.0", description="兼容平台版本")
    description: str = Field(default="", description="模板描述")
    author: str = Field(default="Shuqing Big Data Platform Team")
    status: TemplateStatus = Field(default=TemplateStatus.CATALOG, description="模板状态")
    installCount: int = Field(default=0, ge=0, description="安装次数")
    rating: float = Field(default=5.0, ge=0, le=5, description="评分")
    tags: list[str] = Field(default_factory=list, description="标签")
    icon: str = Field(default="", description="图标 URL 或 emoji")
    chartRef: Optional[str] = Field(
        default=None,
        description="Helm Chart 引用（对应 chartBase 下目录名/Chart.yaml name；"
        "空则按 templateId → {industry}-template 回退推导，Sprint 4.2）",
    )


# ---------- 完整模板 ----------


class Template(TimestampMixin):
    """完整行业应用模板.

    一个模板 = 元信息 + 参数定义 + 数据流 + 计算逻辑 + 可视化 + README
    """

    meta: TemplateMeta = Field(..., description="模板元信息")
    parameters: list[TemplateParameter] = Field(default_factory=list, description="参数定义列表")
    dataFlow: DataFlowConfig = Field(default_factory=DataFlowConfig, description="数据流配置")
    computeLogic: ComputeLogicConfig = Field(default_factory=ComputeLogicConfig, description="计算逻辑配置")
    visualization: VisualizationConfig = Field(default_factory=VisualizationConfig, description="可视化配置")
    readme: str = Field(default="", description="业务说明 / 适用场景 / 参数表")
    validationSchema: dict[str, Any] = Field(
        default_factory=dict,
        description="参数校验 JSON Schema",
    )

    @property
    def id(self) -> str:
        """模板 ID."""
        return self.meta.id

    @property
    def name(self) -> str:
        """模板名."""
        return self.meta.name

    @property
    def industry(self) -> Industry:
        """行业."""
        return self.meta.industry


# ---------- 部署实例 ----------


class DeploymentRequest(BaseModel):
    """部署请求（POST /templates/{id}/deploy 的请求体）."""

    tenantId: str = Field(..., description="租户 ID")
    releaseName: str = Field(..., description="Helm release 名称")
    namespace: Optional[str] = Field(default=None, description="K8s namespace（不传则用租户默认）")
    values: dict[str, Any] = Field(default_factory=dict, description="参数取值（注入 values.yaml）")
    datasourceBindings: list[dict[str, str]] = Field(
        default_factory=list,
        description="数据源绑定：[{占位, 资产ID}]",
    )


class DeploymentRecord(TimestampMixin):
    """部署记录."""

    deploymentId: str = Field(..., description="部署 ID")
    templateId: str = Field(..., description="模板 ID")
    templateVersion: str = Field(..., description="模板版本")
    tenantId: str = Field(..., description="租户 ID")
    releaseName: str = Field(..., description="Helm release 名称")
    namespace: str = Field(..., description="K8s namespace")
    status: DeploymentStatus = Field(default=DeploymentStatus.PENDING, description="部署状态")
    renderedValues: dict[str, Any] = Field(default_factory=dict, description="渲染后的 values")
    errorMessage: Optional[str] = Field(default=None, description="失败原因")
    jobRunId: Optional[str] = Field(default=None, description="首次作业运行 ID")
    dashboardSnapshotUrl: Optional[str] = Field(default=None, description="仪表盘快照 URL")
    finishedAt: Optional[datetime] = Field(default=None, description="完成时间")


# ---------- 预览 ----------


class TemplatePreview(BaseModel):
    """模板预览（架构图 + 参数表 + 关键统计）."""

    templateId: str = Field(..., description="模板 ID")
    templateName: str = Field(..., description="模板名")
    industry: Industry = Field(..., description="行业")
    architecture: dict[str, Any] = Field(default_factory=dict, description="架构图（节点+边）")
    parameterSummary: list[dict[str, Any]] = Field(default_factory=list, description="参数摘要")
    stats: dict[str, int] = Field(default_factory=dict, description="统计：节点数/步骤数/面板数")
