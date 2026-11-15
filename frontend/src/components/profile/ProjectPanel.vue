<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../../api/client'

/**
 * 个人项目（模块 10 并入模块 5）。
 *
 * 项目与证据的关系：项目是「一段经历」，证据是「一份材料」。这里挂技能关联，
 * 表示「这个项目让我具备了这些技能」，与证据的 relevance 是两条独立信息，不互相换算。
 */
const loading = ref(true)
const saving = ref(false)
const projects = ref([])
const skills = ref([])
const dialogVisible = ref(false)
const editingId = ref(null)

const form = reactive({
  name: '', role: '', techStackText: '', description: '', repoUrl: '',
  startDate: null, endDate: null, skillRows: [],
})

const skillOptions = computed(() => skills.value.map((s) => ({ value: s.id, label: s.name })))

async function load() {
  loading.value = true
  try {
    const [list, skillList] = await Promise.all([http.get('/projects'), http.get('/skills')])
    projects.value = list.items
    skills.value = skillList.items
  } catch (error) {
    ElMessage.error(error.message || '项目加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, {
    name: '', role: '', techStackText: '', description: '', repoUrl: '',
    startDate: null, endDate: null, skillRows: [],
  })
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    role: row.role || '',
    techStackText: (row.techStack || []).join(', '),
    description: row.description || '',
    repoUrl: row.repoUrl || '',
    startDate: row.startDate || null,
    endDate: row.endDate || null,
    skillRows: (row.skills || []).map((s) => ({ skillId: s.skillId, contributionNote: s.contributionNote || '' })),
  })
  dialogVisible.value = true
}

function addSkillRow() {
  form.skillRows.push({ skillId: '', contributionNote: '' })
}

async function submit() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写项目名称')
    return
  }
  const techStack = form.techStackText.split(/[,，、]/).map((s) => s.trim()).filter(Boolean)
  const payload = {
    name: form.name,
    role: form.role || null,
    techStack,
    description: form.description || null,
    repoUrl: form.repoUrl || null,
    startDate: form.startDate || null,
    endDate: form.endDate || null,
  }
  saving.value = true
  try {
    const saved = editingId.value
      ? await http.put(`/projects/${editingId.value}`, payload)
      : await http.post('/projects', payload)
    const items = form.skillRows
      .filter((row) => row.skillId)
      .map((row) => ({ skillId: row.skillId, contributionNote: row.contributionNote || null }))
    if (items.length) {
      await http.post(`/projects/${saved.id}/skills`, { items })
    }
    ElMessage.success(editingId.value ? '已更新' : '已添加')
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
    await ElMessageBox.confirm(`删除项目「${row.name}」？`, '删除项目', { type: 'warning' })
  } catch {
    return
  }
  try {
    await http.delete(`/projects/${row.id}`)
    ElMessage.success('已删除')
    load()
  } catch (error) {
    ElMessage.error(error.message || '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="panel">
    <div class="head">
      <div>
        <h3>个人项目</h3>
        <p class="sub">项目是简历上最容易被追问的部分：写清你负责的模块，比罗列技术栈更有说服力。</p>
      </div>
      <el-button type="primary" @click="openCreate">添加项目</el-button>
    </div>

    <div class="cards">
      <article v-for="item in projects" :key="item.id" class="card">
        <header class="card-head">
          <div>
            <h4>{{ item.name }}</h4>
            <p class="card-meta">
              <span v-if="item.role">{{ item.role }}</span>
              <span v-if="item.startDate || item.endDate" class="cp-num">
                {{ item.startDate || '?' }} ~ {{ item.endDate || '至今' }}
              </span>
            </p>
          </div>
          <div class="card-actions">
            <el-button link type="primary" @click="openEdit(item)">编辑</el-button>
            <el-button link type="danger" @click="remove(item)">删除</el-button>
          </div>
        </header>
        <p v-if="item.description" class="card-desc">{{ item.description }}</p>
        <div class="tags">
          <el-tag v-for="tech in item.techStack" :key="tech" size="small" effect="plain">{{ tech }}</el-tag>
        </div>
        <div class="tags">
          <el-tag v-for="skill in item.skills" :key="skill.skillId" size="small" type="success" effect="plain">
            {{ skill.skillName }}
          </el-tag>
          <a v-if="item.repoUrl" :href="item.repoUrl" target="_blank" rel="noreferrer" class="repo">仓库链接</a>
        </div>
      </article>
    </div>

    <el-empty v-if="!loading && !projects.length" description="还没有项目：课程设计、竞赛作品、个人练手都算" />

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑项目' : '添加项目'" width="660px">
      <el-form :model="form" label-position="top">
        <div class="grid">
          <el-form-item label="项目名称" required>
            <el-input v-model="form.name" maxlength="128" placeholder="如 校园二手交易平台" />
          </el-form-item>
          <el-form-item label="我的角色">
            <el-input v-model="form.role" maxlength="64" placeholder="如 后端负责人" />
          </el-form-item>
          <el-form-item label="开始日期">
            <el-date-picker v-model="form.startDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
          <el-form-item label="结束日期">
            <el-date-picker v-model="form.endDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
        </div>
        <el-form-item label="技术栈（逗号分隔）">
          <el-input v-model="form.techStackText" placeholder="Java, Spring Boot, MySQL" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="负责什么、解决了什么问题、结果如何" />
        </el-form-item>
        <el-form-item label="仓库地址">
          <el-input v-model="form.repoUrl" maxlength="512" placeholder="https://github.com/…" />
        </el-form-item>
        <el-form-item label="用到的技能">
          <div class="skill-rows">
            <div v-for="(row, index) in form.skillRows" :key="index" class="skill-row">
              <el-select v-model="row.skillId" filterable placeholder="选择技能" size="small" style="flex: 1">
                <el-option v-for="item in skillOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
              <el-input v-model="row.contributionNote" size="small" placeholder="在这项技能上做了什么" style="flex: 1" />
              <el-button link type="danger" @click="form.skillRows.splice(index, 1)">删</el-button>
            </div>
            <el-button link type="primary" @click="addSkillRow">+ 关联一项技能</el-button>
          </div>
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

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
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
  gap: 8px;
}

.card-head h4 {
  font-size: 13.5px;
  font-weight: 600;
}

.card-meta {
  display: flex;
  gap: 10px;
  margin-top: 2px;
  font-size: 11px;
  color: var(--cp-text-3);
}

.card-actions {
  flex: none;
}

.card-desc {
  margin: 8px 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--cp-text-2);
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin-top: 6px;
}

.repo {
  font-size: 11.5px;
  color: var(--cp-primary);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 0 16px;
}

.skill-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.skill-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
