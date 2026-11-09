# 集成测试栈 AUTH_MODE 开发面豁免记录（2026-11-10 主理人裁决）

> 关联：台账 #68（本记录即其开发面收口）、#119⑦；门禁 `scripts/check-it-auth-mode.py`；基线 `docs/it-auth-mode-baseline.yaml`
> 裁决：**文档化豁免**——IT 开发栈维持匿名夹具形态，剩余 6 域不逐域翻 `AUTH_MODE=jwt`；豁免显性入账（本记录 + 基线在册），门禁逻辑与判据不动。

## 一、豁免范围（与基线在册 6 条一一对应）

| 服务 | 类别（门禁判据） | compose 形态 |
|---|---|---|
| asset-exchange | none | 显式 `AUTH_MODE=none` |
| business-portal | none | 同上 |
| industry-templates | none | 同上 |
| open-api-catalog | none | 同上 |
| evaluation | undeclared | 未声明 `AUTH_MODE`（jwt_auth 回落缺省 none） |
| llm-gateway | undeclared | 同上 |

门禁现值（2026-11-10 实跑 `scripts/check-it-auth-mode.py --report`）：服务 31 个 / 纳入判据 9 个 / 违规 6 个 / 基线在册 6 个 / 新增 0 / 已清偿未销账 0，校验 `rc=0`。
已翻出豁免面、不回退的三域：llmops（PR #472）、model-registry（#555）、finetuning-loop（#560）。

## 二、裁决理由

1. **IT 栈定位＝开发面匿名夹具**。逐域翻 jwt 的真实成本是每服务同批 `JWT_SECRET` + token 同源改造 + e2e 夹具与 `local-e2e.yml:180` pytest 腿同批加 token（取证见 #68 行），相对开发面收益不对等；2026-11-10 主理人裁「文档化豁免」，取代 10-14 方向①的执行口径（已翻三域不回退）。
2. **生产面不在豁免面**。生产由 chart 侧 `AUTH_MODE: jwt` + `jwt_auth.py` 的 K8s fail-fast（仅 `KUBERNETES_SERVICE_HOST` 存在时生效）兜底；本豁免只覆盖 compose 开发栈及其 k3s 开发对应面。
3. **已知局限如实入账**（不包装成覆盖）：
   - 这 6 个 python 域**零 401 覆盖**——`toBe(401)` 断言全部分布在 Java/网关域；
   - 另有 **7 处「匿名可达返回 200」正向断言**把 none 固化成契约：`api-market.spec.ts:27/:34`、`asset-market.spec.ts:28/:35`、`business-portal.spec.ts:23-30`（该域从 `X-Tenant-Id` 头读租户）、`template-market.spec.ts:24/:31`。豁免期内它们是夹具形态断言，不代表鉴权回归覆盖。

## 三、边界（不被本豁免覆盖的面）

- **生产 / 任何 K8s 部署面**：chart `AUTH_MODE: jwt` + fail-fast 是硬前提；若 chart 侧出现 `AUTH_MODE=none` 或 fail-fast 被绕过的证据，本豁免立即失效并按生产事件重新评估。
- **新增服务**：门禁判据不变（模块目录有 `jwt_auth.py` ⇒ 须钉 `AUTH_MODE=jwt` 或入基线）；借本豁免把新域直接入册不被允许，须主理人另裁。
- **k3s 开发栈**：与 compose 6 域同属本豁免（#68 行原记「compose 6 域 + k3s 9 域」）；门禁棘轮的执法面只有 compose，k3s manifest 同口径翻 jwt 属清偿行为（从基线删条目），不受本豁免阻碍。

## 四、复核触发

- 任一域实际翻 `AUTH_MODE=jwt` ⇒ 从基线删条目（棘轮只减不增）并同步本记录范围表；
- 本记录与基线在册条目必须账实相符——门禁对「新增违规」与「已清偿未销账」双向判红，豁免不是免检；
- IT 栈定位变更（如 #129 Mock 替换后需要真 token 链路）或生产面口径变化 ⇒ 本豁免按主理人新裁重开。
