# Flink CDC 部署形态裁决与使用说明（2026-11-07 主理人准）

> 归属：数据平台 · 流数据摄取层
> 裁决：**作业提交器**（非常驻服务）；提供「即时提交 / 定时提交」两种受配置驱动的调用模式
> 关联：台账 #117（部署完整性专项）、`platform/flink-cdc/`

## 一、这个模块是什么（仓内实况）

`platform/flink-cdc` 是平台自研的 **Flink CDC 作业框架**（Debezium 系源 + 多 Sink + exactly-once 检查点）：

- **形态证据**（均在仓内可核）：① `CdcFrameworkMain.java` 类头即「Flink CDC 管道命令行入口」，用法 `flink run -c ...CdcFrameworkMain flink-cdc.jar --config cdc-mysql.yaml`，参数含 `--async`（提交后立即返回）；② `pom.xml` 的 flink-streaming-java / flink-clients / flink-runtime 全部 **`provided` scope** ⇒ 它只携带作业逻辑，运行时由外部 Flink 集群提供；③ 运维脚本 `scripts/start-cdc-mysql.sh` 的用法是带 JobManager 主机名提交作业；④ `resources/` 下四个 YAML 模板（mysql/postgres/oracle/kafka）是**作业定义**而非服务配置。
- **另含一个未完成面**：`materializedview/MaterializedViewController.java` 声明了 `/api/materialized-views` 六个 REST 路由，但**模块内无任何 `SpringApplication.run` / HTTP 服务器入口**——该 Controller 目前无运行宿主（APISIX 已生成的 `flink-cdc:8080` 路由指向一个不存在服务的端口，属「部署完整性专项」待办，不在本裁决范围内）。

## 二、裁决：作业提交器，不常驻

| 维度 | 作业提交器（采纳） | 常驻服务（否决） |
|---|---|---|
| 与代码现状 | 完全吻合（provided scope + `flink run` 契约 + `--async`） | 需要在仓内自维护 Flink RM/TM 生命周期 |
| 云原生底座成本 | 一份小镜像（JRE + flink-clients + job jar），按需运行、用完即退 | 常驻 Pod 空转计费 + 与 Flink 集群**双重资源管理**（RM 职责重沓） |
| CDC 作业的真实位置 | 长驻在 **Flink 集群**（checkpoint/ exactly-once 由集群承担），提交器只负责「定义与投递」 | 把长驻职责搬进提交器 = 自建集群 |
| 故障域 | 提交器挂了不影响在跑作业（Flink 集群自治） | 单点 |
| 特定时点调用 | CronJob/Job + `--config` 天然适配低峰窗口、批量追赶 | 常驻没有「时点」概念 |

**结论：CDC 作业长驻 Flink 集群；本模块的部署单元是「提交器」**（一份可带 YAML 配置运行的镜像）。

## 三、配置驱动的两种调用模式（可选项，采纳）

同一镜像同一 artifact，按需切换，不捏造第三种形态：

1. **即时提交（ad-hoc）**——运维窗口人工触发：
   ```bash
   docker run --rm -e FLINK_JOBMANAGER=flink-jm:8081 \
     -v ./cdc-mysql.yaml:/config/cdc.yaml \
     <registry>/sq-flink-cdc-submitter:<ver> \
     flink run -c com.levango7.dataenginebdp.flinkcdc.CdcFrameworkMain \
       /opt/flink-cdc/flink-cdc.jar --config /config/cdc.yaml --async
   ```
2. **定时提交（scheduled）**——特定时点（低峰/批量追赶）以 K8s CronJob 调用同一镜像与同一 `flink run` 契语（CronJob manifest 属部署完整性专项 chart 批次交付；本说明锁定镜像与行为契约）。

## 四、本裁决的落地物

- `platform/flink-cdc/Dockerfile`：提交器镜像（多段构建：Maven 打包 job jar → `flink:1.20.0-java17` 运行层放入 job jar 与模板；默认 CMD 即 `flink run -c ...` 提交形态；同时提供 `--async` 用法）
- 不在本镜像内：物化视图管理面（无 server 入口，见 §一末段，专项 backlog）
