"""医疗行业模板 (med-emr-quality).

业务场景：电子病历结构化 → 医疗质控 → DRG/DIP 分组，覆盖：
    病历接入 → NLP 结构化 → 质控规则 → DRG/DIP 分组 → 监管报表

对齐设计文档 ROADMAP v2.1「医疗行业模板：电子病历结构化 + 医疗质控
+ DRG/DIP 分组 + 医疗设备物联」。
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
    """构建医疗行业模板."""
    meta = TemplateMeta(
        id="med-emr-quality",
        name="医疗质控与 DRG/DIP",
        industry=Industry.MEDICAL,
        version="0.1.0",
        appVersion="0.1.0",
        description=(
            "医疗行业模板：电子病历 NLP 结构化、医疗质控规则、DRG/DIP 分组"
            "与医保监管报表，覆盖门诊/住院/手术全流程。开箱即用。"
        ),
        author="Shuqing Big Data Platform Team",
        status=TemplateStatus.CATALOG,
        tags=["医疗", "电子病历", "质控", "DRG", "DIP", "NLP"],
        icon="🏥",
    )

    parameters = [
        TemplateParameter(
            name="datasource.his_db",
            type=ParameterType.DATASOURCE,
            description="HIS 系统数据库 JDBC（挂号/医嘱/收费）",
            required=True,
            placeholder="${HIS_DB_JDBC}",
        ),
        TemplateParameter(
            name="datasource.emr_db",
            type=ParameterType.DATASOURCE,
            description="EMR 电子病历库 JDBC（病历文本）",
            required=True,
            placeholder="${EMR_DB_JDBC}",
        ),
        TemplateParameter(
            name="nlp.model_type",
            type=ParameterType.ENUM,
            description="病历 NLP 模型",
            defaultValue="bert-medical",
            enumOptions=["bert-medical", "chinese-medical-bert", "llm-extract"],
            required=True,
        ),
        TemplateParameter(
            name="quality.overstay_hours",
            type=ParameterType.FLOAT,
            description="超长住院告警阈值（小时）",
            defaultValue=72.0,
            required=True,
        ),
        TemplateParameter(
            name="drg.version",
            type=ParameterType.STRING,
            description="DRG 分组版本",
            defaultValue="CHS-DRG-2023",
            required=True,
        ),
        TemplateParameter(
            name="alert.webhook",
            type=ParameterType.STRING,
            description="质控异常告警 webhook",
            required=False,
        ),
        TemplateParameter(
            name="schedule.cron",
            type=ParameterType.STRING,
            description="日结质控 cron",
            defaultValue="0 2 * * *",
            required=True,
        ),
    ]

    # 数据流：病历接入 → NLP 结构化 → 质控 → DRG/DIP → 监管报表
    dataFlow = DataFlowConfig(
        nodes=[
            DataFlowNode(
                id="collect_his",
                name="采集 HIS 数据",
                nodeType="source",
                layer="ods",
                description="采集挂号/医嘱/收费等 HIS 数据",
                outputs=["ods.his_visit"],
                config={"source": "${HIS_DB_JDBC}"},
            ),
            DataFlowNode(
                id="collect_emr",
                name="采集电子病历",
                nodeType="source",
                layer="ods",
                description="采集病历文书与结构化字段",
                outputs=["ods.emr_doc"],
                config={"source": "${EMR_DB_JDBC}"},
            ),
            DataFlowNode(
                id="nlp_struct",
                name="病历 NLP 结构化",
                nodeType="transform",
                layer="dwd",
                description="NLP 抽取主诊断/手术/并发症等结构化字段",
                inputs=["ods.emr_doc"],
                outputs=["dwd.emr_struct"],
                config={"model": "${nlp.model_type}"},
            ),
            DataFlowNode(
                id="quality_check",
                name="医疗质控",
                nodeType="transform",
                layer="dws",
                description="质控规则：诊断书写/时效/超长住院",
                inputs=["dwd.emr_struct", "ods.his_visit"],
                outputs=["dws.quality_issue"],
                config={"overstay": "${quality.overstay_hours}"},
            ),
            DataFlowNode(
                id="drg_group",
                name="DRG/DIP 分组",
                nodeType="transform",
                layer="dws",
                description="按主诊断+手术+并发症进行 DRG/DIP 分组",
                inputs=["dwd.emr_struct", "ods.his_visit"],
                outputs=["dws.drg_result"],
                config={"version": "${drg.version}"},
            ),
            DataFlowNode(
                id="report_export",
                name="监管报表生成",
                nodeType="sink",
                layer="ads",
                description="生成医保监管/质控月报",
                inputs=["dws.quality_issue", "dws.drg_result"],
                outputs=["ads.med_report"],
            ),
        ],
        schedule="${schedule.cron}",
        description="医疗数据流：HIS/EMR → NLP 结构化 → 质控/DRG → 监管报表",
    )

    # 计算逻辑
    computeLogic = ComputeLogicConfig(
        steps=[
            ComputeLogicStep(
                id="python_nlp",
                name="病历 NLP 结构化",
                stepType="model",
                description="基于 ${nlp.model_type} 抽取诊断/手术/并发症",
                inputs=["ods.emr_doc"],
                outputs=["dwd.emr_struct"],
                code=(
                    "from transformers import pipeline\n"
                    "extractor = pipeline('token-classification', '${nlp.model_type}')\n"
                    "for doc in emr_docs:\n"
                    "    save_struct(extract_entities(doc, extractor))"
                ),
                params={"modelType": "${nlp.model_type}"},
            ),
            ComputeLogicStep(
                id="sql_quality",
                name="医疗质控规则",
                stepType="sql",
                description="诊断书写完整性 + 超长住院告警",
                inputs=["dwd.emr_struct", "ods.his_visit"],
                outputs=["dws.quality_issue"],
                code=(
                    "SELECT v.visit_id, v.patient_id, "
                    "CASE WHEN e.main_diag IS NULL THEN 1 ELSE 0 END AS missing_diag, "
                    "CASE WHEN TIMESTAMPDIFF(HOUR, v.admit, v.discharge) > ${quality.overstay_hours} "
                    "THEN 1 ELSE 0 END AS overstay\n"
                    "FROM his_visit v LEFT JOIN emr_struct e ON v.visit_id = e.visit_id"
                ),
            ),
            ComputeLogicStep(
                id="python_drg",
                name="DRG/DIP 分组器",
                stepType="feature",
                description="按 CHS-DRG 规则分组",
                inputs=["dwd.emr_struct"],
                outputs=["dws.drg_result"],
                code=(
                    "from drg_py import DrgGrouper\n"
                    "grouper = DrgGrouper('${drg.version}')\n"
                    "for row in emr_struct:\n"
                    "    save_drg(grouper.group(row))"
                ),
            ),
        ],
        description="医疗计算逻辑：NLP 结构化 → SQL 质控 → DRG 分组",
    )

    # 可视化
    visualization = VisualizationConfig(
        panels=[
            VisualizationPanel(
                id="drg_dist",
                title="DRG 分组分布",
                chartType="bar",
                description="各 DRG 组病例数分布",
                dataSource="dws.drg_result",
                config={"x": "drg_code", "y": "cnt"},
            ),
            VisualizationPanel(
                id="quality_trend",
                title="质控问题趋势",
                chartType="line",
                description="质控问题数按月趋势",
                dataSource="dws.quality_issue",
                config={"x": "month", "y": "issue_cnt"},
            ),
            VisualizationPanel(
                id="overstay_rank",
                title="超长住院 TOP10",
                chartType="table",
                description="超长住院科室排名",
                dataSource="dws.quality_issue",
                config={"columns": ["dept", "patient_id", "days"]},
            ),
        ],
    )

    readme = (
        "# 医疗质控与 DRG/DIP 模板\n\n"
        "## 适用场景\n"
        "- 三级医院电子病历质控与医保 DRG/DIP 分组\n"
        "- 卫健委区域医疗质量监管\n"
        "- 病案首页质控与医保结算核查\n\n"
        "## 参数表\n"
        "| 参数 | 类型 | 必填 | 默认值 | 说明 |\n"
        "|---|---|---|---|---|\n"
        "| datasource.his_db | datasource | 是 | - | HIS 库 JDBC |\n"
        "| datasource.emr_db | datasource | 是 | - | EMR 库 JDBC |\n"
        "| nlp.model_type | enum | 是 | bert-medical | NLP 模型 |\n"
        "| quality.overstay_hours | float | 是 | 72 | 超长住院阈值 |\n"
        "| drg.version | string | 是 | CHS-DRG-2023 | 分组版本 |\n\n"
        "## 升级注意事项\n"
        "- DRG 版本更新需医保局发文对齐\n"
        "- NLP 模型升级需临床科室参与标注验证"
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
            "required": ["datasource", "nlp", "quality", "drg"],
        },
    )
