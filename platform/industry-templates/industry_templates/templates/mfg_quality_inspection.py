"""制造产线质检模板 (mfg-quality-inspection).

业务场景：产品质检流水线，覆盖：
    图像采集 → 缺陷检测 → 质量分级 → 报告生成

对齐设计文档第 3 节"行业模板清单"中的 mfg-line-quality。
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
    """构建制造产线质检模板."""
    meta = TemplateMeta(
        id="mfg-quality-inspection",
        name="产线质检流水线",
        industry=Industry.MANUFACTURING,
        version="0.1.0",
        appVersion="0.1.0",
        description=(
            "制造产线质检模板：从图像采集到质量报告全流水线，"
            "覆盖缺陷检测、质量分级、SPC 监控。"
            "基于深度学习 + 时序异常检测 + 规则引擎，开箱即用。"
        ),
        author="Shuqing Big Data Platform Team",
        status=TemplateStatus.CATALOG,
        tags=["制造", "质检", "缺陷检测", "SPC", "深度学习"],
        icon="🏭",
    )

    parameters = [
        TemplateParameter(
            name="datasource.image_stream",
            type=ParameterType.DATASOURCE,
            description="产线图像流接入地址（Kafka topic 或 RTSP）",
            required=True,
            placeholder="${IMAGE_STREAM_URL}",
        ),
        TemplateParameter(
            name="datasource.mes_db",
            type=ParameterType.DATASOURCE,
            description="MES 系统数据库 JDBC（工单/工艺参数）",
            required=True,
            placeholder="${MES_DB_JDBC}",
        ),
        TemplateParameter(
            name="datasource.iotdb",
            type=ParameterType.DATASOURCE,
            description="IoTDB 时序库连接（传感器数据）",
            required=True,
            placeholder="${IOTDB_URL}",
        ),
        TemplateParameter(
            name="defect.model_type",
            type=ParameterType.ENUM,
            description="缺陷检测模型类型",
            defaultValue="yolov8",
            enumOptions=["yolov8", "mask_rcnn", "efficientdet", "resnet_cls"],
            required=True,
        ),
        TemplateParameter(
            name="defect.confidence_threshold",
            type=ParameterType.FLOAT,
            description="缺陷置信度阈值",
            defaultValue=0.5,
            required=True,
        ),
        TemplateParameter(
            name="quality.spc_cl_threshold",
            type=ParameterType.FLOAT,
            description="SPC 控制限倍数（如 3σ）",
            defaultValue=3.0,
            required=True,
        ),
        TemplateParameter(
            name="quality.batch_size",
            type=ParameterType.INTEGER,
            description="批次大小（每批报告一次）",
            defaultValue=100,
            required=True,
        ),
        TemplateParameter(
            name="alert.webhook",
            type=ParameterType.STRING,
            description="质量异常告警 webhook",
            required=False,
        ),
        TemplateParameter(
            name="schedule.cron",
            type=ParameterType.STRING,
            description="SPC 周期计算 cron",
            defaultValue="*/10 * * * *",
            required=True,
        ),
    ]

    # 数据流：图像采集 → 缺陷检测 → 质量分级 → 报告生成
    dataFlow = DataFlowConfig(
        nodes=[
            DataFlowNode(
                id="collect_image",
                name="采集产线图像",
                nodeType="source",
                layer="ods",
                description="从产线图像流采集产品图像",
                outputs=["ods.product_image"],
                config={
                    "source": "${IMAGE_STREAM_URL}",
                    "mode": "stream",
                },
            ),
            DataFlowNode(
                id="collect_mes",
                name="采集 MES 工单",
                nodeType="source",
                layer="ods",
                description="从 MES 系统采集工单与工艺参数",
                outputs=["ods.mes_workorder"],
                config={"source": "${MES_DB_JDBC}"},
            ),
            DataFlowNode(
                id="collect_sensor",
                name="采集传感器数据",
                nodeType="source",
                layer="ods",
                description="从 IoTDB 采集温度/压力/振动等时序数据",
                outputs=["ods.sensor_ts"],
                config={"source": "${IOTDB_URL}"},
            ),
            DataFlowNode(
                id="defect_detect",
                name="缺陷检测",
                nodeType="transform",
                layer="dwd",
                description="深度学习模型检测产品缺陷",
                inputs=["ods.product_image"],
                outputs=["dwd.defect_result"],
                config={
                    "model": "${defect.model_type}",
                    "confidence": "${defect.confidence_threshold}",
                },
            ),
            DataFlowNode(
                id="sensor_anomaly",
                name="传感器异常检测",
                nodeType="transform",
                layer="dwd",
                description="时序异常检测识别设备异常",
                inputs=["ods.sensor_ts"],
                outputs=["dwd.sensor_anomaly"],
                config={"method": "iqr", "window": "5min"},
            ),
            DataFlowNode(
                id="quality_grade",
                name="质量分级",
                nodeType="transform",
                layer="dws",
                description="综合缺陷+传感器结果划分质量等级",
                inputs=["dwd.defect_result", "dwd.sensor_anomaly", "ods.mes_workorder"],
                outputs=["dws.quality_grade"],
            ),
            DataFlowNode(
                id="spc_monitor",
                name="SPC 监控",
                nodeType="transform",
                layer="dws",
                description="统计过程控制，计算控制限与异常告警",
                inputs=["dws.quality_grade"],
                outputs=["dws.spc_stat"],
                config={
                    "cl_multiple": "${quality.spc_cl_threshold}",
                },
            ),
            DataFlowNode(
                id="report_gen",
                name="质检报告生成",
                nodeType="sink",
                layer="ads",
                description="按批次生成质检报告与不良品追溯",
                inputs=["dws.quality_grade", "dws.spc_stat"],
                outputs=["ads.quality_report"],
                config={"batch_size": "${quality.batch_size}"},
            ),
        ],
        schedule="${schedule.cron}",
        description="产线质检数据流：图像/MES/传感器 → 缺陷/异常 → 分级/SPC → 报告",
    )

    # 计算逻辑
    computeLogic = ComputeLogicConfig(
        steps=[
            ComputeLogicStep(
                id="python_defect",
                name="缺陷检测模型",
                stepType="model",
                description="基于 ${defect.model_type} 检测产品缺陷",
                inputs=["ods.product_image"],
                outputs=["dwd.defect_result"],
                code=(
                    "from ultralytics import YOLO\n"
                    "model = YOLO('${defect.model_type}.pt')\n"
                    "for img in image_stream:\n"
                    "    results = model(img, conf=${defect.confidence_threshold})\n"
                    "    save_defects(results)"
                ),
                params={"modelType": "${defect.model_type}"},
            ),
            ComputeLogicStep(
                id="python_sensor_anomaly",
                name="传感器异常检测",
                stepType="feature",
                description="IQR + 滑动窗口检测传感器异常",
                inputs=["ods.sensor_ts"],
                outputs=["dwd.sensor_anomaly"],
                code=(
                    "def detect_anomaly(ts, window='5min'):\n"
                    "    q1, q3 = ts.rolling(window).quantile(0.25), ts.rolling(window).quantile(0.75)\n"
                    "    iqr = q3 - q1\n"
                    "    return ts[(ts < q1 - 1.5*iqr) | (ts > q3 + 1.5*iqr)]"
                ),
            ),
            ComputeLogicStep(
                id="sql_quality_grade",
                name="质量分级 SQL",
                stepType="sql",
                description="综合缺陷数与传感器异常划分 A/B/C/D 等级",
                inputs=["dwd.defect_result", "dwd.sensor_anomaly"],
                outputs=["dws.quality_grade"],
                code=(
                    "CREATE TABLE dws.quality_grade AS\n"
                    "SELECT\n"
                    "    w.workorder_id, w.product_id,\n"
                    "    COUNT(d.defect_id) AS defect_count,\n"
                    "    COUNT(a.anomaly_id) AS anomaly_count,\n"
                    "    CASE\n"
                    "        WHEN COUNT(d.defect_id) = 0 AND COUNT(a.anomaly_id) = 0 THEN 'A'\n"
                    "        WHEN COUNT(d.defect_id) <= 2 THEN 'B'\n"
                    "        WHEN COUNT(d.defect_id) <= 5 THEN 'C'\n"
                    "        ELSE 'D'\n"
                    "    END AS quality_grade\n"
                    "FROM ods.mes_workorder w\n"
                    "LEFT JOIN dwd.defect_result d ON w.product_id = d.product_id\n"
                    "LEFT JOIN dwd.sensor_anomaly a ON w.workorder_id = a.workorder_id\n"
                    "GROUP BY w.workorder_id, w.product_id;"
                ),
            ),
            ComputeLogicStep(
                id="python_spc",
                name="SPC 控制图",
                stepType="scoring",
                description="计算 X-bar R 控制限，识别超限点",
                inputs=["dws.quality_grade"],
                outputs=["dws.spc_stat"],
                code=(
                    "mean, std = batch['defect_count'].mean(), batch['defect_count'].std()\n"
                    "ucl = mean + ${quality.spc_cl_threshold} * std  # 上控制限\n"
                    "lcl = mean - ${quality.spc_cl_threshold} * std  # 下控制限\n"
                    "out_of_control = batch[(batch['defect_count'] > ucl) | (batch['defect_count'] < lcl)]\n"
                    "if not out_of_control.empty and ${alert.webhook}:\n"
                    "    notify(${alert.webhook}, out_of_control)"
                ),
            ),
            ComputeLogicStep(
                id="python_report",
                name="报告生成",
                stepType="rule",
                description="按批次生成 PDF/Excel 质检报告",
                inputs=["dws.quality_grade", "dws.spc_stat"],
                outputs=["ads.quality_report"],
                code=(
                    "from reportlab import generate_pdf\n"
                    "batch = collect_batch(size=${quality.batch_size})\n"
                    "report = generate_pdf(batch, spc_stat)\n"
                    "save_to_asset_catalog(report, source='template:mfg-quality-inspection')"
                ),
            ),
        ],
        description="产线质检计算逻辑：缺陷检测 + 异常检测 + 质量分级 + SPC + 报告生成",
    )

    # 可视化
    visualization = VisualizationConfig(
        title="产线质检仪表盘",
        panels=[
            VisualizationPanel(
                id="panel_yield_rate",
                title="良率趋势",
                chartType="line",
                description="各产线良率趋势（A 级占比）",
                dataSource="dws.quality_grade",
                width=12,
                height=320,
                config={
                    "xField": "date",
                    "yField": "yield_rate",
                    "seriesField": "line_id",
                },
            ),
            VisualizationPanel(
                id="panel_grade_dist",
                title="质量等级分布",
                chartType="pie",
                description="A/B/C/D 等级占比",
                dataSource="dws.quality_grade",
                width=6,
                height=300,
                config={
                    "field": "quality_grade",
                    "values": ["A", "B", "C", "D"],
                },
            ),
            VisualizationPanel(
                id="panel_defect_pareto",
                title="缺陷帕累托",
                chartType="bar",
                description="缺陷类型帕累托图（Top10）",
                dataSource="dwd.defect_result",
                width=6,
                height=300,
                config={"xField": "count", "yField": "defect_type"},
            ),
            VisualizationPanel(
                id="panel_spc_control",
                title="SPC 控制图",
                chartType="line",
                description="X-bar 控制图含 UCL/CL/LCL",
                dataSource="dws.spc_stat",
                width=12,
                height=340,
                config={
                    "xField": "batch_id",
                    "yField": "mean",
                    "markLines": ["ucl", "cl", "lcl"],
                },
            ),
            VisualizationPanel(
                id="panel_sensor_heatmap",
                title="传感器热力",
                chartType="heatmap",
                description="传感器异常分布热力图",
                dataSource="dwd.sensor_anomaly",
                width=12,
                height=320,
                config={
                    "xField": "timestamp",
                    "yField": "sensor_id",
                    "valueField": "anomaly_score",
                },
            ),
        ],
        description="产线质检仪表盘：良率趋势 / 等级分布 / 缺陷帕累托 / SPC 控制图 / 传感器热力",
    )

    readme = (
        "# 产线质检流水线模板\n\n"
        "## 业务场景\n"
        "制造产线质检：图像采集 → 缺陷检测 → 质量分级 → 报告生成。\n\n"
        "## 适用场景\n"
        "- 离散制造（电子/汽车/机械）的在线质检\n"
        "- 流程制造（化工/食品）的 SPC 监控\n"
        "- 需要追溯与报告合规的场景\n\n"
        "## 参数表\n"
        "| 参数 | 类型 | 必填 | 默认值 | 说明 |\n"
        "|---|---|---|---|---|\n"
        "| datasource.image_stream | datasource | 是 | - | 图像流地址 |\n"
        "| datasource.mes_db | datasource | 是 | - | MES 库 JDBC |\n"
        "| defect.model_type | enum | 是 | yolov8 | 检测模型 |\n"
        "| defect.confidence_threshold | float | 是 | 0.5 | 置信度阈值 |\n"
        "| quality.spc_cl_threshold | float | 是 | 3.0 | SPC 控制限倍数 |\n"
        "| quality.batch_size | int | 是 | 100 | 批次大小 |\n\n"
        "## 升级注意事项\n"
        "- 模型升级需保留旧模型对比，避免漏检率突变\n"
        "- SPC 控制限变更需工艺工程师复核"
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
            "required": ["datasource", "defect", "quality"],
        },
    )
