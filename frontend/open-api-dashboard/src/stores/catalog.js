import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as catalogApi from '@/api/catalog'

// API 目录 store
export const useCatalogStore = defineStore('catalog', () => {
  const apis = ref([])
  const total = ref(0)
  const loading = ref(false)

  // 加载 API 列表
  async function loadApis(params) {
    loading.value = true
    try {
      const data = await catalogApi.listApis(params)
      apis.value = Array.isArray(data) ? data : (data.items || [])
      total.value = apis.value.length
    } finally {
      loading.value = false
    }
  }

  // 一键生成 SQL API
  async function generateSql(data) {
    return await catalogApi.generateFromSql(data)
  }

  // 一键生成模型 API
  async function generateModel(data) {
    return await catalogApi.generateFromModel(data)
  }

  // 一键生成函数 API
  async function generateFunction(data) {
    return await catalogApi.generateFromFunction(data)
  }

  return {
    apis,
    total,
    loading,
    loadApis,
    generateSql,
    generateModel,
    generateFunction,
  }
})