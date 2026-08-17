# 组件库 Storybook

> 归属：多平台多租户大数据平台 · UI 设计文档
> 版本：v1.0 ｜ 日期：2026-08-18 ｜ 状态：已完成
> 关联：`design/UI设计/设计系统规范.md`；`design/UI设计/设计Token-Figma同步.md`；`design/多平台多租户大数据平台_产品原型设计_v0.4.md`
> 适用范围：前端组件库（Vue 3 + TypeScript + Pinia）的 Storybook 文档与示例

---

## 1. 概述

### 1.1 目标

为多平台多租户大数据平台前端组件库提供 Storybook 文档，确保：

- **可视化展示**：所有组件在隔离环境展示，便于设计与开发对照。
- **交互试玩**：每个组件可在 Storybook 中实时调整 props，验证交互。
- **文档化**：组件 API、用法、设计依据自动生成文档。
- **回归守护**：每个 story 对应一个视觉回归测试，避免样式漂移。
- **跨主题验证**：深色/浅色主题切换即时生效。

### 1.2 技术栈

| 项 | 版本 | 用途 |
| --- | --- | --- |
| Vue | 3.4+ | 组件运行时 |
| TypeScript | 5.x | 类型 |
| Storybook | 8.x | 文档工具 |
| Vite | 5.x | 构建 |
| Pinia | 2.x | 状态 |
| Vitest | 1.x | 单测 |
| @storybook/addon-a11y | 8.x | 无障碍审计 |

---

## 2. 组件库总览

### 2.1 组件分类

| 分类 | 组件数 | 路径 | 说明 |
| --- | --- | --- | --- |
| 基础组件 | 12 | `components/basic/` | 按钮、输入、选择、开关等 |
| 表单组件 | 18 | `components/form/` | 表单、校验、上传等 |
| 数据展示 | 15 | `components/data/` | 表格、列表、卡片、图表等 |
| 反馈组件 | 8 | `components/feedback/` | 弹窗、消息、抽屉等 |
| 导航组件 | 6 | `components/nav/` | 菜单、面包屑、分页等 |
| 布局组件 | 10 | `components/layout/` | 栅格、间距、容器等 |
| 业务组件 | 24 | `components/biz/` | 资产卡片、SQL 编辑器等 |
| **合计** | **93** | — | — |

### 2.2 组件清单

#### 2.2.1 基础组件

| 组件 | 路径 | Story 数 | 备注 |
| --- | --- | --- | --- |
| Button | `basic/Button.vue` | 8 | 含 loading、disabled、icon |
| Input | `basic/Input.vue` | 12 | 含前后缀、清空、密码 |
| Select | `basic/Select.vue` | 10 | 含单选、多选、远程 |
| Switch | `basic/Switch.vue` | 4 | — |
| Checkbox | `basic/Checkbox.vue` | 6 | 含全选、半选 |
| Radio | `basic/Radio.vue` | 4 | — |
| DatePicker | `basic/DatePicker.vue` | 8 | 含范围、快捷 |
| TimePicker | `basic/TimePicker.vue` | 4 | — |
| Tag | `basic/Tag.vue` | 6 | 含可关闭、颜色 |
| Badge | `basic/Badge.vue` | 4 | — |
| Avatar | `basic/Avatar.vue` | 4 | 含 fallback |
| Icon | `basic/Icon.vue` | 2 | 统一图标组件 |

#### 2.2.2 表单组件

| 组件 | 路径 | Story 数 | 备注 |
| --- | --- | --- | --- |
| Form | `form/Form.vue` | 10 | 含校验、异步校验 |
| FormItem | `form/FormItem.vue` | 6 | — |
| Upload | `form/Upload.vue` | 8 | 含拖拽、进度 |
| Transfer | `form/Transfer.vue` | 4 | — |
| Cascader | `form/Cascader.vue` | 6 | 含远程加载 |
| AutoComplete | `form/AutoComplete.vue` | 4 | — |
| Rate | `form/Rate.vue` | 2 | — |
| Slider | `form/Slider.vue` | 4 | — |
| ColorPicker | `form/ColorPicker.vue` | 4 | — |
| MarkdownEditor | `form/MarkdownEditor.vue` | 4 | 富文本 |
| CodeEditor | `form/CodeEditor.vue` | 4 | 代码编辑器 |
| JsonEditor | `form/JsonEditor.vue` | 4 | JSON 编辑 |

#### 2.2.3 数据展示

| 组件 | 路径 | Story 数 | 备注 |
| --- | --- | --- | --- |
| Table | `data/Table.vue` | 18 | 含分页、排序、筛选、虚拟滚动 |
| Tree | `data/Tree.vue` | 8 | 含异步加载、拖拽 |
| Card | `data/Card.vue` | 6 | — |
| Descriptions | `data/Descriptions.vue` | 4 | — |
| Statistic | `data/Statistic.vue` | 4 | — |
| Timeline | `data/Timeline.vue` | 4 | — |
| Collapse | `data/Collapse.vue` | 4 | — |
| Tabs | `data/Tabs.vue` | 6 | — |
| Carousel | `data/Carousel.vue` | 4 | — |
| Chart | `data/Chart.vue` | 8 | ECharts 封装 |
| Empty | `data/Empty.vue` | 2 | — |
| Skeleton | `data/Skeleton.vue` | 4 | — |
| Result | `data/Result.vue` | 4 | — |
| List | `data/List.vue` | 4 | — |
| Pagination | `data/Pagination.vue` | 4 | — |

#### 2.2.4 反馈组件

| 组件 | 路径 | Story 数 | 备注 |
| --- | --- | --- | --- |
| Modal | `feedback/Modal.vue` | 8 | 含确认、自定义 |
| Drawer | `feedback/Drawer.vue` | 6 | — |
| Message | `feedback/Message.vue` | 4 | 全局提示 |
| Notification | `feedback/Notification.vue` | 4 | — |
| Popover | `feedback/Popover.vue` | 4 | — |
| Tooltip | `feedback/Tooltip.vue` | 4 | — |
| Popconfirm | `feedback/Popconfirm.vue` | 4 | — |
| Loading | `feedback/Loading.vue` | 4 | — |

#### 2.2.5 导航组件

| 组件 | 路径 | Story 数 | 备注 |
| --- | --- | --- | --- |
| Menu | `nav/Menu.vue` | 6 | 含多级、折叠 |
| Breadcrumb | `nav/Breadcrumb.vue` | 4 | — |
| Steps | `nav/Steps.vue` | 4 | — |
| TabNav | `nav/TabNav.vue` | 4 | — |
| BackTop | `nav/BackTop.vue` | 2 | — |
| Anchor | `nav/Anchor.vue` | 4 | — |

#### 2.2.6 布局组件

| 组件 | 路径 | Story 数 | 备注 |
| --- | --- | --- | --- |
| Layout | `layout/Layout.vue` | 4 | 含首栏/尾栏/侧边栏 |
| Container | `layout/Container.vue` | 4 | — |
| Row | `layout/Row.vue` | 4 | 栅格 |
| Col | `layout/Col.vue` | 4 | — |
| Space | `layout/Space.vue` | 4 | 间距 |
| Divider | `layout/Divider.vue` | 2 | — |
| Grid | `layout/Grid.vue` | 4 | — |
| Cell | `layout/Cell.vue` | 2 | — |
| Stack | `layout/Stack.vue` | 2 | — |
| Aspect | `layout/Aspect.vue` | 2 | 宽高比 |

#### 2.2.7 业务组件

| 组件 | 路径 | Story 数 | 备注 |
| --- | --- | --- | --- |
| AssetCard | `biz/AssetCard.vue` | 6 | 资产卡片 |
| AssetTree | `biz/AssetTree.vue` | 4 | 资产树 |
| SqlWorkbench | `biz/SqlWorkbench.vue` | 8 | SQL 工作台 |
| TaskDag | `biz/TaskDag.vue` | 6 | 任务 DAG |
| QualityRule | `biz/QualityRule.vue` | 4 | 质量规则 |
| TenantSwitch | `biz/TenantSwitch.vue` | 4 | 租户切换 |
| UserPicker | `biz/UserPicker.vue` | 4 | 用户选择 |
| RolePicker | `biz/RolePicker.vue` | 4 | 角色选择 |
| MetricPanel | `biz/MetricPanel.vue` | 6 | 指标面板 |
| LineageGraph | `biz/LineageGraph.vue` | 4 | 血缘图 |
| SchemaViewer | `biz/SchemaViewer.vue` | 4 | Schema 浏览 |
| DataPreview | `biz/DataPreview.vue` | 4 | 数据预览 |
| JobLogViewer | `biz/JobLogViewer.vue` | 4 | 日志查看 |
| ConfigEditor | `biz/ConfigEditor.vue` | 4 | 配置编辑 |
| ApiTester | `biz/ApiTester.vue` | 4 | API 测试 |
| DiffViewer | `biz/DiffViewer.vue` | 4 | 差异查看 |
| ... | ... | ... | 共 24 个业务组件 |

---

## 3. Storybook 配置

### 3.1 目录结构

```text
frontend/
├── .storybook/
│   ├── main.ts              # Storybook 配置
│   ├── preview.ts           # 全局装饰器
│   ├── theme.ts             # 主题
│   └── manager.ts           # UI 定制
├── src/components/
│   ├── basic/
│   │   ├── Button.vue
│   │   ├── Button.stories.ts    # Story 文件
│   │   └── Button.test.ts       # 单测
│   └── ...
└── ...
```

### 3.2 main.ts 配置

```typescript
import type { StorybookConfig } from '@storybook/vue3-vite';

const config: StorybookConfig = {
  stories: ['../src/components/**/*.stories.ts'],
  addons: [
    '@storybook/addon-essentials',
    '@storybook/addon-a11y',
    '@storybook/addon-interactions',
    '@storybook/addon-themes',
  ],
  framework: { name: '@storybook/vue3-vite', options: {} },
  docs: { autodocs: 'tag' },
};

export default config;
```

### 3.3 preview.ts 全局装饰

```typescript
import type { Preview } from '@storybook/vue3';
import { setup } from '@pinia/testing';
import './preview.css';

const preview: Preview = {
  decorators: [
    (story) => ({
      components: { story },
      setup() { setup(); return {}; },
      template: '<div class="storybook-container"><story /></div>',
    }),
  ],
  parameters: {
    backgrounds: {
      default: 'light',
      values: [
        { name: 'light', value: '#f8fafc' },
        { name: 'dark', value: '#0f172a' },
      ],
    },
    a11y: { config: { rules: [{ id: 'color-contrast', enabled: true }] } },
  },
};

export default preview;
```

---

## 4. Story 编写规范

### 4.1 Story 文件模板

```typescript
import type { Meta, StoryObj } from '@storybook/vue3';
import Button from './Button.vue';

const meta: Meta<typeof Button> = {
  title: 'Basic/Button',
  component: Button,
  tags: ['autodocs'],
  argTypes: {
    type: { control: 'select', options: ['primary', 'secondary', 'ghost', 'danger'] },
    size: { control: 'select', options: ['sm', 'md', 'lg'] },
    loading: { control: 'boolean' },
    disabled: { control: 'boolean' },
  },
};

export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = { args: { type: 'primary', size: 'md' } };
export const Loading: Story = { args: { loading: true, children: '提交' } };
export const Disabled: Story = { args: { disabled: true } };
export const WithIcon: Story = { args: { icon: 'plus', children: '新增' } };
```

### 4.2 Story 命名约定

- Story title 使用 `分类/组件名` 格式：`Basic/Button`、`Form/Upload`。
- Story 名称使用 PascalCase：`Default`、`WithIcon`、`LoadingState`。
- 每个组件至少包含：`Default`、`Loading`、`Disabled`、`WithError`（如适用）。

### 4.3 文档自动生成

- 使用 `tags: ['autodocs']` 自动生成文档。
- 组件 props/emits/slots 自动从 TypeScript 类型提取。
- 在组件文件中使用 TSDoc 注释增强文档：

```typescript
/**
 * 按钮组件，用于触发操作。
 *
 * @example
 * <Button type="primary" @click="handle">提交</Button>
 */
defineProps<{
  /** 按钮类型 */
  type?: 'primary' | 'secondary' | 'ghost' | 'danger';
  /** 按钮尺寸 */
  size?: 'sm' | 'md' | 'lg';
  /** 加载中状态 */
  loading?: boolean;
}>();
```

---

## 5. 视觉回归测试

### 5.1 配置

使用 `@storybook/addon-test` + playwright 进行视觉回归：

```typescript
import { test, expect } from '@storybook/test-runner';

test('Button snapshot', async ({ page }) => {
  await page.goto('/?path=/story/basic-button--default');
  await expect(page).toHaveScreenshot('button-default.png');
});
```

### 5.2 快照管理

- 首次执行自动生成基线快照。
- 后续执行对比，差异 > 0.1% 视为失败。
- 快照变更须由 UI 组评审后更新。

---

## 6. 无障碍审计

### 6.1 自动审计

`@storybook/addon-a11y` 在每个 story 自动运行 axe 审计，违规项在面板展示。

### 6.2 审计规则

| 规则 | 级别 | 说明 |
| --- | --- | --- |
| color-contrast | error | WCAG 2.1 AA 对比度 |
| keyboard-navigation | error | 键盘可达 |
| aria-label | warning | 交互元素须有 aria-label |
| heading-order | warning | 标题层级递增 |

---

## 7. 部署与访问

### 7.1 本地启动

```bash
cd frontend
npm run storybook
# 访问 http://localhost:6006
```

### 7.2 静态构建

```bash
npm run build-storybook
# 产物在 storybook-static/
```

### 7.3 CI 部署

- PR 流水线构建 Storybook 静态产物。
- 部署到内部 CDN，PR 评论附 Storybook 链接。
- 主分支构建部署到 `storybook.nexus.internal`。

---

## 8. 维护与治理

### 8.1 组件新增流程

1. 在 `components/<分类>/` 创建组件 + stories + test。
2. 在本文件 §2.2 对应分类表格登记。
3. PR 必过 Storybook 构建 + 视觉回归 + a11y 审计。
4. UI 组 Review 视觉，前端组 Review 实现。

### 8.2 组件废弃流程

1. 在组件文件头标注 `@deprecated` + 替代方案。
2. 在本文件 §2.2 标记为废弃。
3. 90 天观察期后删除。

### 8.3 治理指标

| 指标 | 目标 | 度量方式 |
| --- | --- | --- |
| 组件文档覆盖率 | 100% | 有 stories 的组件 / 总组件 |
| 视觉回归通过率 | ≥ 95% | 通过快照数 / 总快照数 |
| a11y 违规数 | 0 | axe 审计结果 |
| Story 数 / 组件数 | ≥ 4 | 平均每个组件 4 个 story |

---

## 9. 版本与变更

| 版本 | 日期 | 变更内容 | 作者 |
| --- | --- | --- | --- |
| v1.0 | 2026-08-18 | 首次发布，覆盖 93 组件 | 前端组 |

> 本文档由前端组维护，组件库变更须同步更新。