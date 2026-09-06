import type { Meta, StoryObj } from '@storybook/vue3'
import PageCard from './PageCard.vue'

const meta: Meta<typeof PageCard> = {
  title: 'UI/PageCard',
  component: PageCard,
  tags: ['autodocs'],
  argTypes: {
    title: { control: 'text' },
    subtitle: { control: 'text' }
  }
}

export default meta
type Story = StoryObj<typeof PageCard>

export const Default: Story = {
  args: {
    title: 'Data Sources',
    subtitle: 'Manage your data connections'
  },
  render: (args) => ({
    components: { PageCard },
    setup() {
      return { args }
    },
    template: `
      <PageCard v-bind="args">
        <p style="padding:4px 0;color:var(--el-text-color-regular)">Card content goes here.</p>
      </PageCard>
    `
  })
}

export const NoHeader: Story = {
  render: () => ({
    components: { PageCard },
    template: `
      <PageCard>
        <p style="padding:4px 0;color:var(--el-text-color-regular)">A card without a title or subtitle.</p>
      </PageCard>
    `
  })
}

export const WithHeaderActions: Story = {
  args: {
    title: 'Cluster Metrics'
  },
  render: (args) => ({
    components: { PageCard },
    setup() {
      return { args }
    },
    template: `
      <PageCard v-bind="args">
        <template #header-actions>
          <el-button size="small" type="primary">Export</el-button>
        </template>
        <p style="padding:4px 0;color:var(--el-text-color-regular)">Metrics dashboard content.</p>
      </PageCard>
    `
  })
}
