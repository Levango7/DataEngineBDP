import type { Meta, StoryObj } from '@storybook/vue3'
import StatusTag from './StatusTag.vue'

const meta: Meta<typeof StatusTag> = {
  title: 'UI/StatusTag',
  component: StatusTag,
  tags: ['autodocs'],
  argTypes: {
    status: { control: 'text' },
    label: { control: 'text' },
    statusMap: { control: 'object' }
  }
}

export default meta
type Story = StoryObj<typeof StatusTag>

const defaultMap = {
  connected: 'success' as const,
  disconnected: 'info' as const,
  testing: 'warning' as const,
  error: 'danger' as const
}

export const Connected: Story = {
  args: {
    status: 'connected',
    label: 'Connected',
    statusMap: defaultMap
  }
}

export const Disconnected: Story = {
  args: {
    status: 'disconnected',
    label: 'Disconnected',
    statusMap: defaultMap
  }
}

export const Error: Story = {
  args: {
    status: 'error',
    label: 'Error',
    statusMap: defaultMap
  }
}

export const UnknownFallback: Story = {
  args: {
    status: 'unknown_status',
    label: 'Unknown',
    statusMap: defaultMap
  }
}
