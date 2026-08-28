/**
 * 引擎层 API 模块
 *
 * 涵盖统一存储、批计算 Spark、流计算 Flink、OLAP Doris、消息流 Kafka、
 * 时序引擎 IoTDB、多模型引擎 7 个子域，对接后端：
 * - VirtualTableController        /virtual-tables
 * - MaterializedViewController    /materialized-views  (注意：无 v1 前缀)
 * - JobController                 /jobs (type=batch_spark / stream_flink)
 * - StreamBatchSchedulerController /stream-batch
 * - SqlGatewayController          /sql/engines, /sql/execute, /sql/explain
 * - DataSourceController          /datasources (type=kafka / iotdb)
 * - 专用 Kafka / IoTDB 端点
 *
 * 所有方法通过 `@/api/client` 的 get/post/put/del 调用，
 * 自动享受 Bearer token 注入、ApiResponse<T> 拆包、401/403/500 统一错误提示、30s 超时。
 */
import { get, post, del } from './client'
import type { PagedResult } from './types'

/* ================================================================== */
/* 通用类型                                                            */
/* ================================================================== */

/** 通用分页查询参数 */
export interface EnginePageQuery {
  /** 关键字搜索 */
  keyword?: string
  /** 页码（从 1 开始） */
  page?: number
  /** 每页条数 */
  size?: number
}

/* ================================================================== */
/* 1. 统一存储（虚拟表 + 物化视图）                                     */
/* ================================================================== */

/** 虚拟表状态 */
export type VirtualTableStatus = 'ACTIVE' | 'INACTIVE' | 'ERROR' | 'REFRESHING'

/** 虚拟表列定义 */
export interface ColumnDefinition {
  /** 列名 */
  name: string
  /** 列类型 */
  type: string
  /** 是否可空 */
  nullable?: boolean
  /** 注释 */
  comment?: string
}

/** 虚拟表定义 */
export interface VirtualTableDefinition {
  /** 表名 */
  tableName: string
  /** 数据源类型 */
  dataSourceType: string
  /** 数据源名称 */
  dataSourceName?: string
  /** schema 描述 */
  schema?: string
  /** 状态 */
  status?: VirtualTableStatus
  /** 行数 */
  rowCount?: number
  /** 最近查询时间 */
  lastQueryAt?: string
  /** 最近刷新时间 */
  lastRefreshAt?: string
  /** 创建时间 */
  createdAt?: string
  /** 备注 */
  comment?: string
}

/** 虚拟表查询结果 */
export interface VirtualTableQueryResult {
  /** 列名列表 */
  columns: string[]
  /** 数据行 */
  rows: unknown[][]
  /** 行数 */
  rowCount: number
  /** 耗时（毫秒） */
  durationMs?: number
}

/** 缓存统计 */
export interface CacheStats {
  /** 缓存命中率（百分比） */
  hitRate: number
  /** 缓存总条目数 */
  totalEntries: number
  /** 命中次数 */
  hitCount: number
  /** 未命中次数 */
  missCount: number
  /** 今日刷新次数 */
  refreshToday: number
  /** 缓存大小（MB） */
  sizeMb?: number
}

/** 物化视图定义 */
export interface MaterializedViewDef {
  /** 视图名 */
  viewName: string
  /** 源表名 */
  sourceTable: string
  /** 刷新策略 */
  refreshStrategy?: string
  /** 最近刷新时间 */
  lastRefreshAt?: string
  /** 状态 */
  status?: string
  /** 行数 */
  rowCount?: number
  /** 创建时间 */
  createdAt?: string
}

/** 物化视图刷新状态 */
export interface MaterializedViewStatus {
  /** 视图名 */
  viewName: string
  /** 当前状态 */
  status: string
  /** 最近刷新时间 */
  lastRefreshAt?: string
  /** 下次计划刷新时间 */
  nextRefreshAt?: string
  /** 最近一次错误信息 */
  errorMessage?: string
}

/** 虚拟表资源根路径 */
const VT_BASE = '/virtual-tables'

/** 物化视图资源根路径（无 v1 前缀，使用 baseURL 覆盖） */
const MV_BASE = '/materialized-views'
const MV_CONFIG = { baseURL: '/api' }

/**
 * 列出虚拟表
 * @param type 数据源类型过滤（可选）
 */
export function getVirtualTables(type?: string): Promise<VirtualTableDefinition[]> {
  return get<VirtualTableDefinition[]>(VT_BASE, type ? { dataSourceType: type } : undefined)
}

/**
 * 注册虚拟表
 * @param def 虚拟表定义
 */
export function registerVirtualTable(
  def: Partial<VirtualTableDefinition> & { tableName: string; dataSourceType: string }
): Promise<VirtualTableDefinition> {
  return post<VirtualTableDefinition>(VT_BASE, def)
}

/**
 * 获取虚拟表 schema
 * @param name 表名
 */
export function getVirtualTableSchema(name: string): Promise<ColumnDefinition[]> {
  return get<ColumnDefinition[]>(`${VT_BASE}/${encodeURIComponent(name)}/schema`)
}

/**
 * 查询虚拟表数据
 * @param name 表名
 * @param predicate 过滤谓词（可选）
 * @param limit 行数限制（可选）
 */
export function queryVirtualTable(
  name: string,
  predicate?: string,
  limit?: number
): Promise<VirtualTableQueryResult> {
  return post<VirtualTableQueryResult>(`${VT_BASE}/${encodeURIComponent(name)}/query`, {
    predicate,
    limit
  })
}

/**
 * 测试虚拟表连接
 * @param name 表名
 */
export function testVirtualTableConnection(
  name: string
): Promise<{ connected: boolean; latencyMs?: number }> {
  return post<{ connected: boolean; latencyMs?: number }>(
    `${VT_BASE}/${encodeURIComponent(name)}/test-connection`
  )
}

/**
 * 刷新虚拟表（物化表）
 * @param name 表名
 */
export function refreshVirtualTable(name: string): Promise<{ refreshed: boolean; rows: number }> {
  return post<{ refreshed: boolean; rows: number }>(
    `${VT_BASE}/${encodeURIComponent(name)}/refresh`
  )
}

/**
 * 获取缓存统计
 */
export function getCacheStats(): Promise<CacheStats> {
  return get<CacheStats>(`${VT_BASE}/cache/stats`)
}

/**
 * 列出支持的数据源类型
 */
export function listDataSourceTypes(): Promise<string[]> {
  return get<string[]>(`${VT_BASE}/types`)
}

/**
 * 列出物化视图
 */
export function getMaterializedViews(): Promise<MaterializedViewDef[]> {
  return get<MaterializedViewDef[]>(MV_BASE, undefined, MV_CONFIG)
}

/**
 * 手动刷新物化视图
 * @param name 视图名
 */
export function refreshMaterializedView(name: string): Promise<{ eventId: string }> {
  return post<{ eventId: string }>(
    `${MV_BASE}/${encodeURIComponent(name)}/refresh`,
    undefined,
    MV_CONFIG
  )
}

/**
 * 查询物化视图刷新状态
 * @param name 视图名
 */
export function getMaterializedViewStatus(name: string): Promise<MaterializedViewStatus> {
  return get<MaterializedViewStatus>(
    `${MV_BASE}/${encodeURIComponent(name)}/status`,
    undefined,
    MV_CONFIG
  )
}

/**
 * 查询全部物化视图全局状态
 */
export function getAllMaterializedViewStatus(): Promise<MaterializedViewStatus[]> {
  return get<MaterializedViewStatus[]>(`${MV_BASE}/status`, undefined, MV_CONFIG)
}

/* ================================================================== */
/* 2. 批计算 Spark（JobController，type=batch_spark）                    */
/* ================================================================== */

/** Spark 作业状态 */
export type SparkJobStatus = 'RUNNING' | 'FINISHED' | 'FAILED' | 'KILLED' | 'PENDING' | 'SCHEDULED'

/** Spark 作业信息 */
export interface SparkJob {
  /** 作业 ID */
  id: string
  /** 作业名 */
  name: string
  /** 工作空间 ID */
  workspaceId: string
  /** 作业类型固定为 batch_spark */
  type: 'batch_spark'
  /** 作业配置（JSON 字符串：mainClass, args, jars, ...） */
  config: string
  /** 调度表达式（cron） */
  schedule?: string
  /** 负责人 */
  owner?: string
  /** 当前状态 */
  status: SparkJobStatus | string
  /** 最近运行状态 */
  lastRunStatus?: string
  /** Driver 资源（CPU/内存） */
  driverResource?: string
  /** Executor 资源 */
  executorResource?: string
  /** Stage 总数 */
  stageTotal?: number
  /** Stage 完成数 */
  stageCompleted?: number
  /** 提交时间 */
  submittedAt?: string
  /** 运行时长（毫秒） */
  durationMs?: number
  /** 创建时间 */
  createdAt: string
  /** 更新时间 */
  updatedAt: string
}

/** Spark 作业创建请求 */
export interface SparkJobCreateRequest {
  name: string
  workspaceId: string
  /** mainClass 全限定名 */
  mainClass: string
  /** 应用 JAR 路径 */
  jarUri?: string
  /** 启动参数 */
  args?: string
  /** Driver 资源 */
  driverResource?: string
  /** Executor 资源 */
  executorResource?: string
  /** 调度表达式 */
  schedule?: string
  /** 负责人 */
  owner?: string
}

/** Spark 作业列表查询参数 */
export interface SparkJobListQuery extends EnginePageQuery {
  workspaceId?: string
  status?: string
}

/** Spark 作业资源根路径 */
const SPARK_BASE = '/jobs'
const SPARK_TYPE = 'batch_spark'

/**
 * 列出 Spark 批作业
 * @param params 查询参数
 */
export function getSparkJobs(params?: SparkJobListQuery): Promise<PagedResult<SparkJob>> {
  return get<PagedResult<SparkJob>>(SPARK_BASE, {
    ...params,
    type: SPARK_TYPE
  })
}

/**
 * 获取 Spark 作业详情
 * @param id 作业 ID
 */
export function getSparkJob(id: string): Promise<SparkJob> {
  return get<SparkJob>(`${SPARK_BASE}/${encodeURIComponent(id)}`)
}

/**
 * 创建 Spark 批作业
 * @param req 创建请求
 */
export function submitSparkJob(req: SparkJobCreateRequest): Promise<SparkJob> {
  const config = JSON.stringify({
    mainClass: req.mainClass,
    jarUri: req.jarUri,
    args: req.args,
    driverResource: req.driverResource,
    executorResource: req.executorResource
  })
  return post<SparkJob>(SPARK_BASE, {
    name: req.name,
    workspaceId: req.workspaceId,
    type: SPARK_TYPE,
    config,
    schedule: req.schedule,
    owner: req.owner
  })
}

/**
 * 运行 Spark 作业（转 DAG 提交）
 * @param id 作业 ID
 */
export function runSparkJob(id: string): Promise<{ dagId: string; status: string }> {
  return post<{ dagId: string; status: string }>(`${SPARK_BASE}/${encodeURIComponent(id)}/run`)
}

/**
 * 取消 Spark 作业
 * @param id 作业 ID
 */
export function cancelSparkJob(id: string): Promise<void> {
  return post<void>(`${SPARK_BASE}/${encodeURIComponent(id)}/cancel`)
}

/**
 * 删除 Spark 作业
 * @param id 作业 ID
 */
export function deleteSparkJob(id: string): Promise<void> {
  return del<void>(`${SPARK_BASE}/${encodeURIComponent(id)}`)
}

/**
 * 查询 Spark 作业日志
 * @param id 作业 ID
 */
export function getSparkJobLogs(id: string): Promise<string> {
  return get<string>(`${SPARK_BASE}/${encodeURIComponent(id)}/logs`)
}

/* ================================================================== */
/* 3. 流计算 Flink（JobController，type=stream_flink + 专用端点）        */
/* ================================================================== */

/** Flink 作业状态 */
export type FlinkJobStatus =
  'RUNNING' | 'FAILED' | 'CANCELED' | 'FINISHED' | 'RESTARTING' | 'CREATED' | 'SCHEDULED'

/** 反压等级 */
export type BackpressureLevel = 'ok' | 'low' | 'high'

/** Flink 作业信息 */
export interface FlinkJob {
  /** 作业 ID */
  id: string
  /** 作业名 */
  name: string
  /** 工作空间 ID */
  workspaceId: string
  /** 作业类型固定为 stream_flink */
  type: 'stream_flink'
  /** 配置（JSON 字符串） */
  config: string
  /** 当前状态 */
  status: FlinkJobStatus | string
  /** 并行度 */
  parallelism: number
  /** 启动时间 */
  startTime?: string
  /** 运行时长（毫秒） */
  durationMs?: number
  /** Checkpoint 总数 */
  checkpointCount: number
  /** Checkpoint 成功数 */
  checkpointSuccessCount?: number
  /** Checkpoint 失败数 */
  checkpointFailCount?: number
  /** 反压等级 */
  backpressureLevel: BackpressureLevel
  /** Source 吞吐（条/秒） */
  sourceThroughput?: number
  /** Sink 吞吐（条/秒） */
  sinkThroughput?: number
  /** 平均延迟（毫秒） */
  latencyMs?: number
  /** 负责人 */
  owner?: string
  /** 创建时间 */
  createdAt: string
  /** 更新时间 */
  updatedAt: string
}

/** Flink 作业创建请求 */
export interface FlinkJobCreateRequest {
  name: string
  workspaceId: string
  /** Flink Job JAR 路径或 SQL */
  jobUri?: string
  /** SQL 内容（与 jobUri 二选一） */
  sql?: string
  /** 并行度 */
  parallelism: number
  /** Checkpoint 间隔（毫秒） */
  checkpointIntervalMs?: number
  /** 负责人 */
  owner?: string
}

/** Checkpoint 信息 */
export interface Checkpoint {
  /** Checkpoint ID */
  id: string
  /** 触发时间 */
  triggerTime: string
  /** 完成时间 */
  completedTime?: string
  /** 状态 */
  status: 'COMPLETED' | 'IN_PROGRESS' | 'FAILED' | 'DISCARDED'
  /** 大小（字节） */
  size?: number
  /** 路径 */
  path?: string
  /** 耗时（毫秒） */
  durationMs?: number
}

/** Savepoint 信息 */
export interface Savepoint {
  /** Savepoint ID */
  id: string
  /** 触发时间 */
  triggerTime: string
  /** 完成时间 */
  completedTime?: string
  /** 状态 */
  status: 'COMPLETED' | 'IN_PROGRESS' | 'FAILED'
  /** 路径 */
  path?: string
  /** 耗时（毫秒） */
  durationMs?: number
}

/** 反压指标 */
export interface BackpressureMetrics {
  /** 作业 ID */
  jobId: string
  /** 整体反压等级 */
  level: BackpressureLevel
  /** 反压比率（0-1） */
  ratio: number
  /** 各算子反压详情 */
  operators?: Array<{
    name: string
    level: BackpressureLevel
    ratio: number
  }>
}

/** Flink 作业列表查询参数 */
export interface FlinkJobListQuery extends EnginePageQuery {
  workspaceId?: string
  status?: string
}

const FLINK_BASE = '/jobs'
const FLINK_TYPE = 'stream_flink'

/**
 * 列出 Flink 流作业
 * @param params 查询参数
 */
export function getFlinkJobs(params?: FlinkJobListQuery): Promise<PagedResult<FlinkJob>> {
  return get<PagedResult<FlinkJob>>(FLINK_BASE, {
    ...params,
    type: FLINK_TYPE
  })
}

/**
 * 获取 Flink 作业详情
 * @param id 作业 ID
 */
export function getFlinkJob(id: string): Promise<FlinkJob> {
  return get<FlinkJob>(`${FLINK_BASE}/${encodeURIComponent(id)}`)
}

/**
 * 创建 Flink 流作业
 * @param req 创建请求
 */
export function submitFlinkJob(req: FlinkJobCreateRequest): Promise<FlinkJob> {
  const config = JSON.stringify({
    jobUri: req.jobUri,
    sql: req.sql,
    parallelism: req.parallelism,
    checkpointIntervalMs: req.checkpointIntervalMs
  })
  return post<FlinkJob>(FLINK_BASE, {
    name: req.name,
    workspaceId: req.workspaceId,
    type: FLINK_TYPE,
    config,
    owner: req.owner
  })
}

/**
 * 运行 Flink 作业
 * @param id 作业 ID
 */
export function runFlinkJob(id: string): Promise<{ dagId: string }> {
  return post<{ dagId: string }>(`${FLINK_BASE}/${encodeURIComponent(id)}/run`)
}

/**
 * 停止 Flink 作业
 * @param id 作业 ID
 */
export function stopFlinkJob(id: string): Promise<void> {
  return post<void>(`${FLINK_BASE}/${encodeURIComponent(id)}/cancel`)
}

/**
 * 触发 Savepoint
 * @param id 作业 ID
 */
export function triggerSavepoint(id: string): Promise<{ savepointPath: string; eventId: string }> {
  return post<{ savepointPath: string; eventId: string }>(
    `${FLINK_BASE}/${encodeURIComponent(id)}/savepoint`
  )
}

/**
 * 列出 Checkpoint 历史
 * @param jobId 作业 ID
 */
export function getCheckpoints(jobId: string): Promise<Checkpoint[]> {
  return get<Checkpoint[]>(`/flink/jobs/${encodeURIComponent(jobId)}/checkpoints`)
}

/**
 * 列出 Savepoint 历史
 * @param jobId 作业 ID
 */
export function getSavepoints(jobId: string): Promise<Savepoint[]> {
  return get<Savepoint[]>(`/flink/jobs/${encodeURIComponent(jobId)}/savepoints`)
}

/**
 * 查询反压指标
 * @param jobId 作业 ID
 */
export function getBackpressure(jobId: string): Promise<BackpressureMetrics> {
  return get<BackpressureMetrics>(`/flink/jobs/${encodeURIComponent(jobId)}/backpressure`)
}

/* ================================================================== */
/* 4. OLAP Doris（SqlGatewayController，engine=doris + 专用端点）       */
/* ================================================================== */

/** Doris 节点角色 */
export type DorisNodeRole = 'FE' | 'BE'

/** Doris 节点状态 */
export type DorisNodeStatus = 'alive' | 'dead' | 'decommission'

/** Doris 节点信息 */
export interface DorisNode {
  /** 主机 */
  host: string
  /** 端口 */
  port: number
  /** 角色 */
  role: DorisNodeRole
  /** 状态 */
  status: DorisNodeStatus | string
  /** CPU 使用率（百分比） */
  cpuUsage: number
  /** 内存使用率（百分比） */
  memUsage: number
  /** 磁盘使用率（百分比） */
  diskUsage?: number
  /** 是否可用 */
  alive?: boolean
}

/** Doris 查询记录 */
export interface DorisQuery {
  /** QueryId */
  queryId: string
  /** SQL 摘要 */
  sqlSummary: string
  /** 用户 */
  user: string
  /** 数据库 */
  database?: string
  /** 时长（毫秒） */
  durationMs: number
  /** 状态 */
  status: 'RUNNING' | 'FINISHED' | 'FAILED' | 'CANCELED' | string
  /** 开始时间 */
  startTime: string
  /** 结束时间 */
  endTime?: string
  /** 扫描行数 */
  scanRows?: number
  /** 返回行数 */
  returnRows?: number
}

/** SQL 执行响应 */
export interface SqlExecuteResponse {
  /** 查询 ID */
  queryId?: string
  /** 列名列表 */
  columns: string[]
  /** 数据行 */
  rows: unknown[][]
  /** 行数 */
  rowCount: number
  /** 耗时（毫秒） */
  durationMs: number
  /** 状态 */
  status: 'SUCCESS' | 'FAILED' | 'DEGRADED' | string
  /** 错误信息 */
  error?: string
  /** 结果是否被截断（行数上限或分页首页） */
  truncated?: boolean
  /** 截断说明 / 拒绝原因 / 降级原因等附加信息 */
  message?: string
}

/** SQL 执行计划响应 */
export interface SqlExplainResponse {
  /** 原始 SQL */
  sql: string
  /** 执行计划文本 */
  plan?: string
  /** 涉及的表 */
  tables?: string[]
  /** 估算行数 */
  estimatedRows?: number
  /** 估算代价 */
  estimatedCost?: number
  /** 耗时（毫秒） */
  durationMs: number
  /** 错误信息 */
  error?: string
}

/**
 * 列出 Doris 节点
 */
export function getDorisNodes(): Promise<DorisNode[]> {
  return get<DorisNode[]>('/doris/nodes')
}

/**
 * 列出 Doris 数据库
 */
export function getDorisDatabases(): Promise<string[]> {
  return get<string[]>('/doris/databases')
}

/**
 * 列出 Doris 表
 * @param db 数据库名
 */
export function getDorisTables(db: string): Promise<string[]> {
  return get<string[]>(`/doris/databases/${encodeURIComponent(db)}/tables`)
}

/**
 * 列出 Doris 查询记录
 */
export function getDorisQueries(): Promise<DorisQuery[]> {
  return get<DorisQuery[]>('/doris/queries')
}

/**
 * 执行 Doris SQL
 * @param sql SQL 文本
 */
export function executeDorisSql(sql: string): Promise<SqlExecuteResponse> {
  return post<SqlExecuteResponse>('/sql/execute', {
    sql,
    engine: 'doris',
    dialect: 'DORIS'
  })
}

/**
 * 生成 Doris SQL 执行计划
 * @param sql SQL 文本
 */
export function explainDorisSql(sql: string): Promise<SqlExplainResponse> {
  return post<SqlExplainResponse>('/sql/explain', {
    sql,
    dialect: 'DORIS'
  })
}

/* ================================================================== */
/* 5. 消息流接入 Kafka（DataSource + 专用 Kafka 端点）                  */
/* ================================================================== */

/** Kafka 集群信息 */
export interface KafkaCluster {
  /** 集群 ID */
  id: string
  /** 集群名称 */
  name: string
  /** Bootstrap servers */
  bootstrapServers: string
  /** Kafka 版本 */
  version?: string
  /** 状态 */
  status: 'connected' | 'disconnected' | string
  /** 创建时间 */
  createdAt?: string
}

/** Kafka Broker 信息 */
export interface Broker {
  /** Broker ID */
  id: number
  /** 主机 */
  host: string
  /** 端口 */
  port: number
  /** Kafka 版本 */
  version?: string
  /** 状态 */
  status: 'alive' | 'dead' | string
  /** 作为分区 Leader 的数量 */
  partitionLeaderCount?: number
}

/** Kafka Topic 信息 */
export interface Topic {
  /** Topic 名称 */
  name: string
  /** 分区数 */
  partitions: number
  /** 副本因子 */
  replicas: number
  /** 总消息数 */
  messageCount: number
  /** 大小（字节） */
  sizeBytes?: number
  /** 最早偏移 */
  earliestOffset?: number
  /** 最新偏移 */
  latestOffset?: number
}

/** Kafka 消费组信息 */
export interface ConsumerGroup {
  /** 组名 */
  groupId: string
  /** 计算引擎 */
  engine?: string
  /** 总 Lag */
  lag: number
  /** 状态 */
  status: 'STABLE' | 'PREPARING_REBALANCE' | 'COMPLETING_REBALANCE' | 'EMPTY' | 'DEAD' | string
  /** 成员数 */
  memberCount?: number
  /** 订阅 Topic 数 */
  topicCount?: number
}

/** Kafka 消息 */
export interface KafkaMessage {
  /** 分区 */
  partition: number
  /** 偏移 */
  offset: number
  /** 时间戳 */
  timestamp: string
  /** Key */
  key?: string
  /** Value */
  value: string
  /** 头部 */
  headers?: Record<string, string>
}

/** Topic 创建请求 */
export interface TopicCreateRequest {
  /** Topic 名称 */
  name: string
  /** 分区数 */
  partitions: number
  /** 副本因子 */
  replicas: number
  /** 配置项 */
  configs?: Record<string, string>
}

/**
 * 列出 Kafka 集群（数据源）
 */
export function getKafkaClusters(): Promise<KafkaCluster[]> {
  return get<KafkaCluster[]>('/datasources', { type: 'kafka' })
}

/**
 * 列出 Broker
 * @param clusterId 集群 ID
 */
export function getKafkaBrokers(clusterId: string): Promise<Broker[]> {
  return get<Broker[]>(`/kafka/${encodeURIComponent(clusterId)}/brokers`)
}

/**
 * 列出 Topic
 * @param clusterId 集群 ID
 */
export function getKafkaTopics(clusterId: string): Promise<Topic[]> {
  return get<Topic[]>(`/kafka/${encodeURIComponent(clusterId)}/topics`)
}

/**
 * 创建 Topic
 * @param clusterId 集群 ID
 * @param req 创建请求
 */
export function createKafkaTopic(clusterId: string, req: TopicCreateRequest): Promise<Topic> {
  return post<Topic>(`/kafka/${encodeURIComponent(clusterId)}/topics`, req)
}

/**
 * 删除 Topic
 * @param clusterId 集群 ID
 * @param name Topic 名称
 */
export function deleteKafkaTopic(clusterId: string, name: string): Promise<void> {
  return del<void>(`/kafka/${encodeURIComponent(clusterId)}/topics/${encodeURIComponent(name)}`)
}

/**
 * 列出消费组
 * @param clusterId 集群 ID
 */
export function getKafkaConsumerGroups(clusterId: string): Promise<ConsumerGroup[]> {
  return get<ConsumerGroup[]>(`/kafka/${encodeURIComponent(clusterId)}/consumer-groups`)
}

/**
 * 消息采样
 * @param clusterId 集群 ID
 * @param topic Topic 名称
 * @param max 最大采样数
 */
export function sampleKafkaMessages(
  clusterId: string,
  topic: string,
  max = 100
): Promise<KafkaMessage[]> {
  return post<KafkaMessage[]>(
    `/kafka/${encodeURIComponent(clusterId)}/topics/${encodeURIComponent(topic)}/sample`,
    { max }
  )
}

/* ================================================================== */
/* 6. 时序引擎 IoTDB（DataSource + 专用 IoTDB 端点 + SqlGateway）       */
/* ================================================================== */

/** IoTDB 实例信息 */
export interface IotdbInstance {
  /** 实例 ID */
  id: string
  /** 实例名称 */
  name: string
  /** 主机 */
  host: string
  /** 端口 */
  port: number
  /** 版本 */
  version?: string
  /** 状态 */
  status: 'connected' | 'disconnected' | string
  /** 创建时间 */
  createdAt?: string
}

/** 时序测点信息 */
export interface Timeseries {
  /** 测点全名 */
  name: string
  /** 所属设备 */
  device: string
  /** 数据类型 */
  dataType: string
  /** 编码 */
  encoding?: string
  /** 压缩 */
  compression?: string
  /** 注释 */
  description?: string
}

/** 写入吞吐点 */
export interface ThroughputPoint {
  /** 时间戳 */
  timestamp: string
  /** 写入点数 */
  points: number
  /** 写入速率（点/秒） */
  rate: number
}

/**
 * 列出 IoTDB 实例（数据源）
 */
export function getIotdbInstances(): Promise<IotdbInstance[]> {
  return get<IotdbInstance[]>('/datasources', { type: 'iotdb' })
}

/**
 * 列出存储组
 * @param id 实例 ID
 */
export function getIotdbStorageGroups(id: string): Promise<string[]> {
  return get<string[]>(`/iotdb/${encodeURIComponent(id)}/storage-groups`)
}

/**
 * 列出设备
 * @param id 实例 ID
 */
export function getIotdbDevices(id: string): Promise<string[]> {
  return get<string[]>(`/iotdb/${encodeURIComponent(id)}/devices`)
}

/**
 * 列出测点
 * @param id 实例 ID
 * @param device 设备名
 */
export function getIotdbTimeseries(id: string, device: string): Promise<Timeseries[]> {
  return get<Timeseries[]>(`/iotdb/${encodeURIComponent(id)}/timeseries`, { device })
}

/**
 * 执行 IoTDB SQL
 * @param id 实例 ID
 * @param sql SQL 文本
 */
export function executeIotdbSql(id: string, sql: string): Promise<SqlExecuteResponse> {
  return post<SqlExecuteResponse>('/sql/execute', {
    sql,
    engine: 'iotdb',
    dialect: 'IOTDB',
    datasourceId: id
  })
}

/**
 * 查询写入吞吐
 * @param id 实例 ID
 */
export function getIotdbWriteThroughput(id: string): Promise<ThroughputPoint[]> {
  return get<ThroughputPoint[]>(`/iotdb/${encodeURIComponent(id)}/write-throughput`)
}

/* ================================================================== */
/* 7. 多模型引擎（VirtualTableController types + 跨模型查询）           */
/* ================================================================== */

/** 模型分组元信息（前端静态定义，配合后端 types 端点） */
export const MODEL_GROUPS = [
  { key: 'relational', label: '关系型', types: ['mysql', 'postgres', 'oracle'] },
  { key: 'document', label: '文档', types: ['mongodb', 'elasticsearch'] },
  { key: 'graph', label: '图', types: ['neo4j', 'nebula'] },
  { key: 'timeseries', label: '时序', types: ['iotdb', 'influxdb', 'tdengine'] },
  { key: 'vector', label: '向量', types: ['milvus', 'pgvector'] },
  { key: 'kv', label: 'KV', types: ['redis', 'hbase'] }
] as const

/** 模型分组键 */
export type ModelGroupKey = (typeof MODEL_GROUPS)[number]['key']

/**
 * 列出多模型支持的数据源类型
 */
export function getMultiModelTypes(): Promise<string[]> {
  return get<string[]>(`${VT_BASE}/types`)
}

/**
 * 按类型列出虚拟表（多模型统一）
 * @param type 数据源类型
 */
export function getMultiModelTables(type: string): Promise<VirtualTableDefinition[]> {
  return get<VirtualTableDefinition[]>(VT_BASE, { dataSourceType: type })
}

/**
 * 跨模型统一查询
 * @param tableName 虚拟表名
 * @param predicate 过滤谓词（可选）
 */
export function crossModelQuery(
  tableName: string,
  predicate?: string
): Promise<VirtualTableQueryResult> {
  return post<VirtualTableQueryResult>(`${VT_BASE}/${encodeURIComponent(tableName)}/query`, {
    predicate
  })
}

/**
 * 测试多模型虚拟表连接
 * @param tableName 虚拟表名
 */
export function testMultiModelConnection(
  tableName: string
): Promise<{ connected: boolean; latencyMs?: number }> {
  return post<{ connected: boolean; latencyMs?: number }>(
    `${VT_BASE}/${encodeURIComponent(tableName)}/test-connection`
  )
}
