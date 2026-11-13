# 外部组件镜像 tag 上游核实表（#48②）

- 落盘时间：2026-11-13 07:1x UTC（本表为重建版，见「诚实缺口声明」）
- 核实时间：2026-11-13 07:05–07:13 UTC（fresh 全量复跑）；初核 05:41Z（transcript L38724/L38728 有输出在案）
- 核实方式：`gh api` GitHub REST（`/releases/latest`、`/tags?per_page=100`、`/git/matching-refs/tags/<前缀>`），绕过本机 Docker Hub 不可达；tag 形态差异（spark 无 Release 只有 tags、flink 用 `release-X.Y.Z`、doris 用 `-release` 后缀）均按 matching-refs 精确前缀查证，不以 tags top-100 列表表象判命中
- 判据：不猜 tag；本表只登记事实、不改任何部署坐标；`scripts/check-image-reference.py` 基线只减不增（本表动作不影响基线，现跑 rc=0、21 条在册）

## 范围口径

`design/deploy` values 面 + iceberg compose 面全量 image 引用中，tag 带上游版本前缀的坐标（`-0.1.0` 后缀为本仓包装版）。纯 `0.1.0` 自研件无上游，单列对照。原「8 个外部组件」名单源自上一会话；本表补登 sq-flink（漏登根因见下），实为 9 个上游族 + 2 个自研对照。

## 核实结果

| 组件 | 仓库坐标（file:line） | 上游 repo | 上游 latest（核实时） | 上游同系列 tag 证据 | 结论 |
|---|---|---|---|---|---|
| seatunnel | `sq-seatunnel:2.3-0.1.0`（values-base.yaml:115、values/seatunnel-values.yaml:11） | apache/seatunnel | v3.0.0（2026-09-29） | 2.3.0…2.3.13 | 系列命中，未钉 patch |
| dolphinscheduler | `sq-dolphinscheduler:3.2-0.1.0`（values-base.yaml:118、values/dolphinscheduler-values.yaml:11） | apache/dolphinscheduler | 3.4.3（2026-09-06） | 3.2.0/3.2.1/3.2.2 | 系列命中 |
| iotdb ×3 | `sq-iotdb`/`sq-iotdb-confignode`/`sq-iotdb-datanode`:2.0-0.1.0（values/iotdb-values.yaml:12/25/48） | apache/iotdb | v2.0.11（2026-09-11） | v2.0.7…v2.0.11 | 系列命中 |
| theia | `sq-theia:1.45-0.1.0`（values/theia-values.yaml:18） | eclipse-theia/theia | v1.77.0（2026-11-05） | v1.45.0/v1.45.1 | 系列命中 |
| spark | `sq-spark:3.5.3-0.1.0`（values/spark-values.yaml:21；values-base.yaml:70 为 `3.5-0.1.0`，漂移） | apache/spark | releases/latest 404（该仓无 GitHub Release，仅 tags） | v3.5.0/v3.5.1…（v3.5.3 matching-refs 命中；tags top-100 被 v4.x 占满，须前缀查） | 系列命中 |
| spark-shuffle | `sq-spark-shuffle:3.5.3-0.1.0`（values/spark-values.yaml:74） | apache/spark | 同上 | 同上 | 系列命中 |
| flink（补登） | `sq-flink:1.20.0-0.1.0`（values/flink-values.yaml:18；values-base.yaml:73 为 `1.18-0.1.0`，漂移） | apache/flink | releases/latest 404（仅 tags） | release-1.20.0…release-1.20.3（tag 形态 `release-X.Y.Z`）；release-1.18.0/1.18.1 亦命中 | 系列命中 |
| doris | `sq-doris:2.1-0.1.0`（values-base.yaml:79、values/doris-values.yaml:11） | apache/doris | 4.1.5-release（2026-10-15） | 2.1.0-release、2.1.8.1（final 存在；初核按 top-100 tags 误判「仅 connector 误命中」，本表订正） | 系列命中 |
| iceberg-rest | `tabio/iceberg-rest:1.11.0`（platform/batch-pipeline/docker/iceberg/docker-compose.yml:78，上游直引非 sq- 包装） | tabio/iceberg-rest | releases+tags 双 404（repo 不存在或私有） | — | 不可经 GitHub 核验（同 KNOWN-FAILURES #17 MinIO 型缺口） |

自研无上游对照：`sq-iceberg-catalog:0.1.0`（values-base.yaml:57）、`sq-theia-init:0.1.0`（values/theia-values.yaml:45）等 `-0.1.0` 件。

## 范围口径注

- 原 8 名单漏 sq-flink 的根因：`scripts/check-image-reference.py` 静态判据只吃 `image:` 键与 `repository:+tag` 形态（IMAGE_RE 在 scripts/check-image-reference.py:54），`sparkImage:`/`flinkImage:` 键不在扫描面 ⇒ sq-spark/sq-flink 从门禁缺口清单逃逸，原名单遂漏 flink。此为门禁覆盖缺口登记项（另案；改判据须自带变异验证）。
- 值漂移：values-base.yaml 与分服务 values 对 flink（1.18 vs 1.20.0）/ spark（3.5 vs 3.5.3）钉不一致；两系列上游均验真，但「钉哪一版」属钉版裁决（甲方案第二步），不在本表动作范围。
- tabio/iceberg-rest:1.11.0 为上游直引；GitHub 双 404 ⇒ 建议按 #17 同型登记 KNOWN-FAILURES，或在可联网环境用 `docker manifest inspect` 实证（本机 Docker Hub 不可达，不猜 tag）。

## 诚实缺口声明

上一会话任务 #71 标 completed 但核实表未落盘（本会话压缩前发现）。本表为重建版：数据 = 2026-11-13 07:05–07:13 UTC fresh `gh api` 全量复跑；初核输出（transcript L38724/L38728）交叉一致处直接采用，冲突处（doris「connector 误命中」判、spark top-100 假象）以 matching-refs 精确查为准并已在表内订正。

## 后续候选（均待裁决，非本表动作）

1. 钉版裁决（甲方案第二步：改 prod values 属部署面，须用户拍板）
2. tabio/iceberg-rest 缺口登记 KNOWN-FAILURES（#17 同型）
3. `*Image:` 键门禁覆盖面（判据改动，须变异验证）
4. values-base.yaml 与分服务 values 的 flink/spark 版本漂移统一
