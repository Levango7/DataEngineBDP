import type { Meta, StoryObj } from '@storybook/vue3'
import EmptyState from './EmptyState.vue'

const meta: Meta<typeof EmptyState> = {
  title: 'UI/EmptyState',
  component: EmptyState,
  tags: ['autodocs'],
  argTypes: {
    message: { control: 'text' },
    actionLabel: { control: 'text' }
  }
}

export default meta
type Story = StoryObj<typeof EmptyState>

export const Default: Story = {
  args: {
    message: 'No data sources configured'
  }
}

export const WithAction: Story = {
  args: {
    message: 'No tenants found',
    actionLabel: 'Create Tenant'
  }
}

export const CustomMessage: Story = {
  args: {
    message: 'Search returned no results. Try adjusting your filters.'
  }
}
