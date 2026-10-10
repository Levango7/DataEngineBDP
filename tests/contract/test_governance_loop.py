"""治理闭环契约测试（阶段 0）——先红后绿。

背景（依据：docs/治理闭环修复草案.md 第 3 节，均带 路径:行号 取证）：
    闭环的编排本身是完整实现的（CatalogEventListener -> MetadataCollector ->
    GovernancePipelineOrchestrator），但**跨服务的三根线没接**，导致
    "血缘页看不到自动血缘"。本文件的断言就是那三根线的可失败契约：

    T1. 血缘写入者唯一：real-time-pipeline 不得自持血缘图客户端
        （现状：它自带 NebulaLineageGraphClient，与服务侧 LineageGraphWriter 形成两个写入者）。
    T2. 血缘接收端有人调用：lineage-analyzer 的 POST /api/v1/lineage/events
        必须存在非本服务的调用方（现状：零调用方，端点白造）。
    T3. 采集器要 emit：metadata-collector 的 MetadataWriterService 写入 catalog 后
        必须触发通知（现状：只写不发，非 Iceberg 源永远没有自动血缘）。

裁决前提（2026-10-04，见草案 §9）：
    - Q2：保留 Nebula 通路，但统一写入者 → T1 删除的是 pipeline 的写入路径，不是 Nebula 本身。
    - 因此 T1 落地方式可以是"删除"或"降为只读"，本测试只断言"pipeline 不再自持写入实现"。

预期状态：**当前三条全红**。阶段 1（统一写入者）与阶段 2（采集器接入）完成后转绿，
届时本文件即为"闭环已接通"的 CI 可验事实，而不是口头承诺。
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

# 本文件在 tests/contract/（**不在** tests/integration/ 下，故不被 IT 腿收集，
# 不给 IT 计数添红；由 ci.yml 的 standards job 单独跑，秒级、无需服务）
REPO = Path(__file__).resolve().parents[2]
PLATFORM = REPO / "platform"
PIPELINE = PLATFORM / "governance" / "real-time-pipeline"
LINEAGE = PLATFORM / "governance" / "lineage-analyzer"
COLLECTOR = PLATFORM / "governance" / "metadata-collector"


LINEAGE_CLIENT_FILE = (
    PIPELINE / "src/main/java/com/levango7/dataenginebdp/governance/realtime/lineage/LineageIngestClient.java"
)
MINTER_FILE = (
    PLATFORM / "common-security/src/main/java/com/levango7/dataenginebdp/common/security/ServiceTokenMinter.java"
)
COLLECTOR_WRITER_FILE = (
    PLATFORM
    / "governance/metadata-collector/src/main/java/com/levango7/dataenginebdp/governance/collector/service/MetadataWriterService.java"
)


def _java_sources(root: Path) -> list[Path]:
    if not root.is_dir():
        return []
    return [
        p
        for p in root.rglob("*.java")
        if "/target/" not in p.as_posix() and "/src/test/" not in p.as_posix()
    ]


def _sources_outside(root: Path, exclude: Path) -> list[Path]:
    """平台内除 exclude 子树的全部主源码。"""
    return [p for p in _java_sources(root) if exclude not in p.parents]


def test_t1_pipeline_must_not_write_the_lineage_graph() -> None:
    """T1：血缘图的**写入行为**只能存在于 lineage-analyzer 一侧。

    判据修正（2026-10-04）：原版断言"pipeline 内不得存在 Nebula*Lineage*Client 类"，
    属**代理指标**——该类同时承载读缓存（GovernanceController 曾用它查询），
    类存在 ≠ 写入者不唯一，按类名断言会导致"改对了也永远红"。
    改为直接断言写入行为（这才是判定面）：
      a) pipeline 主源码内不得出现 nGQL 写语句（INSERT VERTEX / INSERT EDGE）；
      b) 不得调用图客户端的写入方法 writeLineage。
    """
    violations: list[str] = []
    for src in _java_sources(PIPELINE):
        text = src.read_text(encoding="utf-8", errors="replace")
        if re.search(r"INSERT\s+(VERTEX|EDGE)", text, re.IGNORECASE):
            violations.append(f"{src.relative_to(REPO)}：直接拼 nGQL 写入")
        if re.search(r"\.writeLineage\s*\(", text):
            violations.append(f"{src.relative_to(REPO)}：调用图客户端 writeLineage（图写入行为）")

    assert not violations, (
        "血缘写入者不唯一（裁决 Q2：保留 Nebula 通路，但只允许 lineage-analyzer 写）。"
        "命中：\n  - " + "\n  - ".join(violations)
    )


def test_t2_lineage_ingest_endpoint_has_a_caller_outside_its_own_service() -> None:
    """T2：lineage-analyzer 的血缘写入端点必须被别的服务调用。

    判据：平台内（排除 lineage-analyzer 自身与测试代码）至少一处引用
    "/api/v1/lineage/events" 或等价的血缘 ingest 路径。
    """
    needle = "/api/v1/lineage/events"
    callers = [
        src.relative_to(REPO).as_posix()
        for src in _sources_outside(PLATFORM, LINEAGE)
        if needle in src.read_text(encoding="utf-8", errors="replace")
    ]

    assert callers, (
        f"没有任何服务调用血缘写入端点 {needle} —— 端点存在但零调用方，"
        "自动血缘无法进入查询侧（草案断点 2）。阶段 1 完成后此处应列出 pipeline/collector。"
    )


def test_t3_metadata_writer_emits_after_write() -> None:
    """T3：采集器写入 catalog 后必须 emit，否则非 Iceberg 源没有自动血缘。

    判据：MetadataWriterService 所在文件中，写入之后存在对编排/血缘的触发调用
    （命中 orchestrator.onMetadataCollected / lineage ingest / 事件发布之一）。
    """
    candidates = [
        p
        for p in _java_sources(COLLECTOR)
        if p.name in {"MetadataWriterService.java", "MetadataCollectorService.java"}
    ]
    assert candidates, "未找到采集器写入服务（metadata-collector 结构已变，需更新本契约）"

    triggers = re.compile(
        r"onMetadataCollected|/api/v1/lineage/events|governance/catalog/events|publishEvent|emitEvent|kafkaTemplate\.send",
        re.IGNORECASE,
    )
    silent = [
        p.relative_to(REPO).as_posix()
        for p in candidates
        if not triggers.search(p.read_text(encoding="utf-8", errors="replace"))
    ]

    assert not silent, (
        "采集器只写 catalog、不通知下游（草案断点 4）：" + ", ".join(silent) + "。"
        "阶段 2 完成前，非 Iceberg 源的自动血缘必然缺失。"
    )


def test_t4_outbound_calls_carry_credentials_the_server_accepts() -> None:
    """T4（2026-10-04 探针实测 + 裁决 A 后升级）：接线存在 ≠ 调用能通过。

    实测依据：lineage-analyzer 单跑于 8086 时两个端点均 403；服务端租户过滤器只从
    JWT 声明取租户（TenantContextFilter.java:153-155），无 X-Tenant-Id 分支，
    且要求 iss 与 tenantId 同时存在（:40）。

    裁决 A（服务账号 JWT 换发）落地后，判定面升级为四项：
      Authorization 头、使用 ServiceTokenMinter（而非裸头）、iss 声明、tenantId 声明、
      以及 act=service（审计可区分服务写与用户写）。
    """
    # 判定面按角色分：调用方负责"带上凭据"，签发器负责"声明口径"（单一来源）
    caller_required = {
        "Authorization 头": re.compile(r'header\s*\(\s*"?Authorization"?', re.IGNORECASE),
        "使用服务令牌签发器": re.compile(r"ServiceTokenMinter"),
    }
    minter_required = {
        "iss 声明": re.compile(r"\.issuer\s*\("),
        "tenantId 声明": re.compile(r'\.claim\s*\(\s*"tenantId"'),
        "act=service 声明": re.compile(r'\.claim\s*\(\s*"act"\s*,\s*"service"'),
    }
    checks = {
        "pipeline/LineageIngestClient": LINEAGE_CLIENT_FILE,
        "collector/MetadataWriterService": COLLECTOR_WRITER_FILE,
        "common-security/ServiceTokenMinter": MINTER_FILE,
    }
    violations: list[str] = []
    for label, path in checks.items():
        if not path.is_file():
            violations.append(label + "：文件缺失（结构变更，需更新本契约）")
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        applicable = minter_required if label.endswith("ServiceTokenMinter") else caller_required
        missing = [name for name, pat in applicable.items() if not pat.search(text)]
        if missing:
            violations.append(str(path.relative_to(REPO)) + "：缺 " + "、".join(missing))

    assert not violations, (
        "出站调用缺服务端可接受的凭据形态（裁决 A）。命中：\n  - " + "\n  - ".join(violations)
    )


@pytest.mark.xfail(
    strict=False,
    reason="#6 阶段 3 未实施：pipeline 尚未从 rule-engine 拉取规则；完成后自动 xpass",
)
def test_t5_quality_rules_have_a_single_source_of_truth() -> None:
    """T5（阶段 3 契约，Q1=rule-engine 权威）：流式质量规则必须**从 rule-engine 拉取**。

    判定面（沿用 T1 的教训：断言**接线/行为**，不断言类名）：pipeline 主源码内
    必须出现对 rule-engine 质量规则端点 `/api/v1/quality/rules` 的调用接线
    （WebClient / RestClient / URL 常量均可）。

    预期状态：**当前 xfail（阶段 3 未实施）**。现状是 pipeline 只经自身
    `GovernanceController` 的进程内 `ruleRegistry` 注册（重启即空、多实例各一套），
    rule-engine 里配的规则不作用于任何自动评估（草案断点 C）。
    阶段 3（pipeline 从 rule-engine 拉规则 + 缓存降级 + 去重）完成后本用例自动
    xpass，届时去掉 xfail 标记并纳入阻断。
    """
    needle = "/api/v1/quality/rules"
    wired = [
        src.relative_to(REPO).as_posix()
        for src in _java_sources(PIPELINE)
        if needle in src.read_text(encoding="utf-8", errors="replace")
    ]
    assert wired, (
        "pipeline 未从 rule-engine 拉取质量规则（草案断点 C / Q1 未落地）："
        f"主源码内没有任何文件引用 {needle}。阶段 3 应新增 RuleEngineRuleSource 之类的接线，"
        "把 rule-engine 作为规则唯一权威源，并定 rule-engine 不可用时的降级策略。"
    )
