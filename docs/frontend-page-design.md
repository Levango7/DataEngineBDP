# 前端占位页面替代方案设计文档

> 任务 #358 交付物 · 为 16 个 Roadmap 占位页面设计真实功能页面替代方案
> 项目：DataEngineBDP · 前端栈：Vue3 + Vite + TypeScript + Element Plus + ECharts
> 编写日期：2026-08-16

## 第1章 设计概要

### 1.1 设计背景

当前 `frontend/src/router/index.ts` 中存在 16 个占位路由，统一指向 `Roadmap.vue` 占位组件，仅展示模块名而无实际功能：

```
{ path: '/infra-machine', component: Roadmap, meta: { title: '机器供应' } }
... 共 16 条
```

本设计为这 16 个占位路由提供真实功能页面替代方案，对接后端已实现的 Controller，形成"前端页面 ↔ 前端 API 模块 ↔ 后端 Controller"完整闭环。

### 1.2 设计原则

#### 1.2.1 分层差异化

| 层级 | 页面特征 | 主交互模式 | 数据更新频率 |
| --- | --- | --- | --- |
| 基础设施层（5 个） | 状态监控 + 资源管理 | 列表 + 详情 + 创建向导 + 扩缩容 | 准实时（30s 轮询） |
| 引擎层（7 个） | 引擎监控 + 作业管理 | KPI 卡片 + 作业表格 + 日志查看 | 准实时（15s 轮询） |
| 治理/开发层（4 个） | 功能操作为主 | 表单 + DAG 编排 + 标签计算 | 按需触发 |

#### 1.2.2 布局一致性

所有 16 个页面统一遵循 `ClusterOverview.vue` 已验证的布局模式，保证用户视觉与操作习惯一致：

```
┌─────────────────────────────────────────────┐
│ H1 页面标题                                  │
│ 副标题/描述（color: #717a80, 13px）          │
├─────────────────────────────────────────────┤
│ KPI 卡片区域（el-row + el-col + el-card）    │
│ 4 个指标卡，三态 loading/error/data          │
├─────────────────────────────────────────────┤
│ 主内容区域（el-card 包裹）                   │
│ 表格 / ECharts 图 / 表单 / DAG 画布          │
├─────────────────────────────────────────────┤
│ 操作区域（按钮 / 弹窗 el-dialog）            │
└─────────────────────────────────────────────┘
```

#### 1.2.3 三态强制

所有数据驱动区域必须实现三态，复用 `useApi` composable：

- `loading`：`v-loading` 指令或骨架卡片，禁止白屏
- `error`：错误卡片 + 重试链接，错误信息可读
- `data`：正常渲染

#### 1.2.4 API 模块化

每个页面在 `frontend/src/api/` 下新建独立模块（或在已有模块中扩展），统一通过 `@/api/client` 的 `get/post/put/del` 四个泛型方法调用，自动享受：

- Bearer token 注入
- `ApiResponse<T>` 拆包
- 401/403/500 统一错误提示
- 30s 超时

### 1.3 后端 Controller 对照总表

| # | 占位路由 | 模块名 | 后端 Controller | 后端基础路径 |
| --- | --- | --- | --- | --- |
| 1 | `/infra-machine` | 机器供应 | XinchangClusterController | `/api/v1/clusters/xinchang` |
| 2 | `/infra-k8s` | K8s 集群 | PrivateClusterController + CloudClusterController + ClusterController | `/api/v1/clusters/{private\|cloud\|env}` |
| 3 | `/infra-net` | 容器网络 | ClusterController（网络配置子资源） | `/api/v1/clusters/{env}/{id}/network` |
| 4 | `/infra-store` | 容器存储 | ClusterController（存储配置子资源） | `/api/v1/clusters/{env}/{id}/storage` |
| 5 | `/infra-sched` | 弹性调度 | ClusterController（扩缩容） | `/api/v1/clusters/{env}/{id}/scale` |
| 6 | `/eng-storage` | 统一存储 | VirtualTableController + MaterializedViewController | `/api/v1/virtual-tables` + `/api/materialized-views` |
| 7 | `/eng-spark` | 批计算（Spark） | JobController（type=batch_spark） | `/api/v1/jobs?type=batch_spark` |
| 8 | `/eng-flink` | 流计算（Flink） | JobController（type=stream_flink）+ StreamBatchSchedulerController | `/api/v1/jobs?type=stream_flink` + `/api/v1/stream-batch` |
| 9 | `/eng-doris` | OLAP（Doris） | SqlGatewayController（engine=doris） | `/api/v1/sql/engines` |
| 10 | `/eng-kafka` | 消息流接入（Kafka） | DataSourceController + JobController | `/api/v1/datasources?type=kafka` |
| 11 | `/eng-iotdb` | 时序引擎（IoTDB） | SqlGatewayController + DataSourceController | `/api/v1/sql/engines` + `/api/v1/datasources?type=iotdb` |
| 12 | `/eng-mmg` | 多模型引擎 | VirtualTableController（types） | `/api/v1/virtual-tables/types` |
| 13 | `/govern-meta` | 元数据管理 | CollectorController | `/api/v1/metadata` |
| 14 | `/dev-sched` | 调度编排 | StreamBatchSchedulerController + JobController | `/api/v1/stream-batch/dags` + `/api/v1/jobs` |
| 15 | `/dev-tag` | 标签画像 | TagController + ProfileController + AudienceController | `/api/v1/tags` + `/api/v1/profiles` + `/api/v1/audiences` |
| 16 | `/dev-ml` | 机器学习 | JobController（type=ml_train）+ LlmopsApi | `/api/v1/jobs?type=ml_train` |

### 1.4 命名约定

- 页面组件：`PascalCase.vue`，位于 `frontend/src/views/<layer>/<PageName>.vue`
- API 模块：`camelCase.ts`，位于 `frontend/src/api/<module>.ts`
- 子组件：`PascalCase.vue`，与页面同目录或 `frontend/src/components/<group>/`
- 路由 name：`PascalCase`，与组件名一致
- 路由 path：`kebab-case`，保持现有占位路径不变

## 第2章 基础设施层页面设计（5 个）

### 2.1 机器供应页（`/infra-machine`）

#### 2.1.1 页面定位

- **页面名称**：机器供应
- **路由路径**：`/infra-machine`
- **所属层级**：基础设施层
- **核心功能**：管理信创集群（创建/销毁/扩缩容/查询），对接信创资源供应 Provider。展示当前租户下全部信创集群的运行状态、节点规格、K8s 版本，支持通过创建向导新建集群。

#### 2.1.2 页面布局

```
┌─────────────────────────────────────────────┐
│ H1 机器供应                                  │
│ 副标题：信创集群供应 · 创建/销毁/扩缩容       │
├─────────────────────────────────────────────┤
│ KPI 卡片（4 个）                             │
│  集群总数 · 运行中 · 创建中 · 异常           │
├─────────────────────────────────────────────┤
│ 操作栏：[+ 新建集群] [刷新] [环境筛选]       │
├─────────────────────────────────────────────┤
│ 集群列表（el-table）                         │
│  集群名 · 状态 · K8s 版本 · 节点数 · 网段    │
│  · 创建时间 · 操作（详情/扩缩容/销毁）       │
├─────────────────────────────────────────────┤
│ 弹窗：新建集群向导（el-dialog + el-steps）   │
│ 弹窗：扩缩容表单                             │
└─────────────────────────────────────────────┘
```

#### 2.1.3 API 对接设计

**后端 API 路径**（XinchangClusterController）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/clusters/xinchang` | 列出租户全部信创集群 |
| GET | `/api/v1/clusters/xinchang/{clusterId}` | 查询集群状态 |
| POST | `/api/v1/clusters/xinchang` | 创建集群 |
| DELETE | `/api/v1/clusters/xinchang/{clusterId}` | 销毁集群 |
| POST | `/api/v1/clusters/xinchang/{clusterId}/scale` | 扩缩容 |

**请求/响应数据结构**：

```typescript
// 创建请求
interface ClusterCreateRequest {
  tenantId?: string
  clusterName: string
  k8sVersion: string         // 如 v1.28
  podCidr: string            // 如 10.244.0.0/16
  serviceCidr: string        // 如 10.96.0.0/12
  workers: WorkerSpec[]      // 工作节点规格
}
interface WorkerSpec {
  role: string               // worker/master
  count: number
  cpu: number                // 核数
  memory: number             // GB
  disk: number               // GB
}

// 集群信息响应
interface ClusterInfo {
  clusterId: string
  clusterName: string
  status: 'CREATING' | 'RUNNING' | 'FAILED' | 'DESTROYED'
  k8sVersion: string
  podCidr: string
  serviceCidr: string
  workerCount: number
  controlPlaneCount: number
  createdAt: string
  updatedAt: string
  errorMessage?: string
}

// 扩缩容请求
interface ClusterScaleRequest {
  targetNodeCount: number
  workerSpec?: WorkerSpec
}
```

**前端 API 模块**：新建 `frontend/src/api/infraXinchang.ts`

```typescript
// frontend/src/api/infraXinchang.ts
import { get, post, del } from './client'

const BASE = '/clusters/xinchang'

export function listClusters(): Promise<ClusterInfo[]> {
  return get<ClusterInfo[]>(BASE)
}
export function getCluster(clusterId: string): Promise<ClusterInfo> {
  return get<ClusterInfo>(`${BASE}/${clusterId}`)
}
export function createCluster(req: ClusterCreateRequest): Promise<ClusterInfo> {
  return post<ClusterInfo>(BASE, req)
}
export function destroyCluster(clusterId: string): Promise<ClusterInfo> {
  return del<ClusterInfo>(`${BASE}/${clusterId}`)
}
export function scaleCluster(clusterId: string, req: ClusterScaleRequest): Promise<ClusterInfo> {
  return post<ClusterInfo>(`${BASE}/${clusterId}/scale`, req)
}
```

#### 2.1.4 组件结构

- **页面组件**：`frontend/src/views/infra/MachineSupply.vue`
- **子组件**：
  - `ClusterCreateDialog.vue`：新建集群向导（el-steps 3 步：基础信息 → 节点规格 → 网络配置）
  - `ClusterScaleDialog.vue`：扩缩容表单
  - `ClusterStatusTag.vue`：集群状态标签（复用，状态 → el-tag type 映射）
- **Element Plus 组件**：`el-card` `el-table` `el-table-column` `el-tag` `el-button` `el-dialog` `el-steps` `el-form` `el-input` `el-input-number` `el-select`

#### 2.1.5 数据流

```typescript
// 列表三态
const {
  data: clusters,
  loading,
  error,
  execute: reload
} = useApi<ClusterInfo[]>(() => infraXinchangApi.listClusters())

// 创建
const createApi = useApi((req: ClusterCreateRequest) => infraXinchangApi.createCluster(req), {
  onSuccess: () => { ElMessage.success('集群创建已提交'); reload() }
})

// 销毁（带二次确认）
async function handleDestroy(row: ClusterInfo) {
  await ElMessageBox.confirm(`确认销毁集群 ${row.clusterName}？`, '危险操作', { type: 'warning' })
  await infraXinchangApi.destroyCluster(row.clusterId)
  ElMessage.success('销毁请求已提交')
  reload()
}

onMounted(reload)
// 30s 轮询刷新
const timer = setInterval(reload, 30000)
onUnmounted(() => clearInterval(timer))
```

### 2.2 K8s 集群页（`/infra-k8s`）

#### 2.2.1 页面定位

- **页面名称**：K8s 集群
- **路由路径**：`/infra-k8s`
- **所属层级**：基础设施层
- **核心功能**：跨环境统一管理 K8s 集群（私有云 vsphere/openstack + 公有云 huawei/ali/tencent + 信创），通过编排层统一入口创建集群，按环境分组展示集群列表与节点详情。

#### 2.2.2 页面布局

```
┌─────────────────────────────────────────────┐
│ H1 K8s 集群                                 │
│ 副标题：跨环境统一集群管理                   │
├─────────────────────────────────────────────┤
│ KPI 卡片（4 个）                             │
│  集群总数 · 运行中 · 异常 · 跨环境分布       │
├─────────────────────────────────────────────┤
│ 操作栏：[+ 新建集群] [刷新]                  │
│ 环境筛选 tabs：全部 · 私有云 · 公有云 · 信创 │
├─────────────────────────────────────────────┤
│ 集群列表（el-table，按环境分组）             │
│  集群名 · 环境 · Provider · 状态 · K8s 版本  │
│  · 节点数 · 创建时间 · 操作                  │
├─────────────────────────────────────────────┤
│ 抽屉：集群详情（el-drawer）                  │
│  基本信息 + 节点列表 + 事件流                │
└─────────────────────────────────────────────┘
```

#### 2.2.3 API 对接设计

**后端 API 路径**（ClusterController 编排层统一入口）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/clusters` | 列出所有集群（跨环境） |
| GET | `/api/v1/clusters/{environment}` | 列出指定环境集群 |
| GET | `/api/v1/clusters/{environment}/{clusterId}` | 查询集群 |
| POST | `/api/v1/clusters` | 创建集群（请求体含 environment） |
| DELETE | `/api/v1/clusters/{environment}/{clusterId}` | 销毁集群 |
| POST | `/api/v1/clusters/{environment}/{clusterId}/scale` | 扩缩容 |
| GET | `/api/v1/clusters/providers` | 列出已注册 Provider |
| GET | `/api/v1/clusters/environments` | 列出支持的环境类型 |
| GET | `/api/v1/clusters/profiles` | 列出环境默认配置 |

**前端 API 模块**：新建 `frontend/src/api/infraCluster.ts`

```typescript
// frontend/src/api/infraCluster.ts
import { get, post, del } from './client'

const BASE = '/clusters'

export function listAllClusters(): Promise<ClusterInfo[]> {
  return get<ClusterInfo[]>(BASE)
}
export function listClustersByEnv(env: string): Promise<ClusterInfo[]> {
  return get<ClusterInfo[]>(`${BASE}/${env}`)
}
export function createCluster(req: ClusterCreateRequest): Promise<SupplyResult> {
  return post<SupplyResult>(BASE, req)
}
export function destroyCluster(env: string, clusterId: string): Promise<ClusterInfo> {
  return del<ClusterInfo>(`${BASE}/${env}/${clusterId}`)
}
export function listProviders(): Promise<{ providers: ProviderDescriptor[]; total: number; enabled: number }> {
  return get(`${BASE}/providers`)
}
export function listEnvironments(): Promise<{ environments: EnvInfo[]; total: number }> {
  return get(`${BASE}/environments`)
}
export function listProfiles(): Promise<Record<string, ProfileDefaults>> {
  return get(`${BASE}/profiles`)
}
```

#### 2.2.4 组件结构

- **页面组件**：`frontend/src/views/infra/K8sCluster.vue`
- **子组件**：
  - `ClusterCreateWizard.vue`：创建向导（el-steps 4 步：选环境 → 基础信息 → 节点规格 → 确认）
  - `ClusterDetailDrawer.vue`：集群详情抽屉
  - `NodeListTable.vue`：节点列表子表
  - `EnvTabs.vue`：环境筛选 tabs
- **Element Plus 组件**：`el-card` `el-table` `el-tabs` `el-tab-pane` `el-drawer` `el-steps` `el-form` `el-descriptions`

#### 2.2.5 数据流

```typescript
// 跨环境集群列表
const { data: clusters, loading, error, execute: reload } =
  useApi<ClusterInfo[]>(() => infraClusterApi.listAllClusters())

// 环境元数据（一次性加载）
const { data: envMeta } = useApi(() => infraClusterApi.listEnvironments(), { immediate: true })

// 按 tabs 筛选
const activeEnv = ref('all')
const filteredClusters = computed(() =>
  activeEnv.value === 'all'
    ? clusters.value ?? []
    : (clusters.value ?? []).filter(c => c.environment === activeEnv.value)
)
```

### 2.3 容器网络页（`/infra-net`）

#### 2.3.1 页面定位

- **页面名称**：容器网络
- **路由路径**：`/infra-net`
- **所属层级**：基础设施层
- **核心功能**：管理 K8s 集群容器网络配置（CNI 插件、Pod CIDR、Service CIDR、NetworkPolicy），展示各集群网络拓扑与流量策略，支持 NetworkPolicy 模板下发。

#### 2.3.2 页面布局

```
┌─────────────────────────────────────────────┐
│ H1 容器网络                                 │
│ 副标题：CNI 配置 · CIDR 规划 · 网络策略      │
├─────────────────────────────────────────────┤
│ KPI 卡片（4 个）                             │
│  集群数 · CNI 类型分布 · NetworkPolicy 数    │
│  · 异常流量策略                              │
├─────────────────────────────────────────────┤
│ 集群选择器（el-select）                      │
├─────────────────────────────────────────────┤
│ 网络配置卡片（el-descriptions）              │
│  CNI 插件 · Pod CIDR · Service CIDR          │
│  · IPFamily · MTU                            │
├─────────────────────────────────────────────┤
│ NetworkPolicy 列表（el-table）               │
│  策略名 · 命名空间 · 类型 · 端口 · 操作      │
├─────────────────────────────────────────────┤
│ 弹窗：下发 NetworkPolicy 模板                │
└─────────────────────────────────────────────┘
```

#### 2.3.3 API 对接设计

**后端 API 路径**（基于 ClusterController 扩展网络子资源，需后端补充）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/clusters/{env}/{id}/network` | 获取集群网络配置 |
| PUT | `/api/v1/clusters/{env}/{id}/network` | 更新网络配置 |
| GET | `/api/v1/clusters/{env}/{id}/network/policies` | 列出 NetworkPolicy |
| POST | `/api/v1/clusters/{env}/{id}/network/policies` | 创建 NetworkPolicy |
| DELETE | `/api/v1/clusters/{env}/{id}/network/policies/{name}` | 删除 NetworkPolicy |

> 注：当前后端 ClusterController 未暴露网络子资源端点，前端 API 模块按上述契约先行定义，后端需补充对应端点（属于本设计的下游依赖）。

**前端 API 模块**：新建 `frontend/src/api/infraNetwork.ts`

```typescript
// frontend/src/api/infraNetwork.ts
import { get, put, post, del } from './client'

export interface NetworkConfig {
  cni: 'calico' | 'flannel' | 'cilium' | 'kube-ovn'
  podCidr: string
  serviceCidr: string
  ipFamily: 'IPv4' | 'IPv6' | 'DualStack'
  mtu: number
}
export interface NetworkPolicy {
  name: string
  namespace: string
  type: 'ingress' | 'egress' | 'both'
  ports: number[]
  selector: string
}

export function getNetworkConfig(env: string, clusterId: string): Promise<NetworkConfig> {
  return get(`/clusters/${env}/${clusterId}/network`)
}
export function updateNetworkConfig(env: string, clusterId: string, cfg: NetworkConfig): Promise<NetworkConfig> {
  return put(`/clusters/${env}/${clusterId}/network`, cfg)
}
export function listNetworkPolicies(env: string, clusterId: string): Promise<NetworkPolicy[]> {
  return get(`/clusters/${env}/${clusterId}/network/policies`)
}
export function createNetworkPolicy(env: string, clusterId: string, policy: NetworkPolicy): Promise<NetworkPolicy> {
  return post(`/clusters/${env}/${clusterId}/network/policies`, policy)
}
export function deleteNetworkPolicy(env: string, clusterId: string, name: string): Promise<void> {
  return del(`/clusters/${env}/${clusterId}/network/policies/${name}`)
}
```

#### 2.3.4 组件结构

- **页面组件**：`frontend/src/views/infra/ContainerNetwork.vue`
- **子组件**：
  - `NetworkConfigCard.vue`：网络配置展示与编辑
  - `NetworkPolicyTable.vue`：策略列表
  - `NetworkPolicyDialog.vue`：策略创建弹窗
- **Element Plus 组件**：`el-card` `el-descriptions` `el-table` `el-select` `el-form` `el-tag` `el-dialog`

#### 2.3.5 数据流

```typescript
const selectedCluster = ref<{ env: string; id: string }>()

const { data: networkConfig, loading, error, execute: loadConfig } =
  useApi<NetworkConfig>(() => infraNetworkApi.getNetworkConfig(
    selectedCluster.value!.env, selectedCluster.value!.id
  ))

const { data: policies, execute: loadPolicies } =
  useApi<NetworkPolicy[]>(() => infraNetworkApi.listNetworkPolicies(
    selectedCluster.value!.env, selectedCluster.value!.id
  ))

watch(selectedCluster, () => { loadConfig(); loadPolicies() })
```

### 2.4 容器存储页（`/infra-store`）

#### 2.4.1 页面定位

- **页面名称**：容器存储
- **路由路径**：`/infra-store`
- **所属层级**：基础设施层
- **核心功能**：管理 K8s 集群容器存储（StorageClass、PV、PVC），展示存储用量与 IOPS，支持动态 Provisioner 配置与快照管理。

#### 2.4.2 页面布局

```
┌─────────────────────────────────────────────┐
│ H1 容器存储                                 │
│ 副标题：StorageClass · PV/PVC · 用量监控     │
├─────────────────────────────────────────────┤
│ KPI 卡片（4 个）                             │
│  StorageClass 数 · PVC 数 · 总容量           │
│  · 已用容量（含使用率）                      │
├─────────────────────────────────────────────┤
│ 集群选择器 + StorageClass tabs               │
├─────────────────────────────────────────────┤
│ 存储用量图（ECharts 环形图）                 │
│  按 StorageClass 分组展示容量分布            │
├─────────────────────────────────────────────┤
│ PVC 列表（el-table）                         │
│  名称 · 命名空间 · StorageClass · 容量       │
│  · 状态 · 绑定 PV · 创建时间 · 操作          │
├─────────────────────────────────────────────┤
│ 弹窗：创建 PVC / 创建快照                    │
└─────────────────────────────────────────────┘
```

#### 2.4.3 API 对接设计

**后端 API 路径**（基于 ClusterController 扩展存储子资源，需后端补充）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/clusters/{env}/{id}/storage/classes` | 列出 StorageClass |
| GET | `/api/v1/clusters/{env}/{id}/storage/pvcs` | 列出 PVC |
| POST | `/api/v1/clusters/{env}/{id}/storage/pvcs` | 创建 PVC |
| DELETE | `/api/v1/clusters/{env}/{id}/storage/pvcs/{name}` | 删除 PVC |
| GET | `/api/v1/clusters/{env}/{id}/storage/usage` | 存储用量统计 |
| POST | `/api/v1/clusters/{env}/{id}/storage/pvcs/{name}/snapshot` | 创建快照 |

**前端 API 模块**：新建 `frontend/src/api/infraStorage.ts`

```typescript
// frontend/src/api/infraStorage.ts
import { get, post, del } from './client'

export interface StorageClass {
  name: string
  provisioner: string
  reclaimPolicy: 'Retain' | 'Delete'
  default: boolean
}
export interface PersistentVolumeClaim {
  name: string
  namespace: string
  storageClassName: string
  capacity: string         // 如 100Gi
  status: 'Bound' | 'Pending' | 'Lost'
  volumeName?: string
  createdAt: string
}
export interface StorageUsage {
  totalCapacityBytes: number
  usedCapacityBytes: number
  byStorageClass: { name: string; capacity: number; used: number }[]
}

export function listStorageClasses(env: string, id: string): Promise<StorageClass[]> {
  return get(`/clusters/${env}/${id}/storage/classes`)
}
export function listPvcs(env: string, id: string): Promise<PersistentVolumeClaim[]> {
  return get(`/clusters/${env}/${id}/storage/pvcs`)
}
export function createPvc(env: string, id: string, pvc: Partial<PersistentVolumeClaim>): Promise<PersistentVolumeClaim> {
  return post(`/clusters/${env}/${id}/storage/pvcs`, pvc)
}
export function deletePvc(env: string, id: string, name: string): Promise<void> {
  return del(`/clusters/${env}/${id}/storage/pvcs/${name}`)
}
export function getStorageUsage(env: string, id: string): Promise<StorageUsage> {
  return get(`/clusters/${env}/${id}/storage/usage`)
}
export function createSnapshot(env: string, id: string, pvcName: string): Promise<{ snapshotName: string }> {
  return post(`/clusters/${env}/${id}/storage/pvcs/${pvcName}/snapshot`)
}
```

#### 2.4.4 组件结构

- **页面组件**：`frontend/src/views/infra/ContainerStorage.vue`
- **子组件**：
  - `StorageUsageChart.vue`：ECharts 环形图
  - `PvcTable.vue`：PVC 列表
  - `PvcCreateDialog.vue`：创建 PVC 弹窗
- **Element Plus 组件**：`el-card` `el-table` `el-tabs` `el-dialog` `el-form` `el-progress`

#### 2.4.5 数据流

```typescript
const { data: usage, loading: usageLoading } = useApi<StorageUsage>(
  () => infraStorageApi.getStorageUsage(selectedCluster.value!.env, selectedCluster.value!.id)
)
const { data: pvcs, loading: pvcsLoading, execute: loadPvcs } = useApi<PersistentVolumeClaim[]>(
  () => infraStorageApi.listPvcs(selectedCluster.value!.env, selectedCluster.value!.id)
)
// ECharts 环形图基于 usage.byStorageClass 渲染
```

### 2.5 弹性调度页（`/infra-sched`）

#### 2.5.1 页面定位

- **页面名称**：弹性调度
- **路由路径**：`/infra-sched`
- **所属层级**：基础设施层
- **核心功能**：管理集群弹性伸缩策略（HPA/VPA/Cluster Autoscaler），展示扩缩容事件历史与触发趋势，支持配置基于 CPU/内存/自定义指标的伸缩规则。

#### 2.5.2 页面布局

```
┌─────────────────────────────────────────────┐
│ H1 弹性调度                                 │
│ 副标题：HPA · Cluster Autoscaler · 扩缩容    │
├─────────────────────────────────────────────┤
│ KPI 卡片（4 个）                             │
│  策略总数 · 今日扩容次数 · 今日缩容次数      │
│  · 平均响应时长                              │
├─────────────────────────────────────────────┤
│ 集群选择器                                   │
├─────────────────────────────────────────────┤
│ HPA 策略列表（el-table）                     │
│  名称 · 命名空间 · 目标 Deployment           │
│  · 最小/最大副本 · 当前副本 · CPU 阈值       │
│  · 状态 · 操作                               │
├─────────────────────────────────────────────┤
│ 扩缩容事件流（el-timeline）                  │
│  时间 · 类型(扩/缩) · 触发指标 · 副本变化    │
│  · 耗时                                      │
├─────────────────────────────────────────────┤
│ 弹窗：创建/编辑 HPA 策略                     │
└─────────────────────────────────────────────┘
```

#### 2.5.3 API 对接设计

**后端 API 路径**（基于 ClusterController 扩缩容端点 + HPA 子资源，需后端补充 HPA 管理）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/clusters/{env}/{id}/scale` | 手动扩缩容（已有） |
| GET | `/api/v1/clusters/{env}/{id}/hpa` | 列出 HPA 策略 |
| POST | `/api/v1/clusters/{env}/{id}/hpa` | 创建 HPA |
| PUT | `/api/v1/clusters/{env}/{id}/hpa/{name}` | 更新 HPA |
| DELETE | `/api/v1/clusters/{env}/{id}/hpa/{name}` | 删除 HPA |
| GET | `/api/v1/clusters/{env}/{id}/scale/events` | 扩缩容事件历史 |

**前端 API 模块**：新建 `frontend/src/api/infraSched.ts`

```typescript
// frontend/src/api/infraSched.ts
import { get, post, put, del } from './client'

export interface HpaPolicy {
  name: string
  namespace: string
  targetDeployment: string
  minReplicas: number
  maxReplicas: number
  currentReplicas: number
  cpuThreshold: number       // 百分比
  memoryThreshold?: number
  customMetrics?: { name: string; target: number }[]
  status: 'active' | 'paused'
}
export interface ScaleEvent {
  timestamp: string
  type: 'scale_up' | 'scale_down'
  trigger: string            // 如 cpu>80%
  fromReplicas: number
  toReplicas: number
  durationMs: number
}

export function listHpa(env: string, id: string): Promise<HpaPolicy[]> {
  return get(`/clusters/${env}/${id}/hpa`)
}
export function createHpa(env: string, id: string, hpa: HpaPolicy): Promise<HpaPolicy> {
  return post(`/clusters/${env}/${id}/hpa`, hpa)
}
export function updateHpa(env: string, id: string, name: string, hpa: HpaPolicy): Promise<HpaPolicy> {
  return put(`/clusters/${env}/${id}/hpa/${name}`, hpa)
}
export function deleteHpa(env: string, id: string, name: string): Promise<void> {
  return del(`/clusters/${env}/${id}/hpa/${name}`)
}
export function listScaleEvents(env: string, id: string): Promise<ScaleEvent[]> {
  return get(`/clusters/${env}/${id}/scale/events`)
}
```

#### 2.5.4 组件结构

- **页面组件**：`frontend/src/views/infra/ElasticScheduling.vue`
- **子组件**：
  - `HpaTable.vue`：HPA 策略列表
  - `HpaEditDialog.vue`：HPA 创建/编辑弹窗
  - `ScaleEventTimeline.vue`：扩缩容事件时间线
- **Element Plus 组件**：`el-card` `el-table` `el-timeline` `el-timeline-item` `el-dialog` `el-form` `el-input-number` `el-slider`

#### 2.5.5 数据流

```typescript
const { data: hpaList, loading, execute: loadHpa } = useApi<HpaPolicy[]>(
  () => infraSchedApi.listHpa(selectedCluster.value!.env, selectedCluster.value!.id)
)
const { data: events } = useApi<ScaleEvent[]>(
  () => infraSchedApi.listScaleEvents(selectedCluster.value!.env, selectedCluster.value!.id)
)
```

## 第3章 引擎层页面设计（7 个）

### 3.1 统一存储页（`/eng-storage`）

#### 3.1.1 页面定位

- **页面名称**：统一存储
- **路由路径**：`/eng-storage`
- **所属层级**：引擎层
- **核心功能**：管理湖仓集一体的虚拟表与物化视图，对接跨源归并引擎。展示虚拟表清单、物化视图刷新状态、缓存命中率，支持虚拟表注册/查询/连接测试与物化视图手动刷新。

#### 3.1.2 页面布局

```
┌─────────────────────────────────────────────┐
│ H1 统一存储                                 │
│ 副标题：虚拟表 · 物化视图 · 跨源归并         │
├─────────────────────────────────────────────┤
│ KPI 卡片（4 个）                             │
│  虚拟表数 · 物化视图数 · 缓存命中率          │
│  · 今日刷新次数                              │
├─────────────────────────────────────────────┤
│ Tabs：虚拟表 · 物化视图 · 缓存统计           │
├─────────────────────────────────────────────┤
│ Tab1 虚拟表列表（el-table）                 │
│  表名 · 数据源类型 · schema · 状态           │
│  · 最近查询 · 操作(查询/测试/刷新/删除)      │
│ Tab2 物化视图列表（el-table）                │
│  视图名 · 源表 · 刷新策略 · 最近刷新         │
│  · 状态 · 操作(刷新/查看状态)                │
│ Tab3 缓存统计（el-descriptions + ECharts）   │
├─────────────────────────────────────────────┤
│ 弹窗：注册虚拟表 / 虚拟表查询结果            │
└─────────────────────────────────────────────┘
```

#### 3.1.3 API 对接设计

**后端 API 路径**：

| 方法 | 路径 | Controller | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/virtual-tables` | VirtualTableController | 列出虚拟表 |
| POST | `/api/v1/virtual-tables` | VirtualTableController | 注册虚拟表 |
| GET | `/api/v1/virtual-tables/{name}/schema` | VirtualTableController | 获取 schema |
| POST | `/api/v1/virtual-tables/{name}/query` | VirtualTableController | 查询数据 |
| POST | `/api/v1/virtual-tables/{name}/test-connection` | VirtualTableController | 测试连接 |
| POST | `/api/v1/virtual-tables/{name}/refresh` | VirtualTableController | 刷新物化表 |
| GET | `/api/v1/virtual-tables/cache/stats` | VirtualTableController | 缓存统计 |
| GET | `/api/v1/virtual-tables/types` | VirtualTableController | 数据源类型 |
| GET | `/api/materialized-views` | MaterializedViewController | 列出物化视图 |
| POST | `/api/materialized-views/{name}/refresh` | MaterializedViewController | 手动刷新 |
| GET | `/api/materialized-views/{name}/status` | MaterializedViewController | 刷新状态 |
| GET | `/api/materialized-views/status` | MaterializedViewController | 全局状态 |

**前端 API 模块**：新建 `frontend/src/api/engStorage.ts`（封装上述两端 Controller）

```typescript
// frontend/src/api/engStorage.ts
import { get, post, put, del } from './client'

// 虚拟表
export function listVirtualTables(type?: string): Promise<VirtualTableDefinition[]> {
  return get('/virtual-tables', type ? { dataSourceType: type } : undefined)
}
export function registerVirtualTable(def: VirtualTableDefinition): Promise<VirtualTableDefinition> {
  return post('/virtual-tables', def)
}
export function getVirtualTableSchema(name: string): Promise<ColumnDefinition[]> {
  return get(`/virtual-tables/${name}/schema`)
}
export function queryVirtualTable(name: string, predicate?: string, limit?: number): Promise<QueryResult> {
  return post(`/virtual-tables/${name}/query`, { predicate, limit })
}
export function testVirtualTableConnection(name: string): Promise<{ connected: boolean }> {
  return post(`/virtual-tables/${name}/test-connection`)
}
export function refreshVirtualTable(name: string): Promise<{ refreshed: boolean; rows: number }> {
  return post(`/virtual-tables/${name}/refresh`)
}
export function getCacheStats(): Promise<Record<string, unknown>> {
  return get('/virtual-tables/cache/stats')
}
export function listDataSourceTypes(): Promise<string[]> {
  return get('/virtual-tables/types')
}

// 物化视图（注意：后端路径为 /api/materialized-views，无 v1 前缀）
export function listMaterializedViews(): Promise<MaterializedViewDef[]> {
  return get('/materialized-views', undefined, { baseURL: '/api' })
}
export function refreshMaterializedView(name: string): Promise<{ eventId: string }> {
  return post(`/materialized-views/${name}/refresh`, undefined, { baseURL: '/api' })
}
export function getMaterializedViewStatus(name: string): Promise<Record<string, unknown>> {
  return get(`/materialized-views/${name}/status`, undefined, { baseURL: '/api' })
}
```

#### 3.1.4 组件结构

- **页面组件**：`frontend/src/views/engine/UnifiedStorage.vue`
- **子组件**：
  - `VirtualTableTable.vue`：虚拟表列表
  - `MaterializedViewTable.vue`：物化视图列表
  - `CacheStatsPanel.vue`：缓存统计面板
  - `VirtualTableRegisterDialog.vue`：注册弹窗
  - `VirtualTableQueryDialog.vue`：查询结果弹窗（含 el-table 动态列）
- **Element Plus 组件**：`el-card` `el-tabs` `el-table` `el-dialog` `el-form` `el-tag` `el-descriptions`

#### 3.1.5 数据流

```typescript
const { data: virtualTables, loading: vtLoading, execute: loadVt } =
  useApi<VirtualTableDefinition[]>(() => engStorageApi.listVirtualTables())
const { data: matViews, loading: mvLoading, execute: loadMv } =
  useApi<MaterializedViewDef[]>(() => engStorageApi.listMaterializedViews())
const { data: cacheStats } = useApi(() => engStorageApi.getCacheStats())

// 测试连接
async function handleTestConnection(row: VirtualTableDefinition) {
  const { connected } = await engStorageApi.testVirtualTableConnection(row.tableName)
  ElMessage[connected ? 'success' : 'error'](connected ? '连接成功' : '连接失败')
}
```

### 3.2 批计算 Spark 页（`/eng-spark`）

#### 3.2.1 页面定位

- **页面名称**：批计算（Spark）
- **路由路径**：`/eng-spark`
- **所属层级**：引擎层
- **核心功能**：监控 Spark 引擎运行状态与批作业列表，展示作业执行历史、资源占用、Stage 进度，支持作业提交/停止/查看日志。

#### 3.2.2 页面布局

```
┌─────────────────────────────────────────────┐
│ H1 批计算（Spark）                          │
│ 副标题：Spark 引擎监控 · 批作业管理          │
├─────────────────────────────────────────────┤
│ KPI 卡片（4 个）                             │
│  运行中作业 · 今日完成 · 今日失败            │
│  · 平均执行时长                              │
├─────────────────────────────────────────────┤
│ 操作栏：[+ 提交作业] [刷新]                  │
├─────────────────────────────────────────────┤
│ 作业列表（el-table）                         │
│  作业名 · 状态 · 提交时间 · 运行时长         │
│  · Driver 资源 · Stage 进度 · 操作           │
├─────────────────────────────────────────────┤
│ 抽屉：作业详情                               │
│  Stage 概览图 + 任务列表 + 日志查看          │
└─────────────────────────────────────────────┘
```

#### 3.2.3 API 对接设计

**后端 API 路径**（JobController，按 type=batch_spark 过滤）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/jobs?type=batch_spark` | 列出 Spark 批作业 |
| GET | `/api/v1/jobs/{id}` | 作业详情 |
| POST | `/api/v1/jobs` | 创建作业（type=batch_spark） |
| PUT | `/api/v1/jobs/{id}` | 更新作业 |
| DELETE | `/api/v1/jobs/{id}` | 删除作业 |
| POST | `/api/v1/jobs/{id}/run` | 运行作业（转 DAG 提交） |

**前端 API 模块**：新建 `frontend/src/api/engSpark.ts`（封装 JobController，固定 type）

```typescript
// frontend/src/api/engSpark.ts
import { get, post, put, del } from './client'

const TYPE = 'batch_spark'

export function listSparkJobs(workspaceId?: string, page = 1, size = 20): Promise<PagedResult<SparkJob>> {
  return get('/jobs', { workspaceId, type: TYPE, page, size })
}
export function getSparkJob(id: string): Promise<SparkJob> {
  return get(`/jobs/${id}`)
}
export function createSparkJob(req: SparkJobCreateRequest): Promise<SparkJob> {
  return post('/jobs', { ...req, type: TYPE })
}
export function runSparkJob(id: string): Promise<{ dagId: string; status: string }> {
  return post(`/jobs/${id}/run`)
}
export function deleteSparkJob(id: string): Promise<void> {
  return del(`/jobs/${id}`)
}

export interface SparkJob {
  id: string
  name: string
  workspaceId: string
  type: 'batch_spark'
  config: string         // JSON：mainClass, args, jars, ...
  schedule?: string
  owner: string
  status: string
  lastRunStatus?: string
  createdAt: string
  updatedAt: string
}
```

#### 3.2.4 组件结构

- **页面组件**：`frontend/src/views/engine/SparkEngine.vue`
- **子组件**：
  - `SparkJobTable.vue`：作业列表
  - `SparkJobSubmitDialog.vue`：提交作业弹窗（含 mainClass/jars/args 配置）
  - `SparkJobDetailDrawer.vue`：作业详情抽屉
  - `StageProgressChart.vue`：Stage 进度图（ECharts 横向条形图）
- **Element Plus 组件**：`el-card` `el-table` `el-drawer` `el-dialog` `el-form` `el-input` `el-upload` `el-progress`

#### 3.2.5 数据流

```typescript
const { data: jobs, loading, error, execute: reload } = useApi<PagedResult<SparkJob>>(
  () => engSparkApi.listSparkJobs()
)
// 15s 轮询
const timer = setInterval(reload, 15000)
onUnmounted(() => clearInterval(timer))
```

### 3.3 流计算 Flink 页（`/eng-flink`）

#### 3.3.1 页面定位

- **页面名称**：流计算（Flink）
- **路由路径**：`/eng-flink`
- **所属层级**：引擎层
- **核心功能**：监控 Flink 流作业运行状态，展示 Checkpoint/Savepoint 情况、反压指标、Source/Sink 吞吐，支持作业启停与 Savepoint 触发。

#### 3.3.2 页面布局

```
┌─────────────────────────────────────────────┐
│ H1 流计算（Flink）                          │
│ 副标题：Flink 流作业 · Checkpoint · 反压    │
├─────────────────────────────────────────────┤
│ KPI 卡片（4 个）                             │
│  运行中作业 · 今日失败 · 平均延迟            │
│  · Checkpoint 成功率                         │
├─────────────────────────────────────────────┤
│ 作业列表（el-table）                         │
│  作业名 · 状态 · 并行度 · 运行时长           │
│  · Checkpoint · 反压 · 操作(停止/Savepoint)  │
├─────────────────────────────────────────────┤
│ 抽屉：作业监控详情                           │
│  顶栏：吞吐/延迟实时图                       │
│  中部：Checkpoint 历史表                     │
│  底部：算子拓扑图                            │
└─────────────────────────────────────────────┘
```

#### 3.3.3 API 对接设计

**后端 API 路径**：

| 方法 | 路径 | Controller | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/jobs?type=stream_flink` | JobController | 列出 Flink 流作业 |
| POST | `/api/v1/jobs` | JobController | 创建流作业 |
| POST | `/api/v1/jobs/{id}/run` | JobController | 运行作业 |
| GET | `/api/v1/stream-batch/dags/{dagId}/runs` | StreamBatchSchedulerController | 运行历史 |
| POST | `/api/v1/stream-batch/dags/{dagId}/runs/{runId}/rerun` | StreamBatchSchedulerController | 失败重跑 |

**前端 API 模块**：新建 `frontend/src/api/engFlink.ts`

```typescript
// frontend/src/api/engFlink.ts
import { get, post, del } from './client'

const TYPE = 'stream_flink'

export function listFlinkJobs(): Promise<PagedResult<FlinkJob>> {
  return get('/jobs', { type: TYPE })
}
export function createFlinkJob(req: FlinkJobCreateRequest): Promise<FlinkJob> {
  return post('/jobs', { ...req, type: TYPE })
}
export function runFlinkJob(id: string): Promise<{ dagId: string }> {
  return post(`/jobs/${id}/run`)
}
export function stopFlinkJob(id: string): Promise<void> {
  return post(`/jobs/${id}/stop`)
}
export function triggerSavepoint(id: string): Promise<{ savepointPath: string }> {
  return post(`/jobs/${id}/savepoint`)
}
export function listCheckpoints(jobId: string): Promise<Checkpoint[]> {
  return get(`/flink/jobs/${jobId}/checkpoints`)
}
export function getBackpressure(jobId: string): Promise<BackpressureMetrics> {
  return get(`/flink/jobs/${jobId}/backpressure`)
}

export interface FlinkJob {
  id: string
  name: string
  status: 'RUNNING' | 'FAILED' | 'CANCELED' | 'FINISHED' | 'RESTARTING'
  parallelism: number
  startTime?: string
  durationMs?: number
  checkpointCount: number
  backpressureLevel: 'ok' | 'low' | 'high'
}
```

#### 3.3.4 组件结构

- **页面组件**：`frontend/src/views/engine/FlinkEngine.vue`
- **子组件**：
  - `FlinkJobTable.vue`：作业列表
  - `FlinkJobMonitorDrawer.vue`：监控详情抽屉
  - `ThroughputChart.vue`：吞吐/延迟实时图（ECharts line）
  - `CheckpointTable.vue`：Checkpoint 历史
  - `OperatorTopology.vue`：算子拓扑图（ECharts graph）
- **Element Plus 组件**：`el-card` `el-table` `el-drawer` `el-tag` `el-button` `el-dialog`

#### 3.3.5 数据流

```typescript
const { data: jobs, loading, execute: reload } = useApi<PagedResult<FlinkJob>>(
  () => engFlinkApi.listFlinkJobs()
)
// 选中作业后加载监控详情
const selectedJobId = ref<string>()
const { data: checkpoints } = useApi<Checkpoint[]>(
  () => engFlinkApi.listCheckpoints(selectedJobId.value!),
  { immediate: false }
)
watch(selectedJobId, (id) => { if (id) checkpoints.execute() })
```

### 3.4 OLAP Doris 页（`/eng-doris`）

#### 3.4.1 页面定位

- **页面名称**：OLAP（Doris）
- **路由路径**：`/eng-doris`
- **所属层级**：引擎层
- **核心功能**：管理 Doris OLAP 引擎，展示数据库/表/物化视图目录、查询负载、BE/FE 节点状态，支持 SQL 执行与查询计划查看。

#### 3.4.2 页面布局

```
┌─────────────────────────────────────────────┐
│ H1 OLAP（Doris）                            │
│ 副标题：MPP 引擎 · FE/BE 节点 · 查询负载    │
├─────────────────────────────────────────────┤
│ KPI 卡片（4 个）                             │
│  FE 节点 · BE 节点 · 今日查询数              │
│  · 平均查询时长                              │
├─────────────────────────────────────────────┤
│ 左右分栏：                                   │
│  左：数据库/表目录树（el-tree）              │
│  右：Tab [节点状态 | 查询列表 | SQL 工作台]  │
├─────────────────────────────────────────────┤
│ Tab1 节点状态（el-table）                    │
│  节点 · 角色(FE/BE) · IP · 状态 · 负载       │
│ Tab2 查询列表（el-table）                    │
│  QueryId · SQL 摘要 · 用户 · 时长 · 状态     │
│ Tab3 SQL 工作台（编辑器 + 结果表）           │
└─────────────────────────────────────────────┘
```

#### 3.4.3 API 对接设计

**后端 API 路径**（SqlGatewayController，engine=doris）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/sql/engines` | 列出可用引擎（含 doris） |
| POST | `/api/v1/sql/execute` | 执行 SQL（指定 engine=doris） |
| POST | `/api/v1/sql/explain` | 生成执行计划 |
| POST | `/api/v1/sql/parse` | 解析 SQL |
| POST | `/api/v1/sql/validate` | 校验 SQL |
| GET | `/api/v1/sql/routes` | 路由规则 |

**前端 API 模块**：新建 `frontend/src/api/engDoris.ts`

```typescript
// frontend/src/api/engDoris.ts
import { get, post } from './client'

export function listDorisNodes(): Promise<DorisNode[]> {
  return get('/doris/nodes')
}
export function listDorisDatabases(): Promise<string[]> {
  return get('/doris/databases')
}
export function listDorisTables(db: string): Promise<string[]> {
  return get(`/doris/databases/${db}/tables`)
}
export function listDorisQueries(): Promise<DorisQuery[]> {
  return get('/doris/queries')
}
export function executeDorisSql(sql: string): Promise<SqlExecuteResponse> {
  return post('/sql/execute', { sql, engine: 'doris', dialect: 'DORIS' })
}
export function explainDorisSql(sql: string): Promise<SqlOptimizeResponse> {
  return post('/sql/explain', { sql, dialect: 'DORIS' })
}

export interface DorisNode {
  host: string
  port: number
  role: 'FE' | 'BE'
  status: 'alive' | 'dead'
  cpuUsage: number
  memUsage: number
}
```

#### 3.4.4 组件结构

- **页面组件**：`frontend/src/views/engine/DorisEngine.vue`
- **子组件**：
  - `DorisCatalogTree.vue`：数据库/表目录树
  - `DorisNodeTable.vue`：节点状态表
  - `DorisQueryTable.vue`：查询列表
  - `SqlWorkbenchPanel.vue`：SQL 工作台（复用已有 SqlWorkbench 模式）
- **Element Plus 组件**：`el-card` `el-tree` `el-tabs` `el-table` `el-input` `el-button` `el-tag`

#### 3.4.5 数据流

```typescript
const { data: nodes, loading: nodesLoading } = useApi<DorisNode[]>(
  () => engDorisApi.listDorisNodes()
)
const { data: databases } = useApi<string[]>(() => engDorisApi.listDorisDatabases())
// SQL 执行
const { data: result, loading: executing, error: execError, execute: runSql } =
  useApi<SqlExecuteResponse>((sql: string) => engDorisApi.executeDorisSql(sql))
```

### 3.5 消息流接入 Kafka 页（`/eng-kafka`）

#### 3.5.1 页面定位

- **页面名称**：消息流接入（Kafka）
- **路由路径**：`/eng-kafka`
- **所属层级**：引擎层
- **核心功能**：管理 Kafka 集群与 Topic，展示 Broker 状态、Topic 分区/副本分布、消费组 Lag，支持 Topic 创建/删除与消息采样。

#### 3.5.2 页面布局

```
┌─────────────────────────────────────────────┐
│ H1 消息流接入（Kafka）                      │
│ 副标题：Broker · Topic · 消费组 · Lag 监控   │
├─────────────────────────────────────────────┤
│ KPI 卡片（4 个）                             │
│  Broker 数 · Topic 数 · 消费组数             │
│  · 总 Lag                                    │
├─────────────────────────────────────────────┤
│ Tabs：[Broker] [Topic] [消费组]              │
├─────────────────────────────────────────────┤
│ Tab1 Broker 列表（el-table）                 │
│  ID · Host · Port · 版本 · 状态 · 分区Leader │
│ Tab2 Topic 列表（el-table）                  │
│  名称 · 分区数 · 副本因子 · 总消息数         │
│  · 操作(采样/查看分布/删除)                  │
│ Tab3 消费组列表（el-table）                  │
│  组名 · 订算引擎 · Lag · 状态 · 操作         │
├─────────────────────────────────────────────┤
│ 弹窗：创建 Topic / 消息采样                  │
└─────────────────────────────────────────────┘
```

#### 3.5.3 API 对接设计

**后端 API 路径**（基于 DataSourceController + 专用 Kafka 端点，需后端补充）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/datasources?type=kafka` | 列出 Kafka 数据源 |
| GET | `/api/v1/kafka/{clusterId}/brokers` | Broker 列表 |
| GET | `/api/v1/kafka/{clusterId}/topics` | Topic 列表 |
| POST | `/api/v1/kafka/{clusterId}/topics` | 创建 Topic |
| DELETE | `/api/v1/kafka/{clusterId}/topics/{name}` | 删除 Topic |
| GET | `/api/v1/kafka/{clusterId}/consumer-groups` | 消费组列表 |
| POST | `/api/v1/kafka/{clusterId}/topics/{name}/sample` | 消息采样 |

**前端 API 模块**：新建 `frontend/src/api/engKafka.ts`

```typescript
// frontend/src/api/engKafka.ts
import { get, post, del } from './client'

export function listKafkaClusters(): Promise<KafkaCluster[]> {
  return get('/datasources', { type: 'kafka' })
}
export function listBrokers(clusterId: string): Promise<Broker[]> {
  return get(`/kafka/${clusterId}/brokers`)
}
export function listTopics(clusterId: string): Promise<Topic[]> {
  return get(`/kafka/${clusterId}/topics`)
}
export function createTopic(clusterId: string, req: { name: string; partitions: number; replicas: number }): Promise<Topic> {
  return post(`/kafka/${clusterId}/topics`, req)
}
export function deleteTopic(clusterId: string, name: string): Promise<void> {
  return del(`/kafka/${clusterId}/topics/${name}`)
}
export function listConsumerGroups(clusterId: string): Promise<ConsumerGroup[]> {
  return get(`/kafka/${clusterId}/consumer-groups`)
}
export function sampleMessages(clusterId: string, topic: string, max = 100): Promise<Message[]> {
  return post(`/kafka/${clusterId}/topics/${topic}/sample`, { max })
}
```

#### 3.5.4 组件结构

- **页面组件**：`frontend/src/views/engine/KafkaEngine.vue`
- **子组件**：
  - `BrokerTable.vue`：Broker 列表
  - `TopicTable.vue`：Topic 列表
  - `ConsumerGroupTable.vue`：消费组列表
  - `TopicCreateDialog.vue`：创建 Topic 弹窗
  - `MessageSampleDialog.vue`：消息采样弹窗
- **Element Plus 组件**：`el-card` `el-tabs` `el-table` `el-dialog` `el-form` `el-input-number`

#### 3.5.5 数据流

```typescript
const { data: clusters } = useApi<KafkaCluster[]>(() => engKafkaApi.listKafkaClusters())
const selectedCluster = ref<string>()
const { data: brokers, execute: loadBrokers } = useApi<Broker[]>(
  () => engKafkaApi.listBrokers(selectedCluster.value!), { immediate: false }
)
const { data: topics, execute: loadTopics } = useApi<Topic[]>(
  () => engKafkaApi.listTopics(selectedCluster.value!), { immediate: false }
)
const { data: groups, execute: loadGroups } = useApi<ConsumerGroup[]>(
  () => engKafkaApi.listConsumerGroups(selectedCluster.value!), { immediate: false }
)
watch(selectedCluster, () => { loadBrokers(); loadTopics(); loadGroups() })
```

### 3.6 时序引擎 IoTDB 页（`/eng-iotdb`）

#### 3.6.1 页面定位

- **页面名称**：时序引擎（IoTDB）
- **路由路径**：`/eng-iotdb`
- **所属层级**：引擎层
- **核心功能**：管理 IoTDB 时序引擎，展示存储组/设备/测点元数据、写入吞吐、查询延迟，支持 IoTDB SQL 查询与时间序列预览。

#### 3.6.2 页面布局

```
┌─────────────────────────────────────────────┐
│ H1 时序引擎（IoTDB）                        │
│ 副标题：存储组 · 设备 · 测点 · 时序查询      │
├─────────────────────────────────────────────┤
│ KPI 卡片（4 个）                             │
│  存储组数 · 设备数 · 测点数 · 写入点/秒      │
├─────────────────────────────────────────────┤
│ 左右分栏：                                   │
│  左：存储组/设备/测点树（el-tree）            │
│  右：Tab [写入监控 | 时序预览 | SQL 工作台]   │
├─────────────────────────────────────────────┤
│ Tab1 写入监控（ECharts line 实时图）         │
│ Tab2 时序预览（ECharts line + 表格）         │
│ Tab3 SQL 工作台                              │
└─────────────────────────────────────────────┘
```

#### 3.6.3 API 对接设计

**后端 API 路径**（基于 SqlGatewayController + DataSourceController + 专用 IoTDB 端点）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/datasources?type=iotdb` | 列出 IoTDB 实例 |
| GET | `/api/v1/iotdb/{id}/storage-groups` | 存储组 |
| GET | `/api/v1/iotdb/{id}/devices` | 设备列表 |
| GET | `/api/v1/iotdb/{id}/timeseries?device=` | 测点列表 |
| POST | `/api/v1/sql/execute` | 执行 IoTDB SQL（dialect=IOTDB） |
| GET | `/api/v1/iotdb/{id}/write-throughput` | 写入吞吐 |

**前端 API 模块**：新建 `frontend/src/api/engIotdb.ts`

```typescript
// frontend/src/api/engIotdb.ts
import { get, post } from './client'

export function listIotdbInstances(): Promise<IotdbInstance[]> {
  return get('/datasources', { type: 'iotdb' })
}
export function listStorageGroups(id: string): Promise<string[]> {
  return get(`/iotdb/${id}/storage-groups`)
}
export function listDevices(id: string): Promise<string[]> {
  return get(`/iotdb/${id}/devices`)
}
export function listTimeseries(id: string, device: string): Promise<Timeseries[]> {
  return get(`/iotdb/${id}/timeseries`, { device })
}
export function executeIotdbSql(id: string, sql: string): Promise<SqlExecuteResponse> {
  return post('/sql/execute', { sql, engine: 'iotdb', dialect: 'IOTDB', datasourceId: id })
}
export function getWriteThroughput(id: string): Promise<ThroughputPoint[]> {
  return get(`/iotdb/${id}/write-throughput`)
}
```

#### 3.6.4 组件结构

- **页面组件**：`frontend/src/views/engine/IotdbEngine.vue`
- **子组件**：
  - `IotdbCatalogTree.vue`：存储组/设备/测点树
  - `WriteThroughputChart.vue`：写入吞吐图
  - `TimeseriesPreviewChart.vue`：时序预览图
  - `IotdbSqlPanel.vue`：SQL 工作台
- **Element Plus 组件**：`el-card` `el-tree` `el-tabs` `el-table` `el-input` `el-date-picker`

#### 3.6.5 数据流

```typescript
const { data: instances } = useApi<IotdbInstance[]>(() => engIotdbApi.listIotdbInstances())
const selectedInstance = ref<string>()
const { data: storageGroups } = useApi<string[]>(
  () => engIotdbApi.listStorageGroups(selectedInstance.value!), { immediate: false }
)
const { data: throughput } = useApi<ThroughputPoint[]>(
  () => engIotdbApi.getWriteThroughput(selectedInstance.value!), { immediate: false }
)
```

### 3.7 多模型引擎页（`/eng-mmg`）

#### 3.7.1 页面定位

- **页面名称**：多模型引擎
- **路由路径**：`/eng-mmg`
- **所属层级**：引擎层
- **核心功能**：统一管理多模型数据源（关系型/文档/图/时序/向量），通过虚拟表适配层屏蔽底层差异，展示各模型引擎状态与统一查询能力。

#### 3.7.2 页面布局

```
┌─────────────────────────────────────────────┐
│ H1 多模型引擎                               │
│ 副标题：统一适配 · 跨模型查询 · 虚拟表        │
├─────────────────────────────────────────────┤
│ KPI 卡片（4 个）                             │
│  支持模型数 · 已接入数据源 · 虚拟表数        │
│  · 今日跨模型查询数                          │
├─────────────────────────────────────────────┤
│ 模型类型卡片网格（el-row + el-col）          │
│  关系型 · 文档 · 图 · 时序 · 向量 · KV       │
│  每张卡片：模型名 + 接入数 + 健康状态         │
├─────────────────────────────────────────────┤
│ 虚拟表列表（el-table）                       │
│  表名 · 数据源类型 · 模型 · 行数 · 操作      │
├─────────────────────────────────────────────┤
│ 弹窗：跨模型统一查询                         │
└─────────────────────────────────────────────┘
```

#### 3.7.3 API 对接设计

**后端 API 路径**（VirtualTableController）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/virtual-tables/types` | 支持的数据源类型 |
| GET | `/api/v1/virtual-tables` | 虚拟表列表（含模型分类） |
| POST | `/api/v1/virtual-tables/{name}/query` | 跨模型查询 |
| POST | `/api/v1/virtual-tables/{name}/test-connection` | 测试连接 |
| GET | `/api/v1/virtual-tables/cache/stats` | 缓存统计 |

**前端 API 模块**：新建 `frontend/src/api/engMmg.ts`（复用 engStorage 部分接口）

```typescript
// frontend/src/api/engMmg.ts
import { get, post } from './client'

export function listSupportedTypes(): Promise<string[]> {
  return get('/virtual-tables/types')
}
export function listVirtualTablesByType(type: string): Promise<VirtualTableDefinition[]> {
  return get('/virtual-tables', { dataSourceType: type })
}
export function crossModelQuery(tableName: string, predicate?: string): Promise<QueryResult> {
  return post(`/virtual-tables/${tableName}/query`, { predicate })
}
export function testConnection(tableName: string): Promise<{ connected: boolean }> {
  return post(`/virtual-tables/${tableName}/test-connection`)
}

// 模型分类元信息（前端静态定义，配合后端 types 端点）
export const MODEL_GROUPS = [
  { key: 'relational', label: '关系型', types: ['mysql', 'postgres', 'oracle'] },
  { key: 'document', label: '文档', types: ['mongodb', 'elasticsearch'] },
  { key: 'graph', label: '图', types: ['neo4j', 'nebula'] },
  { key: 'timeseries', label: '时序', types: ['iotdb', 'influxdb', 'tdengine'] },
  { key: 'vector', label: '向量', types: ['milvus', 'pgvector'] },
  { key: 'kv', label: 'KV', types: ['redis', 'hbase'] }
] as const
```

#### 3.7.4 组件结构

- **页面组件**：`frontend/src/views/engine/MultiModelEngine.vue`
- **子组件**：
  - `ModelTypeCard.vue`：模型类型卡片
  - `VirtualTableByModelTable.vue`：按模型分组的虚拟表
  - `CrossModelQueryDialog.vue`：跨模型查询弹窗
- **Element Plus 组件**：`el-card` `el-row` `el-col` `el-table` `el-dialog` `el-tag`

#### 3.7.5 数据流

```typescript
const { data: types } = useApi<string[]>(() => engMmgApi.listSupportedTypes())
const { data: virtualTables, loading, execute: reload } = useApi<VirtualTableDefinition[]>(
  () => engMmgApi.listVirtualTablesByType(selectedType.value || '')
)
```

## 第4章 治理/开发层页面设计（4 个）

### 4.1 元数据管理页（`/govern-meta`）

#### 4.1.1 页面定位

- **页面名称**：元数据管理
- **路由路径**：`/govern-meta`
- **所属层级**：治理/开发层
- **核心功能**：管理元数据采集数据源与采集任务，对接 CollectorController。展示数据源列表、采集历史、定时调度配置，支持手动触发采集、测试连接、注册定时任务。

#### 4.1.2 页面布局

```
┌─────────────────────────────────────────────┐
│ H1 元数据管理                               │
│ 副标题：数据源 · 采集调度 · 采集历史         │
├─────────────────────────────────────────────┤
│ KPI 卡片（4 个）                             │
│  数据源数 · 活跃数 · 今日采集次数            │
│  · 最近采集成功率                            │
├─────────────────────────────────────────────┤
│ 操作栏：[+ 添加数据源] [刷新]                │
├─────────────────────────────────────────────┤
│ 数据源列表（el-table）                       │
│  名称 · 类型 · 状态 · Cron · 最近采集        │
│  · 操作(采集/测试/调度/编辑/删除)            │
├─────────────────────────────────────────────┤
│ 抽屉：采集历史（el-timeline）                │
│  时间 · 触发方式 · 状态 · 耗时 · 对象数      │
├─────────────────────────────────────────────┤
│ 弹窗：添加/编辑数据源 · 配置定时采集         │
└─────────────────────────────────────────────┘
```

#### 4.1.3 API 对接设计

**后端 API 路径**（CollectorController）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/metadata/sources` | 添加数据源 |
| GET | `/api/v1/metadata/sources` | 列出全部数据源 |
| GET | `/api/v1/metadata/sources/{id}` | 获取单个数据源 |
| PUT | `/api/v1/metadata/sources/{id}` | 更新数据源 |
| DELETE | `/api/v1/metadata/sources/{id}` | 删除数据源 |
| POST | `/api/v1/metadata/collect/{sourceId}` | 手动触发采集 |
| GET | `/api/v1/metadata/collect/status/{sourceId}` | 查询采集状态 |
| POST | `/api/v1/metadata/collect/test/{sourceId}` | 测试连接 |
| POST | `/api/v1/metadata/collect/schedule/{sourceId}` | 注册定时采集 |
| DELETE | `/api/v1/metadata/collect/schedule/{sourceId}` | 取消定时采集 |
| GET | `/api/v1/metadata/collectors` | 已注册 Collector 类型 |

**前端 API 模块**：新建 `frontend/src/api/governMeta.ts`

```typescript
// frontend/src/api/governMeta.ts
import { get, post, put, del } from './client'

const BASE = '/metadata'

export function listSources(): Promise<MetadataSource[]> {
  return get(`${BASE}/sources`)
}
export function addSource(source: MetadataSource): Promise<MetadataSource> {
  return post(`${BASE}/sources`, source)
}
export function updateSource(id: number, source: MetadataSource): Promise<MetadataSource> {
  return put(`${BASE}/sources/${id}`, source)
}
export function deleteSource(id: number): Promise<void> {
  return del(`${BASE}/sources/${id}`)
}
export function triggerCollection(sourceId: number): Promise<CollectionResult> {
  return post(`${BASE}/collect/${sourceId}`)
}
export function getCollectionStatus(sourceId: number): Promise<CollectionHistory> {
  return get(`${BASE}/collect/status/${sourceId}`)
}
export function testConnection(sourceId: number): Promise<{ connected: boolean; message: string }> {
  return post(`${BASE}/collect/test/${sourceId}`)
}
export function scheduleCollection(sourceId: number, cron: string): Promise<{ scheduled: boolean }> {
  return post(`${BASE}/collect/schedule/${sourceId}`, { cron })
}
export function unscheduleCollection(sourceId: number): Promise<{ unscheduled: boolean }> {
  return del(`${BASE}/collect/schedule/${sourceId}`)
}
export function listCollectors(): Promise<string[]> {
  return get(`${BASE}/collectors`)
}

export interface MetadataSource {
  id?: number
  name: string
  type: string              // hive/mysql/kafka/...
  connectionUrl: string
  username?: string
  password?: string
  cron?: string
  status?: 'ACTIVE' | 'INACTIVE'
  createdAt?: string
  updatedAt?: string
}
```

#### 4.1.4 组件结构

- **页面组件**：`frontend/src/views/govern/MetadataManagement.vue`
- **子组件**：
  - `MetadataSourceTable.vue`：数据源列表
  - `SourceEditDialog.vue`：添加/编辑弹窗
  - `CollectionHistoryDrawer.vue`：采集历史抽屉
  - `ScheduleDialog.vue`：定时采集配置弹窗（含 cron 表达式编辑器）
- **Element Plus 组件**：`el-card` `el-table` `el-drawer` `el-timeline` `el-dialog` `el-form` `el-select` `el-input`

#### 4.1.5 数据流

```typescript
const { data: sources, loading, error, execute: reload } = useApi<MetadataSource[]>(
  () => governMetaApi.listSources()
)
const { data: collectorTypes } = useApi<string[]>(() => governMetaApi.listCollectors())

// 手动触发采集
async function handleTrigger(row: MetadataSource) {
  const result = await governMetaApi.triggerCollection(row.id!)
  ElMessage.success(`采集完成，共 ${result.collectedCount} 个对象`)
  reload()
}
// 测试连接
async function handleTest(row: MetadataSource) {
  const { connected, message } = await governMetaApi.testConnection(row.id!)
  ElMessage[connected ? 'success' : 'error'](message)
}
```

### 4.2 调度编排页（`/dev-sched`）

#### 4.2.1 页面定位

- **页面名称**：调度编排（DolphinScheduler）
- **路由路径**：`/dev-sched`
- **所属层级**：治理/开发层
- **核心功能**：可视化编排流批 DAG 工作流，对接 StreamBatchSchedulerController。展示 DAG 列表与执行历史，支持 DAG 编辑、提交、失败重跑、补数据。

#### 4.2.2 页面布局

```
┌─────────────────────────────────────────────┐
│ H1 调度编排（DolphinScheduler）             │
│ 副标题：DAG 工作流 · 流批统一 · 补数据        │
├─────────────────────────────────────────────┤
│ KPI 卡片（4 个）                             │
│  DAG 总数 · 运行中 · 今日成功 · 今日失败     │
├─────────────────────────────────────────────┤
│ 操作栏：[+ 新建 DAG] [刷新]                  │
├─────────────────────────────────────────────┤
│ DAG 列表（el-table）                         │
│  DAG ID · 名称 · 状态 · 最近运行 · 调度      │
│  · 操作(编辑/运行/历史/重跑/补数据)          │
├─────────────────────────────────────────────┤
│ 抽屉：DAG 可视化编辑器（复用 DagVisualizer） │
│ 抽屉：运行历史（el-table 分页）              │
│ 弹窗：补数据（日期范围 + interval）          │
└─────────────────────────────────────────────┘
```

#### 4.2.3 API 对接设计

**后端 API 路径**：

| 方法 | 路径 | Controller | 说明 |
| --- | --- | --- | --- |
| GET | `/api/v1/jobs` | JobController | DAG 作业列表 |
| POST | `/api/v1/jobs` | JobController | 创建作业 |
| POST | `/api/v1/jobs/{id}/run` | JobController | 运行作业 |
| POST | `/api/v1/stream-batch/dags` | StreamBatchSchedulerController | 提交 DAG |
| GET | `/api/v1/stream-batch/dags/{dagId}` | StreamBatchSchedulerController | 查询 DAG 结果 |
| GET | `/api/v1/stream-batch/dags` | StreamBatchSchedulerController | 全部历史 |
| GET | `/api/v1/stream-batch/dags/{dagId}/runs` | StreamBatchSchedulerController | 分页运行历史 |
| POST | `/api/v1/stream-batch/dags/{dagId}/runs/{runId}/rerun` | StreamBatchSchedulerController | 失败重跑 |
| POST | `/api/v1/stream-batch/dags/{dagId}/backfill` | StreamBatchSchedulerController | 补数据 |

**前端 API 模块**：新建 `frontend/src/api/devSched.ts`（封装 JobController + StreamBatchSchedulerController，复用已有 streamBatch.ts）

```typescript
// frontend/src/api/devSched.ts
import { get, post, put, del } from './client'
import * as streamBatchApi from './streamBatch'  // 复用已有模块

export function listDags(workspaceId?: string, page = 1, size = 20): Promise<PagedResult<DagJob>> {
  return get('/jobs', { workspaceId, page, size })
}
export function createDag(req: DagCreateRequest): Promise<DagJob> {
  return post('/jobs', req)
}
export function runDag(id: string): Promise<{ dagId: string; status: string }> {
  return post(`/jobs/${id}/run`)
}
export function submitDag(dag: StreamBatchDag): Promise<DagExecutionResult> {
  return post('/stream-batch/dags', dag)
}
export function getDagResult(dagId: string): Promise<DagExecutionResult> {
  return get(`/stream-batch/dags/${dagId}`)
}
// 复用 streamBatch.ts 的 listDagRuns / rerunDagRun / backfillDag
export { streamBatchApi }
```

#### 4.2.4 组件结构

- **页面组件**：`frontend/src/views/dev/ScheduleOrchestration.vue`
- **子组件**：
  - `DagListTable.vue`：DAG 列表
  - `DagEditDrawer.vue`：DAG 编辑抽屉（复用 `@/views/orchestrator/DagVisualizer.vue`）
  - `DagRunHistoryDrawer.vue`：运行历史抽屉
  - `BackfillDialog.vue`：补数据弹窗
- **Element Plus 组件**：`el-card` `el-table` `el-drawer` `el-dialog` `el-form` `el-date-picker` `el-input-number`

#### 4.2.5 数据流

```typescript
const { data: dags, loading, execute: reload } = useApi<PagedResult<DagJob>>(
  () => devSchedApi.listDags()
)
// 运行历史（复用 streamBatch.ts）
const selectedDagId = ref<string>()
const { data: runs, loading: runsLoading, execute: loadRuns } = useApi<DagRunPage>(
  () => streamBatchApi.listDagRuns(selectedDagId.value!, { page: 1, size: 20 }),
  { immediate: false }
)
// 补数据
async function handleBackfill(dagId: string, req: BackfillRequest) {
  const { created } = await streamBatchApi.backfillDag(dagId, req)
  ElMessage.success(`已生成 ${created} 个回填实例`)
  loadRuns()
}
```

### 4.3 标签画像页（`/dev-tag`）

#### 4.3.1 页面定位

- **页面名称**：标签画像
- **路由路径**：`/dev-tag`
- **所属层级**：治理/开发层
- **核心功能**：管理标签体系与人群画像，对接 TagController + ProfileController + AudienceController。展示标签定义、规则、计算结果，支持人群圈选与画像查询。

#### 4.3.2 页面布局

```
┌─────────────────────────────────────────────┐
│ H1 标签画像                                 │
│ 副标题：标签定义 · 规则计算 · 人群圈选       │
├─────────────────────────────────────────────┤
│ KPI 卡片（4 个）                             │
│  标签总数 · 已计算标签 · 人群数              │
│  · 今日圈选次数                              │
├─────────────────────────────────────────────┤
│ Tabs：[标签定义] [用户画像] [人群圈选]       │
├─────────────────────────────────────────────┤
│ Tab1 标签定义（el-table）                    │
│  标签名 · 类型 · 规则数 · 最近计算 · 操作    │
│  操作：编辑规则/计算/批计算/删除             │
│ Tab2 用户画像（按 userId 查询）              │
│  userId 输入 + 画像卡片（标签值列表）        │
│ Tab3 人群圈选                                │
│  左：标签条件构建器（el-form 动态行）        │
│  右：结果（人数 + 用户列表分页）             │
├─────────────────────────────────────────────┤
│ 弹窗：标签定义编辑 · 规则编辑                │
└─────────────────────────────────────────────┘
```

#### 4.3.3 API 对接设计

**后端 API 路径**：

| 方法 | 路径 | Controller | 说明 |
| --- | --- | --- | --- |
| POST | `/api/v1/tags` | TagController | 创建标签定义 |
| GET | `/api/v1/tags?tenantId=` | TagController | 列出标签 |
| GET | `/api/v1/tags/{id}` | TagController | 标签详情 |
| DELETE | `/api/v1/tags/{id}` | TagController | 删除标签 |
| POST | `/api/v1/tags/{id}/rules` | TagController | 添加规则 |
| GET | `/api/v1/tags/{id}/rules` | TagController | 规则列表 |
| POST | `/api/v1/tags/{id}/compute` | TagController | 计算标签 |
| POST | `/api/v1/tags/batch-compute` | TagController | 批量计算 |
| GET | `/api/v1/profiles/{userId}` | ProfileController | 单用户画像 |
| POST | `/api/v1/profiles/query` | ProfileController | 按标签查询用户 |
| POST | `/api/v1/profiles/count` | ProfileController | 按标签统计人数 |
| POST | `/api/v1/audiences/select` | AudienceController | 人群圈选 |

**前端 API 模块**：新建 `frontend/src/api/devTag.ts`

```typescript
// frontend/src/api/devTag.ts
import { get, post, del } from './client'

// 标签定义
export function listTags(tenantId: string): Promise<TagDefinition[]> {
  return get('/tags', { tenantId })
}
export function createTag(req: TagDefinitionRequest): Promise<TagDefinition> {
  return post('/tags', req)
}
export function deleteTag(id: string): Promise<void> {
  return del(`/tags/${id}`)
}
export function listTagRules(id: string): Promise<TagRule[]> {
  return get(`/tags/${id}/rules`)
}
export function addTagRule(id: string, req: TagRuleRequest): Promise<TagRule> {
  return post(`/tags/${id}/rules`, req)
}
export function computeTag(id: string, req: ComputeRequest): Promise<TagComputeResult> {
  return post(`/tags/${id}/compute`, req)
}
export function batchCompute(tagIds: string[], req?: ComputeRequest): Promise<BatchComputeResult> {
  return post('/tags/batch-compute', { tagIds, req })
}

// 用户画像
export function getProfile(userId: string): Promise<UserProfile> {
  return get(`/profiles/${userId}`)
}
export function queryByTags(query: TagQuery): Promise<UserProfile[]> {
  return post('/profiles/query', query)
}
export function countByTags(query: TagQuery): Promise<{ count: number }> {
  return post('/profiles/count', query)
}

// 人群圈选
export function selectAudience(req: AudienceRequest): Promise<AudienceResult> {
  return post('/audiences/select', req)
}
```

#### 4.3.4 组件结构

- **页面组件**：`frontend/src/views/dev/TagProfile.vue`
- **子组件**：
  - `TagDefinitionTable.vue`：标签定义列表
  - `TagRuleEditDialog.vue`：规则编辑弹窗
  - `UserProfilePanel.vue`：用户画像面板
  - `AudienceSelector.vue`：人群圈选条件构建器
  - `AudienceResultPanel.vue`：圈选结果面板
- **Element Plus 组件**：`el-card` `el-tabs` `el-table` `el-form` `el-dialog` `el-input` `el-tag` `el-select`

#### 4.3.5 数据流

```typescript
const { data: tags, loading, execute: reload } = useApi<TagDefinition[]>(
  () => devTagApi.listTags(currentTenantId)
)
// 用户画像查询
const userIdInput = ref('')
const { data: profile, loading: profileLoading, execute: loadProfile } = useApi<UserProfile>(
  () => devTagApi.getProfile(userIdInput.value), { immediate: false }
)
// 人群圈选
const conditions = ref<TagQuery>({ tags: [] })
const { data: audienceResult, loading: selecting, execute: select } = useApi<AudienceResult>(
  () => devTagApi.selectAudience({ query: conditions.value }), { immediate: false }
)
// 批量计算
async function handleBatchCompute(ids: string[]) {
  const result = await devTagApi.batchCompute(ids)
  ElMessage.success(`批量计算完成，成功 ${result.successCount} / ${ids.length}`)
  reload()
}
```

### 4.4 机器学习页（`/dev-ml`）

#### 4.4.1 页面定位

- **页面名称**：机器学习
- **路由路径**：`/dev-ml`
- **所属层级**：治理/开发层
- **核心功能**：管理机器学习训练作业与模型生命周期，展示训练实验、模型版本、推理服务状态，支持训练作业提交、模型注册、推理服务部署。

#### 4.4.2 页面布局

```
┌─────────────────────────────────────────────┐
│ H1 机器学习                                 │
│ 副标题：训练实验 · 模型注册 · 推理服务       │
├─────────────────────────────────────────────┤
│ KPI 卡片（4 个）                             │
│  训练作业数 · 运行中 · 模型数 · 推理服务数   │
├─────────────────────────────────────────────┤
│ Tabs：[训练实验] [模型仓库] [推理服务]       │
├─────────────────────────────────────────────┤
│ Tab1 训练实验（el-table）                    │
│  实验名 · 算法 · 数据集 · 状态 · 指标        │
│  · 操作(查看日志/停止/注册模型)              │
│ Tab2 模型仓库（el-table）                    │
│  模型名 · 版本 · 算法 · 指标 · 注册时间      │
│  · 操作(部署推理/对比/删除)                  │
│ Tab3 推理服务（el-table）                    │
│  服务名 · 模型·版本 · 状态 · QPS · 延迟      │
│  · 操作(停止/扩缩容/查看监控)                │
├─────────────────────────────────────────────┤
│ 弹窗：提交训练 · 部署推理                    │
└─────────────────────────────────────────────┘
```

#### 4.4.3 API 对接设计

**后端 API 路径**（基于 JobController + LlmopsApi + 专用 ML 端点，需后端补充 ML 专用端点）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/jobs?type=ml_train` | 训练作业列表 |
| POST | `/api/v1/jobs` | 创建训练作业（type=ml_train） |
| POST | `/api/v1/jobs/{id}/run` | 启动训练 |
| GET | `/api/v1/ml/models` | 模型仓库 |
| POST | `/api/v1/ml/models` | 注册模型 |
| GET | `/api/v1/ml/models/{name}/versions` | 模型版本列表 |
| GET | `/api/v1/ml/inference-services` | 推理服务列表 |
| POST | `/api/v1/ml/inference-services` | 部署推理 |
| DELETE | `/api/v1/ml/inference-services/{id}` | 停止推理 |

**前端 API 模块**：新建 `frontend/src/api/devMl.ts`

```typescript
// frontend/src/api/devMl.ts
import { get, post, del } from './client'

const TRAIN_TYPE = 'ml_train'

// 训练实验
export function listTrainJobs(): Promise<PagedResult<TrainJob>> {
  return get('/jobs', { type: TRAIN_TYPE })
}
export function createTrainJob(req: TrainJobRequest): Promise<TrainJob> {
  return post('/jobs', { ...req, type: TRAIN_TYPE })
}
export function runTrainJob(id: string): Promise<{ dagId: string }> {
  return post(`/jobs/${id}/run`)
}

// 模型仓库
export function listModels(): Promise<MlModel[]> {
  return get('/ml/models')
}
export function registerModel(req: ModelRegisterRequest): Promise<MlModel> {
  return post('/ml/models', req)
}
export function listModelVersions(name: string): Promise<ModelVersion[]> {
  return get(`/ml/models/${name}/versions`)
}

// 推理服务
export function listInferenceServices(): Promise<InferenceService[]> {
  return get('/ml/inference-services')
}
export function deployInference(req: InferenceDeployRequest): Promise<InferenceService> {
  return post('/ml/inference-services', req)
}
export function stopInference(id: string): Promise<void> {
  return del(`/ml/inference-services/${id}`)
}

export interface TrainJob {
  id: string
  name: string
  algorithm: string         // xgboost/tensorflow/pytorch/...
  dataset: string
  status: string
  metrics?: Record<string, number>
  hyperparams?: string      // JSON
}
```

#### 4.4.4 组件结构

- **页面组件**：`frontend/src/views/dev/MachineLearning.vue`
- **子组件**：
  - `TrainJobTable.vue`：训练实验列表
  - `ModelRegistryTable.vue`：模型仓库列表
  - `InferenceServiceTable.vue`：推理服务列表
  - `TrainSubmitDialog.vue`：提交训练弹窗
  - `InferenceDeployDialog.vue`：部署推理弹窗
- **Element Plus 组件**：`el-card` `el-tabs` `el-table` `el-dialog` `el-form` `el-input` `el-select` `el-tag`

#### 4.4.5 数据流

```typescript
const { data: trainJobs, loading: trainLoading, execute: loadTrain } = useApi<PagedResult<TrainJob>>(
  () => devMlApi.listTrainJobs()
)
const { data: models, loading: modelsLoading, execute: loadModels } = useApi<MlModel[]>(
  () => devMlApi.listModels()
)
const { data: services, loading: svcLoading, execute: loadServices } = useApi<InferenceService[]>(
  () => devMlApi.listInferenceServices()
)
// 部署推理
async function handleDeploy(model: MlModel, version: string) {
  await devMlApi.deployInference({ modelName: model.name, version, replicas: 1 })
  ElMessage.success('推理服务已部署')
  loadServices()
}
```

## 第5章 新增 API 模块清单

### 5.1 模块清单总表

下表列出本设计需要新建的前端 API 模块，全部位于 `frontend/src/api/` 下：

| # | 文件名 | 对接后端 Controller | 服务页面 | 复用已有模块 |
| --- | --- | --- | --- | --- |
| 1 | `infraXinchang.ts` | XinchangClusterController | 机器供应 | 无 |
| 2 | `infraCluster.ts` | ClusterController（编排层） | K8s 集群 | 无 |
| 3 | `infraNetwork.ts` | ClusterController 网络子资源 | 容器网络 | 无 |
| 4 | `infraStorage.ts` | ClusterController 存储子资源 | 容器存储 | 无 |
| 5 | `infraSched.ts` | ClusterController 扩缩容 + HPA | 弹性调度 | 无 |
| 6 | `engStorage.ts` | VirtualTableController + MaterializedViewController | 统一存储 | 无 |
| 7 | `engSpark.ts` | JobController（type=batch_spark） | 批计算 Spark | 无 |
| 8 | `engFlink.ts` | JobController（type=stream_flink）+ StreamBatchSchedulerController | 流计算 Flink | 复用 streamBatch.ts |
| 9 | `engDoris.ts` | SqlGatewayController（engine=doris） | OLAP Doris | 无 |
| 10 | `engKafka.ts` | DataSourceController + 专用 Kafka 端点 | 消息流 Kafka | 无 |
| 11 | `engIotdb.ts` | SqlGatewayController + DataSourceController + 专用 IoTDB 端点 | 时序 IoTDB | 无 |
| 12 | `engMmg.ts` | VirtualTableController（types） | 多模型引擎 | 部分复用 engStorage |
| 13 | `governMeta.ts` | CollectorController | 元数据管理 | 无 |
| 14 | `devSched.ts` | StreamBatchSchedulerController + JobController | 调度编排 | 复用 streamBatch.ts |
| 15 | `devTag.ts` | TagController + ProfileController + AudienceController | 标签画像 | 无 |
| 16 | `devMl.ts` | JobController（type=ml_train）+ 专用 ML 端点 | 机器学习 | 无 |

### 5.2 模块实现约定

1. **统一导入**：所有模块从 `./client` 导入 `get/post/put/del` 四个泛型方法
2. **类型导出**：接口类型在模块内 `export interface`，不集中到 `types.ts`（避免巨型文件）
3. **路径常量**：模块内定义 `const BASE = '/xxx'`，方法使用模板字符串拼接
4. **JSDoc 注释**：每个导出函数附中文 JSDoc，说明对应后端端点
5. **错误处理**：不在 API 模块内 try/catch，由 `useApi` 与 `client.ts` 拦截器统一处理
6. **后端依赖标注**：需后端补充的端点在模块顶部用 `// TODO(backend):` 注释标注

### 5.3 后端需补充的端点清单

本设计前端先行定义了以下后端暂未实现的端点契约，需后端团队补充：

| 模块 | 端点 | 说明 |
| --- | --- | --- |
| infraNetwork | `/clusters/{env}/{id}/network*` | 集群网络配置与 NetworkPolicy 管理 |
| infraStorage | `/clusters/{env}/{id}/storage*` | StorageClass/PVC/快照管理 |
| infraSched | `/clusters/{env}/{id}/hpa*` + `/scale/events` | HPA 策略管理与扩缩容事件 |
| engKafka | `/kafka/{clusterId}/*` | Kafka Broker/Topic/消费组管理 |
| engIotdb | `/iotdb/{id}/*` | IoTDB 存储组/设备/测点/吞吐 |
| engDoris | `/doris/*` | Doris 节点/数据库/查询列表 |
| devMl | `/ml/models*` + `/ml/inference-services*` | 模型仓库与推理服务 |

## 第6章 新增组件清单

### 6.1 页面组件清单（16 个）

| # | 组件路径 | 路由 | 说明 |
| --- | --- | --- | --- |
| 1 | `views/infra/MachineSupply.vue` | `/infra-machine` | 机器供应页 |
| 2 | `views/infra/K8sCluster.vue` | `/infra-k8s` | K8s 集群页 |
| 3 | `views/infra/ContainerNetwork.vue` | `/infra-net` | 容器网络页 |
| 4 | `views/infra/ContainerStorage.vue` | `/infra-store` | 容器存储页 |
| 5 | `views/infra/ElasticScheduling.vue` | `/infra-sched` | 弹性调度页 |
| 6 | `views/engine/UnifiedStorage.vue` | `/eng-storage` | 统一存储页 |
| 7 | `views/engine/SparkEngine.vue` | `/eng-spark` | 批计算 Spark 页 |
| 8 | `views/engine/FlinkEngine.vue` | `/eng-flink` | 流计算 Flink 页 |
| 9 | `views/engine/DorisEngine.vue` | `/eng-doris` | OLAP Doris 页 |
| 10 | `views/engine/KafkaEngine.vue` | `/eng-kafka` | 消息流 Kafka 页 |
| 11 | `views/engine/IotdbEngine.vue` | `/eng-iotdb` | 时序 IoTDB 页 |
| 12 | `views/engine/MultiModelEngine.vue` | `/eng-mmg` | 多模型引擎页 |
| 13 | `views/govern/MetadataManagement.vue` | `/govern-meta` | 元数据管理页 |
| 14 | `views/dev/ScheduleOrchestration.vue` | `/dev-sched` | 调度编排页 |
| 15 | `views/dev/TagProfile.vue` | `/dev-tag` | 标签画像页 |
| 16 | `views/dev/MachineLearning.vue` | `/dev-ml` | 机器学习页 |

### 6.2 子组件清单

#### 6.2.1 基础设施层子组件

| 组件 | 父页面 | 功能 |
| --- | --- | --- |
| `ClusterCreateDialog.vue` | MachineSupply | 信创集群创建向导 |
| `ClusterScaleDialog.vue` | MachineSupply | 扩缩容表单 |
| `ClusterStatusTag.vue` | 多页面共用 | 集群状态标签 |
| `ClusterCreateWizard.vue` | K8sCluster | 跨环境创建向导 |
| `ClusterDetailDrawer.vue` | K8sCluster | 集群详情抽屉 |
| `NodeListTable.vue` | K8sCluster | 节点列表 |
| `EnvTabs.vue` | K8sCluster | 环境筛选 tabs |
| `NetworkConfigCard.vue` | ContainerNetwork | 网络配置卡片 |
| `NetworkPolicyTable.vue` | ContainerNetwork | NetworkPolicy 列表 |
| `NetworkPolicyDialog.vue` | ContainerNetwork | 策略创建弹窗 |
| `StorageUsageChart.vue` | ContainerStorage | 存储用量环形图 |
| `PvcTable.vue` | ContainerStorage | PVC 列表 |
| `PvcCreateDialog.vue` | ContainerStorage | 创建 PVC 弹窗 |
| `HpaTable.vue` | ElasticScheduling | HPA 策略列表 |
| `HpaEditDialog.vue` | ElasticScheduling | HPA 编辑弹窗 |
| `ScaleEventTimeline.vue` | ElasticScheduling | 扩缩容事件时间线 |

#### 6.2.2 引擎层子组件

| 组件 | 父页面 | 功能 |
| --- | --- | --- |
| `VirtualTableTable.vue` | UnifiedStorage | 虚拟表列表 |
| `MaterializedViewTable.vue` | UnifiedStorage | 物化视图列表 |
| `CacheStatsPanel.vue` | UnifiedStorage | 缓存统计面板 |
| `VirtualTableRegisterDialog.vue` | UnifiedStorage | 注册虚拟表弹窗 |
| `VirtualTableQueryDialog.vue` | UnifiedStorage | 查询结果弹窗 |
| `SparkJobTable.vue` | SparkEngine | Spark 作业列表 |
| `SparkJobSubmitDialog.vue` | SparkEngine | 提交作业弹窗 |
| `SparkJobDetailDrawer.vue` | SparkEngine | 作业详情抽屉 |
| `StageProgressChart.vue` | SparkEngine | Stage 进度图 |
| `FlinkJobTable.vue` | FlinkEngine | Flink 作业列表 |
| `FlinkJobMonitorDrawer.vue` | FlinkEngine | 监控详情抽屉 |
| `ThroughputChart.vue` | FlinkEngine | 吞吐实时图 |
| `CheckpointTable.vue` | FlinkEngine | Checkpoint 历史 |
| `OperatorTopology.vue` | FlinkEngine | 算子拓扑图 |
| `DorisCatalogTree.vue` | DorisEngine | 数据库/表目录树 |
| `DorisNodeTable.vue` | DorisEngine | 节点状态表 |
| `DorisQueryTable.vue` | DorisEngine | 查询列表 |
| `SqlWorkbenchPanel.vue` | DorisEngine/IotdbEngine | SQL 工作台（可复用） |
| `BrokerTable.vue` | KafkaEngine | Broker 列表 |
| `TopicTable.vue` | KafkaEngine | Topic 列表 |
| `ConsumerGroupTable.vue` | KafkaEngine | 消费组列表 |
| `TopicCreateDialog.vue` | KafkaEngine | 创建 Topic 弹窗 |
| `MessageSampleDialog.vue` | KafkaEngine | 消息采样弹窗 |
| `IotdbCatalogTree.vue` | IotdbEngine | 存储组/设备/测点树 |
| `WriteThroughputChart.vue` | IotdbEngine | 写入吞吐图 |
| `TimeseriesPreviewChart.vue` | IotdbEngine | 时序预览图 |
| `ModelTypeCard.vue` | MultiModelEngine | 模型类型卡片 |
| `VirtualTableByModelTable.vue` | MultiModelEngine | 按模型分组虚拟表 |
| `CrossModelQueryDialog.vue` | MultiModelEngine | 跨模型查询弹窗 |

#### 6.2.3 治理/开发层子组件

| 组件 | 父页面 | 功能 |
| --- | --- | --- |
| `MetadataSourceTable.vue` | MetadataManagement | 数据源列表 |
| `SourceEditDialog.vue` | MetadataManagement | 数据源编辑弹窗 |
| `CollectionHistoryDrawer.vue` | MetadataManagement | 采集历史抽屉 |
| `ScheduleDialog.vue` | MetadataManagement | 定时采集配置弹窗 |
| `DagListTable.vue` | ScheduleOrchestration | DAG 列表 |
| `DagEditDrawer.vue` | ScheduleOrchestration | DAG 编辑抽屉（复用 DagVisualizer） |
| `DagRunHistoryDrawer.vue` | ScheduleOrchestration | 运行历史抽屉 |
| `BackfillDialog.vue` | ScheduleOrchestration | 补数据弹窗 |
| `TagDefinitionTable.vue` | TagProfile | 标签定义列表 |
| `TagRuleEditDialog.vue` | TagProfile | 规则编辑弹窗 |
| `UserProfilePanel.vue` | TagProfile | 用户画像面板 |
| `AudienceSelector.vue` | TagProfile | 人群圈选条件构建器 |
| `AudienceResultPanel.vue` | TagProfile | 圈选结果面板 |
| `TrainJobTable.vue` | MachineLearning | 训练实验列表 |
| `ModelRegistryTable.vue` | MachineLearning | 模型仓库列表 |
| `InferenceServiceTable.vue` | MachineLearning | 推理服务列表 |
| `TrainSubmitDialog.vue` | MachineLearning | 提交训练弹窗 |
| `InferenceDeployDialog.vue` | MachineLearning | 部署推理弹窗 |

### 6.3 可复用现有组件

| 现有组件 | 复用场景 |
| --- | --- |
| `@/views/orchestrator/DagVisualizer.vue` | 调度编排页 DAG 编辑抽屉 |
| `@/composables/useApi.ts` | 全部 16 个页面三态包装 |
| `@/api/client.ts` | 全部新增 API 模块的 HTTP 客户端 |
| `@/api/streamBatch.ts` | Flink/调度编排页运行历史与重跑 |

## 第7章 路由更新方案

### 7.1 更新原则

1. **保持路径不变**：16 个占位路由 path 完全保留，仅替换 component
2. **添加 name 与 meta**：补充 `name`（用于命名路由跳转）与完整 `meta`（title/icon）
3. **懒加载**：所有新页面采用与现有页面一致的 `() => import(...)` 懒加载
4. **删除 Roadmap 引用**：替换后 Roadmap.vue 不再被这 16 条路由引用（保留组件文件供其他用途或后续删除）

### 7.2 路由更新 diff 示例

以下为 `frontend/src/router/index.ts` 的更新示意（仅展示变更部分）：

```typescript
// === 新增：批量12 占位页面替换为真实页面组件 ===
const MachineSupply = () => import('@/views/infra/MachineSupply.vue')
const K8sCluster = () => import('@/views/infra/K8sCluster.vue')
const ContainerNetwork = () => import('@/views/infra/ContainerNetwork.vue')
const ContainerStorage = () => import('@/views/infra/ContainerStorage.vue')
const ElasticScheduling = () => import('@/views/infra/ElasticScheduling.vue')
const UnifiedStorage = () => import('@/views/engine/UnifiedStorage.vue')
const SparkEngine = () => import('@/views/engine/SparkEngine.vue')
const FlinkEngine = () => import('@/views/engine/FlinkEngine.vue')
const DorisEngine = () => import('@/views/engine/DorisEngine.vue')
const KafkaEngine = () => import('@/views/engine/KafkaEngine.vue')
const IotdbEngine = () => import('@/views/engine/IotdbEngine.vue')
const MultiModelEngine = () => import('@/views/engine/MultiModelEngine.vue')
const MetadataManagement = () => import('@/views/govern/MetadataManagement.vue')
const ScheduleOrchestration = () => import('@/views/dev/ScheduleOrchestration.vue')
const TagProfile = () => import('@/views/dev/TagProfile.vue')
const MachineLearning = () => import('@/views/dev/MachineLearning.vue')

// === 替换：占位路由（原指向 Roadmap）→ 真实页面 ===
// 基础设施层
{ path: '/infra-machine', name: 'MachineSupply', component: MachineSupply,
  meta: { title: '机器供应', icon: 'Cpu' } },
{ path: '/infra-k8s', name: 'K8sCluster', component: K8sCluster,
  meta: { title: 'K8s 集群', icon: 'Grid' } },
{ path: '/infra-net', name: 'ContainerNetwork', component: ContainerNetwork,
  meta: { title: '容器网络', icon: 'Connection' } },
{ path: '/infra-store', name: 'ContainerStorage', component: ContainerStorage,
  meta: { title: '容器存储', icon: 'Files' } },
{ path: '/infra-sched', name: 'ElasticScheduling', component: ElasticScheduling,
  meta: { title: '弹性调度', icon: 'Expand' } },
// 引擎层
{ path: '/eng-storage', name: 'UnifiedStorage', component: UnifiedStorage,
  meta: { title: '统一存储', icon: 'Box' } },
{ path: '/eng-spark', name: 'SparkEngine', component: SparkEngine,
  meta: { title: '批计算（Spark）', icon: 'Lightning' } },
{ path: '/eng-flink', name: 'FlinkEngine', component: FlinkEngine,
  meta: { title: '流计算（Flink）', icon: 'VideoPlay' } },
{ path: '/eng-doris', name: 'DorisEngine', component: DorisEngine,
  meta: { title: 'OLAP（Doris）', icon: 'DataAnalysis' } },
{ path: '/eng-kafka', name: 'KafkaEngine', component: KafkaEngine,
  meta: { title: '消息流接入（Kafka）', icon: 'ChatLineRound' } },
{ path: '/eng-iotdb', name: 'IotdbEngine', component: IotdbEngine,
  meta: { title: '时序引擎（IoTDB）', icon: 'Timer' } },
{ path: '/eng-mmg', name: 'MultiModelEngine', component: MultiModelEngine,
  meta: { title: '多模型引擎', icon: 'Coin' } },
// 治理/开发层
{ path: '/govern-meta', name: 'MetadataManagement', component: MetadataManagement,
  meta: { title: '元数据管理', icon: 'Collection' } },
{ path: '/dev-sched', name: 'ScheduleOrchestration', component: ScheduleOrchestration,
  meta: { title: '调度编排', icon: 'Share' } },
{ path: '/dev-tag', name: 'TagProfile', component: TagProfile,
  meta: { title: '标签画像', icon: 'PriceTag' } },
{ path: '/dev-ml', name: 'MachineLearning', component: MachineLearning,
  meta: { title: '机器学习', icon: 'MagicStick' } },
```

### 7.3 路由守卫与鉴权

现有 `authGuard` 已对所有非 `/login` 路由强制鉴权，新增 16 条路由无需修改守卫逻辑，自动继承：

```typescript
// 现有守卫（无需改动）
router.beforeEach((to) => {
  const authStore = useAuthStore()
  return authGuard(to, authStore.isAuthenticated)
})
```

### 7.4 菜单集成建议

建议在 `App.vue` 或布局组件的侧边菜单中，按三层分组新增菜单项：

```
基础设施
  ├ 机器供应      /infra-machine
  ├ K8s 集群     /infra-k8s
  ├ 容器网络     /infra-net
  ├ 容器存储     /infra-store
  └ 弹性调度     /infra-sched
引擎
  ├ 统一存储     /eng-storage
  ├ 批计算       /eng-spark
  ├ 流计算       /eng-flink
  ├ OLAP         /eng-doris
  ├ 消息流       /eng-kafka
  ├ 时序引擎     /eng-iotdb
  └ 多模型       /eng-mmg
治理/开发
  ├ 元数据       /govern-meta
  ├ 调度编排     /dev-sched
  ├ 标签画像     /dev-tag
  └ 机器学习     /dev-ml
```

## 第8章 实施建议

### 8.1 实施顺序

按依赖关系与价值优先级分三批实施：

#### 8.1.1 第1批（后端已就绪，可直接对接）

| 顺序 | 页面 | 原因 |
| --- | --- | --- |
| 1 | 机器供应 | XinchangClusterController 已完整实现 |
| 2 | K8s 集群 | ClusterController 编排层已完整实现 |
| 3 | 元数据管理 | CollectorController 已完整实现 |
| 4 | 标签画像 | TagController/ProfileController/AudienceController 已完整实现 |
| 5 | 统一存储 | VirtualTableController + MaterializedViewController 已完整实现 |
| 6 | 调度编排 | StreamBatchSchedulerController + JobController 已完整实现 |

#### 8.1.2 第2批（部分端点需后端补充）

| 顺序 | 页面 | 待补充端点 |
| --- | --- | --- |
| 7 | 批计算 Spark | 复用 JobController，需确认 type 过滤 |
| 8 | 流计算 Flink | 需补充 Checkpoint/Backpressure 端点 |
| 9 | OLAP Doris | 需补充 Doris 节点/查询列表端点 |
| 10 | 弹性调度 | 需补充 HPA 管理端点 |

#### 8.1.3 第3批（后端端点全新）

| 顺序 | 页面 | 待补充端点 |
| --- | --- | --- |
| 11 | 容器网络 | 全部网络子资源端点 |
| 12 | 容器存储 | 全部存储子资源端点 |
| 13 | 消息流 Kafka | 全部 Kafka 管理端点 |
| 14 | 时序 IoTDB | 全部 IoTDB 管理端点 |
| 15 | 多模型引擎 | 复用 VirtualTable，需补充模型分类元信息 |
| 16 | 机器学习 | 全部 ML 模型/推理端点 |

### 8.2 测试要求

每个页面实施时需同步补充：

1. **API 模块单测**：`frontend/src/api/__tests__/<module>.spec.ts`，使用 vitest mock client 验证请求路径与参数
2. **页面组件单测**：`frontend/src/views/__tests__/<Page>.spec.ts`，验证三态渲染与关键交互
3. **路由守卫测试**：扩展 `frontend/src/router/__tests__/` 确保新路由受鉴权保护

### 8.3 验收标准

| 维度 | 标准 |
| --- | --- |
| 路由 | 16 条占位路由全部指向真实组件，无 Roadmap 残留 |
| 三态 | 每个数据区域均有 loading/error/data 三态，无白屏 |
| API | 每个页面至少对接 1 个后端端点，无 mock 数据 |
| 错误处理 | 网络错误/业务错误均有可读提示与重试 |
| 响应式 | KPI 卡片在窄屏下自动堆叠（el-col xs=24） |
| 鉴权 | 未登录访问任一新页面跳转 /login |
| 文档 | 每个页面组件顶部附 JSDoc 说明对应设计文档章节 |

## 第9章 风险与依赖

### 9.1 后端依赖风险

| 风险 | 影响 | 缓解措施 |
| --- | --- | --- |
| 网络子资源端点未实现 | 容器网络页无法对接 | 前端 API 模块先行定义契约，后端按契约实现 |
| 存储子资源端点未实现 | 容器存储页无法对接 | 同上 |
| HPA 管理端点未实现 | 弹性调度页部分功能空挂 | 已有 scale 端点先支撑手动扩缩容 |
| Kafka/IoTDB/ML 端点未实现 | 4 个页面无法对接 | 前端先实现 UI 骨架，标注"待后端就绪" |

### 9.2 前端内部依赖

| 依赖 | 状态 | 备注 |
| --- | --- | --- |
| `useApi` composable | 已就绪 | 直接复用 |
| `client.ts` HTTP 客户端 | 已就绪 | 直接复用 |
| Element Plus | 已就绪 | 直接使用 |
| ECharts | 已就绪 | 直接使用 |
| `DagVisualizer.vue` | 已就绪 | 调度编排页复用 |
| `streamBatch.ts` | 已就绪 | Flink/调度编排页复用 |

### 9.3 兼容性

- **浏览器**：与现有页面一致，支持现代浏览器（Chrome/Edge/Firefox 最新版）
- **TypeScript**：strict 模式，所有新增模块需通过 `tsc --noEmit`
- **ESLint**：遵循项目已有规则，新增文件需通过 lint

---

**文档结束** · 共设计 16 个页面 · 新增 16 个 API 模块 · 新增 16 个页面组件 + 50+ 子组件 · 替换 16 条占位路由