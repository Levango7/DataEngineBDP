# storage-io（纯 Java 库，非部署单元）

- 形态：Java 库（groupId `com.levango7.dataenginebdp`，artifactId `storage-io`）。提供对象存储抽象 `storage.api.ObjectStore` / `ObjectMetadata` / `StorageProfile`、S3 实现 `storage.impl.S3ObjectStore` 与工厂 `ObjectStoreFactory`。无 Application 主类、无打包插件，不产出可独立部署的服务。
- 交付形态：jar。由消费方服务在自己的构建里依赖本库，打包进其镜像统一交付；本模块自身没有 Dockerfile、Helm chart、APISIX 前缀。`scripts/check-deployable-coverage.py` 的部署单元缺口门禁只审计「含 Dockerfile 的组件」，本库不在其统计口径内。
- 仓内消费者：截至 2026-11-10 全仓核实为零（root pom 仅 `<module>` 收录；无其他 pom 的 `<dependency>`、无模块外 Java import）。`docker/` 内仅 compose 复制用的场景文件，不构成部署单元。后续若有服务接入，按 Maven 依赖声明消费，不需要也不应为本库补部署面。
- 除名留痕：本模块原被登记在「P1-7 配了存储但无 Dockerfile」六模块清单，2026-11-07 盘查分级为「纯库、误登记、不是缺口」（`docs/deploy-completeness-plan.md` §三）；本 README 即除名说明，防止后再被当部署缺口补。
