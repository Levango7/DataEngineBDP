<template>
  <div class="eng-kafka-page">
    <h1>{{ t('engines.kafka.title') }}</h1>
    <div class="sub">{{ t('engines.kafka.page.subtitle') }}</div>

    <!-- KPI 卡片区：三态 loading / error / data -->
    <div class="grid g4">
      <template v-if="clustersLoading">
        <div v-for="i in 4" :key="i" class="card">
          <h3>{{ t('engines.kpi.loading') }}</h3>
          <div class="kpi">--</div>
          <div class="meta">{{ t('engines.kpi.loadingMeta') }}</div>
        </div>
      </template>
      <template v-else-if="clustersError">
        <div class="card" style="grid-column: span 4">
          <h3>{{ t('engines.kpi.loadFailed') }}</h3>
          <div class="meta" style="color: var(--muted)">
            {{ clustersError.message }}，
            <a href="javascript:void(0)" @click="reloadClusters">{{ t('engines.kpi.loadFailedRetry') }}</a>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>{{ t('engines.kafka.kpi.clusterCount') }}</h3>
          <div class="kpi">{{ clusters?.length ?? 0 }}</div>
          <div class="meta">{{ t('engines.kafka.kpi.clusterMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('engines.kafka.kpi.brokerCount') }}</h3>
          <div class="kpi">{{ brokers?.length ?? 0 }}</div>
          <div class="meta">{{ t('engines.kafka.kpi.brokerAlive', { count: brokerAliveCount }) }}</div>
        </div>
        <div class="card">
          <h3>{{ t('engines.kafka.kpi.topicCount') }}</h3>
          <div class="kpi s">{{ topics?.length ?? 0 }}</div>
          <div class="meta">{{ t('engines.kafka.kpi.topicMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('engines.kafka.kpi.groupCount') }}</h3>
          <div class="kpi">{{ consumerGroups?.length ?? 0 }}</div>
          <div class="meta">{{ t('engines.kafka.kpi.totalLag', { count: totalLag }) }}</div>
        </div>
      </template>
    </div>

    <!-- 主内容区：集群选择 + Tabs -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <div class="toolbar">
        <el-select
          v-model="selectedClusterId"
          :placeholder="t('engines.kafka.select.placeholder')"
          style="width: 240px"
          @change="handleClusterChange"
        >
          <el-option v-for="c in clusters ?? []" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-tabs v-model="activeTab" type="card" class="main-tabs">
          <el-tab-pane :label="t('engines.kafka.tabs.brokers')" name="brokers" />
          <el-tab-pane :label="t('engines.kafka.tabs.topics')" name="topics" />
          <el-tab-pane :label="t('engines.kafka.tabs.groups')" name="groups" />
        </el-tabs>
        <div class="spacer"></div>
        <el-button
          v-if="activeTab === 'topics'"
          type="primary"
          :disabled="!selectedClusterId"
          @click="openCreateTopicDialog"
        >
          {{ t('engines.kafka.create.createTopic') }}
        </el-button>
        <el-button :icon="Refresh" circle :aria-label="t('engines.kafka.select.refreshAria')" @click="reloadCurrent" />
      </div>

      <!-- Tab1 Broker 列表 -->
      <template v-if="activeTab === 'brokers'">
        <el-table
          v-loading="brokersLoading"
          :data="brokers ?? []"
          stripe
          border
          style="width: 100%"
          :empty-text="brokersError ? t('engines.kafka.loadFailed') : t('engines.kafka.broker.empty')"
        >
          <el-table-column prop="id" :label="t('engines.kafka.broker.columns.id')" width="100" />
          <el-table-column prop="host" :label="t('engines.kafka.broker.columns.host')" min-width="180" />
          <el-table-column prop="port" :label="t('engines.kafka.broker.columns.port')" width="100" />
          <el-table-column prop="version" :label="t('engines.kafka.broker.columns.version')" width="140" />
          <el-table-column :label="t('engines.kafka.broker.columns.status')" width="120">
            <template #default="{ row }">
              <el-tag :type="brokerStatusTagType(row.status)" effect="light" size="small">
                {{ t(`engines.kafka.broker.status.${row.status}`, row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('engines.kafka.broker.columns.leaderCount')" width="140" align="right">
            <template #default="{ row }">{{ row.partitionLeaderCount ?? '--' }}</template>
          </el-table-column>
        </el-table>
      </template>

      <!-- Tab2 Topic 列表 -->
      <template v-else-if="activeTab === 'topics'">
        <div class="search-bar">
          <el-input
            v-model="topicKeyword"
            :placeholder="t('engines.kafka.topic.searchPlaceholder')"
            clearable
            style="width: 240px"
          />
        </div>
        <el-table
          v-loading="topicsLoading"
          :data="filteredTopics"
          stripe
          border
          style="width: 100%"
          :empty-text="topicsError ? t('engines.kafka.loadFailed') : t('engines.kafka.topic.empty')"
        >
          <el-table-column prop="name" :label="t('engines.kafka.topic.columns.name')" min-width="200" />
          <el-table-column :label="t('engines.kafka.topic.columns.partitions')" width="100" align="center">
            <template #default="{ row }">{{ row.partitions }}</template>
          </el-table-column>
          <el-table-column :label="t('engines.kafka.topic.columns.replicas')" width="100" align="center">
            <template #default="{ row }">{{ row.replicas }}</template>
          </el-table-column>
          <el-table-column :label="t('engines.kafka.topic.columns.messageCount')" width="160" align="right">
            <template #default="{ row }">{{ row.messageCount.toLocaleString() }}</template>
          </el-table-column>
          <el-table-column :label="t('engines.kafka.topic.columns.size')" width="120" align="right">
            <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
          </el-table-column>
          <el-table-column :label="t('engines.kafka.topic.columns.actions')" width="220" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openSampleDialog(row)">{{ t('engines.kafka.topic.actions.sample') }}</el-button>
              <el-button link type="danger" @click="handleDeleteTopic(row)">{{ t('engines.kafka.topic.actions.delete') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <!-- Tab3 消费组列表 -->
      <template v-else>
        <div class="search-bar">
          <el-input
            v-model="groupKeyword"
            :placeholder="t('engines.kafka.group.searchPlaceholder')"
            clearable
            style="width: 240px"
          />
        </div>
        <el-table
          v-loading="groupsLoading"
          :data="filteredGroups"
          stripe
          border
          style="width: 100%"
          :empty-text="groupsError ? t('engines.kafka.loadFailed') : t('engines.kafka.group.empty')"
        >
          <el-table-column prop="groupId" :label="t('engines.kafka.group.columns.name')" min-width="200" />
          <el-table-column prop="engine" :label="t('engines.kafka.group.columns.engine')" width="160" />
          <el-table-column :label="t('engines.kafka.group.columns.lag')" width="140" align="right">
            <template #default="{ row }">
              <span :class="{ 'lag-warn': row.lag > 1000 }">{{ row.lag.toLocaleString() }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="t('engines.kafka.group.columns.status')" width="160">
            <template #default="{ row }">
              <el-tag :type="groupStatusTagType(row.status)" effect="light" size="small">
                {{ t(`engines.kafka.group.status.${row.status}`, row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('engines.kafka.group.columns.members')" width="100" align="center">
            <template #default="{ row }">{{ row.memberCount ?? '--' }}</template>
          </el-table-column>
          <el-table-column :label="t('engines.kafka.group.columns.topics')" width="120" align="center">
            <template #default="{ row }">{{ row.topicCount ?? '--' }}</template>
          </el-table-column>
        </el-table>
      </template>
    </el-card>

    <!-- 创建 Topic 弹窗 -->
    <el-dialog
      v-model="createTopicDialogVisible"
      :title="t('engines.kafka.create.title')"
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
        <el-form-item :label="t('engines.kafka.create.fields.name')" prop="name">
          <el-input v-model="createTopicForm.name" :placeholder="t('engines.kafka.create.fields.namePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('engines.kafka.create.fields.partitions')" prop="partitions">
          <el-input-number v-model="createTopicForm.partitions" :min="1" :max="1000" />
        </el-form-item>
        <el-form-item :label="t('engines.kafka.create.fields.replicas')" prop="replicas">
          <el-input-number v-model="createTopicForm.replicas" :min="1" :max="10" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createTopicDialogVisible = false">{{ t('engines.kafka.create.actions.cancel') }}</el-button>
        <el-button type="primary" :loading="creatingTopic" @click="handleCreateTopic">
          {{ t('engines.kafka.create.actions.create') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 消息采样弹窗 -->
    <el-dialog
      v-model="sampleDialogVisible"
      :title="t('engines.kafka.sample.title', { name: currentSampleTopic ?? '' })"
      width="800px"
      :close-on-click-modal="true"
    >
      <div v-loading="sampling" class="sample-result">
        <template v-if="sampleMessages.length > 0">
          <div class="sample-meta">{{ t('engines.kafka.sample.totalFmt', { count: sampleMessages.length }) }}</div>
          <el-table
            :data="sampleMessages"
            stripe
            border
            size="small"
            style="width: 100%"
            max-height="420"
          >
            <el-table-column :label="t('engines.kafka.sample.columns.partition')" width="80" align="center">
              <template #default="{ row }">{{ row.partition }}</template>
            </el-table-column>
            <el-table-column :label="t('engines.kafka.sample.columns.offset')" width="120" align="right">
              <template #default="{ row }">{{ row.offset }}</template>
            </el-table-column>
            <el-table-column prop="timestamp" :label="t('engines.kafka.sample.columns.timestamp')" width="180" />
            <el-table-column prop="key" :label="t('engines.kafka.sample.columns.key')" width="160" show-overflow-tooltip />
            <el-table-column prop="value" :label="t('engines.kafka.sample.columns.value')" min-width="240" show-overflow-tooltip />
          </el-table>
        </template>
        <el-empty v-else-if="!sampling" :description="t('engines.kafka.sample.empty')" />
      </div>
      <template #footer>
        <el-button @click="sampleDialogVisible = false">{{ t('engines.kafka.sample.close') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as engineApi from '@/api/engine'
import type { KafkaCluster, Broker, Topic, ConsumerGroup, KafkaMessage } from '@/api/engine'

const { t, te } = useI18n()

/* ------------------------------ 集群列表 ------------------------------ */

const {
  data: clusters,
  loading: clustersLoading,
  error: clustersError,
  execute: reloadClusters
} = useApi<KafkaCluster[]>(() => engineApi.getKafkaClusters())

const selectedClusterId = ref<string>('')
const activeTab = ref<'brokers' | 'topics' | 'groups'>('brokers')

/** Topic 搜索关键字 */
const topicKeyword = ref<string>('')
/** 消费组搜索关键字 */
const groupKeyword = ref<string>('')

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
const totalLag = computed(() => (consumerGroups.value ?? []).reduce((s, g) => s + g.lag, 0))

/** Topic 列表按关键字过滤 */
const filteredTopics = computed(() => {
  const list = topics.value ?? []
  const kw = topicKeyword.value.trim().toLowerCase()
  if (!kw) return list
  return list.filter((t) => t.name.toLowerCase().includes(kw))
})

/** 消费组列表按关键字过滤 */
const filteredGroups = computed(() => {
  const list = consumerGroups.value ?? []
  const kw = groupKeyword.value.trim().toLowerCase()
  if (!kw) return list
  return list.filter((g) => g.groupId.toLowerCase().includes(kw))
})

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

const createTopicRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('engines.kafka.rules.nameRequired'), trigger: 'blur' }],
  partitions: [{ required: true, message: t('engines.kafka.rules.partitionsRequired'), trigger: 'change' }],
  replicas: [{ required: true, message: t('engines.kafka.rules.replicasRequired'), trigger: 'change' }]
}))

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
      ElMessage.success(t('engines.kafka.messages.created'))
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
    await ElMessageBox.confirm(t('engines.kafka.messages.deleteConfirm', { name: row.name }), t('engines.kafka.messages.deleteConfirmTitle'), {
      type: 'warning',
      confirmButtonText: t('engines.kafka.messages.deleteConfirmOk'),
      cancelButtonText: t('engines.kafka.messages.deleteConfirmCancel'),
      confirmButtonClass: 'el-button--danger'
    })
    await engineApi.deleteKafkaTopic(selectedClusterId.value, row.name)
    ElMessage.success(t('engines.kafka.messages.deleted'))
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
  // 10s 轮询刷新
  timer = setInterval(() => void reloadCurrent(), 10000)
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
  color: var(--ds-text-secondary);
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
  border: 1px solid var(--ds-border-default);
  border-radius: 10px;
  padding: 16px;
  background: #fff;
}
.card h3 {
  font-size: 13px;
  font-weight: 600;
  color: var(--ds-text-secondary);
  margin: 0 0 8px;
}
.kpi {
  font-size: 28px;
  font-weight: 700;
  color: var(--ds-text-primary);
  line-height: 1.2;
}
.kpi.s {
  color: var(--ds-color-success-600);
}
.kpi.d {
  color: var(--ds-color-error-600);
}
.meta {
  font-size: 12px;
  color: var(--ds-text-secondary);
  margin-top: 6px;
}
.page-card {
  border: 1px solid var(--ds-border-default);
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
.search-bar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 12px;
}
.lag-warn {
  color: var(--ds-color-warning-600);
  font-weight: 600;
}
.sample-result {
  min-height: 240px;
}
.sample-meta {
  color: var(--ds-text-secondary);
  font-size: 12px;
  margin-bottom: 12px;
}
</style>
