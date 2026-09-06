import type { Meta, StoryObj } from '@storybook/vue3'
import Toolbar from './Toolbar.vue'

const meta: Meta<typeof Toolbar> = {
  title: 'UI/Toolbar',
  component: Toolbar,
  tags: ['autodocs'],
  argTypes: {
    showCreate: { control: 'boolean' },
    createLabel: { control: 'text' },
    searchPlaceholder: { control: 'text' },
    showRefresh: { control: 'boolean' }
  }
}

export default meta
type Story = StoryObj<typeof Toolbar>

export const Default: Story = {
  args: {
    showCreate: true,
    createLabel: 'New Data Source',
    searchPlaceholder: 'Search by name...',
    showRefresh: true
  }
}

export const SearchOnly: Story = {
  args: {
    searchPlaceholder: 'Filter results...'
  }
}

export const WithFilters: Story = {
  args: {
    showCreate: true,
    createLabel: 'Add Tenant',
    searchPlaceholder: 'Search tenants...',
    showRefresh: true
  },
  render: (args) => ({
    components: { Toolbar },
    setup() {
      return { args }
    },
    template: `
      <Toolbar v-bind="args">
        <template #filters>
          <el-select placeholder="Status" style="width:140px">
            <el-option label="Active" value="active" />
            <el-option label="Disabled" value="disabled" />
          </el-select>
        </template>
      </Toolbar>
    `
  })
}
