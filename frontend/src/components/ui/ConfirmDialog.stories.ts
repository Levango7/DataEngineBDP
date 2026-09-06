import type { Meta, StoryObj } from '@storybook/vue3'
import ConfirmDialog from './ConfirmDialog.vue'

const meta: Meta<typeof ConfirmDialog> = {
  title: 'UI/ConfirmDialog',
  component: ConfirmDialog,
  tags: ['autodocs'],
  argTypes: {
    visible: { control: 'boolean' },
    title: { control: 'text' },
    message: { control: 'text' },
    confirmText: { control: 'text' },
    cancelText: { control: 'text' },
    type: { control: 'select', options: ['warning', 'danger', 'info'] },
    loading: { control: 'boolean' }
  }
}

export default meta
type Story = StoryObj<typeof ConfirmDialog>

export const Warning: Story = {
  args: {
    visible: true,
    title: 'Delete Data Source',
    message: 'Are you sure you want to delete "MySQL-Prod"? This action cannot be undone.',
    confirmText: 'Delete',
    cancelText: 'Cancel',
    type: 'warning'
  }
}

export const Danger: Story = {
  args: {
    visible: true,
    title: 'Remove Cluster',
    message: 'This will permanently remove the cluster and all associated resources.',
    confirmText: 'Remove Permanently',
    type: 'danger'
  }
}

export const Loading: Story = {
  args: {
    visible: true,
    title: 'Confirm Action',
    message: 'Processing your request...',
    confirmText: 'Confirm',
    loading: true
  }
}
