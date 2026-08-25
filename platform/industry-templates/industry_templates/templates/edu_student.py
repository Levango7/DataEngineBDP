"""教育行业模板 (edu-student-profile).

业务场景：学情画像 + 教学质量评估 + 资源调度优化，覆盖：
    教务数据接入 → 学习行为采集 → 学情画像 → 教学评估 → 资源调度

对齐 ROADMAP v2.1「教育行业模板：学情画像 + 教学质量评估 + 资源调度优化」。
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
    """构建教育行业模板."""
    meta = TemplateMeta(
        id="edu-student-profile",
        name="学情画像与教学评估",
        industry=Industry.EDUCATION,
        version="0.1.0",
        appVersion="0.1.0",
        description=(
            "教育行业模板：学生学情画像、教学质量评估与教育资源调度。" "覆盖成绩/行为/选课多维分析，服务智慧校园建设。"
        ),
        author="Shuqing Big Data Platform Team",
        status=TemplateStatus.CATALOG,
        tags=["教育", "学情", "画像", "教学评估", "智慧校园"],
        icon="🎓",
    )

    parameters = [
        TemplateParameter(
            name="datasource.edu_db",
            type=ParameterType.DATASOURCE,
            description="教务系统数据库 JDBC（成绩/选课/学籍）",
            required=True,
            placeholder="${EDU_DB_JDBC}",
        ),
        TemplateParameter(
            name="profile.dimensions",
            type=ParameterType.ENUM,
            description="画像维度",
            defaultValue="full",
            enumOptions=["full", "academic", "behavior"],
            required=True,
        ),
        TemplateParameter(
            name="evaluate.kpi_version",
            type=ParameterType.STRING,
            description="教学评估 KPI 版本",
            defaultValue="v2026",
            required=True,
        ),
        TemplateParameter(
            name="behavior.watch_window_days",
            type=ParameterType.INTEGER,
            description="学习行为观察窗（天）",
            defaultValue=30,
            required=True,
        ),
        TemplateParameter(
            name="schedule.cron",
            type=ParameterType.STRING,
            description="学情日结 cron",
            defaultValue="0 1 * * *",
            required=True,
        ),
    ]

    # 数据流：教务接入 → 行为采集 → 学情画像 → 教学评估 → 资源调度
    dataFlow = DataFlowConfig(
        nodes=[
            DataFlowNode(
                id="collect_edu",
                name="采集教务数据",
                nodeType="source",
                layer="ods",
                description="采集学籍/成绩/选课/排课数据",
                outputs=["ods.edu_core"],
                config={"source": "${EDU_DB_JDBC}"},
            ),
            DataFlowNode(
                id="collect_behavior",
                name="采集学习行为",
                nodeType="source",
                layer="ods",
                description="采集登录/刷课/作业等行为日志",
                outputs=["ods.learn_behavior"],
                config={"window": "${behavior.watch_window_days}"},
            ),
            DataFlowNode(
                id="student_profile",
                name="学情画像构建",
                nodeType="transform",
                layer="dwd",
                description="融合成绩+行为构建学生多维画像",
                inputs=["ods.edu_core", "ods.learn_behavior"],
                outputs=["dwd.student_profile"],
                config={"dimensions": "${profile.dimensions}"},
            ),
            DataFlowNode(
                id="teach_evaluate",
                name="教学质量评估",
                nodeType="transform",
                layer="dws",
                description="按班级/课程/教师计算教学 KPI",
                inputs=["dwd.student_profile"],
                outputs=["dws.teach_kpi"],
                config={"version": "${evaluate.kpi_version}"},
            ),
            DataFlowNode(
                id="resource_plan",
                name="资源调度优化",
                nodeType="sink",
                layer="ads",
                description="基于学情生成辅导/选课/排课建议",
                inputs=["dwd.student_profile", "dws.teach_kpi"],
                outputs=["ads.resource_plan"],
            ),
        ],
        schedule="${schedule.cron}",
        description="教育数据流：教务/行为 → 学情画像 → 教学评估 → 资源调度",
    )

    # 计算逻辑
    computeLogic = ComputeLogicConfig(
        steps=[
            ComputeLogicStep(
                id="sql_profile",
                name="学情画像",
                stepType="sql",
                description="成绩百分位 + 行为活跃度融合",
                inputs=["ods.edu_core", "ods.learn_behavior"],
                outputs=["dwd.student_profile"],
                code=(
                    "SELECT s.student_id, s.class_id, "
                    "PERCENT_RANK() OVER (ORDER BY s.avg_score) AS score_pct, "
                    "b.active_days, s.trend\n"
                    "FROM edu_core s LEFT JOIN learn_behavior b ON s.student_id = b.student_id"
                ),
            ),
            ComputeLogicStep(
                id="sql_evaluate",
                name="教学 KPI",
                stepType="sql",
                description="班级均分/及格率/进步度",
                inputs=["dwd.student_profile"],
                outputs=["dws.teach_kpi"],
                code=(
                    "SELECT class_id, AVG(avg_score) AS avg_score, "
                    "SUM(CASE WHEN avg_score >= 60 THEN 1 ELSE 0 END) / COUNT(*) AS pass_rate\n"
                    "FROM student_profile GROUP BY class_id"
                ),
            ),
            ComputeLogicStep(
                id="python_alert",
                name="学业预警",
                stepType="rule",
                description="成绩下滑/行为异常预警",
                inputs=["dwd.student_profile"],
                outputs=["ads.resource_plan"],
                code=(
                    "for stu in student_profile:\n"
                    "    if stu.score_pct < 0.3 and stu.active_days < 5:\n"
                    "        emit_alert(stu.student_id, '学业预警')"
                ),
            ),
        ],
        description="教育计算逻辑：SQL 画像 → SQL 评估 → 规则预警",
    )

    # 可视化
    visualization = VisualizationConfig(
        panels=[
            VisualizationPanel(
                id="score_dist",
                title="成绩分布",
                chartType="bar",
                description="全校成绩分布直方图",
                dataSource="dwd.student_profile",
                config={"x": "score_bucket", "y": "cnt"},
            ),
            VisualizationPanel(
                id="class_rank",
                title="班级教学 KPI",
                chartType="table",
                description="班级均分/及格率排名",
                dataSource="dws.teach_kpi",
                config={"columns": ["class_id", "avg_score", "pass_rate"]},
            ),
            VisualizationPanel(
                id="risk_list",
                title="学业预警名单",
                chartType="table",
                description="需关注学生名单",
                dataSource="ads.resource_plan",
                config={"columns": ["student_id", "risk_level", "reason"]},
            ),
        ],
    )

    readme = (
        "# 学情画像与教学评估模板\n\n"
        "## 适用场景\n"
        "- K12/高校学情分析与个性化辅导\n"
        "- 教学质量评估与教师绩效\n"
        "- 教育资源（师资/教室）调度优化\n\n"
        "## 参数表\n"
        "| 参数 | 类型 | 必填 | 默认值 | 说明 |\n"
        "|---|---|---|---|---|\n"
        "| datasource.edu_db | datasource | 是 | - | 教务库 JDBC |\n"
        "| profile.dimensions | enum | 是 | full | 画像维度 |\n"
        "| evaluate.kpi_version | string | 是 | v2026 | KPI 版本 |\n"
        "| behavior.watch_window_days | int | 是 | 30 | 行为观察窗 |\n\n"
        "## 升级注意事项\n"
        "- KPI 口径变更需教务部门确认\n"
        "- 行为画像需隐私脱敏合规"
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
            "required": ["datasource", "profile", "evaluate"],
        },
    )
