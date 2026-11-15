<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import http from '../api/client'
import { useCatalogStore } from '../stores/catalog'

const catalog = useCatalogStore()

const profiles = ref([])
const currentId = ref(null)
const ratings = reactive({})
const baseline = ref('{}')
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const toast = ref('')
const activeDim = ref('')

const creating = ref(false)
const newName = ref('')

// 简历解析面板
const resumeOpen = ref(false)
const resumeText = ref('')
const resumeBusy = ref(false)
const suggestions = ref([])
const picked = reactive({})

let toastTimer = null
let observer = null

const MAX_PROFILES = 10

const total = computed(() => catalog.skills.length)
const ratedCount = computed(() => Object.keys(cleanRatings()).length)

const currentProfile = computed(() => profiles.value.find((p) => p.id === currentId.value) || null)

function cleanRatings() {
  const out = {}
  Object.keys(ratings)
    .sort()
    .forEach((k) => {
      if (ratings[k] !== null && ratings[k] !== undefined && ratings[k] >= 0) out[k] = ratings[k]
    })
  return out
}

const dirty = computed(() => JSON.stringify(cleanRatings()) !== baseline.value)

const grouped = computed(() => catalog.skillsByDim())

const coveredDims = computed(() => {
  const set = new Set()
  for (const id of Object.keys(cleanRatings())) {
    const s = catalog.skillMap[id]
    if (s) set.add(s.dim)
  }
  return set.size
})

function dimRated(dim) {
  return dim.skills.filter((s) => ratings[s.id] !== undefined && ratings[s.id] !== null).length
}

function groupSkills(list) {
  const order = []
  const map = new Map()
  for (const s of list) {
    const key = s.group || '其他'
    if (!map.has(key)) {
      map.set(key, [])
      order.push(key)
    }
    map.get(key).push(s)
  }
  return order.map((name) => ({ name, items: map.get(name) }))
}

function showToast(text) {
  toast.value = text
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toast.value = ''), 2600)
}

function toggle(skillId, level) {
  if (ratings[skillId] === level) delete ratings[skillId]
  else ratings[skillId] = level
}

async function loadProfiles(autoSelect = true) {
  const data = await http.get('/profiles')
  profiles.value = data.profiles
  if (autoSelect && data.profiles.length) {
    const stillThere = data.profiles.some((p) => p.id === currentId.value)
    if (!stillThere) await select(data.profiles[0].id)
  }
  if (!data.profiles.length) {
    currentId.value = null
    clearAll()
  }
}

async function select(id) {
  if (dirty.value && !window.confirm('当前画像有未保存的改动，切换后会丢失。继续？')) return
  currentId.value = id
  const data = await http.get(`/profiles/${id}`)
  Object.keys(ratings).forEach((k) => delete ratings[k])
  for (const s of data.skills) ratings[s.skillId] = s.level
  baseline.value = JSON.stringify(cleanRatings())
}

function clearAll() {
  Object.keys(ratings).forEach((k) => delete ratings[k])
  baseline.value = '{}'
}

function reset() {
  Object.keys(ratings).forEach((k) => delete ratings[k])
  Object.entries(JSON.parse(baseline.value)).forEach(([k, v]) => (ratings[k] = v))
}

async function createProfile() {
  const name = newName.value.trim() || `技能画像 ${profiles.value.length + 1}`
  creating.value = false
  newName.value = ''
  try {
    const p = await http.post('/profiles', { name })
    await loadProfiles(false)
    currentId.value = p.id
    clearAll()
    showToast('画像已创建，开始自评吧')
  } catch (e) {
    error.value = e.message
  }
}

async function removeProfile() {
  const p = currentProfile.value
  if (!p) return
  if (!window.confirm(`删除「${p.name}」？该画像的自评记录会一并删除，已存档的报告不受影响。`)) return
  try {
    await http.delete(`/profiles/${p.id}`)
    baseline.value = JSON.stringify(cleanRatings())
    currentId.value = null
    await loadProfiles()
    showToast('画像已删除')
  } catch (e) {
    error.value = e.message
  }
}

async function save() {
  if (!currentId.value || saving.value) return
  saving.value = true
  error.value = ''
  try {
    const skills = Object.entries(cleanRatings()).map(([skillId, level]) => ({ skillId, level }))
    await http.put(`/profiles/${currentId.value}`, { skills })
    baseline.value = JSON.stringify(cleanRatings())
    await loadProfiles(false)
    showToast(`已保存 ${skills.length} 项自评`)
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = false
  }
}

async function parseResume() {
  const text = resumeText.value.trim()
  if (!text) return
  resumeBusy.value = true
  error.value = ''
  try {
    const data = await http.post('/resume/parse', { text })
    suggestions.value = data.suggestions
    Object.keys(picked).forEach((k) => delete picked[k])
    for (const s of data.suggestions) picked[s.skillId] = true
    if (!data.suggestions.length) error.value = '没识别出技能关键词，可以手动勾选，或换个说法再试'
  } catch (e) {
    error.value = e.message
  } finally {
    resumeBusy.value = false
  }
}

function applySuggestions() {
  let n = 0
  for (const s of suggestions.value) {
    if (picked[s.skillId]) {
      ratings[s.skillId] = s.level
      n += 1
    }
  }
  resumeOpen.value = false
  suggestions.value = []
  resumeText.value = ''
  showToast(`已填入 ${n} 项，请逐条核对后再保存`)
}

function closeResume() {
  resumeOpen.value = false
  suggestions.value = []
  resumeText.value = ''
}

function beforeUnload(e) {
  if (dirty.value) {
    e.preventDefault()
    e.returnValue = ''
  }
}

onMounted(async () => {
  window.addEventListener('beforeunload', beforeUnload)
  try {
    await catalog.ensure()
    await loadProfiles()
    // 左侧维度导航跟随滚动高亮
    observer = new IntersectionObserver(
      (entries) => {
        const visible = entries.filter((e) => e.isIntersecting)
        if (visible.length) activeDim.value = visible[0].target.id.replace('dim-', '')
      },
      { rootMargin: '-88px 0px -70% 0px', threshold: 0 },
    )
    grouped.value.forEach((d) => {
      const el = document.getElementById(`dim-${d.id}`)
      if (el) observer.observe(el)
    })
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', beforeUnload)
  if (observer) observer.disconnect()
  clearTimeout(toastTimer)
})
</script>

<template>
  <div class="shell">
    <header class="page-head">
      <div>
        <h1 class="page-title">技能自评</h1>
        <p class="page-sub">
          按真实水平逐项打分，拿不准时选低一档。没接触过的选 0，完全不选则视为未填。
        </p>
      </div>
      <div class="head-actions">
        <button class="btn" type="button" @click="resumeOpen = true">从简历粘贴</button>
        <button class="btn btn-primary" type="button" :disabled="!dirty || saving" @click="save">
          {{ saving ? '保存中…' : '保存' }}
        </button>
      </div>
    </header>

    <div v-if="error" class="alert alert-error page-alert">
      {{ error }}
      <button class="alert-x" type="button" @click="error = ''">×</button>
    </div>

    <div v-if="loading" class="card card-pad loading-block">
      <div class="skeleton" style="height: 16px; width: 40%"></div>
      <div class="skeleton" style="height: 16px; width: 65%; margin-top: 10px"></div>
    </div>

    <template v-else>
      <!-- 画像切换条 -->
      <div class="card profile-bar">
        <div class="chips">
          <button
            v-for="p in profiles"
            :key="p.id"
            type="button"
            class="chip"
            :class="{ on: p.id === currentId }"
            @click="select(p.id)"
          >
            {{ p.name }}
            <span class="chip-count num">{{ p.skillCount }}</span>
          </button>
          <button
            v-if="profiles.length < MAX_PROFILES"
            type="button"
            class="chip chip-add"
            @click="creating = true"
          >
            ＋ 新建
          </button>
        </div>
        <div class="bar-right">
          <span class="muted num">已自评 <b class="strong">{{ ratedCount }}</b> / {{ total }}</span>
          <button
            v-if="currentProfile"
            type="button"
            class="btn btn-sm btn-ghost danger"
            @click="removeProfile"
          >
            删除画像
          </button>
        </div>
      </div>

      <div v-if="creating" class="card card-pad create-panel">
        <label class="field">
          <span class="field-label">画像名称</span>
          <input
            v-model="newName"
            class="input"
            placeholder="例如：前端方向、数据分析方向"
            maxlength="64"
            @keyup.enter="createProfile"
          />
          <span class="field-hint">最多 10 份，方便按不同求职方向分别自评</span>
        </label>
        <div class="create-actions">
          <button class="btn" type="button" @click="creating = false">取消</button>
          <button class="btn btn-primary" type="button" @click="createProfile">创建</button>
        </div>
      </div>

      <!-- 空态 -->
      <div v-if="!profiles.length" class="card empty">
        <p class="empty-title">还没有技能画像</p>
        <p class="empty-sub">先建一份，再按 6 个维度逐项自评</p>
        <button class="btn btn-primary" type="button" @click="creating = true">创建第一份画像</button>
      </div>

      <template v-else-if="currentId">
        <!-- 等级图例 -->
        <div class="card legend">
          <span class="legend-label">等级</span>
          <div class="legend-items">
            <span v-for="l in catalog.levels" :key="l.level" class="legend-item">
              <b class="num">{{ l.level }}</b>{{ l.label }}
            </span>
          </div>
        </div>

        <div class="layout">
          <aside class="side">
            <a
              v-for="d in grouped"
              :key="d.id"
              :href="`#dim-${d.id}`"
              class="side-item"
              :class="{ on: activeDim === d.id }"
            >
              <span class="side-name">{{ d.name }}</span>
              <span class="side-count num" :class="{ done: dimRated(d) === d.skills.length }">
                {{ dimRated(d) }}/{{ d.skills.length }}
              </span>
            </a>
            <div class="side-summary">
              覆盖 {{ coveredDims }} / {{ grouped.length }} 个维度
            </div>
          </aside>

          <div class="dims">
            <section v-for="d in grouped" :id="`dim-${d.id}`" :key="d.id" class="card dim">
              <div class="dim-head">
                <div class="dim-title">
                  <h2>{{ d.name }}</h2>
                  <span class="dim-tag">{{ d.skills.length }} 项</span>
                </div>
                <p class="dim-desc">{{ d.desc }}</p>
              </div>

              <div v-for="g in groupSkills(d.skills)" :key="g.name" class="group">
                <div class="group-title">{{ g.name }}</div>
                <div v-for="s in g.items" :key="s.id" class="skill-row">
                  <div class="skill-info">
                    <span class="skill-name">{{ s.name }}</span>
                    <span v-if="s.note" class="skill-note">{{ s.note }}</span>
                  </div>
                  <div class="scale">
                    <button
                      v-for="l in catalog.levels"
                      :key="l.level"
                      type="button"
                      class="lv"
                      :class="{ on: ratings[s.id] === l.level }"
                      :title="`${l.label} — ${l.anchor}`"
                      @click="toggle(s.id, l.level)"
                    >
                      {{ l.level }}
                    </button>
                  </div>
                </div>
              </div>
            </section>
          </div>
        </div>
      </template>
    </template>

    <!-- 未保存提示 -->
    <Transition name="slide">
      <div v-if="dirty && currentId" class="savebar">
        <span class="savebar-text">有未保存的改动</span>
        <div class="savebar-actions">
          <button class="btn btn-sm" type="button" @click="reset">撤销</button>
          <button class="btn btn-sm btn-primary" type="button" :disabled="saving" @click="save">
            {{ saving ? '保存中…' : '保存画像' }}
          </button>
        </div>
      </div>
    </Transition>

    <!-- 简历解析面板 -->
    <Transition name="fade">
      <div v-if="resumeOpen" class="overlay" @click.self="closeResume">
        <div class="modal card">
          <div class="modal-head">
            <h2>从简历文本提取技能</h2>
            <button class="modal-x" type="button" @click="closeResume">×</button>
          </div>
          <div class="modal-body">
            <p class="modal-hint">
              粘贴简历里的技能栏或项目经历。识别结果只是<strong>预填建议</strong>，不会直接保存，请逐条核对。
            </p>
            <textarea
              v-model="resumeText"
              class="input"
              rows="7"
              placeholder="例如：熟悉 JavaScript 与 Vue，用 React 做过后台管理系统，了解 TypeScript…"
            ></textarea>
            <button
              class="btn btn-primary"
              type="button"
              :disabled="!resumeText.trim() || resumeBusy"
              @click="parseResume"
            >
              {{ resumeBusy ? '识别中…' : '识别技能' }}
            </button>

            <div v-if="suggestions.length" class="suggests">
              <div class="suggests-head">识别到 {{ suggestions.length }} 项，勾选后填入</div>
              <label v-for="s in suggestions" :key="s.skillId" class="suggest-row">
                <input v-model="picked[s.skillId]" type="checkbox" />
                <span class="suggest-name">{{ s.name }}</span>
                <span class="tag">推定 {{ s.level }} 级</span>
                <span class="suggest-alias">命中「{{ s.matchedAlias }}」</span>
              </label>
            </div>
          </div>
          <div class="modal-foot">
            <button class="btn" type="button" @click="closeResume">取消</button>
            <button
              class="btn btn-primary"
              type="button"
              :disabled="!suggestions.length"
              @click="applySuggestions"
            >
              填入选中项
            </button>
          </div>
        </div>
      </div>
    </Transition>

    <Transition name="fade">
      <div v-if="toast" class="toast">{{ toast }}</div>
    </Transition>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 18px;
}

/* 底部有固定保存条，留出空间，避免最后一行技能被压住 */
.shell {
  padding-bottom: 108px;
}

.head-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.page-alert {
  margin-bottom: 16px;
  position: relative;
}

.alert-x {
  position: absolute;
  top: 8px;
  right: 10px;
  border: 0;
  background: transparent;
  font-size: 16px;
  line-height: 1;
  color: inherit;
  opacity: 0.55;
}

.loading-block {
  display: grid;
  gap: 0;
}

.profile-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.chips {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 11px;
  border: 1px solid var(--border-strong);
  border-radius: 7px;
  background: var(--surface);
  font-size: 13px;
  color: var(--text-2);
  transition: all 0.15s;
}

.chip:hover {
  border-color: #c3c7d6;
  color: var(--text);
}

.chip.on {
  background: var(--primary-weak);
  border-color: #c7c9f7;
  color: var(--primary-dark);
  font-weight: 550;
}

.chip-count {
  font-size: 11px;
  padding: 1px 5px;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.05);
  color: var(--text-3);
}

.chip.on .chip-count {
  background: rgba(79, 70, 229, 0.14);
  color: var(--primary-dark);
}

.chip-add {
  border-style: dashed;
  color: var(--text-3);
}

.bar-right {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}

.strong {
  color: var(--text);
  font-weight: 600;
}

.danger:hover {
  color: var(--red);
  background: var(--red-weak);
}

.create-panel {
  margin-bottom: 14px;
}

.create-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
}

.empty-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text);
}

.empty-sub {
  margin: 6px 0 16px;
  font-size: 13px;
}

.legend {
  display: flex;
  align-items: baseline;
  gap: 14px;
  padding: 10px 16px;
  margin-bottom: 16px;
  font-size: 12.5px;
  color: var(--text-2);
}

.legend-label {
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.1em;
  color: var(--text-3);
  flex-shrink: 0;
}

.legend-items {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.legend-item b {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 17px;
  height: 17px;
  margin-right: 5px;
  border-radius: 4px;
  background: #eef0f6;
  font-size: 11px;
  color: var(--text-2);
}

.layout {
  display: grid;
  grid-template-columns: 168px minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}

.side {
  position: sticky;
  top: 76px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.side-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 7px;
  font-size: 13px;
  color: var(--text-2);
  border-left: 2px solid transparent;
  transition: background 0.15s, color 0.15s;
}

.side-item:hover {
  background: #eff0f6;
  color: var(--text);
}

.side-item.on {
  background: var(--surface);
  border-left-color: var(--primary);
  color: var(--text);
  font-weight: 550;
  box-shadow: var(--shadow-sm);
}

.side-count {
  font-size: 11px;
  color: var(--text-3);
}

.side-count.done {
  color: var(--green);
}

.side-summary {
  margin-top: 10px;
  padding: 0 10px;
  font-size: 11.5px;
  color: var(--text-3);
}

.dims {
  display: grid;
  gap: 16px;
}

.dim {
  scroll-margin-top: 72px;
  overflow: hidden;
}

.dim-head {
  padding: 16px 20px 14px;
  border-bottom: 1px solid var(--border);
}

.dim-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.dim-title h2 {
  font-size: 15px;
  font-weight: 620;
  letter-spacing: -0.01em;
}

.dim-tag {
  font-size: 11px;
  color: var(--text-3);
  padding: 1px 6px;
  border-radius: 4px;
  background: #f2f3f7;
}

.dim-desc {
  margin-top: 4px;
  font-size: 12.5px;
  color: var(--text-3);
  line-height: 1.6;
}

.group {
  padding: 4px 20px 10px;
}

.group + .group {
  border-top: 1px dashed var(--border);
  padding-top: 12px;
}

.group-title {
  padding: 10px 0 4px;
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--text-3);
}

.skill-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 7px 0;
  border-radius: 6px;
}

.skill-info {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
  flex-wrap: wrap;
}

.skill-name {
  font-size: 13.5px;
  color: var(--text);
}

.skill-note {
  font-size: 11.5px;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 320px;
}

.scale {
  display: flex;
  gap: 3px;
  flex-shrink: 0;
}

.lv {
  width: 28px;
  height: 27px;
  border: 1px solid var(--border-strong);
  border-radius: 5px;
  background: var(--surface);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--text-3);
  transition: all 0.12s;
}

.lv:hover {
  border-color: var(--primary);
  color: var(--primary);
  background: var(--primary-weak);
}

.lv.on {
  background: var(--primary);
  border-color: var(--primary);
  color: #fff;
  font-weight: 600;
}

.savebar {
  position: fixed;
  left: 50%;
  bottom: 20px;
  transform: translateX(-50%);
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 10px 14px 10px 18px;
  border-radius: 10px;
  background: #1c1f2a;
  color: #fff;
  box-shadow: 0 12px 30px -10px rgba(20, 24, 40, 0.5);
}

.savebar-text {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.82);
}

.savebar-actions {
  display: flex;
  gap: 8px;
}

.savebar-actions .btn {
  background: transparent;
  border-color: rgba(255, 255, 255, 0.22);
  color: #fff;
}

.savebar-actions .btn:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.34);
}

.savebar-actions .btn-primary {
  background: var(--primary);
  border-color: var(--primary);
}

.savebar-actions .btn-primary:hover:not(:disabled) {
  background: #5b52f0;
  border-color: #5b52f0;
}

.overlay {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(23, 26, 33, 0.42);
  backdrop-filter: blur(2px);
}

.modal {
  width: 100%;
  max-width: 560px;
  max-height: 86vh;
  display: flex;
  flex-direction: column;
  box-shadow: 0 24px 60px -20px rgba(20, 24, 40, 0.5);
}

.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border);
}

.modal-head h2 {
  font-size: 15px;
  font-weight: 600;
}

.modal-x {
  border: 0;
  background: transparent;
  font-size: 20px;
  line-height: 1;
  color: var(--text-3);
  padding: 0 2px;
}

.modal-body {
  padding: 18px 20px;
  overflow-y: auto;
}

.modal-hint {
  margin-bottom: 12px;
  font-size: 12.5px;
  color: var(--text-2);
  line-height: 1.6;
}

.modal-body .btn {
  margin-top: 10px;
}

.modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 14px 20px;
  border-top: 1px solid var(--border);
  background: var(--surface-2);
  border-radius: 0 0 var(--radius) var(--radius);
}

.suggests {
  margin-top: 18px;
  border-top: 1px solid var(--border);
  padding-top: 14px;
}

.suggests-head {
  font-size: 12.5px;
  color: var(--text-2);
  margin-bottom: 8px;
}

.suggest-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 6px 0;
  font-size: 13px;
  cursor: pointer;
}

.suggest-name {
  font-weight: 500;
}

.suggest-alias {
  font-size: 11.5px;
  color: var(--text-3);
}

.toast {
  position: fixed;
  top: 68px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 50;
  padding: 9px 16px;
  border-radius: 8px;
  background: #1c1f2a;
  color: #fff;
  font-size: 13px;
  box-shadow: 0 10px 24px -10px rgba(20, 24, 40, 0.5);
}

.slide-enter-active,
.slide-leave-active {
  transition: opacity 0.2s, transform 0.2s;
}

.slide-enter-from,
.slide-leave-to {
  opacity: 0;
  transform: translate(-50%, 12px);
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.18s;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

@media (max-width: 860px) {
  .layout {
    grid-template-columns: 1fr;
  }
  .side {
    position: static;
    flex-direction: row;
    overflow-x: auto;
    padding-bottom: 4px;
  }
  .side-item {
    border-left: 0;
    border-bottom: 2px solid transparent;
    white-space: nowrap;
  }
  .side-item.on {
    border-bottom-color: var(--primary);
  }
  .side-summary {
    display: none;
  }
  .skill-note {
    display: none;
  }
}

@media (max-width: 560px) {
  .page-head {
    flex-direction: column;
  }
  .head-actions {
    width: 100%;
  }
  .head-actions .btn {
    flex: 1;
  }
  .skill-row {
    flex-direction: column;
    align-items: stretch;
    gap: 6px;
    padding: 8px 0;
  }
  .scale {
    width: 100%;
  }
  .lv {
    flex: 1;
  }
}
</style>
