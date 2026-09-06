import type { Preview } from '@storybook/vue3'
import { createPinia, setActivePinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import { i18n } from '../src/i18n'

// 为每个 story 创建独立的 pinia 实例，避免 store 状态跨 story 污染
const pinia = createPinia()
setActivePinia(pinia)

const preview: Preview = {
  parameters: {
    controls: {
      matchers: {
        color: /(background|color)$/i,
        date: /Date$/i
      }
    },
    layout: 'padded'
  },
  globalTypes: {
    locale: {
      name: 'Locale',
      description: 'Internationalization locale',
      defaultValue: 'zh-CN',
      toolbar: {
        icon: 'globe',
        items: [
          { value: 'zh-CN', title: '中文' },
          { value: 'en-US', title: 'English' }
        ]
      }
    }
  },
  decorators: [
    (story, context) => {
      // 同步 toolbar 选择的 locale 到 i18n 实例
      const locale = context.globals.locale || 'zh-CN'
      if (i18n.global.locale.value !== locale) {
        i18n.global.locale.value = locale
      }

      return {
        components: { story },
        template: '<el-config-provider><story /></el-config-provider>',
        setup() {
          return {}
        }
      }
    }
  ],
  appSetup: (app) => {
    app.use(ElementPlus)
    app.use(i18n)
    app.use(pinia)

    // 全局注册所有 Element Plus 图标
    for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
      app.component(key, component)
    }
  }
}

export default preview
