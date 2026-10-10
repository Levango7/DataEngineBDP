"""门面单一权威契约（台账 #1b）。

裁决（docs/治理闭环修复草案.md §9，2026-10-04）：**以专用服务为权威** ——
`encaps-layer` 的 `TemplateController` / `LLMOpsService` 这些"门面重复实现"
退化为转发或删除；APISIX 前缀按前端实际调用收敛，不再保留"两个服务各自实现同一域"。

背景（台账 #1/#1b）：
- `/api/v1/templates` 网关已裁定指向 **industry-templates**（它实现了 `/{id}/deploy`、
  `/{id}/preview`、`/{id}/deployments`、`/categories` 全量；encaps-layer 的仅 list/get/create），
  故 encaps-layer 的 TemplateController 是**网关不可达的重复实现**。
- `/api/v1/llmops` 网关指向 encaps-layer（因只有它实现 `/inference-services`），
  但它用自有 4 个仓储重复了 llmops 服务的 models/eval-metrics/finetune/human-eval 域。

本文件是静态契约（tests/contract/，不进 IT 腿；由 ci.yml 的 standards job 跑，秒级无服务）。
"""

from __future__ import annotations

from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[2]
ENCAPS = REPO / "platform/encaps-layer/src/main/java/com/levango7/dataenginebdp/encaps"
INDUSTRY_TEMPLATES = REPO / "platform/industry-templates"


def _read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace")


def test_t6_template_domain_has_single_implementation() -> None:
    """T6：templates 域只允许一个实现（industry-templates）——encaps-layer 的门面须已删/转发。

    现状：encaps-layer 的 TemplateController + TemplateEntity + TemplateRepository 已删除
    （无服务间调用方、无测试引用、网关本就指向 industry-templates）。
    """
    leftovers = [
        p.relative_to(REPO).as_posix()
        for p in (
            ENCAPS / "controller/TemplateController.java",
            ENCAPS / "model/TemplateEntity.java",
            ENCAPS / "repository/TemplateRepository.java",
        )
        if p.exists()
    ]
    assert not leftovers, (
        "encaps-layer 仍保留 templates 门面重复实现（裁决 #1b：以专用服务为权威）："
        + ", ".join(leftovers)
    )

    # 权威方必须真的实现该域（否则是"删了两边都没有"）
    impls = [p for p in INDUSTRY_TEMPLATES.rglob("*.py") if "/templates" in _read(p)]
    assert impls, "industry-templates 未实现 /api/v1/templates（templates 域将无权威实现）"


@pytest.mark.xfail(
    strict=False,
    reason="#1b 后续项：encaps-layer 的 LLMOpsService 尚未退化为转发到 llmops 服务",
)
def test_t7_llmops_domain_has_single_authority() -> None:
    """T7：llmops 域的 models/eval-metrics/finetune 实现应只在一处。

    现状：encaps-layer 的 LLMOpsService 自持 MlModelRepository / FinetuneTaskRepository /
    EvalMetricRepository / InferenceServiceRepository，与 `platform/llmops` 服务重复
    （同一业务域两套建表 ⇒ 数据分裂）。裁决要求退化为**转发**（仅 `/inference-services`
    可暂留本地，直至其也迁入专用服务）。完成后本用例自动 xpass，届时去掉 xfail 并纳入阻断。
    """
    svc = ENCAPS / "service/LLMOpsService.java"
    assert svc.is_file(), "结构变更：未找到 LLMOpsService.java（需更新本契约）"
    text = _read(svc)
    duplicated = [
        name
        for name in (
            "MlModelRepository",
            "FinetuneTaskRepository",
            "EvalMetricRepository",
            "InferenceServiceRepository",
        )
        if name in text
    ]
    assert not duplicated, (
        "encaps-layer 的 LLMOpsService 仍自持重复仓储（应退化为转发到 llmops 服务）："
        + ", ".join(duplicated)
    )
