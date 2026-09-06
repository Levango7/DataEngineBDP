import type { Meta, StoryObj } from '@storybook/vue3'
import StatCard from './StatCard.vue'

const meta: Meta<typeof StatCard> = {
  title: 'UI/StatCard',
  component: StatCard,
  tags: ['autodocs'],
  argTypes: {
    value: { control: 'text' },
    label: { control: 'text' },
    trend: { control: 'select', options: ['up', 'down', 'flat'] },
    trendValue: { control: 'text' }
  }
}

export default meta
type Story = StoryObj<typeof StatCard>

export const Default: Story = {
  args: {
    value: '1,284',
    label: 'Total Data Sources',
    trend: 'up',
    trendValue: '+12%'
  }
}

export const DownTrend: Story = {
  args: {
    value: '42',
    label: 'Failed Jobs',
    trend: 'down',
    trendValue: '-5%'
  }
}

export const FlatTrend: Story = {
  args: {
    value: '99.9%',
    label: 'Uptime',
    trend: 'flat',
    trendValue: '0%'
  }
}

export const NoTrend: Story = {
  args: {
    value: '8',
    label: 'Active Clusters'
  }
}
