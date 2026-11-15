import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import http from '../api/client'

/** 技能词典、维度、等级锚点、岗位列表——全站只拉一次 */
export const useCatalogStore = defineStore('catalog', () => {
  const dimensions = ref([])
  const levels = ref([])
  const skills = ref([])
  const jobs = ref([])
  const dataVersion = ref('')
  const scoringVersion = ref('')
  const disclaimer = ref('')
  const loaded = ref(false)
  const loading = ref(false)
  let inflight = null

  const skillMap = ref({})

  const dimNameMap = computed(() =>
    Object.fromEntries(dimensions.value.map((d) => [d.id, d.name])),
  )

  /** 接口返回的是维度 id，界面一律显示中文名 */
  function dimName(id) {
    return dimNameMap.value[id] || id
  }

  async function ensure() {
    if (loaded.value) return
    if (inflight) return inflight
    loading.value = true
    inflight = (async () => {
      const [meta, skillRes, jobRes] = await Promise.all([
        http.get('/skills/dimensions'),
        http.get('/skills'),
        http.get('/jobs'),
      ])
      dimensions.value = meta.dimensions
      levels.value = meta.levels
      dataVersion.value = meta.dataVersion
      scoringVersion.value = meta.scoringVersion
      disclaimer.value = meta.disclaimer
      skills.value = skillRes.skills
      jobs.value = jobRes.jobs
      skillMap.value = Object.fromEntries(skillRes.skills.map((s) => [s.id, s]))
      loaded.value = true
      loading.value = false
      inflight = null
    })().catch((e) => {
      loading.value = false
      inflight = null
      throw e
    })
    return inflight
  }

  /** 按维度分组（保持 dimensions 的展示顺序） */
  function skillsByDim() {
    const buckets = new Map(dimensions.value.map((d) => [d.id, []]))
    for (const s of skills.value) {
      if (buckets.has(s.dim)) buckets.get(s.dim).push(s)
    }
    return dimensions.value.map((d) => ({ ...d, skills: buckets.get(d.id) || [] }))
  }

  return {
    dimensions, levels, skills, jobs, dataVersion, scoringVersion, disclaimer,
    loaded, loading, skillMap, dimNameMap, dimName, ensure, skillsByDim,
  }
})
