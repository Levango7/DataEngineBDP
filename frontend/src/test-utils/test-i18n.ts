/**
 * 测试环境统一 i18n 实例工厂
 *
 * 背景（对应 KNOWN-FAILURES 中"测试环境没有正确安装 i18n"一类失败）：
 * - 大量组件在 `<script setup>` 里调用 `useI18n()`。若 mount 时没有 `app.use(i18n)`，
 *   vue-i18n 会抛 `SyntaxError: Need to install with 'app.use' function`（NOT_INSTALLED）。
 * - 部分测试虽然装了 i18n，但只塞进三五个词条，组件里其它 key 取不到译文，
 *   断言拿到的是原始 key（如 `common.empty`、`jobmgmt.title`）而不是译文。
 *
 * 统一策略：
 * - 框架级词条（common / app / nav / login）直接复用生产 locale 文件，
 *   避免每个测试各写一套、且各写各的版本；
 * - 页面级模块词条由测试按需 `await loadModuleI18n('jobmgmt')` 加载，与生产路由预加载一致；
 * - 关闭 missing / fallback 警告，避免测试输出噪音（缺失 key 会直接显示为 key，测试可见）。
 *
 * 使用方式：
 * - 默认：`src/test-setup.ts` 已把它装进 `config.global.plugins`，绝大多数测试无需关心；
 * - 需要特定语种或特定模块词条时，在测试里 `global: { plugins: [createTestI18n('en-US')] }`
 *   （VTU 会把 mount 级插件追加在全局插件之后，后者生效，不会冲突）。
 */
import { createI18n } from 'vue-i18n'
import zhCN from '@/i18n/locales/zh-CN.json'
import enUS from '@/i18n/locales/en-US.json'

export type TestLocale = 'zh-CN' | 'en-US'

/**
 * 创建测试用 i18n 实例（组合式 API，legacy=false）
 *
 * @param locale 初始语种，默认 zh-CN（与生产默认一致）
 */
export function createTestI18n(locale: TestLocale = 'zh-CN') {
  return createI18n({
    legacy: false,
    locale,
    fallbackLocale: 'zh-CN',
    globalInjection: true,
    messages: {
      'zh-CN': { ...zhCN },
      'en-US': { ...enUS }
    },
    missingWarn: false,
    fallbackWarn: false
  })
}
