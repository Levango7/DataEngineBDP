import type { Meta, StoryObj } from '@storybook/vue3'
import FilterBar from './FilterBar.vue'
import type { FilterConfig } from './FilterBar.vue'

const meta: Meta<typeof FilterBar> = {
  title: 'UI/FilterBar',
  component: FilterBar,
  tags: ['autodocs']
}

export default meta
type Story = StoryObj<typeof FilterBar>

const sampleFilters: FilterConfig[] = [
  {
    key: 'status',
    placeholder: 'Status',
    options: [
      { label: 'Active', value: 'active' },
      { label: 'Disabled', value: 'disabled' }
    ]
  },
  {
    key: 'type',
    placeholder: 'Type',
    options: [
      { label: 'MySQL', value: 'mysql' },
      { label: 'PostgreSQL', value: 'postgresql' }
    ]
  }
]

export const Default: Story = {
  args: {
    filters: sampleFilters,
    modelValue: {},
    searchPlaceholder: 'Search...',
    showClear: true
  }
}

export const FiltersOnly: Story = {
  args: {
    filters: sampleFilters,
    modelValue: {}
  }
}

export const WithPreselectedValues: Story = {
  args: {
    filters: sampleFilters,
    modelValue: { status: 'active', _search: 'prod' },
    searchPlaceholder: 'Search...',
    showClear: true
  }
}
