"""能源行业模板 (energy-iot-monitor).

业务场景：智能电厂/新能源场站物联监控 + 能耗分析 + 异常告警 + 负荷预测，覆盖：
    物联采集 → 能耗聚合 → 能效分析 → 负荷预测 → 告警处置

Sprint 4.1 落地：Phase 4 首个 Sprint——能源行业模板实现（P2-27 从域级契约升级为真实模板）。
对齐 ROADMAP「能源行业模板：IoT 设备模型、能耗分析等典型场景」。
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
    """构建能源行业模板."""
    meta = TemplateMeta(
        id="energy-iot-monitor",
        name="智能能源物联监控与能耗分析",
        industry=Industry.ENERGY,
        version="0.1.0",
        appVersion="0.1.0",
        description=(
            "能源行业模板：发电机组/输变电设备物联采集、能耗聚合、能效分析、"
            "负荷预测与异常告警。适用于智慧电厂、新能源场站与综合能源调度。"
        ),
        author="Shuqing Big Data Platform Team",
        status=TemplateStatus.CATALOG,
        tags=["能源", "物联网", "能耗", "负荷预测", "告警"],
        icon="⚡",
    )

    parameters = [
        TemplateParameter(
            name="datasource.iotdb",
            type=ParameterType.DATASOURCE,
            description="IoTDB 物联时序库连接（发电/输变电设备遥测）",
            required=True,
            placeholder="${IOTDB_URL}",
        ),
        TemplateParameter(
            name="alert.temp_high",
            type=ParameterType.FLOAT,
            description="设备温度高告警阈值（℃）",
            defaultValue=85.0,
            required=True,
        ),
        TemplateParameter(
            name="alert.vibration",
            type=ParameterType.FLOAT,
            description="机组振动告警阈值（mm/s）",
            defaultValue=7.1,
            required=True,
        ),
        TemplateParameter(
            name="forecast.horizon",
            type=ParameterType.INTEGER,
            description="负荷预测时域（天）",
            defaultValue=7,
            required=True,
        ),
        TemplateParameter(
            name="schedule.cron",
            type=ParameterType.STRING,
            description="能耗聚合 cron",
            defaultValue="*/5 * * * *",
            required=True,
        ),
    ]

    # 数据流：物联采集 → 能耗聚合 → 能效分析 → 负荷预测 → 告警
    dataFlow = DataFlowConfig(
        nodes=[
            DataFlowNode(
                id="collect_telemetry",
                name="采集设备遥测",
                nodeType="source",
                layer="ods",
                description="采集发电/输变电设备遥测（温度/振动/功率/电流）",
                outputs=["ods.device_telemetry"],
                config={"source": "${IOTDB_URL}"},
            ),
            DataFlowNode(
                id="energy_agg",
                name="能耗聚合",
                nodeType="transform",
                layer="dwd",
                description="按机组/时段聚合电量与热耗",
                inputs=["ods.device_telemetry"],
                outputs=["dwd.energy_usage"],
            ),
            DataFlowNode(
                id="efficiency",
                name="能效分析",
                nodeType="transform",
                layer="dws",
                description="计算机组能效比/煤耗率/利用率",
                inputs=["dwd.energy_usage"],
                outputs=["dws.energy_kpi"],
            ),
            DataFlowNode(
                id="load_forecast",
                name="负荷预测",
                nodeType="transform",
                layer="dws",
                description="时序模型预测未来负荷",
                inputs=["dws.energy_kpi"],
                outputs=["dws.load_pred"],
                config={"horizon": "${forecast.horizon}"},
            ),
            DataFlowNode(
                id="alert_dispatch",
                name="异常告警",
                nodeType="sink",
                layer="ads",
                description="基于遥测阈值生成设备告警与处置建议",
                inputs=["ods.device_telemetry", "dws.energy_kpi"],
                outputs=["ads.alert_events"],
            ),
        ],
        schedule="${schedule.cron}",
        description="能源数据流：物联遥测 → 能耗/能效 → 负荷预测 → 告警",
    )

    # 计算逻辑
    computeLogic = ComputeLogicConfig(
        steps=[
            ComputeLogicStep(
                id="sql_agg",
                name="能耗聚合",
                stepType="sql",
                description="按机组+5分钟窗口聚合电耗热耗",
                inputs=["ods.device_telemetry"],
                outputs=["dwd.energy_usage"],
                code=(
                    "SELECT unit_id, FLOOR(ts / 300) AS slot, "
                    "SUM(power_kw) AS kwh, SUM(heat_mj) AS mj\n"
                    "FROM device_telemetry GROUP BY unit_id, FLOOR(ts / 300)"
                ),
            ),
            ComputeLogicStep(
                id="python_efficiency",
                name="能效计算",
                stepType="feature",
                description="计算能效比/煤耗率",
                inputs=["dwd.energy_usage"],
                outputs=["dws.energy_kpi"],
                code=(
                    "from energy import EfficiencyAnalyzer\n"
                    "analyzer = EfficiencyAnalyzer()\n"
                    "kpi = analyzer.compute(energy_usage)\n"
                    "save_kpi(kpi)"
                ),
            ),
            ComputeLogicStep(
                id="python_forecast",
                name="负荷预测",
                stepType="model",
                description="时序模型预测未来负荷",
                inputs=["dws.energy_kpi"],
                outputs=["dws.load_pred"],
                code=(
                    "from models import LoadForecaster\n"
                    "model = LoadForecaster()\n"
                    "pred = model.predict(energy_kpi, horizon=${forecast.horizon})\n"
                    "save_pred(pred)"
                ),
            ),
            ComputeLogicStep(
                id="rule_alert",
                name="告警规则",
                stepType="rule",
                description="温度/振动越限触发设备告警",
                inputs=["ods.device_telemetry"],
                outputs=["ads.alert_events"],
                code=(
                    "if device.temp > ${alert.temp_high}: emit('TEMP_HIGH')\n"
                    "if device.vibration > ${alert.vibration}: emit('VIBRATION')\n"
                ),
            ),
        ],
        description="能源计算逻辑：能耗聚合 → 能效计算 → 负荷预测 → 告警规则",
    )

    # 可视化
    visualization = VisualizationConfig(
        panels=[
            VisualizationPanel(
                id="energy_trend",
                title="能耗趋势",
                chartType="line",
                description="分机组电耗/热耗时序趋势",
                dataSource="dwd.energy_usage",
                config={"x": "ts", "series": ["kwh", "mj"]},
            ),
            VisualizationPanel(
                id="efficiency_rank",
                title="机组能效 TOP",
                chartType="bar",
                description="各机组能效比排名",
                dataSource="dws.energy_kpi",
                config={"x": "unit_id", "y": "efficiency"},
            ),
            VisualizationPanel(
                id="load_forecast",
                title="负荷预测",
                chartType="line",
                description="未来负荷预测曲线",
                dataSource="dws.load_pred",
                config={"x": "ts", "series": ["pred", "actual"]},
            ),
            VisualizationPanel(
                id="alert_list",
                title="设备告警",
                chartType="table",
                description="实时设备异常告警清单",
                dataSource="ads.alert_events",
                config={"columns": ["device_id", "type", "level", "time"]},
            ),
        ],
    )

    readme = (
        "# 智能能源物联监控与能耗分析模板\n\n"
        "## 适用场景\n"
        "- 智慧电厂：发电机组遥测监控与能效分析\n"
        "- 新能源场站：风机/光伏功率预测与告警\n"
        "- 综合能源调度：负荷预测与需求响应\n\n"
        "## 参数表\n"
        "| 参数 | 类型 | 必填 | 默认值 | 说明 |\n"
        "|---|---|---|---|---|\n"
        "| datasource.iotdb | datasource | 是 | - | 物联时序库 |\n"
        "| alert.temp_high | float | 是 | 85.0 | 高温阈值(℃) |\n"
        "| alert.vibration | float | 是 | 7.1 | 振动阈值(mm/s) |\n"
        "| forecast.horizon | int | 是 | 7 | 预测时域(天) |\n\n"
        "## 升级注意事项\n"
        "- 告警阈值需按设备型号/季节校准\n"
        "- 负荷预测模型需定期用近期数据重训"
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
            "required": ["datasource", "alert", "forecast"],
        },
    )