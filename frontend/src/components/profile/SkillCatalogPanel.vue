<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../../api/client'

/**
 * 技能词典（模块 4）——「我的能力」页的第四个 tab。
 *
 * 只读视图：回答「这个方向由哪些能力构成、先学哪个再学哪个」。不掺个人数据，
 * 但会在卡片上标出「我的等级」，因为看不到自己的位置时，词典就只是张目录。
 *
 * 原先是独立的「技能图谱」页，与「技能画像」并列在导航里；两个入口都叫「技能」，
 * 用户分不清该点哪个，自评这类核心动作反而被埋进二级 tab。合并后这里只负责查阅。
 */
const loading = ref(true)
const categories = ref([])
const skills = ref([])
const relations = ref([])
const activeCategory = ref('')
const keyword = ref('')
const myLevels = ref({})
const detail = ref(null)
const drawerVisible = ref(false)

const filtered = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  return skills.value.filter((skill) => {
    if (activeCategory.value && skill.categoryId !== activeCategory.value) return false
    if (!key) return true
    return skill.name.toLowerCase().includes(key) || (skill.nameEn || '').toLowerCase().includes(key)
  })
})

const grouped = computed(() => {
  const map = new Map()
  for (const category of categories.value) {
    const items = filtered.value.filter((skill) => skill.categoryId === category.id)
    if (items.length) map.set(category.id, { ...category, items })
  }
  return [...map.values()]
})

const relationsOf = computed(() => {
  if (!detail.value) return { before: [], after: [], related: [] }
  const id = detail.value.id
  const name = (skillId) => skills.value.find((s) => s.id === skillId)?.name || skillId
  const before = relations.value.filter((r) => r.toSkillId === id && r.relationType === 'prerequisite').map((r) => name(r.fromSkillId))
  const after = relations.value.filter((r) => r.fromSkillId === id && r.relationType === 'prerequisite').map((r) => name(r.toSkillId))
  const related = relations.value
    .filter((r) => (r.fromSkillId === id || r.toSkillId === id) && r.relationType !== 'prerequisite')
    .map((r) => `${name(r.fromSkillId === id ? r.toSkillId : r.fromSkillId)}（${r.relationType === 'ecosystem' ? '生态' : '相关'}）`)
  return { before, after, related }
})

async function load() {
  loading.value = true
  try {
    const [cats, list, rels] = await Promise.all([
      http.get('/skills/categories'),
      http.get('/skills'),
      http.get('/skills/relations'),
    ])
    categories.value = cats.items
    skills.value = list.items
    relations.value = rels.items
    try {
      const mine = await http.get('/user-skills')
      myLevels.value = Object.fromEntries(mine.items.map((item) => [item.skillId, item.level]))
    } catch {
      myLevels.value = {}
    }
  } catch (error) {
    ElMessage.error(error.message || '技能图谱加载失败')
  } finally {
    loading.value = false
  }
}

function open(skill) {
  detail.value = skill
  drawerVisible.value = true
}

function levelText(skillId) {
  const level = myLevels.value[skillId]
  return level === undefined ? '未自评' : `我的等级 L${level}`
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="page">
    <div class="toolbar">
      <el-radio-group v-model="activeCategory" size="small">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button v-for="item in categories" :key="item.id" :value="item.id">{{ item.short || item.name }}</el-radio-button>
      </el-radio-group>
      <div class="spacer" />
      <el-input v-model="keyword" placeholder="搜技能名或英文名" clearable style="max-width: 220px" />
    </div>

    <section v-for="group in grouped" :key="group.id" class="group">
      <div class="group-head">
        <h3>{{ group.name }}</h3>
        <span class="why">{{ group.why }}</span>
      </div>
      <div class="cards">
        <button v-for="skill in group.items" :key="skill.id" class="card" type="button" @click="open(skill)">
          <span class="name">{{ skill.name }}</span>
          <span class="desc">{{ skill.description }}</span>
          <span class="meta">
            <el-tag v-if="myLevels[skill.id] !== undefined" size="small" :type="myLevels[skill.id] >= 3 ? 'danger' : 'info'" effect="plain">
              L{{ myLevels[skill.id] }}
            </el-tag>
            <span v-else class="muted">未自评</span>
            <span class="id">{{ skill.id }}</span>
          </span>
        </button>
      </div>
    </section>

    <el-empty v-if="!loading && !grouped.length" description="没有匹配的技能" />

    <el-drawer v-model="drawerVisible" :title="detail?.name" size="420px">
      <template v-if="detail">
        <p class="drawer-desc">{{ detail.description }}</p>
        <el-descriptions :column="1" size="small" border>
          <el-descriptions-item label="技能 id">{{ detail.id }}</el-descriptions-item>
          <el-descriptions-item label="维度">
            {{ categories.find((c) => c.id === detail.categoryId)?.name || detail.categoryId }}
          </el-descriptions-item>
          <el-descriptions-item label="我的等级">{{ levelText(detail.id) }}</el-descriptions-item>
        </el-descriptions>

        <h4 class="drawer-title">学习顺序</h4>
        <div class="rel-block">
          <span class="rel-label">先学</span>
          <span v-if="relationsOf.before.length" class="rel-items">{{ relationsOf.before.join('、') }}</span>
          <span v-else class="muted">没有前置技能</span>
        </div>
        <div class="rel-block">
          <span class="rel-label">再学</span>
          <span v-if="relationsOf.after.length" class="rel-items">{{ relationsOf.after.join('、') }}</span>
          <span v-else class="muted">没有后续技能</span>
        </div>
        <div class="rel-block">
          <span class="rel-label">常一起用</span>
          <span v-if="relationsOf.related.length" class="rel-items">{{ relationsOf.related.join('、') }}</span>
          <span v-else class="muted">—</span>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.spacer {
  flex: 1;
}

.group-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 10px;
}

.group-head h3 {
  font-size: 14px;
  font-weight: 600;
}

.why {
  font-size: 11.5px;
  color: var(--cp-text-3);
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(238px, 1fr));
  gap: 10px;
}

.card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px;
  text-align: left;
  background: #fff;
  border: 1px solid var(--cp-border);
  border-radius: 9px;
  cursor: pointer;
  font: inherit;
  color: inherit;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.card:hover {
  border-color: var(--cp-primary);
  box-shadow: 0 2px 10px rgba(79, 70, 229, 0.08);
}

.name {
  font-size: 13px;
  font-weight: 600;
}

.desc {
  font-size: 11.5px;
  line-height: 1.55;
  color: var(--cp-text-3);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 2px;
}

.id {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 10.5px;
  color: var(--cp-text-3);
}

.muted {
  color: var(--cp-text-3);
  font-size: 11.5px;
}

.drawer-desc {
  margin-bottom: 12px;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--cp-text-2);
}

.drawer-title {
  margin: 18px 0 8px;
  font-size: 13px;
}

.rel-block {
  display: flex;
  gap: 10px;
  padding: 6px 0;
  font-size: 12.5px;
  border-top: 1px dashed var(--cp-border);
}

.rel-label {
  flex: none;
  width: 56px;
  color: var(--cp-text-3);
}

.rel-items {
  color: var(--cp-text);
}
</style>
