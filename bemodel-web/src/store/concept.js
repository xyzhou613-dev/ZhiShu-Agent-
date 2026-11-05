import { defineStore } from 'pinia'
import { listConcepts } from '../api/ontology'

// 概念列表在数据源绑定、统一口径、链路追溯多个页面复用，缓存一份
export const useConceptStore = defineStore('concept', {
  state: () => ({
    concepts: [],
    loaded: false
  }),
  actions: {
    async fetchAll(force = false) {
      if (this.loaded && !force) return
      this.concepts = await listConcepts()
      this.loaded = true
    }
  }
})
