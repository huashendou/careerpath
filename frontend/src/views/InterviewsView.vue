<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/client'

/**
 * 面试复盘（二期模块 8 简化版）。
 *
 * 不做题库、不做日程：这一页只回答一个问题——「这场面试我哪里没答上来，下次怎么补」。
 * 所以复盘正文（reflection）占的版面比题目表格更大，题目只是用来定位具体卡点。
 */
const route = useRoute()

const loading = ref(true)
const rows = ref([])
const applications = ref([])
const skills = ref([])
const dialogVisible = ref(false)
const editingId = ref(null)
const saving = ref(false)

const form = reactive({
  applicationId: null,
  companyName: '',
  jobTitle: '',
  interviewDate: null,
  round: '',
  format: 'video',
  result: 'pending',
  reflection: '',
  questions: [],
  relatedSkills: [],
})

const formatMeta = { phone: '电话', video: '视频', onsite: '现场' }
const resultMeta = {
  pending: { text: '待定', type: 'info' },
  pass: { text: '通过', type: 'success' },
  reject: { text: '未通过', type: 'danger' },
}

const skillOptions = computed(() => skills.value.map((item) => ({ value: item.id, label: item.name })))

async function load() {
  loading.value = true
  try {
    const [list, apps, skillList] = await Promise.all([
      http.get('/interviews'),
      http.get('/applications'),
      http.get('/skills'),
    ])
    rows.value = list.items
    applications.value = apps.items
    skills.value = skillList.items
  } catch (error) {
    ElMessage.error(error.message || '面试记录加载失败')
  } finally {
    loading.value = false
  }
}

function resetForm() {
  Object.assign(form, {
    applicationId: null, companyName: '', jobTitle: '', interviewDate: null,
    round: '', format: 'video', result: 'pending', reflection: '',
    questions: [], relatedSkills: [],
  })
}

function openCreate() {
  editingId.value = null
  resetForm()
  const preset = Number(route.query.applicationId)
  if (preset) {
    const app = applications.value.find((item) => item.id === preset)
    if (app) {
      form.applicationId = app.id
      form.companyName = app.companyName || ''
      form.jobTitle = app.title || ''
    }
  }
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  Object.assign(form, {
    applicationId: row.applicationId,
    companyName: row.companyName || '',
    jobTitle: row.jobTitle || '',
    interviewDate: row.interviewDate,
    round: row.round || '',
    format: row.format || 'video',
    result: row.result || 'pending',
    reflection: row.reflection || '',
    questions: (row.questions || []).map((item) => ({ ...item })),
    relatedSkills: [...(row.relatedSkills || [])],
  })
  dialogVisible.value = true
}

function fillFromApplication(id) {
  const app = applications.value.find((item) => item.id === id)
  if (app) {
    form.companyName = form.companyName || app.companyName || ''
    form.jobTitle = form.jobTitle || app.title || ''
  }
}

function addQuestion() {
  form.questions.push({ question: '', selfRating: 3, note: '' })
}

async function submit() {
  if (!form.companyName && !form.jobTitle) {
    ElMessage.warning('至少填写公司或岗位，否则复盘以后找不到是哪一场')
    return
  }
  const payload = {
    applicationId: form.applicationId || null,
    companyName: form.companyName || null,
    jobTitle: form.jobTitle || null,
    interviewDate: form.interviewDate || null,
    round: form.round || null,
    format: form.format || null,
    result: form.result,
    reflection: form.reflection || null,
    questions: form.questions.filter((item) => item.question && item.question.trim()),
    relatedSkills: form.relatedSkills,
  }
  saving.value = true
  try {
    if (editingId.value) {
      await http.patch(`/interviews/${editingId.value}`, payload)
      ElMessage.success('已更新')
    } else {
      await http.post('/interviews', payload)
      ElMessage.success('已记录')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(error.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm('删除这条面试记录？', '删除', { type: 'warning' })
  } catch {
    return
  }
  try {
    await http.delete(`/interviews/${row.id}`)
    ElMessage.success('已删除')
    load()
  } catch (error) {
    ElMessage.error(error.message || '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <span class="count cp-muted">共 {{ rows.length }} 场面试</span>
      <div class="spacer" />
      <el-button type="primary" @click="openCreate">记录一场面试</el-button>
    </div>

    <div v-loading="loading" class="list">
      <article v-for="row in rows" :key="row.id" class="card">
        <header class="card-head">
          <div>
            <h3 class="card-title">{{ row.jobTitle || '未填岗位' }}<span v-if="row.companyName" class="company"> · {{ row.companyName }}</span></h3>
            <p class="card-meta cp-num">
              <span>{{ row.interviewDate || '未填日期' }}</span>
              <span v-if="row.round">{{ row.round }}</span>
              <span v-if="row.format">{{ formatMeta[row.format] || row.format }}</span>
            </p>
          </div>
          <div class="card-actions">
            <el-tag size="small" :type="resultMeta[row.result]?.type || 'info'" effect="plain">
              {{ resultMeta[row.result]?.text || row.result }}
            </el-tag>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </div>
        </header>

        <p v-if="row.reflection" class="reflection">{{ row.reflection }}</p>
        <p v-else class="reflection muted">还没有写复盘：面试后 24 小时内写下来最有价值。</p>

        <details v-if="row.questions?.length" class="questions">
          <summary>{{ row.questions.length }} 道题</summary>
          <ul>
            <li v-for="(item, index) in row.questions" :key="index">
              <span class="q">{{ item.question }}</span>
              <span class="rating cp-num">自评 {{ item.selfRating ?? '-' }}/5</span>
              <span v-if="item.note" class="note">{{ item.note }}</span>
            </li>
          </ul>
        </details>

        <div v-if="row.relatedSkills?.length" class="skills">
          <el-tag v-for="skillId in row.relatedSkills" :key="skillId" size="small" effect="plain">
            {{ skills.find((s) => s.id === skillId)?.name || skillId }}
          </el-tag>
        </div>
      </article>
    </div>

    <el-empty v-if="!loading && !rows.length" description="还没有面试记录：从投递清单点「面试复盘」可以直接带过来" />

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑面试复盘' : '记录一场面试'" width="720px" top="6vh">
      <el-form :model="form" label-position="top">
        <div class="grid">
          <el-form-item label="关联投递（可选）">
            <el-select v-model="form.applicationId" filterable clearable placeholder="从投递清单选" style="width: 100%" @change="fillFromApplication">
              <el-option v-for="app in applications" :key="app.id" :label="`${app.title}${app.companyName ? ' · ' + app.companyName : ''}`" :value="app.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="公司">
            <el-input v-model="form.companyName" maxlength="64" />
          </el-form-item>
          <el-form-item label="岗位">
            <el-input v-model="form.jobTitle" maxlength="64" />
          </el-form-item>
          <el-form-item label="面试日期">
            <el-date-picker v-model="form.interviewDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="轮次">
            <el-input v-model="form.round" maxlength="32" placeholder="一面 / 二面 / HR 面" />
          </el-form-item>
          <el-form-item label="形式">
            <el-select v-model="form.format">
              <el-option v-for="(label, value) in formatMeta" :key="value" :label="label" :value="value" />
            </el-select>
          </el-form-item>
          <el-form-item label="结果">
            <el-select v-model="form.result">
              <el-option v-for="(meta, value) in resultMeta" :key="value" :label="meta.text" :value="value" />
            </el-select>
          </el-form-item>
        </div>

        <el-form-item label="复盘">
          <el-input v-model="form.reflection" type="textarea" :rows="4" placeholder="哪题答得好、哪题卡住了、下次怎么补" />
        </el-form-item>

        <el-form-item label="被问到的题">
          <div class="q-rows">
            <div v-for="(item, index) in form.questions" :key="index" class="q-row">
              <el-input v-model="item.question" size="small" placeholder="题目" style="flex: 2" />
              <el-rate v-model="item.selfRating" :max="5" />
              <el-input v-model="item.note" size="small" placeholder="当时怎么答的 / 卡在哪" style="flex: 2" />
              <el-button link type="danger" @click="form.questions.splice(index, 1)">删</el-button>
            </div>
            <el-button link type="primary" @click="addQuestion">+ 加一道题</el-button>
          </div>
        </el-form-item>

        <el-form-item label="涉及技能">
          <el-select v-model="form.relatedSkills" multiple filterable placeholder="选被追问到的技能" style="width: 100%">
            <el-option v-for="item in skillOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
}

.spacer {
  flex: 1;
}

.count {
  font-size: 12px;
}

.list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 12px;
  min-height: 80px;
}

.card {
  padding: 14px 16px;
  background: #fff;
  border: 1px solid var(--cp-border);
  border-radius: 9px;
}

.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.card-title {
  font-size: 13.5px;
  font-weight: 600;
}

.company {
  color: var(--cp-text-2);
  font-weight: 400;
}

.card-meta {
  display: flex;
  gap: 10px;
  margin-top: 2px;
  font-size: 11px;
  color: var(--cp-text-3);
}

.card-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}

.reflection {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.75;
  color: var(--cp-text-2);
  white-space: pre-wrap;
}

.reflection.muted {
  color: var(--cp-text-3);
}

.questions {
  margin-top: 8px;
  font-size: 11.5px;
  color: var(--cp-text-2);
}

.questions summary {
  cursor: pointer;
  color: var(--cp-primary);
}

.questions ul {
  margin: 6px 0 0;
  padding-left: 16px;
}

.questions li {
  margin-bottom: 4px;
}

.q {
  margin-right: 8px;
}

.rating {
  margin-right: 8px;
  color: var(--cp-text-3);
}

.note {
  color: var(--cp-text-3);
}

.skills {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 8px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 0 16px;
}

.q-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.q-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
