<template>
  <div class="template-market">
    <h1>行业应用模板</h1>
    <div class="sub">
      L5.3 · 面向外部客户的预置分析模板，开箱即用。选定行业模板后，仅需绑定数据源即可获得完整分析能力。
    </div>

    <!-- 顶部操作栏：分类筛选 + 搜索 -->
    <el-card shadow="never" class="page-card">
      <div class="toolbar">
        <el-radio-group v-model="filterIndustry" @change="handleFilter">
          <el-radio-button label="">全部</el-radio-button>
          <el-radio-button
            v-for="cat in categories"
            :key="cat.industry"
            :label="cat.industry"
          >
            {{ cat.name }} ({{ cat.count }})
          </el-radio-button>
        </el-radio-group>
        <div class="spacer"></div>
        <el-input
          v-model="searchKeyword"
          placeholder="按名称/描述/标签搜索"
          clearable
          style="width: 260px"
          @keyup.enter="handleFilter"
          @clear="handleFilter"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button :icon="Refresh" circle @click="loadAll" />
      </div>

      <!-- 模板卡片网格 -->
      <div v-loading="loading" class="template-grid">
        <el-empty v-if="!loading && filteredTemplates.length === 0" description="暂无匹配模板" />
        <el-card
          v-for="tpl in filteredTemplates"
          :key="tpl.id"
          shadow="hover"
          class="template-card"
          @click="openDetail(tpl.id)"
        >
          <div class="card-header">
            <span class="card-icon">{{ tpl.icon }}</span>
            <div class="card-title-wrap">
              <div class="card-title">{{ tpl.name }}</div>
              <div class="card-id">{{ tpl.id }}</div>
            </div>
            <el-tag :type="industryTagType(tpl.industry)" effect="light" size="small">
              {{ industryLabel(tpl.industry) }}
            </el-tag>
          </div>
          <div class="card-desc">{{ tpl.description }}</div>
          <div class="card-tags">
            <el-tag
              v-for="tag in tpl.tags.slice(0, 4)"
              :key="tag"
              type="info"
              effect="plain"
              size="small"
            >
              {{ tag }}
            </el-tag>
          </div>
          <div class="card-footer">
            <span class="meta-item">
              <el-icon><Download /></el-icon> {{ tpl.installCount }} 次安装
            </span>
            <span class="meta-item">
              <el-icon><Star /></el-icon> {{ tpl.rating.toFixed(1) }}
            </span>
            <span class="meta-item">v{{ tpl.version }}</span>
          </div>
        </el-card>
      </div>
    </el-card>

    <!-- 模板详情弹窗 -->
    <el-dialog
      v-model="detailVisible"
      :title="detailTemplate ? `${detailTemplate.meta.icon} ${detailTemplate.meta.name}` : '模板详情'"
      width="900px"
      :close-on-click-modal="false"
      class="detail-dialog"
    >
      <div v-if="detailLoading" class="loading-wrap">
        <el-skeleton :rows="8" animated />
      </div>
      <template v-else-if="detailTemplate">
        <!-- 元信息区 -->
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="模板 ID">{{ detailTemplate.meta.id }}</el-descriptions-item>
          <el-descriptions-item label="行业">
            {{ industryLabel(detailTemplate.meta.industry) }}
          </el-descriptions-item>
          <el-descriptions-item label="版本">v{{ detailTemplate.meta.version }}</el-descriptions-item>
          <el-descriptions-item label="安装次数">
            {{ detailTemplate.meta.installCount }}
          </el-descriptions-item>
          <el-descriptions-item label="评分">
            {{ detailTemplate.meta.rating.toFixed(1) }} / 5.0
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(detailTemplate.meta.status)" size="small">
              {{ statusLabel(detailTemplate.meta.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="描述" :span="3">
            {{ detailTemplate.meta.description }}
          </el-descriptions-item>
        </el-descriptions>

        <!-- Tab：架构图 / 数据流 / 计算逻辑 / 可视化 / 参数 / README -->
        <el-tabs v-model="activeTab" class="detail-tabs">
          <!-- 架构图 -->
          <el-tab-pane label="架构图" name="arch">
            <div class="arch-section">
              <div class="arch-stats">
                <el-statistic title="数据流节点" :value="preview?.stats.dataFlowNodes || 0" />
                <el-statistic title="计算步骤" :value="preview?.stats.computeSteps || 0" />
                <el-statistic title="可视化面板" :value="preview?.stats.visualizationPanels || 0" />
                <el-statistic title="参数数量" :value="preview?.stats.parameters || 0" />
              </div>
              <h4>数据流架构</h4>
              <div class="arch-flow">
                <div
                  v-for="node in detailTemplate.dataFlow.nodes"
                  :key="node.id"
                  class="arch-node"
                  :class="`node-${node.nodeType}`"
                >
                  <div class="node-layer">{{ node.layer || '-' }}</div>
                  <div class="node-name">{{ node.name }}</div>
                  <div class="node-type">{{ node.nodeType }}</div>
                </div>
              </div>
            </div>
          </el-tab-pane>

          <!-- 数据流 -->
          <el-tab-pane label="数据流" name="dataflow">
            <el-table :data="detailTemplate.dataFlow.nodes" border size="small">
              <el-table-column prop="id" label="节点 ID" width="160" />
              <el-table-column prop="name" label="名称" width="160" />
              <el-table-column prop="nodeType" label="类型" width="100" />
              <el-table-column prop="layer" label="分层" width="80" />
              <el-table-column prop="description" label="说明" />
              <el-table-column label="输入 → 输出" width="220">
                <template #default="{ row }">
                  <div class="io-cell">
                    <span class="io-in">{{ row.inputs.join(', ') || '-' }}</span>
                    <span class="io-arrow">→</span>
                    <span class="io-out">{{ row.outputs.join(', ') || '-' }}</span>
                  </div>
                </template>
              </el-table-column>
            </el-table>
            <div class="schedule-info">
              调度周期：<el-tag size="small">{{ detailTemplate.dataFlow.schedule || '未设置' }}</el-tag>
            </div>
          </el-tab-pane>

          <!-- 计算逻辑 -->
          <el-tab-pane label="计算逻辑" name="compute">
            <el-collapse>
              <el-collapse-item
                v-for="step in detailTemplate.computeLogic.steps"
                :key="step.id"
                :title="`${step.name}（${step.stepType}）`"
                :name="step.id"
              >
                <div class="step-desc">{{ step.description }}</div>
                <div class="step-io">
                  <span>输入: <el-tag v-for="i in step.inputs" :key="i" size="small">{{ i }}</el-tag></span>
                  <span>输出: <el-tag v-for="o in step.outputs" :key="o" type="success" size="small">{{ o }}</el-tag></span>
                </div>
                <pre class="step-code"><code>{{ step.code }}</code></pre>
              </el-collapse-item>
            </el-collapse>
          </el-tab-pane>

          <!-- 可视化 -->
          <el-tab-pane label="可视化" name="viz">
            <div class="viz-grid">
              <el-card
                v-for="panel in detailTemplate.visualization.panels"
                :key="panel.id"
                shadow="never"
                class="viz-panel-card"
              >
                <div class="panel-title">
                  <el-icon><DataLine /></el-icon>
                  {{ panel.title }}
                </div>
                <div class="panel-meta">
                  <el-tag size="small">{{ panel.chartType }}</el-tag>
                  <span class="panel-size">{{ panel.width }}×{{ panel.height }}</span>
                </div>
                <div class="panel-desc">{{ panel.description }}</div>
              </el-card>
            </div>
          </el-tab-pane>

          <!-- 参数配置 -->
          <el-tab-pane label="参数配置" name="params">
            <el-table :data="detailTemplate.parameters" border size="small">
              <el-table-column prop="name" label="参数名" width="240" />
              <el-table-column prop="type" label="类型" width="100">
                <template #default="{ row }">
                  <el-tag size="small" :type="paramTypeTag(row.type)">{{ row.type }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="必填" width="70" align="center">
                <template #default="{ row }">
                  <el-icon v-if="row.required" color="#67c23a"><Check /></el-icon>
                  <span v-else>-</span>
                </template>
              </el-table-column>
              <el-table-column label="默认值" width="140">
                <template #default="{ row }">
                  {{ row.defaultValue !== null ? String(row.defaultValue) : '-' }}
                </template>
              </el-table-column>
              <el-table-column prop="description" label="说明" />
            </el-table>
          </el-tab-pane>

          <!-- README -->
          <el-tab-pane label="README" name="readme">
            <pre class="readme-content">{{ detailTemplate.readme }}</pre>
          </el-tab-pane>
        </el-tabs>
      </template>

      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button type="primary" :icon="Cpu" @click="openDeployDialog">一键部署</el-button>
      </template>
    </el-dialog>

    <!-- 部署弹窗 -->
    <el-dialog
      v-model="deployVisible"
      title="部署模板"
      width="640px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="deployFormRef"
        :model="deployForm"
        :rules="deployRules"
        label-width="120px"
        label-position="right"
      >
        <el-form-item label="模板">
          <el-input :value="detailTemplate?.meta.name" disabled />
        </el-form-item>
        <el-form-item label="租户 ID" prop="tenantId">
          <el-input v-model="deployForm.tenantId" placeholder="如 tenant-001" />
        </el-form-item>
        <el-form-item label="Release 名称" prop="releaseName">
          <el-input v-model="deployForm.releaseName" placeholder="如 my-risk-scorecard" />
        </el-form-item>
        <el-form-item label="Namespace">
          <el-input
            v-model="deployForm.namespace"
            placeholder="留空则使用 tenant-{tenantId}"
          />
        </el-form-item>
        <el-divider content-position="left">参数取值</el-divider>
        <el-form-item
          v-for="param in deployableParams"
          :key="param.name"
          :label="param.name"
          :prop="`values.${param.name}`"
          :rules="param.required ? [{ required: true, message: '必填', trigger: 'blur' }] : []"
        >
          <el-select
            v-if="param.type === 'enum' && param.enumOptions"
            v-model="deployForm.values[param.name]"
            placeholder="选择"
            style="width: 100%"
          >
            <el-option v-for="opt in param.enumOptions" :key="opt" :label="opt" :value="opt" />
          </el-select>
          <el-switch
            v-else-if="param.type === 'boolean'"
            v-model="deployForm.values[param.name]"
          />
          <el-input-number
            v-else-if="param.type === 'integer'"
            v-model="deployForm.values[param.name]"
            controls-position="right"
            style="width: 100%"
          />
          <el-input-number
            v-else-if="param.type === 'float'"
            v-model="deployForm.values[param.name]"
            :step="0.01"
            controls-position="right"
            style="width: 100%"
          />
          <el-input
            v-else
            v-model="deployForm.values[param.name]"
            :placeholder="param.placeholder || param.description"
          />
          <div class="param-hint">{{ param.description }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deployVisible = false">取消</el-button>
        <el-button type="primary" :loading="deploying" @click="handleDeploy">
          确认部署
        </el-button>
      </template>
    </el-dialog>

    <!-- 部署结果弹窗 -->
    <el-dialog v-model="resultVisible" title="部署结果" width="540px">
      <div v-if="deployResult" class="deploy-result">
        <el-result
          :icon="deployResult.status === 'running' ? 'success' : 'warning'"
          :title="statusLabel(deployResult.status)"
          :sub-title="`部署 ID: ${deployResult.deploymentId}`"
        />
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="模板">{{ deployResult.templateId }}</el-descriptions-item>
          <el-descriptions-item label="Release">{{ deployResult.releaseName }}</el-descriptions-item>
          <el-descriptions-item label="Namespace">{{ deployResult.namespace }}</el-descriptions-item>
          <el-descriptions-item label="作业 ID">{{ deployResult.jobRunId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="仪表盘快照">
            <el-link v-if="deployResult.dashboardSnapshotUrl" type="primary" :href="deployResult.dashboardSnapshotUrl" target="_blank">
              查看快照
            </el-link>
            <span v-else>-</span>
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <template #footer>
        <el-button type="primary" @click="resultVisible = false">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import {
  Refresh,
  Search,
  Download,
  Star,
  Cpu,
  Check,
  DataLine
} from '@element-plus/icons-vue'
import * as templateApi from '@/api/template'
import type {
  TemplateMeta,
  Template,
  TemplatePreview,
  TemplateCategory,
  Industry,
  TemplateStatus,
  DeploymentRequest,
  DeploymentRecord,
  DeploymentStatus
} from '@/api/template'

/* ------------------------------ 列表与分类 ------------------------------ */

const loading = ref(false)
const templateList = ref<TemplateMeta[]>([])
const categories = ref<TemplateCategory[]>([])
const filterIndustry = ref<Industry | ''>('')
const searchKeyword = ref('')

/** 拉取模板列表 + 分类 */
async function loadAll() {
  loading.value = true
  try {
    const [tpls, cats] = await Promise.all([
      templateApi.listTemplates(),
      templateApi.listCategories()
    ])
    templateList.value = tpls
    categories.value = cats
  } catch {
    ElMessage.error('模板列表加载失败')
  } finally {
    loading.value = false
  }
}

/** 过滤后的模板列表 */
const filteredTemplates = computed(() => {
  let list = templateList.value
  if (filterIndustry.value) {
    list = list.filter((t) => t.industry === filterIndustry.value)
  }
  if (searchKeyword.value) {
    const kw = searchKeyword.value.toLowerCase()
    list = list.filter(
      (t) =>
        t.name.toLowerCase().includes(kw) ||
        t.description.toLowerCase().includes(kw) ||
        t.tags.some((tag) => tag.toLowerCase().includes(kw))
    )
  }
  return list
})

/** 触发过滤（按行业/关键字） */
function handleFilter() {
  // filteredTemplates 是 computed，自动响应；此函数仅为输入事件占位
}

/* ------------------------------ 模板详情 ------------------------------ */

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailTemplate = ref<Template | null>(null)
const preview = ref<TemplatePreview | null>(null)
const activeTab = ref('arch')

/** 打开模板详情 */
async function openDetail(id: string) {
  detailVisible.value = true
  detailLoading.value = true
  activeTab.value = 'arch'
  detailTemplate.value = null
  preview.value = null
  try {
    const [tpl, prev] = await Promise.all([
      templateApi.getTemplate(id),
      templateApi.previewTemplate(id)
    ])
    detailTemplate.value = tpl
    preview.value = prev
  } catch {
    ElMessage.error('模板详情加载失败')
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

/* ------------------------------ 部署 ------------------------------ */

const deployVisible = ref(false)
const deploying = ref(false)
const deployFormRef = ref<FormInstance>()
const deployForm = reactive<{
  tenantId: string
  releaseName: string
  namespace: string
  values: Record<string, unknown>
}>({
  tenantId: '',
  releaseName: '',
  namespace: '',
  values: {}
})

const deployRules: FormRules = {
  tenantId: [{ required: true, message: '请输入租户 ID', trigger: 'blur' }],
  releaseName: [{ required: true, message: '请输入 Release 名称', trigger: 'blur' }]
}

/** 可部署参数（排除有默认值的非必填项以简化表单） */
const deployableParams = computed(() => {
  if (!detailTemplate.value) return []
  return detailTemplate.value.parameters
})

/** 打开部署弹窗 */
function openDeployDialog() {
  if (!detailTemplate.value) return
  deployForm.tenantId = ''
  deployForm.releaseName = ''
  deployForm.namespace = ''
  deployForm.values = {}
  // 预填默认值
  for (const p of detailTemplate.value.parameters) {
    if (p.defaultValue !== null && p.defaultValue !== undefined) {
      deployForm.values[p.name] = p.defaultValue
    }
  }
  deployVisible.value = true
}

/** 提交部署 */
async function handleDeploy() {
  if (!deployFormRef.value || !detailTemplate.value) return
  const templateId = detailTemplate.value.meta.id
  await deployFormRef.value.validate(async (valid) => {
    if (!valid) return
    deploying.value = true
    try {
      const req: DeploymentRequest = {
        tenantId: deployForm.tenantId,
        releaseName: deployForm.releaseName,
        namespace: deployForm.namespace || undefined,
        values: deployForm.values,
        datasourceBindings: []
      }
      const result = await templateApi.deployTemplate(templateId, req)
      deployVisible.value = false
      deployResult.value = result
      resultVisible.value = true
      ElMessage.success('模板部署成功')
    } catch {
      // 错误提示由拦截器统一处理
    } finally {
      deploying.value = false
    }
  })
}

/* ------------------------------ 部署结果 ------------------------------ */

const resultVisible = ref(false)
const deployResult = ref<DeploymentRecord | null>(null)

/* ------------------------------ 标签辅助 ------------------------------ */

const INDUSTRY_LABELS: Record<Industry, string> = {
  finance: '金融',
  retail: '零售',
  manufacturing: '制造',
  government: '政务',
  iot: '物联网'
}

function industryLabel(ind: Industry): string {
  return INDUSTRY_LABELS[ind] || ind
}

function industryTagType(ind: Industry): 'primary' | 'success' | 'warning' | 'info' | 'danger' {
  const map: Record<Industry, 'primary' | 'success' | 'warning' | 'info' | 'danger'> = {
    finance: 'warning',
    retail: 'success',
    manufacturing: 'primary',
    government: 'info',
    iot: 'danger'
  }
  return map[ind] || 'info'
}

const STATUS_LABELS: Record<TemplateStatus, string> = {
  dev: '开发中',
  review: '审核中',
  catalog: '已上架',
  deprecated: '已下架'
}

function statusLabel(status: TemplateStatus | DeploymentStatus): string {
  const depMap: Record<DeploymentStatus, string> = {
    pending: '等待中',
    installing: '安装中',
    instantiating: '实例化中',
    running: '运行中',
    failed: '失败',
    stopped: '已停止'
  }
  return (
    STATUS_LABELS[status as TemplateStatus] ||
    depMap[status as DeploymentStatus] ||
    status
  )
}

function statusTagType(status: TemplateStatus): 'success' | 'warning' | 'info' {
  const map: Record<TemplateStatus, 'success' | 'warning' | 'info'> = {
    dev: 'info',
    review: 'warning',
    catalog: 'success',
    deprecated: 'info'
  }
  return map[status] || 'info'
}

function paramTypeTag(type: string): 'primary' | 'success' | 'warning' | 'info' | 'danger' {
  const map: Record<string, 'primary' | 'success' | 'warning' | 'info' | 'danger'> = {
    string: 'info',
    integer: 'primary',
    float: 'primary',
    boolean: 'success',
    enum: 'warning',
    datasource: 'danger'
  }
  return map[type] || 'info'
}

/* ------------------------------ 初始化 ------------------------------ */

onMounted(() => {
  loadAll()
})
</script>

<style scoped>
.template-market {
  padding: 0;
}
.sub {
  color: #717a80;
  font-size: 13px;
  margin-bottom: 16px;
}
.page-card {
  border: 1px solid #e4e8ea;
  border-radius: 10px;
}
.toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 20px;
  flex-wrap: wrap;
}
.toolbar .spacer {
  flex: 1;
}

/* 模板卡片网格 */
.template-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 16px;
  min-height: 200px;
}
.template-card {
  cursor: pointer;
  transition: transform 0.2s, box-shadow 0.2s;
  border-radius: 8px;
}
.template-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
}
.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.card-icon {
  font-size: 28px;
  line-height: 1;
}
.card-title-wrap {
  flex: 1;
  min-width: 0;
}
.card-title {
  font-size: 16px;
  font-weight: 600;
  color: #1f2329;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-id {
  font-size: 12px;
  color: #8a919c;
  margin-top: 2px;
}
.card-desc {
  font-size: 13px;
  color: #6b7280;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 40px;
}
.card-tags {
  margin: 10px 0;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.card-footer {
  display: flex;
  gap: 14px;
  font-size: 12px;
  color: #8a919c;
  padding-top: 8px;
  border-top: 1px solid #f0f2f5;
}
.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

/* 详情弹窗 */
.detail-dialog :deep(.el-dialog__body) {
  max-height: 70vh;
  overflow-y: auto;
}
.loading-wrap {
  padding: 20px;
}
.detail-tabs {
  margin-top: 16px;
}

/* 架构图 */
.arch-section {
  padding: 8px 0;
}
.arch-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 20px;
}
.arch-flow {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  padding: 12px;
  background: #f7f9fa;
  border-radius: 6px;
}
.arch-node {
  background: #fff;
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  padding: 8px 12px;
  min-width: 140px;
  text-align: center;
}
.node-source {
  border-left: 3px solid #67c23a;
}
.node-transform {
  border-left: 3px solid #409eff;
}
.node-sink {
  border-left: 3px solid #e6a23c;
}
.node-layer {
  font-size: 11px;
  color: #8a919c;
  text-transform: uppercase;
}
.node-name {
  font-size: 13px;
  font-weight: 600;
  margin: 4px 0;
}
.node-type {
  font-size: 11px;
  color: #6b7280;
}

/* 数据流 IO 单元格 */
.io-cell {
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: 12px;
}
.io-in {
  color: #8a919c;
}
.io-arrow {
  color: #c0c4cc;
}
.io-out {
  color: #67c23a;
}
.schedule-info {
  margin-top: 10px;
  font-size: 13px;
  color: #6b7280;
}

/* 计算逻辑步骤 */
.step-desc {
  color: #6b7280;
  margin-bottom: 6px;
}
.step-io {
  display: flex;
  gap: 16px;
  margin-bottom: 8px;
  font-size: 13px;
}
.step-io :deep(.el-tag) {
  margin-right: 4px;
}
.step-code {
  background: #1f2329;
  color: #e0e0e0;
  padding: 12px;
  border-radius: 6px;
  font-size: 12px;
  overflow-x: auto;
  font-family: 'Consolas', 'Monaco', monospace;
}

/* 可视化面板 */
.viz-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 12px;
}
.viz-panel-card {
  border-radius: 6px;
}
.panel-title {
  font-size: 14px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}
.panel-meta {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 6px;
}
.panel-size {
  font-size: 12px;
  color: #8a919c;
}
.panel-desc {
  font-size: 12px;
  color: #6b7280;
}

/* README */
.readme-content {
  background: #f7f9fa;
  padding: 16px;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  font-family: 'Consolas', 'Monaco', monospace;
}

/* 部署表单参数提示 */
.param-hint {
  font-size: 12px;
  color: #8a919c;
  margin-top: 2px;
  line-height: 1.4;
}

/* 部署结果 */
.deploy-result {
  padding: 8px 0;
}
</style>