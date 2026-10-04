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

# 本文件在 tests/contract/（**不在** tests/integration/ 下，故不被 IT 腿收集，
# 不给 IT 计数添红；由 ci.yml 的 standards job 单独跑，秒级、无需服务）
REPO = Path(__file__).resolve().parents[2]
PLATFORM = REPO / "platform"
PIPELINE = PLATFORM / "governance" / "real-time-pipeline"
LINEAGE = PLATFORM / "governance" / "lineage-analyzer"
COLLECTOR = PLATFORM / "governance" / "metadata-collector"


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
        r"onMetadataCollected|/api/v1/lineage/events|publishEvent|emitEvent|kafkaTemplate\.send",
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
