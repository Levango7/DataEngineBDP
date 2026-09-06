import type { Meta, StoryObj } from '@storybook/vue3'
import PageHeader from './PageHeader.vue'

const meta: Meta<typeof PageHeader> = {
  title: 'UI/PageHeader',
  component: PageHeader,
  tags: ['autodocs'],
  argTypes: {
    title: { control: 'text' },
    subtitle: { control: 'text' }
  }
}

export default meta
type Story = StoryObj<typeof PageHeader>

export const Default: Story = {
  args: {
    title: 'Data Source Management',
    subtitle: 'Manage and monitor your data sources'
  }
}

export const TitleOnly: Story = {
  args: {
    title: 'Dashboard'
  }
}

export const WithActions: Story = {
  args: {
    title: 'Cluster Overview',
    subtitle: 'Real-time cluster health and metrics'
  },
  render: (args) => ({
    components: { PageHeader },
    setup() {
      return { args }
    },
    template: `
      <PageHeader v-bind="args">
        <template #actions>
          <el-button type="primary">Add Cluster</el-button>
        </template>
      </PageHeader>
    `
  })
}
