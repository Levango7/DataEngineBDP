<template>
  <div class="dev-tag-page">
    <h1>{{ t('devTag.title') }}</h1>
    <div class="sub">{{ t('devTag.subtitle') }}</div>

    <!-- KPI 卡片区 -->
    <div class="grid g4">
      <template v-if="tagsLoading">
        <div v-for="i in 4" :key="i" class="card">
          <h3>{{ t('engines.kpi.loading') }}</h3>
          <div class="kpi">--</div>
          <div class="meta">{{ t('engines.kpi.loadingMeta') }}</div>
        </div>
      </template>
      <template v-else-if="tagsError">
        <div class="card" style="grid-column: span 4">
          <h3>{{ t('engines.kpi.loadFailed') }}</h3>
          <div class="meta" style="color: var(--muted)">
            {{ t('devTag.listLoadFailed') }}
            <a href="javascript:void(0)" @click="reloadTags">
              {{ t('engines.kpi.loadFailedRetry') }}
            </a>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>{{ t('devTag.kpi.total') }}</h3>
          <div class="kpi">{{ kpi.total }}</div>
          <div class="meta">{{ t('devTag.kpi.totalMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('devTag.kpi.computed') }}</h3>
          <div class="kpi s">{{ kpi.computed }}</div>
          <div class="meta">{{ t('devTag.kpi.computedMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('devTag.kpi.computing') }}</h3>
          <div class="kpi">{{ kpi.computing }}</div>
          <div class="meta">{{ t('devTag.kpi.computingMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('devTag.kpi.todaySelect') }}</h3>
          <div class="kpi">{{ todaySelectCount }}</div>
          <div class="meta">{{ t('devTag.kpi.todaySelectMeta') }}</div>
        </div>
      </template>
    </div>

    <!-- Tabs 主区 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <el-tabs v-model="activeTab" type="card">
        <!-- Tab1 标签定义 -->
        <el-tab-pane :label="t('devTag.tabs.definition')" name="definition">
          <div class="toolbar">
            <el-button type="primary" @click="openTagDialog()">
              {{ t('devTag.tagActions.new') }}
            </el-button>
            <el-button
              type="success"
              :disabled="!selectedTagIds.length"
              :loading="batchComputing"
              @click="handleBatchCompute"
            >
              {{ t('devTag.tagActions.batchCompute', { count: selectedTagIds.length }) }}
            </el-button>
            <div class="spacer"></div>
            <el-button
              :icon="Refresh"
              circle
              :aria-label="t('devTag.list.refreshAria')"
              @click="reloadTags"
            />
          </div>

          <el-table
            v-loading="tagsLoading"
            :data="tags"
            stripe
            border
            style="width: 100%"
            :empty-text="tagsError ? t('devTag.list.loadFailed') : t('devTag.list.empty')"
            @selection-change="handleSelectionChange"
          >
            <el-table-column type="selection" width="48" />
            <el-table-column prop="name" :label="t('devTag.tagColumns.name')" min-width="160" />
            <el-table-column prop="code" :label="t('devTag.tagColumns.code')" width="160">
              <template #default="{ row }">
                <span style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px">
                  {{ row.code || t('devTag.tagColumns.codePlaceholder') }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="valueType" :label="t('devTag.tagColumns.valueType')" width="110">
              <template #default="{ row }">
                <el-tag effect="light" size="small">
                  {{ t(`devTag.tagDialog.valueTypes.${row.valueType}`, row.valueType) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              prop="ruleCount"
              :label="t('devTag.tagColumns.ruleCount')"
              width="100"
              align="center"
            >
              <template #default="{ row }">{{ row.ruleCount ?? 0 }}</template>
            </el-table-column>
            <el-table-column :label="t('devTag.tagColumns.status')" width="120">
              <template #default="{ row }">
                <el-tag :type="tagStatusType(row.status)" effect="light" size="small">
                  {{ tagStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column
              prop="lastComputedAt"
              :label="t('devTag.tagColumns.lastComputed')"
              width="180"
            >
              <template #default="{ row }">
                {{ row.lastComputedAt || t('devTag.tagColumns.codePlaceholder') }}
              </template>
            </el-table-column>
            <el-table-column :label="t('devTag.tagColumns.actions')" width="280" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openRuleDialog(row)">
                  {{ t('devTag.tagActions.rule') }}
                </el-button>
                <el-button
                  link
                  type="success"
                  :loading="computingId === row.id"
                  @click="handleCompute(row)"
                >
                  {{ t('devTag.tagActions.compute') }}
                </el-button>
                <el-button link type="primary" @click="openTagDialog(row)">
                  {{ t('devTag.tagActions.edit') }}
                </el-button>
                <el-button link type="danger" @click="handleDeleteTag(row)">
                  {{ t('devTag.tagActions.delete') }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- Tab2 用户画像 -->
        <el-tab-pane :label="t('devTag.tabs.profile')" name="profile">
          <div class="toolbar">
            <el-input
              v-model="userIdInput"
              :placeholder="t('devTag.profile.userIdPlaceholder')"
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
              {{ t('devTag.profile.queryBtn') }}
            </el-button>
          </div>

          <div v-if="profileLoading" class="meta" style="padding: 16px">
            {{ t('devTag.profile.loading') }}
          </div>
          <div v-else-if="profileError" class="meta" style="padding: 16px; color: var(--muted)">
            {{ t('devTag.profile.loadFailed') }}
            <a href="javascript:void(0)" @click="handleQueryProfile">
              {{ t('devTag.profile.loadFailedRetry') }}
            </a>
          </div>
          <el-empty v-else-if="!profile" :description="t('devTag.profile.emptyHint')" />
          <div v-else class="profile-panel">
            <el-descriptions :column="2" border>
              <el-descriptions-item :label="t('devTag.profile.fields.userId')">
                {{ profile.userId }}
              </el-descriptions-item>
              <el-descriptions-item :label="t('devTag.profile.fields.username')">
                {{ profile.username || t('devTag.tagColumns.codePlaceholder') }}
              </el-descriptions-item>
              <el-descriptions-item :label="t('devTag.profile.fields.updatedAt')">
                {{ profile.updatedAt || t('devTag.tagColumns.codePlaceholder') }}
              </el-descriptions-item>
              <el-descriptions-item :label="t('devTag.profile.fields.tagCount')">
                {{ profile.tags.length }}
              </el-descriptions-item>
            </el-descriptions>
            <h3 style="margin: 16px 0 12px">{{ t('devTag.profile.tagsListTitle') }}</h3>
            <el-table :data="profile.tags" stripe border size="small">
              <el-table-column
                prop="tagName"
                :label="t('devTag.profile.tagsColumns.name')"
                min-width="160"
              />
              <el-table-column
                prop="valueType"
                :label="t('devTag.profile.tagsColumns.type')"
                width="100"
              />
              <el-table-column :label="t('devTag.profile.tagsColumns.value')" min-width="160">
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
        <el-tab-pane :label="t('devTag.tabs.audience')" name="audience">
          <el-row :gutter="16">
            <!-- 左：条件构建器 -->
            <el-col :xs="24" :md="12">
              <h3 style="margin: 0 0 12px">{{ t('devTag.audience.condBuilderTitle') }}</h3>
              <div v-for="(cond, idx) in conditions.tags" :key="idx" class="cond-row">
                <el-select
                  v-model="cond.tagId"
                  :placeholder="t('devTag.audience.selectTag')"
                  filterable
                  style="width: 160px"
                >
                  <el-option v-for="t in tags" :key="t.id" :label="t.name" :value="t.id" />
                </el-select>
                <el-select
                  v-model="cond.op"
                  :placeholder="t('devTag.audience.selectOp')"
                  style="width: 100px"
                >
                  <el-option :label="t('devTag.audience.ops.EQ')" value="EQ" />
                  <el-option :label="t('devTag.audience.ops.NE')" value="NE" />
                  <el-option :label="t('devTag.audience.ops.IN')" value="IN" />
                  <el-option :label="t('devTag.audience.ops.GT')" value="GT" />
                  <el-option :label="t('devTag.audience.ops.LT')" value="LT" />
                  <el-option :label="t('devTag.audience.ops.GE')" value="GE" />
                  <el-option :label="t('devTag.audience.ops.LE')" value="LE" />
                  <el-option :label="t('devTag.audience.ops.BETWEEN')" value="BETWEEN" />
                  <el-option :label="t('devTag.audience.ops.LIKE')" value="LIKE" />
                </el-select>
                <el-input
                  v-model="cond.value"
                  :placeholder="t('devTag.audience.value')"
                  style="width: 140px"
                />
                <el-input
                  v-if="cond.op === 'BETWEEN'"
                  v-model="cond.value2"
                  :placeholder="t('devTag.audience.valueMax')"
                  style="width: 100px"
                />
                <el-button link type="danger" @click="removeCondition(idx)">
                  {{ t('devTag.audience.remove') }}
                </el-button>
              </div>
              <el-button type="primary" plain style="margin-top: 8px" @click="addCondition">
                {{ t('devTag.audience.addCondition') }}
              </el-button>

              <div style="margin-top: 16px">
                <el-button
                  type="primary"
                  :loading="selecting"
                  :disabled="!conditions.tags.length"
                  @click="handleSelectAudience"
                >
                  {{ t('devTag.audience.selectBtn') }}
                </el-button>
                <el-checkbox v-model="saveAudience" style="margin-left: 12px">
                  {{ t('devTag.audience.saveAudience') }}
                </el-checkbox>
                <el-input
                  v-if="saveAudience"
                  v-model="audienceName"
                  :placeholder="t('devTag.audience.audienceNamePlaceholder')"
                  style="width: 200px; margin-left: 8px"
                />
              </div>
            </el-col>

            <!-- 右：结果 -->
            <el-col :xs="24" :md="12">
              <h3 style="margin: 0 0 12px">{{ t('devTag.audience.resultTitle') }}</h3>
              <div v-if="selecting" class="meta">{{ t('devTag.audience.selecting') }}</div>
              <div v-else-if="selectError" class="meta" style="color: var(--muted)">
                {{ t('devTag.audience.selectFailed') }}
              </div>
              <el-empty v-else-if="!audienceResult" :description="t('devTag.audience.emptyHint')" />
              <div v-else>
                <el-descriptions :column="1" border size="small">
                  <el-descriptions-item :label="t('devTag.audience.countField')">
                    <span class="kpi" style="font-size: 20px">{{ audienceResult.count }}</span>
                  </el-descriptions-item>
                  <el-descriptions-item
                    v-if="audienceResult.audienceId"
                    :label="t('devTag.audience.audienceIdField')"
                  >
                    {{ audienceResult.audienceId }}
                  </el-descriptions-item>
                </el-descriptions>
                <h4 style="margin: 12px 0 8px">
                  {{ t('devTag.audience.usersTitle', { count: audienceResult.users.length }) }}
                </h4>
                <el-table
                  :data="audienceResult.users"
                  stripe
                  border
                  size="small"
                  :empty-text="t('devTag.audience.noUser')"
                >
                  <el-table-column
                    prop="userId"
                    :label="t('devTag.audience.usersColumns.userId')"
                    min-width="160"
                  />
                  <el-table-column
                    prop="username"
                    :label="t('devTag.audience.usersColumns.username')"
                    min-width="120"
                  >
                    <template #default="{ row }">
                      {{ row.username || t('devTag.tagColumns.codePlaceholder') }}
                    </template>
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
      :title="tagForm.id ? t('devTag.tagDialog.titleEdit') : t('devTag.tagDialog.titleCreate')"
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
        <el-form-item :label="t('devTag.tagDialog.fields.name')" prop="name">
          <el-input
            v-model="tagForm.name"
            :placeholder="t('devTag.tagDialog.fields.namePlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('devTag.tagDialog.fields.code')" prop="code">
          <el-input
            v-model="tagForm.code"
            :placeholder="t('devTag.tagDialog.fields.codePlaceholder')"
            style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
          />
        </el-form-item>
        <el-form-item :label="t('devTag.tagDialog.fields.valueType')" prop="valueType">
          <el-select v-model="tagForm.valueType" style="width: 100%">
            <el-option :label="t('devTag.tagDialog.valueTypes.STRING')" value="STRING" />
            <el-option :label="t('devTag.tagDialog.valueTypes.NUMBER')" value="NUMBER" />
            <el-option :label="t('devTag.tagDialog.valueTypes.BOOLEAN')" value="BOOLEAN" />
            <el-option :label="t('devTag.tagDialog.valueTypes.ENUM')" value="ENUM" />
            <el-option :label="t('devTag.tagDialog.valueTypes.DATE')" value="DATE" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('devTag.tagDialog.fields.description')" prop="description">
          <el-input v-model="tagForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tagDialogVisible = false">
          {{ t('devTag.tagDialog.actions.cancel') }}
        </el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmitTag">
          {{
            tagForm.id ? t('devTag.tagDialog.actions.save') : t('devTag.tagDialog.actions.create')
          }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 规则编辑弹窗 -->
    <el-dialog
      v-model="ruleDialogVisible"
      :title="t('devTag.ruleDialog.title', { name: currentTag?.name ?? '' })"
      width="720px"
      :close-on-click-modal="false"
    >
      <div class="toolbar">
        <el-button type="primary" @click="openAddRuleForm">
          {{ t('devTag.ruleDialog.addRule') }}
        </el-button>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle @click="loadRules" />
      </div>
      <el-table
        v-loading="rulesLoading"
        :data="rules"
        stripe
        border
        size="small"
        :empty-text="t('devTag.ruleDialog.empty')"
      >
        <el-table-column prop="name" :label="t('devTag.ruleDialog.columns.name')" min-width="140" />
        <el-table-column
          prop="ruleType"
          :label="t('devTag.ruleDialog.columns.ruleType')"
          width="110"
        >
          <template #default="{ row }">
            <el-tag size="small" effect="light">
              {{ t(`devTag.ruleDialog.addForm.ruleTypes.${row.ruleType}`, row.ruleType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="priority"
          :label="t('devTag.ruleDialog.columns.priority')"
          width="80"
          align="center"
        />
        <el-table-column
          prop="outputValue"
          :label="t('devTag.ruleDialog.columns.outputValue')"
          width="120"
        >
          <template #default="{ row }">
            {{ row.outputValue || t('devTag.tagColumns.codePlaceholder') }}
          </template>
        </el-table-column>
        <el-table-column
          prop="expression"
          :label="t('devTag.ruleDialog.columns.expression')"
          min-width="200"
          show-overflow-tooltip
        />
        <el-table-column :label="t('devTag.ruleDialog.columns.actions')" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" @click="handleDeleteRule(row)">
              {{ t('devTag.tagActions.delete') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 添加规则表单 -->
      <div v-if="showAddRuleForm" class="rule-form">
        <h4 style="margin: 16px 0 8px">{{ t('devTag.ruleDialog.addForm.title') }}</h4>
        <el-form
          ref="ruleFormRef"
          :model="ruleForm"
          :rules="ruleRules"
          label-width="80px"
          label-position="right"
        >
          <el-form-item :label="t('devTag.ruleDialog.addForm.fields.name')" prop="name">
            <el-input
              v-model="ruleForm.name"
              :placeholder="t('devTag.ruleDialog.addForm.fields.namePlaceholder')"
            />
          </el-form-item>
          <el-form-item :label="t('devTag.ruleDialog.addForm.fields.ruleType')" prop="ruleType">
            <el-select v-model="ruleForm.ruleType" style="width: 100%">
              <el-option :label="t('devTag.ruleDialog.addForm.ruleTypes.SQL')" value="SQL" />
              <el-option
                :label="t('devTag.ruleDialog.addForm.ruleTypes.EXPRESSION')"
                value="EXPRESSION"
              />
              <el-option :label="t('devTag.ruleDialog.addForm.ruleTypes.LOOKUP')" value="LOOKUP" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('devTag.ruleDialog.addForm.fields.priority')" prop="priority">
            <el-input-number v-model="ruleForm.priority" :min="0" :max="100" />
          </el-form-item>
          <el-form-item
            :label="t('devTag.ruleDialog.addForm.fields.outputValue')"
            prop="outputValue"
          >
            <el-input
              v-model="ruleForm.outputValue"
              :placeholder="t('devTag.ruleDialog.addForm.fields.outputValuePlaceholder')"
            />
          </el-form-item>
          <el-form-item :label="t('devTag.ruleDialog.addForm.fields.expression')" prop="expression">
            <el-input
              v-model="ruleForm.expression"
              type="textarea"
              :rows="3"
              :placeholder="t('devTag.ruleDialog.addForm.fields.expressionPlaceholder')"
              style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="addingRule" @click="handleAddRule">
              {{ t('devTag.ruleDialog.addForm.submit') }}
            </el-button>
            <el-button @click="showAddRuleForm = false">
              {{ t('devTag.ruleDialog.cancel') }}
            </el-button>
          </el-form-item>
        </el-form>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as devTagApi from '@/api/dev-tag'
import type { TagDefinition, TagRule, UserProfile, AudienceResult, TagQuery } from '@/api/dev-tag'

const { t, te } = useI18n()

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

const tagRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('devTag.rules.tagName'), trigger: 'blur' }],
  valueType: [{ required: true, message: t('devTag.rules.valueType'), trigger: 'change' }]
}))

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
        ElMessage.success(t('devTag.messages.tagUpdated'))
      } else {
        await devTagApi.createTag({
          name: tagForm.name,
          code: tagForm.code || undefined,
          valueType: tagForm.valueType,
          description: tagForm.description || undefined
        })
        ElMessage.success(t('devTag.messages.tagCreated'))
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
    await ElMessageBox.confirm(
      t('devTag.messages.tagDeleteConfirm', { name: row.name }),
      t('devTag.messages.tagDeleteConfirmTitle'),
      {
        type: 'warning',
        confirmButtonText: t('devTag.tagActions.delete'),
        cancelButtonText: t('devTag.tagDialog.actions.cancel'),
        confirmButtonClass: 'el-button--danger'
      }
    )
    await devTagApi.deleteTag(row.id)
    ElMessage.success(t('devTag.messages.tagDeleted'))
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
      t('devTag.messages.computeDone', { status: result.status }) +
        (result.computedCount !== undefined
          ? t('devTag.messages.computeDoneCount', { count: result.computedCount })
          : '')
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
    ElMessage.success(
      t('devTag.messages.batchComputeDone', {
        success: result.successCount,
        total: selectedTagIds.value.length
      })
    )
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

const ruleRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('devTag.rules.ruleName'), trigger: 'blur' }],
  ruleType: [{ required: true, message: t('devTag.rules.ruleType'), trigger: 'change' }],
  expression: [{ required: true, message: t('devTag.rules.ruleExpression'), trigger: 'blur' }]
}))

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
      ElMessage.success(t('devTag.messages.ruleAdded'))
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
    await ElMessageBox.confirm(
      t('devTag.messages.ruleDeleteConfirm', { name: row.name }),
      t('devTag.messages.ruleDeleteConfirmTitle'),
      {
        type: 'warning',
        confirmButtonClass: 'el-button--danger'
      }
    )
    await devTagApi.deleteTagRule(currentTag.value.id, row.id)
    ElMessage.success(t('devTag.messages.ruleDeleted'))
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
    ElMessage.success(t('devTag.messages.audienceDone', { count: audienceResult.value.count }))
  } catch {
    selectError.value = true
    audienceResult.value = null
  } finally {
    selecting.value = false
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

/** 标签状态 → 词条 */
function tagStatusLabel(status?: string): string {
  if (!status) return t('devTag.tagColumns.codePlaceholder')
  const key = `devTag.status.${status}`
  return te(key) ? t(key) : status
}

/** 标签状态 → tag 类型 */
const TAG_STATUS_TYPE_MAP: Record<string, 'primary' | 'success' | 'danger' | 'info' | 'warning'> = {
  DRAFT: 'info',
  READY: 'warning',
  COMPUTING: 'primary',
  COMPUTED: 'success',
  FAILED: 'danger'
}

function tagStatusType(status?: string): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  return TAG_STATUS_TYPE_MAP[status ?? ''] ?? 'info'
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
