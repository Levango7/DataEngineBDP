"""政务行业模板 (gov-public-services).

业务场景：政务数据共享 + 一网通办办理分析 + 民生诉求监测 + 政务服务指标，覆盖：
    事项数据接入 → 数据共享 → 办理分析 → 民生监测 → 指标看板

Sprint 4.1 落地：Phase 4——政务行业模板实现（P2-28 从域级契约升级为真实模板）。
对齐 ROADMAP「政务行业模板：数据共享、一网通办等典型场景」。
"""

from __future__ import annotations

from industry_templates.models import (
    ComputeLogicConfig,
    ComputeLogicStep,
    DataFlowConfig,
    DataFlowNode,
    Industry,
    ParameterType,
    Template,
    TemplateMeta,
    TemplateParameter,
    TemplateStatus,
    VisualizationConfig,
    VisualizationPanel,
)


def build_template() -> Template:
    """构建政务行业模板."""
    meta = TemplateMeta(
        id="gov-public-services",
        name="政务数据共享与一网通办分析",
        industry=Industry.GOVERNMENT,
        version="0.1.0",
        appVersion="0.1.0",
        description=(
            "政务行业模板：跨部门事项数据接入、数据共享目录、一网通办办理分析、"
            "民生诉求监测与政务服务效能指标。适用于数字政府与政务服务一体化。"
        ),
        author="Shuqing Big Data Platform Team",
        status=TemplateStatus.CATALOG,
        tags=["政务", "数据共享", "一网通办", "民生", "政务服务"],
        icon="🏛️",
    )

    parameters = [
        TemplateParameter(
            name="datasource.affair_db",
            type=ParameterType.DATASOURCE,
            description="政务事项库 JDBC（办理/证照/材料）",
            required=True,
            placeholder="${AFFAIR_DB_JDBC}",
        ),
        TemplateParameter(
            name="datasource.complaint_api",
            type=ParameterType.DATASOURCE,
            description="民生诉求 API（12345/信访）",
            required=True,
            placeholder="${COMPLAINT_API}",
        ),
        TemplateParameter(
            name="share.catalog_owner",
            type=ParameterType.STRING,
            description="数据共享目录责任部门",
            defaultValue="大数据局",
            required=True,
        ),
        TemplateParameter(
            name="kpi.online_rate_target",
            type=ParameterType.FLOAT,
            description="一网通办在线办理率目标",
            defaultValue=0.9,
            required=True,
        ),
        TemplateParameter(
            name="schedule.cron",
            type=ParameterType.STRING,
            description="事项统计 cron",
            defaultValue="0 1 * * *",
            required=True,
        ),
    ]

    # 数据流：事项接入 → 数据共享 → 办理分析 → 民生监测 → 指标看板
    dataFlow = DataFlowConfig(
        nodes=[
            DataFlowNode(
                id="collect_affair",
                name="采集政务事项",
                nodeType="source",
                layer="ods",
                description="采集跨部门事项办理/证照数据",
                outputs=["ods.affair_record"],
                config={"source": "${AFFAIR_DB_JDBC}"},
            ),
            DataFlowNode(
                id="share_catalog",
                name="数据共享目录",
                nodeType="transform",
                layer="dwd",
                description="构建跨部门数据共享目录与授权台账",
                inputs=["ods.affair_record"],
                outputs=["dwd.share_catalog"],
                config={"owner": "${share.catalog_owner}"},
            ),
            DataFlowNode(
                id="affair_analyze",
                name="一网通办分析",
                nodeType="transform",
                layer="dws",
                description="按部门/事项聚合在线办理率与办结时长",
                inputs=["ods.affair_record"],
                outputs=["dws.affair_kpi"],
            ),
            DataFlowNode(
                id="complaint_monitor",
                name="民生诉求监测",
                nodeType="transform",
                layer="dws",
                description="12345 诉求分类聚合与热点识别",
                inputs=["ods.affair_record"],
                outputs=["dws.complaint_topic"],
                config={"source": "${COMPLAINT_API}"},
            ),
            DataFlowNode(
                id="kpi_dashboard",
                name="服务效能看板",
                nodeType="sink",
                layer="ads",
                description="政务服务效能指标汇总与排名",
                inputs=["dws.affair_kpi", "dws.complaint_topic"],
                outputs=["ads.gov_kpi"],
            ),
        ],
        schedule="${schedule.cron}",
        description="政务数据流：事项 → 共享目录 → 办理/民生分析 → 效能看板",
    )

    # 计算逻辑
    computeLogic = ComputeLogicConfig(
        steps=[
            ComputeLogicStep(
                id="sql_share",
                name="共享目录构建",
                stepType="sql",
                description="按部门+事项生成共享目录行",
                inputs=["ods.affair_record"],
                outputs=["dwd.share_catalog"],
                code=(
                    "SELECT dept_id, affair_code, COUNT(*) AS record_cnt, "
                    "MAX(updated_at) AS last_sync\n"
                    "FROM affair_record GROUP BY dept_id, affair_code"
                ),
            ),
            ComputeLogicStep(
                id="sql_affair",
                name="办理分析",
                stepType="sql",
                description="计算在线办理率/平均办结时长",
                inputs=["ods.affair_record"],
                outputs=["dws.affair_kpi"],
                code=(
                    "SELECT dept_id, AVG(CASE WHEN channel='online' THEN 1 ELSE 0 END) "
                    "AS online_rate, AVG(handle_days) AS avg_days\n"
                    "FROM affair_record GROUP BY dept_id"
                ),
            ),
            ComputeLogicStep(
                id="python_complaint",
                name="民生诉求热点",
                stepType="feature",
                description="12345 诉求聚类识别热点",
                inputs=["ods.affair_record"],
                outputs=["dws.complaint_topic"],
                code=(
                    "from complaint import TopicMiner\n"
                    "miner = TopicMiner()\n"
                    "topics = miner.mine(complaint_records)\n"
                    "save_topics(topics)"
                ),
            ),
            ComputeLogicStep(
                id="scoring_kpi",
                name="效能评分",
                stepType="scoring",
                description="综合在线率/办结时长/满意度评分",
                inputs=["dws.affair_kpi", "dws.complaint_topic"],
                outputs=["ads.gov_kpi"],
                code=(
                    "score = 0.4 * online_rate + 0.3 * (1 / avg_days) + 0.3 * satisfaction\n"
                    "flag = '达标' if online_rate >= ${kpi.online_rate_target} else '待改进'\n"
                ),
            ),
        ],
        description="政务计算逻辑：共享目录 → 办理分析 → 民生热点 → 效能评分",
    )

    # 可视化
    visualization = VisualizationConfig(
        panels=[
            VisualizationPanel(
                id="online_trend",
                title="在线办理率趋势",
                chartType="line",
                description="一网通办在线办理率变化",
                dataSource="dws.affair_kpi",
                config={"x": "dt", "series": ["online_rate"]},
            ),
            VisualizationPanel(
                id="dept_rank",
                title="部门效能排名",
                chartType="bar",
                description="各部门综合服务效能得分排名",
                dataSource="ads.gov_kpi",
                config={"x": "dept_id", "y": "score"},
            ),
            VisualizationPanel(
                id="complaint_heatmap",
                title="民生诉求热点",
                chartType="map",
                description="诉求热点区域分布",
                dataSource="dws.complaint_topic",
                config={"geo": "city", "metric": "count"},
            ),
            VisualizationPanel(
                id="share_catalog_table",
                title="数据共享目录",
                chartType="table",
                description="跨部门数据共享目录清单",
                dataSource="dwd.share_catalog",
                config={"columns": ["dept_id", "affair_code", "record_cnt"]},
            ),
        ],
    )

    readme = (
        "# 政务数据共享与一网通办分析模板\n\n"
        "## 适用场景\n"
        "- 数字政府：跨部门数据共享与授权治理\n"
        "- 一网通办：办理效能监测与流程优化\n"
        "- 民生服务：12345 诉求热点识别与回应\n\n"
        "## 参数表\n"
        "| 参数 | 类型 | 必填 | 默认值 | 说明 |\n"
        "|---|---|---|---|---|\n"
        "| datasource.affair_db | datasource | 是 | - | 政务事项库 |\n"
        "| datasource.complaint_api | datasource | 是 | - | 民生诉求 API |\n"
        "| share.catalog_owner | string | 是 | 大数据局 | 共享责任部门 |\n"
        "| kpi.online_rate_target | float | 是 | 0.9 | 在线率目标 |\n\n"
        "## 升级注意事项\n"
        "- 共享目录需按数据安全法落实分级授权\n"
        "- 民生诉求分类模型需随工单语料持续校准"
    )

    return Template(
        meta=meta,
        parameters=parameters,
        dataFlow=dataFlow,
        computeLogic=computeLogic,
        visualization=visualization,
        readme=readme,
        validationSchema={
            "$schema": "http://json-schema.org/draft-07/schema#",
            "type": "object",
            "required": ["datasource", "share", "kpi"],
        },
    )