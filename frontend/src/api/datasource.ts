/**
 * 数据源管理 API
 *
 * 支持的数据源类型：MySQL / PostgreSQL / ClickHouse / Kafka / Hive 等
 * 提供数据源的增删改查与连接测试能力
 */
import { get, post, put, del } from './client'
import type { PagedResult, PageQuery } from './types'

/** 数据源类型枚举 */
export type DataSourceType =
  | 'mysql'
  | 'postgresql'
  | 'clickhouse'
  | 'kafka'
  | 'hive'
  | 'oracle'
  | 'sqlserver'
  | 'doris'
  | 'trino'

/** 数据源连接状态 */
export type DataSourceStatus = 'connected' | 'disconnected' | 'testing'

/** 数据源信息 */
export interface DataSource {
  /** 数据源 ID */
  id: string
  /** 数据源名称 */
  name: string
  /** 数据源类型 */
  type: DataSourceType
  /** 主机地址 */
  host: string
  /** 端口号 */
  port: number
  /** 数据库名（Kafka 等可选） */
  database?: string
  /** 用户名 */
  username: string
  /** 密码（仅写入时传递，查询时不返回） */
  password?: string
  /** 连接状态 */
  status: DataSourceStatus
  /** 创建时间 */
  createdAt: string
  /** 更新时间 */
  updatedAt?: string
}

/** 创建/更新数据源参数 */
export interface SaveDataSourceParams {
  name: string
  type: DataSourceType
  host: string
  port: number
  database?: string
  username: string
  password?: string
}

/** 数据源列表查询参数 */
export interface DataSourceListQuery extends PageQuery {
  type?: DataSourceType
  status?: DataSourceStatus
}

/** 连接测试结果 */
export interface TestResult {
  /** 是否连接成功 */
  success: boolean
  /** 耗时（毫秒） */
  latency?: number
  /** 提示消息 */
  message: string
}

/** 数据源资源根路径 */
const BASE = '/datasources'

/**
 * 查询数据源列表（分页）
 * @param params 查询参数
 */
export function listDataSources(params?: DataSourceListQuery): Promise<PagedResult<DataSource>> {
  return get<PagedResult<DataSource>>(BASE, params as Record<string, unknown>)
}

/**
 * 获取数据源详情
 * @param id 数据源 ID
 */
export function getDataSource(id: string): Promise<DataSource> {
  return get<DataSource>(`${BASE}/${id}`)
}

/**
 * 创建数据源
 * @param data 数据源信息
 */
export function createDataSource(data: SaveDataSourceParams): Promise<DataSource> {
  return post<DataSource>(BASE, data)
}

/**
 * 更新数据源
 * @param id 数据源 ID
 * @param data 待更新字段
 */
export function updateDataSource(
  id: string,
  data: Partial<SaveDataSourceParams>
): Promise<DataSource> {
  return put<DataSource>(`${BASE}/${id}`, data)
}

/**
 * 删除数据源
 * @param id 数据源 ID
 */
export function deleteDataSource(id: string): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}

/**
 * 测试数据源连接
 * @param id 数据源 ID
 */
export function testDataSource(id: string): Promise<TestResult> {
  return post<TestResult>(`${BASE}/${id}/test`)
}
