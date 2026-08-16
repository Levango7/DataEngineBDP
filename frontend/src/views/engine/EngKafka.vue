<template>
  <div class="eng-kafka-page">
    <h1>消息流接入（Kafka）</h1>
    <div class="sub">Broker · Topic · 消费组 · Lag 监控 · 15 秒自动刷新</div>

    <!-- KPI 卡片区：三态 loading / error / data -->
    <div class="grid g4">
      <template v-if="clustersLoading">
        <div class="card" v-for="i in 4" :key="i">
          <h3>加载中…</h3>
          <div class="kpi">--</div>
          <div class="meta">正在拉取数据</div>
        </div>
      </template>
      <template v-else-if="clustersError">
        <div class="card" style="grid-column: span 4">
          <h3>加载失败</h3>
          <div class="meta" style="color: var(--muted)">
            {{ clustersError.message }}，<a href="javascript:void(0)" @click="reloadClusters">重试</a>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>集群数</h3>
          <div class="kpi">{{ clusters?.length ?? 0 }}</div>
          <div class="meta">已接入 Kafka 集群</div>
        </div>
        <div class="card">
          <h3>Broker 数</h3>
          <div class="kpi">{{ brokers?.length ?? 0 }}</div>
          <div class="meta">存活 {{ brokerAliveCount }}</div>
        </div>
        <div class="card">
          <h3>Topic 数</h3>
          <div class="kpi s">{{ topics?.length ?? 0 }}</div>
          <div class="meta">已创建 Topic</div>
        </div>
        <div class="card">
          <h3>消费组数</h3>
          <div class="kpi">{{ consumerGroups?.length ?? 0 }}</div>
          <div class="meta">总 Lag {{ totalLag }}</div>
        </div>
      </template>
    </div>

    <!-- 主内容区：集群选择 + Tabs -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <div class="toolbar">
        <el-select
          v-model="selectedClusterId"
          placeholder="选择 Kafka 集群"
          style="width: 240px"
          @change="handleClusterChange"
        >
          <el-option
            v-for="c in clusters ?? []"
            :key="c.id"
            :label="c.name"
            :value="c.id"
          />
        </el-select>
        <el-tabs v-model="activeTab" type="card" class="main-tabs">
          <el-tab-pane label="Broker" name="brokers" />
          <el-tab-pane label="Topic" name="topics" />
          <el-tab-pane label="消费组" name="groups" />
        </el-tabs>
        <div class="spacer"></div>
        <el-button
          v-if="activeTab === 'topics'"
          type="primary"
          :disabled="!selectedClusterId"
          @click="openCreateTopicDialog"
        >
          + 创建 Topic
        </el-button>
        <el-button :icon="Refresh" circle @click="reloadCurrent" />
      </div>

      <!-- Tab1 Broker 列表 -->
      <template v-if="activeTab === 'brokers'">
        <el-table
          v-loading="brokersLoading"
          :data="brokers ?? []"
          stripe
          border
          style="width: 100%"
          :empty-text="brokersError ? '加载失败，请重试' : '暂无 Broker'"
        >
          <el-table-column prop="id" label="ID" width="100" />
          <el-table-column prop="host" label="Host" min-width="180" />
          <el-table-column prop="port" label="Port" width="100" />
          <el-table-column prop="version" label="版本" width="140" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="brokerStatusTagType(row.status)" effect="light" size="small">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="分区 Leader 数" width="140" align="right">
            <template #default="{ row }">{{ row.partitionLeaderCount ?? '--' }}</template>
          </el-table-column>
        </el-table>
      </template>

      <!-- Tab2 Topic 列表 -->
      <template v-else-if="activeTab === 'topics'">
        <el-table
          v-loading="topicsLoading"
          :data="topics ?? []"
          stripe
          border
          style="width: 100%"
          :empty-text="topicsError ? '加载失败，请重试' : '暂无 Topic'"
        >
          <el-table-column prop="name" label="Topic 名称" min-width="200" />
          <el-table-column label="分区数" width="100" align="center">
            <template #default="{ row }">{{ row.partitions }}</template>
          </el-table-column>
          <el-table-column label="副本因子" width="100" align="center">
            <template #default="{ row }">{{ row.replicas }}</template>
          </el-table-column>
          <el-table-column label="总消息数" width="160" align="right">
            <template #default="{ row }">{{ row.messageCount.toLocaleString() }}</template>
          </el-table-column>
          <el-table-column label="大小" width="120" align="right">
            <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openSampleDialog(row)">采样</el-button>
              <el-button link type="danger" @click="handleDeleteTopic(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <!-- Tab3 消费组列表 -->
      <template v-else>
        <el-table
          v-loading="groupsLoading"
          :data="consumerGroups ?? []"
          stripe
          border
          style="width: 100%"
          :empty-text="groupsError ? '加载失败，请重试' : '暂无消费组'"
        >
          <el-table-column prop="groupId" label="组名" min-width="200" />
          <el-table-column prop="engine" label="计算引擎" width="160" />
          <el-table-column label="Lag" width="140" align="right">
            <template #default="{ row }">
              <span :class="{ 'lag-warn': row.lag > 1000 }">{{ row.lag.toLocaleString() }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="160">
            <template #default="{ row }">
              <el-tag :type="groupStatusTagType(row.status)" effect="light" size="small">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="成员数" width="100" align="center">
            <template #default="{ row }">{{ row.memberCount ?? '--' }}</template>
          </el-table-column>
          <el-table-column label="订阅 Topic" width="120" align="center">
            <template #default="{ row }">{{ row.topicCount ?? '--' }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-card>

    <!-- 创建 Topic 弹窗 -->
    <el-dialog
      v-model="createTopicDialogVisible"
      title="创建 Topic"
      width="500px"
      :close-on-click-modal="false"
      @closed="resetCreateTopicForm"
    >
      <el-form
        ref="createTopicFormRef"
        :model="createTopicForm"
        :rules="createTopicRules"
        label-width="120px"
        label-position="right"
      >
        <el-form-item label="Topic 名称" prop="name">
          <el-input v-model="createTopicForm.name" placeholder="如 order-events" />
        </el-form-item>
        <el-form-item label="分区数" prop="partitions">
          <el-input-number v-model="createTopicForm.partitions" :min="1" :max="1000" />
        </el-form-item>
        <el-form-item label="副本因子" prop="replicas">
          <el-input-number v-model="createTopicForm.replicas" :min="1" :max="10" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createTopicDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creatingTopic" @click="handleCreateTopic">创建</el-button>
      </template>
    </el-dialog>

    <!-- 消息采样弹窗 -->
    <el-dialog
      v-model="sampleDialogVisible"
      :title="`消息采样 - ${currentSampleTopic ?? ''}`"
      width="800px"
      :close-on-click-modal="true"
    >
      <div v-loading="sampling" class="sample-result">
        <template v-if="sampleMessages.length > 0">
          <div class="sample-meta">共 {{ sampleMessages.length }} 条消息</div>
          <el-table
            :data="sampleMessages"
            stripe
            border
            size="small"
            style="width: 100%"
            max-height="420"
          >
            <el-table-column label="分区" width="80" align="center">
              <template #default="{ row }">{{ row.partition }}</template>
            </el-table-column>
            <el-table-column label="Offset" width="120" align="right">
              <template #default="{ row }">{{ row.offset }}</template>
            </el-table-column>
            <el-table-column prop="timestamp" label="时间戳" width="180" />
            <el-table-column prop="key" label="Key" width="160" show-overflow-tooltip />
            <el-table-column prop="value" label="Value" min-width="240" show-overflow-tooltip />
          </el-table>
        </template>
        <el-empty v-else-if="!sampling" description="暂无消息" />
      </div>
      <template #footer>
        <el-button @click="sampleDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as engineApi from '@/api/engine'
import type {
  KafkaCluster,
  Broker,
  Topic,
  ConsumerGroup,
  KafkaMessage
} from '@/api/engine'

/* ------------------------------ 集群列表 ------------------------------ */

const {
  data: clusters,
  loading: clustersLoading,
  error: clustersError,
  execute: reloadClusters
} = useApi<KafkaCluster[]>(() => engineApi.getKafkaClusters())

const selectedClusterId = ref<string>('')
const activeTab = ref<'brokers' | 'topics' | 'groups'>('brokers')

/* ------------------------------ Broker / Topic / 消费组 ------------------------------ */

const {
  data: brokers,
  loading: brokersLoading,
  error: brokersError,
  execute: loadBrokers
} = useApi<Broker[]>(() => engineApi.getKafkaBrokers(selectedClusterId.value), {
  immediate: false
})

const {
  data: topics,
  loading: topicsLoading,
  error: topicsError,
  execute: loadTopics
} = useApi<Topic[]>(() => engineApi.getKafkaTopics(selectedClusterId.value), {
  immediate: false
})

const {
  data: consumerGroups,
  loading: groupsLoading,
  error: groupsError,
  execute: loadGroups
} = useApi<ConsumerGroup[]>(() => engineApi.getKafkaConsumerGroups(selectedClusterId.value), {
  immediate: false
})

/** 集群切换 */
function handleClusterChange() {
  if (selectedClusterId.value) {
    void loadBrokers()
    void loadTopics()
    void loadGroups()
  }
}

/** 重新加载当前选中集群数据 */
async function reloadCurrent() {
  if (selectedClusterId.value) {
    await Promise.all([loadBrokers(), loadTopics(), loadGroups()])
  } else {
    await reloadClusters()
  }
}

/** 集群列表加载完成后自动选中第一个 */
watch(clusters, (list) => {
  if (list && list.length > 0 && !selectedClusterId.value) {
    selectedClusterId.value = list[0].id
    handleClusterChange()
  }
})

/** KPI 聚合 */
const brokerAliveCount = computed(
  () => (brokers.value ?? []).filter((b) => b.status === 'alive').length
)
const totalLag = computed(() =>
  (consumerGroups.value ?? []).reduce((s, g) => s + g.lag, 0)
)

/* ------------------------------ 创建 Topic ------------------------------ */

const createTopicDialogVisible = ref(false)
const creatingTopic = ref(false)
const createTopicFormRef = ref<FormInstance>()

interface CreateTopicForm {
  name: string
  partitions: number
  replicas: number
}

const createTopicForm = reactive<CreateTopicForm>({
  name: '',
  partitions: 3,
  replicas: 1
})

const createTopicRules: FormRules = {
  name: [{ required: true, message: '请输入 Topic 名称', trigger: 'blur' }],
  partitions: [{ required: true, message: '请设置分区数', trigger: 'change' }],
  replicas: [{ required: true, message: '请设置副本因子', trigger: 'change' }]
}

/** 打开创建 Topic 弹窗 */
function openCreateTopicDialog() {
  resetCreateTopicForm()
  createTopicDialogVisible.value = true
}

/** 重置创建 Topic 表单 */
function resetCreateTopicForm() {
  createTopicForm.name = ''
  createTopicForm.partitions = 3
  createTopicForm.replicas = 1
  createTopicFormRef.value?.clearValidate()
}

/** 提交创建 Topic */
async function handleCreateTopic() {
  if (!createTopicFormRef.value) return
  await createTopicFormRef.value.validate(async (valid) => {
    if (!valid) return
    creatingTopic.value = true
    try {
      await engineApi.createKafkaTopic(selectedClusterId.value, {
        name: createTopicForm.name,
        partitions: createTopicForm.partitions,
        replicas: createTopicForm.replicas
      })
      ElMessage.success('Topic 创建成功')
      createTopicDialogVisible.value = false
      await loadTopics()
    } catch {
      // 拦截器已提示
    } finally {
      creatingTopic.value = false
    }
  })
}

/** 删除 Topic */
async function handleDeleteTopic(row: Topic) {
  try {
    await ElMessageBox.confirm(
      `确认删除 Topic「${row.name}」？该操作不可恢复。`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
    await engineApi.deleteKafkaTopic(selectedClusterId.value, row.name)
    ElMessage.success('Topic 已删除')
    await loadTopics()
  } catch {
    // 用户取消或删除失败
  }
}

/* ------------------------------ 消息采样 ------------------------------ */

const sampleDialogVisible = ref(false)
const sampling = ref(false)
const currentSampleTopic = ref<string>('')
const sampleMessages = ref<KafkaMessage[]>([])

/** 打开采样弹窗 */
async function openSampleDialog(row: Topic) {
  currentSampleTopic.value = row.name
  sampleDialogVisible.value = true
  sampling.value = true
  sampleMessages.value = []
  try {
    sampleMessages.value = await engineApi.sampleKafkaMessages(
      selectedClusterId.value,
      row.name,
      100
    )
  } catch {
    // 拦截器已提示
  } finally {
    sampling.value = false
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

/** Broker 状态 → tag 类型 */
function brokerStatusTagType(status: string): 'success' | 'danger' | 'info' {
  if (status === 'alive') return 'success'
  if (status === 'dead') return 'danger'
  return 'info'
}

/** 消费组状态 → tag 类型 */
function groupStatusTagType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  const s = status.toUpperCase()
  if (s === 'STABLE') return 'success'
  if (s === 'PREPARING_REBALANCE' || s === 'COMPLETING_REBALANCE') return 'warning'
  if (s === 'DEAD') return 'danger'
  return 'info'
}

/** 字节格式化 */
function formatBytes(bytes?: number): string {
  if (!bytes && bytes !== 0) return '--'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}

/* ------------------------------ 生命周期 ------------------------------ */

let timer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  void reloadClusters()
  // 15s 轮询刷新
  timer = setInterval(() => void reloadCurrent(), 15000)
})

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})
</script>

<style scoped>
.eng-kafka-page {
  padding: 0;
}
.sub {
  color: #717a80;
  font-size: 13px;
  margin-bottom: 16px;
}
.grid {
  display: grid;
  gap: 14px;
}
.grid.g4 {
  grid-template-columns: repeat(4, 1fr);
}
@media (max-width: 1100px) {
  .grid.g4 {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 720px) {
  .grid.g4 {
    grid-template-columns: 1fr;
  }
}
.card {
  border: 1px solid #e4e8ea;
  border-radius: 10px;
  padding: 16px;
  background: #fff;
}
.card h3 {
  font-size: 13px;
  font-weight: 600;
  color: #717a80;
  margin: 0 0 8px;
}
.kpi {
  font-size: 28px;
  font-weight: 700;
  color: #232a2e;
  line-height: 1.2;
}
.kpi.s {
  color: #2f9e6f;
}
.kpi.d {
  color: #c0504d;
}
.meta {
  font-size: 12px;
  color: #717a80;
  margin-top: 6px;
}
.page-card {
  border: 1px solid #e4e8ea;
  border-radius: 10px;
}
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.toolbar .spacer {
  flex: 1;
}
.main-tabs {
  margin-left: 8px;
}
.lag-warn {
  color: #c08a2e;
  font-weight: 600;
}
.sample-result {
  min-height: 240px;
}
.sample-meta {
  color: #717a80;
  font-size: 12px;
  margin-bottom: 12px;
}
</style>