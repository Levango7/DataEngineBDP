/**
 * 表单校验统一封装 composable
 *
 * 基于项目主流模式（computed FormRules）封装，提供：
 * - 响应式 rules（从 formData 派生）
 * - validate() 方法（返回 boolean，不抛异常）
 * - submit() 方法（校验通过后执行回调）
 *
 * @example
 * ```ts
 * const formRef = ref<FormInstance>()
 * const formData = ref({ name: '', email: '' })
 * const { rules, validate, submit } = useFormValidation(
 *   formRef,
 *   (data) => ({
 *     name: [{ required: true, message: '请输入名称', trigger: 'blur' }],
 *     email: [{ required: true, message: '请输入邮箱', trigger: 'blur' }]
 *   }),
 *   formData
 * )
 *
 * // 提交时
 * await submit(async () => {
 *   await api.save(formData.value)
 *   ElMessage.success('保存成功')
 * })
 * ```
 */
import { computed, type Ref } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'

export function useFormValidation<T extends Record<string, unknown>>(
  formRef: Ref<FormInstance | undefined>,
  rulesFactory: (form: T) => FormRules,
  formData: Ref<T>
) {
  /** 从 formData 派生的响应式校验规则 */
  const rules = computed<FormRules>(() => rulesFactory(formData.value))

  /**
   * 触发表单校验
   * @returns 校验是否通过（true=通过，false=不通过或表单引用不存在）
   */
  async function validate(): Promise<boolean> {
    if (!formRef.value) return false
    try {
      await formRef.value.validate()
      return true
    } catch {
      return false
    }
  }

  /**
   * 校验通过后执行回调
   * @param onValid 校验通过时的回调函数
   */
  async function submit(onValid: () => Promise<void> | void): Promise<void> {
    const valid = await validate()
    if (!valid) return
    await onValid()
  }

  /** 重置表单校验状态 */
  function resetFields(): void {
    formRef.value?.resetFields()
  }

  /** 清除表单校验状态 */
  function clearValidate(): void {
    formRef.value?.clearValidate()
  }

  return {
    rules,
    validate,
    submit,
    resetFields,
    clearValidate
  }
}
