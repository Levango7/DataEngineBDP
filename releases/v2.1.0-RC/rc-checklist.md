# V2.1.0-RC 发布检查清单 (RC Checklist)
> 基于 V2.0.0 GA 检查清单调整，适配 RC 级验收标准
> 所有检查项必须通过方可标记 RC 发布完成

## 1. 版本基线检查

| 编号 | 检查项 | 标准 | 结果 | 备注 |
|------|--------|------|------|------|
| 1.1 | 版本号一致性 | 所有 pom.xml/go.mod/pyproject.toml/package.json 统一为 2.1.0-RC | ☐ | mvn versions:set 已执行 |
| 1.2 | CHANGELOG 更新 | [2.1.0-RC] 章节完整，含 Security/Fixed/Added/Changed/Corrected | ☐ | 已更新 |
| 1.3 | RELEASE-NOTES 存在 | releases/v2.1.0-RC/RELEASE-NOTES.md 完整 | ☐ | 已创建 |
| 1.4 | Git tag 打标 | git tag v2.1.0-RC && git push origin v2.1.0-RC | ☐ | 待执行 |
| 1.5 | 分支状态 | main 分支包含 fix/security-audit-hardening 全部修复 | ☐ | 已合并 |

## 2. CI/CD 流水线检查

| 编号 | 检查项 | 标准 | 结果 | 备注 |
|------|--------|------|------|------|
| 2.1 | ci.yml 全绿 | lint/build/test/helm-lint/coverage-gate 全通过 | ☐ | 含 security-scan/codeql/sonarqube |
| 2.2 | build.yml 全绿 | 多语言构建产物生成无误 | ☐ | Java/Go/Python/Frontend |
| 2.3 | multi-arch-build.yml | ARM64+x86_64 镜像构建推送 Harbor | ☐ | 需 Harbor 凭据 |
| 2.4 | image-sign-sbom.yml | cosign 签名 + SBOM 生成 | ☐ | --tlog-upload=true |
| 2.5 | security-scan.yml | gitleaks/Trivy/govulncheck/依赖审查零 Critical/High | ☐ | |
| 2.6 | codeql.yml | Java/Go/Python/JS SAST 零 Critical/High | ☐ | |
| 2.7 | sonarqube.yml | Quality Gate 通过 | ☐ | 需 SonarCloud token |
| 2.8 | 覆盖率门禁诚实化 | Java line≥35%/branch≥15%、Go≥30%、Python≥55% | ☐ | CI 配置已更新 |

## 3. 功能验收检查

| 编号 | 检查项 | 标准 | 结果 | 备注 |
|------|--------|------|------|------|
| 3.1 | 7 条 E2E 链路 | verify-e2e-dataflow.sh 全通过 | ☐ | 数据集成/批计算/流计算/交互查询/治理闭环/BI/多租户 |
| 3.2 | 集成测试套件 | tests/integration/ 全通过 | ☐ | pytest -v tests/integration/ |
| 3.3 | 单元测试 | mvn test / go test / pytest / vitest 全通过 | ☐ | 零失败 |
| 3.4 | 前端构建 | npm run build:all 成功，37 路由 49 分包 | ☐ | |
| 3.5 | 前端测试 | vitest + playwright 全通过 | ☐ | 183 单测 + 87 E2E |

## 4. 安全合规检查

| 编号 | 检查项 | 标准 | 结果 | 备注 |
|------|--------|------|------|------|
| 4.1 | 等保三级材料 | docs/compliance/ 目录下报告齐备不退化 | ☐ | 测评报告/整改记录/复测报告 |
| 4.2 | 密评材料 | SM2/SM3/SM4 密码应用评估报告存在 | ☐ | |
| 4.3 | 容器非 root | 18 个 Dockerfile 均为 USER app | ☐ | knative runtimes 豁免 |
| 4.4 | JWT 密钥无弱默认 | catalog/llm-gateway 等值为空串，激活 secret.yaml required | ☐ | |
| 4.5 | RBAC 生效 | llm-gateway Provider 注册/路由需 admin，catalog 租户隔离 404 | ☐ | |
| 4.6 | CORS 收敛 | 无 Access-Control-Allow-Origin: *，均为白名单 | ☐ | |
| 4.7 | gitleaks 零泄漏 | CI 阻断 | ☐ | |

## 5. 部署验证检查

| 编号 | 检查项 | 标准 | 结果 | 备注 |
|------|--------|------|------|------|
| 5.1 | kind 本地一键 | scripts/local-up.sh -> catalog 2/2 Running | ☐ | E 盘 Docker Desktop |
| 5.2 | 云环境部署 | 华为云 CCE / 阿里云 ACK 任一 ArgoCD 同步 Healthy | ☐ | 需云账号 |
| 5.3 | 四环境 Profile | chart-render-check.sh 校验 4 套 values 无占位符/合法 YAML | ☐ | xinchuang/onprem/public-cloud/private-cloud |
| 5.4 | Helm Chart 渲染 | 87 个 Chart helm lint 全通过 | ☐ | 含 umbrella |
| 5.5 | 镜像拉取验证 | Harbor v2.1.0-RC 镜像全部可拉取，cosign verify 通过 | ☐ | |

## 6. 文档与交付物检查

| 编号 | 检查项 | 标准 | 结果 | 备注 |
|------|--------|------|------|------|
| 6.1 | README 版本标 | 顶部显示 v2.1.0-RC + experimental 标注 | ☐ | |
| 6.2 | ROADMAP 状态 | v2.1 进展项标记完成，v2.2+ 规划清晰 | ☐ | |
| 6.3 | API 参考文档 | docs/user-guide/api-reference.md V2.2 勘误实况化 | ☐ | |
| 6.4 | 升级指南 | docs/user-guide/upgrade-guide.md 覆盖 V2.0→V2.1 | ☐ | |
| 6.5 | 发布物料完整 | RELEASE-NOTES/helm-values/upgrade-script/rc-checklist/component-matrix 全部存在 | ☐ | |

## 7. 已知限制确认

| 编号 | 限制项 | 文档已披露 | 结果 |
|------|--------|------------|------|
| 7.1 | 10 个 AI/模型组件 experimental | RELEASE-NOTES §2、§4 | ☐ |
| 7.2 | 四环境部署验证进行中 | RELEASE-NOTES §4 | ☐ |
| 7.3 | 性能基线为实验室参考值 | RELEASE-NOTES §4 | ☐ |
| 7.4 | 覆盖率未达 85% | RELEASE-NOTES §4 | ☐ |
| 7.5 | 默认 H2/SQLite 需切 PostgreSQL | RELEASE-NOTES §4、helm-values.yaml 注释 | ☐ |

## 8. 签字确认

| 角色 | 签字 | 日期 | 备注 |
|------|------|------|------|
| 架构负责人 | _______________ | 2026-08-__ | |
| 开发负责人 | _______________ | 2026-08-__ | |
| 测试负责人 | _______________ | 2026-08-__ | |
| 安全负责人 | _______________ | 2026-08-__ | |
| 发布工程师 | _______________ | 2026-08-__ | |
| 产品负责人 | _______________ | 2026-08-__ | |

---

> **DataEngineBDP V2.1.0-RC 发布检查清单**  
> **版本：RC-01 | 日期：2026-08-27 | 状态：待执行**
