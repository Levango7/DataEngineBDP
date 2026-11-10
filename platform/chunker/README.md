# chunker（纯 Python 包，非部署单元）

- 形态：Python 包 `sq-chunker`（`pyproject.toml`）。提供文本/表格/音频/图像分块器与 `embedding` / `rag` / `asr` 子包。无 main/app 入口、不监听端口，仓内无 APISIX 前缀，不是可独立部署的服务。
- 交付形态：pip 包（sdist/wheel）。由消费方服务声明为 Python 依赖后随其镜像统一交付；本模块自身没有 Dockerfile、Helm chart。`scripts/check-deployable-coverage.py` 的部署单元缺口门禁只审计「含 Dockerfile 的组件」，本包不在其统计口径内。
- 仓内消费者：截至 2026-11-10 全仓核实为零（无其他 pyproject/requirements 声明依赖、无模块外 import）。后续接入方按 pip 依赖声明消费（或 vendor 进消费方镜像），不需要也不应为本包补部署面。
- 除名留痕：本模块原被登记在「P1-7 配了存储但无 Dockerfile」六模块清单，2026-11-07 盘查分级为「纯库、误登记、不是缺口」（`docs/deploy-completeness-plan.md` §三）；本 README 即除名说明，防止后再被当部署缺口补。
