<template>
  <div class="dev-tag-page">
    <h1>标签画像</h1>
    <div class="sub">标签定义 · 规则计算 · 人群圈选</div>

    <!-- KPI 卡片区 -->
    <div class="grid g4">
      <template v-if="tagsLoading">
        <div v-for="i in 4" :key="i" class="card">
          <h3>加载中…</h3>
          <div class="kpi">--</div>
          <div class="meta">正在拉取数据</div>
        </div>
      </template>
      <template v-else-if="tagsError">
        <div class="card" style="grid-column: span 4">
          <h3>加载失败</h3>
          <div class="meta" style="color: var(--muted)">
            标签列表加载失败，
            <a href="javascript:void(0)" @click="reloadTags">重试</a>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>标签总数</h3>
          <div class="kpi">{{ kpi.total }}</div>
          <div class="meta">全部标签定义</div>
        </div>
        <div class="card">
          <h3>已计算标签</h3>
          <div class="kpi s">{{ kpi.computed }}</div>
          <div class="meta">状态为 COMPUTED</div>
        </div>
        <div class="card">
          <h3>计算中</h3>
          <div class="kpi">{{ kpi.computing }}</div>
          <div class="meta">状态为 COMPUTING</div>
        </div>
        <div class="card">
          <h3>今日圈选次数</h3>
          <div class="kpi">{{ todaySelectCount }}</div>
          <div class="meta">本次会话累计</div>
        </div>
      </template>
    </div>

    <!-- Tabs 主区 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <el-tabs v-model="activeTab" type="card">
        <!-- Tab1 标签定义 -->
        <el-tab-pane label="标签定义" name="definition">
          <div class="toolbar">
            <el-button type="primary" @click="openTagDialog()">+ 新建标签</el-button>
            <el-button
              type="success"
              :disabled="!selectedTagIds.length"
              :loading="batchComputing"
              @click="handleBatchCompute"
            >
              批量计算 ({{ selectedTagIds.length }})
            </el-button>
            <div class="spacer"></div>
            <el-button :icon="Refresh" circle @click="reloadTags" />
          </div>

          <el-table
            v-loading="tagsLoading"
            :data="tags"
            stripe
            border
            style="width: 100%"
            :empty-text="tagsError ? '加载失败，请重试' : '暂无标签'"
            @selection-change="handleSelectionChange"
          >
            <el-table-column type="selection" width="48" />
            <el-table-column prop="name" label="标签名" min-width="160" />
            <el-table-column prop="code" label="编码" width="160">
              <template #default="{ row }">
                <span style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px">
                  {{ row.code || '--' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="valueType" label="值类型" width="110">
              <template #default="{ row }">
                <el-tag effect="light" size="small">{{ row.valueType }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="ruleCount" label="规则数" width="100" align="center">
              <template #default="{ row }">{{ row.ruleCount ?? 0 }}</template>
            </el-table-column>
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag :type="tagStatusType(row.status)" effect="light" size="small">
                  {{ tagStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="lastComputedAt" label="最近计算" width="180">
              <template #default="{ row }">{{ row.lastComputedAt || '--' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="280" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openRuleDialog(row)">规则</el-button>
                <el-button
                  link
                  type="success"
                  :loading="computingId === row.id"
                  @click="handleCompute(row)"
                >
                  计算
                </el-button>
                <el-button link type="primary" @click="openTagDialog(row)">编辑</el-button>
                <el-button link type="danger" @click="handleDeleteTag(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- Tab2 用户画像 -->
        <el-tab-pane label="用户画像" name="profile">
          <div class="toolbar">
            <el-input
              v-model="userIdInput"
              placeholder="输入用户 ID 查询画像"
              clearable
              style="width: 320px"
              @keyup.enter="handleQueryProfile"
            />
            <el-button
              type="primary"
              :loading="profileLoading"
              :disabled="!userIdInput.trim()"
              @click="handleQueryProfile"
            >
              查询
            </el-button>
          </div>

          <div v-if="profileLoading" class="meta" style="padding: 16px">加载中…</div>
          <div v-else-if="profileError" class="meta" style="padding: 16px; color: var(--muted)">
            画像加载失败，
            <a href="javascript:void(0)" @click="handleQueryProfile">重试</a>
          </div>
          <el-empty v-else-if="!profile" description="请输入用户 ID 查询画像" />
          <div v-else class="profile-panel">
            <el-descriptions :column="2" border>
              <el-descriptions-item label="用户 ID">{{ profile.userId }}</el-descriptions-item>
              <el-descriptions-item label="用户名">
                {{ profile.username || '--' }}
              </el-descriptions-item>
              <el-descriptions-item label="更新时间">
                {{ profile.updatedAt || '--' }}
              </el-descriptions-item>
              <el-descriptions-item label="标签数">{{ profile.tags.length }}</el-descriptions-item>
            </el-descriptions>
            <h3 style="margin: 16px 0 12px">标签值列表</h3>
            <el-table :data="profile.tags" stripe border size="small">
              <el-table-column prop="tagName" label="标签名" min-width="160" />
              <el-table-column prop="valueType" label="类型" width="100" />
              <el-table-column label="值" min-width="160">
                <template #default="{ row }">
                  <span
                    v-if="row.value === null || row.value === undefined"
                    style="color: var(--muted)"
                  >
                    --
                  </span>
                  <span v-else>{{ row.value }}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-tab-pane>

        <!-- Tab3 人群圈选 -->
        <el-tab-pane label="人群圈选" name="audience">
          <el-row :gutter="16">
            <!-- 左：条件构建器 -->
            <el-col :xs="24" :md="12">
              <h3 style="margin: 0 0 12px">圈选条件</h3>
              <div v-for="(cond, idx) in conditions.tags" :key="idx" class="cond-row">
                <el-select
                  v-model="cond.tagId"
                  placeholder="选择标签"
                  filterable
                  style="width: 160px"
                >
                  <el-option v-for="t in tags" :key="t.id" :label="t.name" :value="t.id" />
                </el-select>
                <el-select v-model="cond.op" placeholder="操作" style="width: 100px">
                  <el-option label="等于" value="EQ" />
                  <el-option label="不等于" value="NE" />
                  <el-option label="包含" value="IN" />
                  <el-option label="大于" value="GT" />
                  <el-option label="小于" value="LT" />
                  <el-option label="大于等于" value="GE" />
                  <el-option label="小于等于" value="LE" />
                  <el-option label="区间" value="BETWEEN" />
                  <el-option label="模糊" value="LIKE" />
                </el-select>
                <el-input v-model="cond.value" placeholder="值" style="width: 140px" />
                <el-input
                  v-if="cond.op === 'BETWEEN'"
                  v-model="cond.value2"
                  placeholder="上限"
                  style="width: 100px"
                />
                <el-button link type="danger" @click="removeCondition(idx)">删除</el-button>
              </div>
              <el-button type="primary" plain style="margin-top: 8px" @click="addCondition">
                + 添加条件
              </el-button>

              <div style="margin-top: 16px">
                <el-button
                  type="primary"
                  :loading="selecting"
                  :disabled="!conditions.tags.length"
                  @click="handleSelectAudience"
                >
                  圈选
                </el-button>
                <el-checkbox v-model="saveAudience" style="margin-left: 12px">保存人群</el-checkbox>
                <el-input
                  v-if="saveAudience"
                  v-model="audienceName"
                  placeholder="人群名称"
                  style="width: 200px; margin-left: 8px"
                />
              </div>
            </el-col>

            <!-- 右：结果 -->
            <el-col :xs="24" :md="12">
              <h3 style="margin: 0 0 12px">圈选结果</h3>
              <div v-if="selecting" class="meta">圈选中…</div>
              <div v-else-if="selectError" class="meta" style="color: var(--muted)">
                圈选失败，请重试
              </div>
              <el-empty v-else-if="!audienceResult" description="请构建条件后执行圈选" />
              <div v-else>
                <el-descriptions :column="1" border size="small">
                  <el-descriptions-item label="人数">
                    <span class="kpi" style="font-size: 20px">{{ audienceResult.count }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item v-if="audienceResult.audienceId" label="人群 ID">
                    {{ audienceResult.audienceId }}
                  </el-descriptions-item>
                </el-descriptions>
                <h4 style="margin: 12px 0 8px">
                  用户列表（前 {{ audienceResult.users.length }} 条）
                </h4>
                <el-table
                  :data="audienceResult.users"
                  stripe
                  border
                  size="small"
                  :empty-text="'暂无用户'"
                >
                  <el-table-column prop="userId" label="用户 ID" min-width="160" />
                  <el-table-column prop="username" label="用户名" min-width="120">
                    <template #default="{ row }">{{ row.username || '--' }}</template>
                  </el-table-column>
                </el-table>
              </div>
            </el-col>
          </el-row>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 标签定义编辑弹窗 -->
    <el-dialog
      v-model="tagDialogVisible"
      :title="tagForm.id ? '编辑标签' : '新建标签'"
      width="540px"
      :close-on-click-modal="false"
      @closed="resetTagForm"
    >
      <el-form
        ref="tagFormRef"
        :model="tagForm"
        :rules="tagRules"
        label-width="100px"
        label-position="right"
      >
        <el-form-item label="标签名" prop="name">
          <el-input v-model="tagForm.name" placeholder="如 高价值用户" />
        </el-form-item>
        <el-form-item label="编码" prop="code">
          <el-input
            v-model="tagForm.code"
            placeholder="如 high_value_user"
            style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
          />
        </el-form-item>
        <el-form-item label="值类型" prop="valueType">
          <el-select v-model="tagForm.valueType" style="width: 100%">
            <el-option label="字符串" value="STRING" />
            <el-option label="数值" value="NUMBER" />
            <el-option label="布尔" value="BOOLEAN" />
            <el-option label="枚举" value="ENUM" />
            <el-option label="日期" value="DATE" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="tagForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tagDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmitTag">
          {{ tagForm.id ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 规则编辑弹窗 -->
    <el-dialog
      v-model="ruleDialogVisible"
      :title="`标签规则 - ${currentTag?.name ?? ''}`"
      width="720px"
      :close-on-click-modal="false"
    >
      <div class="toolbar">
        <el-button type="primary" @click="openAddRuleForm">+ 添加规则</el-button>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle @click="loadRules" />
      </div>
      <el-table
        v-loading="rulesLoading"
        :data="rules"
        stripe
        border
        size="small"
        :empty-text="'暂无规则'"
      >
        <el-table-column prop="name" label="规则名" min-width="140" />
        <el-table-column prop="ruleType" label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="light">{{ row.ruleType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="80" align="center" />
        <el-table-column prop="outputValue" label="输出值" width="120">
          <template #default="{ row }">{{ row.outputValue || '--' }}</template>
        </el-table-column>
        <el-table-column prop="expression" label="表达式" min-width="200" show-overflow-tooltip />
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" @click="handleDeleteRule(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 添加规则表单 -->
      <div v-if="showAddRuleForm" class="rule-form">
        <h4 style="margin: 16px 0 8px">新增规则</h4>
        <el-form
          ref="ruleFormRef"
          :model="ruleForm"
          :rules="ruleRules"
          label-width="80px"
          label-position="right"
        >
          <el-form-item label="名称" prop="name">
            <el-input v-model="ruleForm.name" placeholder="规则名" />
          </el-form-item>
          <el-form-item label="类型" prop="ruleType">
            <el-select v-model="ruleForm.ruleType" style="width: 100%">
              <el-option label="SQL" value="SQL" />
              <el-option label="表达式" value="EXPRESSION" />
              <el-option label="查表" value="LOOKUP" />
            </el-select>
          </el-form-item>
          <el-form-item label="优先级" prop="priority">
            <el-input-number v-model="ruleForm.priority" :min="0" :max="100" />
          </el-form-item>
          <el-form-item label="输出值" prop="outputValue">
            <el-input v-model="ruleForm.outputValue" placeholder="命中时输出的标签值" />
          </el-form-item>
          <el-form-item label="表达式" prop="expression">
            <el-input
              v-model="ruleForm.expression"
              type="textarea"
              :rows="3"
              placeholder="如 SELECT 1 FROM users WHERE ..."
              style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="addingRule" @click="handleAddRule">添加</el-button>
            <el-button @click="showAddRuleForm = false">取消</el-button>
          </el-form-item>
        </el-form>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as devTagApi from '@/api/dev-tag'
import type { TagDefinition, TagRule, UserProfile, AudienceResult, TagQuery } from '@/api/dev-tag'

/* ------------------------------ 标签定义列表 ------------------------------ */

const activeTab = ref('definition')

const {
  data: tags,
  loading: tagsLoading,
  error: tagsError,
  execute: reloadTags
} = useApi<TagDefinition[]>(() => devTagApi.listTags())

const selectedTagIds = ref<string[]>([])

/** 多选变化 */
function handleSelectionChange(rows: TagDefinition[]) {
  selectedTagIds.value = rows.map((r) => r.id)
}

/** KPI 聚合 */
const kpi = computed(() => {
  const list = tags.value ?? []
  const total = list.length
  const computed = list.filter((t) => t.status === 'COMPUTED').length
  const computing = list.filter((t) => t.status === 'COMPUTING').length
  return { total, computed, computing }
})

const todaySelectCount = ref(0)

/* ------------------------------ 标签 CRUD ------------------------------ */

const tagDialogVisible = ref(false)
const submitting = ref(false)
const tagFormRef = ref<FormInstance>()

interface TagForm {
  id?: string
  name: string
  code: string
  valueType: string
  description: string
}

const tagForm = reactive<TagForm>({
  id: undefined,
  name: '',
  code: '',
  valueType: 'STRING',
  description: ''
})

const tagRules: FormRules = {
  name: [{ required: true, message: '请输入标签名', trigger: 'blur' }],
  valueType: [{ required: true, message: '请选择值类型', trigger: 'change' }]
}

/** 打开标签弹窗 */
function openTagDialog(row?: TagDefinition) {
  resetTagForm()
  if (row) {
    tagForm.id = row.id
    tagForm.name = row.name
    tagForm.code = row.code ?? ''
    tagForm.valueType = row.valueType
    tagForm.description = row.description ?? ''
  }
  tagDialogVisible.value = true
}

/** 重置标签表单 */
function resetTagForm() {
  tagForm.id = undefined
  tagForm.name = ''
  tagForm.code = ''
  tagForm.valueType = 'STRING'
  tagForm.description = ''
  tagFormRef.value?.clearValidate()
}

/** 提交标签 */
async function handleSubmitTag() {
  if (!tagFormRef.value) return
  await tagFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      if (tagForm.id) {
        // 编辑：先删除再创建（后端暂未提供 update，简化处理）
        await devTagApi.deleteTag(tagForm.id)
        await devTagApi.createTag({
          name: tagForm.name,
          code: tagForm.code || undefined,
          valueType: tagForm.valueType,
          description: tagForm.description || undefined
        })
        ElMessage.success('标签已更新')
      } else {
        await devTagApi.createTag({
          name: tagForm.name,
          code: tagForm.code || undefined,
          valueType: tagForm.valueType,
          description: tagForm.description || undefined
        })
        ElMessage.success('标签已创建')
      }
      tagDialogVisible.value = false
      await reloadTags()
    } catch {
      // 拦截器已提示
    } finally {
      submitting.value = false
    }
  })
}

/** 删除标签 */
async function handleDeleteTag(row: TagDefinition) {
  try {
    await ElMessageBox.confirm(`确认删除标签「${row.name}」？该操作不可恢复。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
    await devTagApi.deleteTag(row.id)
    ElMessage.success('标签已删除')
    await reloadTags()
  } catch {
    // 用户取消或删除失败
  }
}

/* ------------------------------ 计算 ------------------------------ */

const computingId = ref<string>('')
const batchComputing = ref(false)

/** 计算单个标签 */
async function handleCompute(row: TagDefinition) {
  computingId.value = row.id
  try {
    const result = await devTagApi.computeTag(row.id)
    ElMessage.success(
      `计算已提交，状态：${result.status}${
        result.computedCount !== undefined ? '，已计算 ' + result.computedCount + ' 用户' : ''
      }`
    )
    await reloadTags()
  } catch {
    // 拦截器已提示
  } finally {
    computingId.value = ''
  }
}

/** 批量计算 */
async function handleBatchCompute() {
  if (!selectedTagIds.value.length) return
  batchComputing.value = true
  try {
    const result = await devTagApi.batchCompute(selectedTagIds.value)
    ElMessage.success(`批量计算完成，成功 ${result.successCount} / ${selectedTagIds.value.length}`)
    await reloadTags()
  } catch {
    // 拦截器已提示
  } finally {
    batchComputing.value = false
  }
}

/* ------------------------------ 规则管理 ------------------------------ */

const ruleDialogVisible = ref(false)
const rulesLoading = ref(false)
const rules = ref<TagRule[]>([])
const currentTag = ref<TagDefinition | null>(null)
const showAddRuleForm = ref(false)
const addingRule = ref(false)
const ruleFormRef = ref<FormInstance>()

interface RuleForm {
  name: string
  ruleType: string
  expression: string
  priority: number
  outputValue: string
}

const ruleForm = reactive<RuleForm>({
  name: '',
  ruleType: 'SQL',
  expression: '',
  priority: 0,
  outputValue: ''
})

const ruleRules: FormRules = {
  name: [{ required: true, message: '请输入规则名', trigger: 'blur' }],
  ruleType: [{ required: true, message: '请选择规则类型', trigger: 'change' }],
  expression: [{ required: true, message: '请输入表达式', trigger: 'blur' }]
}

/** 打开规则弹窗 */
function openRuleDialog(row: TagDefinition) {
  currentTag.value = row
  ruleDialogVisible.value = true
  showAddRuleForm.value = false
  void loadRules()
}

/** 加载规则列表 */
async function loadRules() {
  if (!currentTag.value) return
  rulesLoading.value = true
  try {
    rules.value = await devTagApi.listTagRules(currentTag.value.id)
  } catch {
    rules.value = []
  } finally {
    rulesLoading.value = false
  }
}

/** 打开添加规则表单 */
function openAddRuleForm() {
  ruleForm.name = ''
  ruleForm.ruleType = 'SQL'
  ruleForm.expression = ''
  ruleForm.priority = 0
  ruleForm.outputValue = ''
  showAddRuleForm.value = true
}

/** 添加规则 */
async function handleAddRule() {
  if (!ruleFormRef.value || !currentTag.value) return
  const tag = currentTag.value
  await ruleFormRef.value.validate(async (valid) => {
    if (!valid) return
    addingRule.value = true
    try {
      await devTagApi.addTagRule(tag.id, {
        name: ruleForm.name,
        ruleType: ruleForm.ruleType,
        expression: ruleForm.expression,
        priority: ruleForm.priority,
        outputValue: ruleForm.outputValue || undefined
      })
      ElMessage.success('规则已添加')
      showAddRuleForm.value = false
      await loadRules()
    } catch {
      // 拦截器已提示
    } finally {
      addingRule.value = false
    }
  })
}

/** 删除规则 */
async function handleDeleteRule(row: TagRule) {
  if (!currentTag.value) return
  try {
    await ElMessageBox.confirm(`确认删除规则「${row.name}」？`, '删除确认', {
      type: 'warning',
      confirmButtonClass: 'el-button--danger'
    })
    await devTagApi.deleteTagRule(currentTag.value.id, row.id)
    ElMessage.success('规则已删除')
    await loadRules()
  } catch {
    // 用户取消或删除失败
  }
}

/* ------------------------------ 用户画像 ------------------------------ */

const userIdInput = ref('')
const profile = ref<UserProfile | null>(null)
const profileLoading = ref(false)
const profileError = ref(false)

/** 查询用户画像 */
async function handleQueryProfile() {
  const id = userIdInput.value.trim()
  if (!id) return
  profileLoading.value = true
  profileError.value = false
  try {
    profile.value = await devTagApi.getProfile(id)
  } catch {
    profileError.value = true
    profile.value = null
  } finally {
    profileLoading.value = false
  }
}

/* ------------------------------ 人群圈选 ------------------------------ */

const conditions = reactive<TagQuery>({ tags: [] })
const saveAudience = ref(false)
const audienceName = ref('')
const audienceResult = ref<AudienceResult | null>(null)
const selecting = ref(false)
const selectError = ref(false)

/** 添加条件 */
function addCondition() {
  conditions.tags.push({
    tagId: '',
    op: 'EQ',
    value: ''
  })
}

/** 删除条件 */
function removeCondition(idx: number) {
  conditions.tags.splice(idx, 1)
}

/** 执行圈选 */
async function handleSelectAudience() {
  if (!conditions.tags.length) return
  selecting.value = true
  selectError.value = false
  try {
    audienceResult.value = await devTagApi.selectAudience({
      query: { tags: conditions.tags },
      audienceName: saveAudience.value ? audienceName.value || undefined : undefined,
      save: saveAudience.value
    })
    todaySelectCount.value += 1
    ElMessage.success(`圈选完成，共 ${audienceResult.value.count} 人`)
  } catch {
    selectError.value = true
    audienceResult.value = null
  } finally {
    selecting.value = false
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

/** 标签状态 → 中文 */
function tagStatusLabel(status?: string): string {
  const map: Record<string, string> = {
    DRAFT: '草稿',
    READY: '就绪',
    COMPUTING: '计算中',
    COMPUTED: '已计算',
    FAILED: '失败'
  }
  return map[status ?? ''] ?? status ?? '--'
}

/** 标签状态 → tag 类型 */
function tagStatusType(status?: string): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  const map: Record<string, 'primary' | 'success' | 'danger' | 'info' | 'warning'> = {
    DRAFT: 'info',
    READY: 'warning',
    COMPUTING: 'primary',
    COMPUTED: 'success',
    FAILED: 'danger'
  }
  return map[status ?? ''] ?? 'info'
}

/* ------------------------------ 生命周期 ------------------------------ */

onMounted(() => {
  void reloadTags()
})
</script>

<style scoped>
.dev-tag-page {
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
.profile-panel {
  padding: 4px 0;
}
.cond-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.rule-form {
  border-top: 1px solid var(--ds-border-default);
  margin-top: 12px;
  padding-top: 8px;
}
</style>
