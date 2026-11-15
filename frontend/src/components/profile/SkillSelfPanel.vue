<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../../api/client'

/**
 * 技能自评（模块 5 前半）。
 *
 * 等级用 0–5 行为锚定，不是「熟练 / 精通」：锚点描述「你实际做到过什么」。
 * 保存是**批量 upsert**——只提交改动过的技能，未动过的保持不变，
 * 这样「改一项」不会把另外 60 项一起重置。
 */
const loading = ref(true)
const saving = ref(false)
const categories = ref([])
const skills = ref([])
const levels = reactive({})
const original = ref({})
const activeCategory = ref('')

const anchors = [
  { level: 0, label: '未接触', hint: '没学过，也说不清它解决什么问题' },
  { level: 1, label: '听过概念', hint: '看过教程，能解释是什么，没动过手' },
  { level: 2, label: '跟做完成', hint: '跟教程做过一遍，脱离教程要频繁查资料' },
  { level: 3, label: '独立完成', hint: '不靠教程做出完整模块，能自己排查报错' },
  { level: 4, label: '工程实践', hint: '实习或多人项目里用过，处理过边界与线上问题' },
  { level: 5, label: '可教他人', hint: '能设计取舍、定位疑难问题，并讲清楚为什么' },
]

const grouped = computed(() => categories.value
  .filter((category) => !activeCategory.value || category.id === activeCategory.value)
  .map((category) => ({
    ...category,
    items: skills.value.filter((skill) => skill.categoryId === category.id),
  }))
  .filter((group) => group.items.length))

const dirtyCount = computed(() => Object.keys(levels).filter((id) => levels[id] !== original.value[id]).length)

async function load() {
  loading.value = true
  try {
    const [cats, list, mine] = await Promise.all([
      http.get('/skills/categories'),
      http.get('/skills'),
      http.get('/user-skills'),
    ])
    categories.value = cats.items
    skills.value = list.items
    const map = {}
    for (const skill of list.items) map[skill.id] = 0
    for (const item of mine.items) map[item.skillId] = item.level
    Object.assign(levels, map)
    original.value = { ...map }
  } catch (error) {
    ElMessage.error(error.message || '技能加载失败')
  } finally {
    loading.value = false
  }
}

async function save() {
  const items = Object.keys(levels)
    .filter((id) => levels[id] !== original.value[id])
    .map((id) => ({ skillId: id, level: levels[id], learnedAt: null, lastUsedAt: null }))
  if (!items.length) {
    ElMessage.info('没有改动需要保存')
    return
  }
  saving.value = true
  try {
    const data = await http.put('/user-skills', { items })
    const map = {}
    for (const skill of skills.value) map[skill.id] = 0
    for (const item of data.items) map[item.skillId] = item.level
    Object.assign(levels, map)
    original.value = { ...map }
    ElMessage.success(`已保存 ${items.length} 项自评`)
  } catch (error) {
    ElMessage.error(error.message || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="panel">
    <div class="head">
      <div>
        <h3>技能自评</h3>
        <p class="sub">按 0–5 级行为锚定自评。没自评过的技能按 0 处理，但会在匹配页提示「尚未完成自评」，不会显示 0%。</p>
      </div>
      <div class="head-actions">
        <el-select v-model="activeCategory" size="small" placeholder="全部维度" clearable style="width: 150px">
          <el-option v-for="item in categories" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-button type="primary" :loading="saving" :disabled="!dirtyCount" @click="save">
          保存改动{{ dirtyCount ? `（${dirtyCount}）` : '' }}
        </el-button>
      </div>
    </div>

    <el-collapse class="anchors">
      <el-collapse-item title="0–5 级锚点说明（自评前先看一眼）" name="1">
        <div class="anchor-grid">
          <div v-for="item in anchors" :key="item.level" class="anchor">
            <span class="anchor-level">L{{ item.level }} · {{ item.label }}</span>
            <span class="anchor-hint">{{ item.hint }}</span>
          </div>
        </div>
      </el-collapse-item>
    </el-collapse>

    <section v-for="group in grouped" :key="group.id" class="group">
      <div class="group-head">
        <h4>{{ group.name }}</h4>
        <span class="group-why">{{ group.why }}</span>
      </div>
      <div v-for="skill in group.items" :key="skill.id" class="row" :class="{ rated: levels[skill.id] > 0 }">
        <div class="row-main">
          <span class="row-name">{{ skill.name }}</span>
          <span class="row-desc">{{ skill.description }}</span>
        </div>
        <el-rate
          :model-value="levels[skill.id]"
          :max="5"
          :colors="['#c0392b', '#c0392b', '#c0392b']"
          @update:model-value="(value) => (levels[skill.id] = value)"
        />
      </div>
    </section>

    <el-empty v-if="!loading && !grouped.length" description="没有技能数据" />
  </div>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.head h3 {
  font-size: 14px;
  font-weight: 600;
}

.sub {
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--cp-text-3);
  max-width: 620px;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.anchors {
  border: 1px solid var(--cp-border);
  border-radius: 8px;
  padding: 0 12px;
  background: #fafbfd;
}

.anchor-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 8px 18px;
}

.anchor {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 11.5px;
}

.anchor-level {
  font-weight: 600;
  color: var(--cp-text-2);
}

.anchor-hint {
  color: var(--cp-text-3);
}

.group {
  border-top: 1px dashed var(--cp-border);
  padding-top: 10px;
}

.group-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 6px;
}

.group-head h4 {
  font-size: 13px;
  font-weight: 600;
}

.group-why {
  font-size: 11px;
  color: var(--cp-text-3);
}

.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 7px 6px;
  border-radius: 6px;
}

.row:hover {
  background: #fafbfd;
}

.row.rated .row-name {
  color: var(--cp-text);
}

.row-main {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.row-name {
  font-size: 12.5px;
  color: var(--cp-text-2);
}

.row-desc {
  font-size: 11px;
  color: var(--cp-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 620px;
}
</style>
