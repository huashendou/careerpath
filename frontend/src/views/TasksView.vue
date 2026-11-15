<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/client'

/**
 * 学习计划（模块 7）。
 *
 * 闭环的最后一步在这里：完成任务时可以选择「把技能等级提到几级」，后端会顺手重算一次匹配。
 * 所以完成按钮不是一个勾选框，而是一次需要用户确认的动作——等级改错了，分数就不可信。
 */
const router = useRouter()

const loading = ref(true)
const tasks = ref([])
const skills = ref([])
const statusFilter = ref('')
const sourceFilter = ref('')

const creating = ref(false)
const createVisible = ref(false)
const form = reactive({ title: '', skillId: '', source: 'manual', priority: 1, dueDate: null })

const completeVisible = ref(false)
const current = ref(null)
const completeForm = reactive({ updateSkillLevel: true, level: 3 })

const counts = computed(() => {
  const base = { todo: 0, doing: 0, done: 0, paused: 0 }
  for (const task of tasks.value) base[task.status] = (base[task.status] || 0) + 1
  return base
})

const statusMeta = {
  todo: { text: '待开始', type: 'info' },
  doing: { text: '进行中', type: 'primary' },
  done: { text: '已完成', type: 'success' },
  paused: { text: '已搁置', type: 'warning' },
}

async function load() {
  loading.value = true
  try {
    const [list, skillList] = await Promise.all([
      http.get('/tasks', {
        params: { status: statusFilter.value || undefined, source: sourceFilter.value || undefined },
      }),
      http.get('/skills'),
    ])
    tasks.value = list.items
    skills.value = skillList.items
  } catch (error) {
    ElMessage.error(error.message || '任务加载失败')
  } finally {
    loading.value = false
  }
}

async function patch(task, status) {
  try {
    await http.patch(`/tasks/${task.id}`, { status })
    await load()
  } catch (error) {
    ElMessage.error(error.message || '更新失败')
  }
}

function openComplete(task) {
  current.value = task
  completeForm.updateSkillLevel = Boolean(task.skillId)
  completeForm.level = Math.min(5, Number(task.priority) ? 3 : 3)
  completeVisible.value = true
}

async function submitComplete() {
  if (!current.value) return
  try {
    const data = await http.post(`/tasks/${current.value.id}/complete`, {
      updateSkillLevel: completeForm.updateSkillLevel,
      level: completeForm.updateSkillLevel ? completeForm.level : null,
    })
    completeVisible.value = false
    await load()
    if (data.recalculated) {
      ElMessage.success('任务完成，技能等级已更新，匹配分数已重算')
      router.push({ name: 'match', params: { jobId: data.matchedJobId } })
    } else if (data.matchedJobId) {
      ElMessage.warning('任务完成，但重算失败（分析服务可能没起），稍后到岗位页重新匹配')
    } else {
      ElMessage.success('任务完成')
    }
  } catch (error) {
    ElMessage.error(error.message || '完成失败')
  }
}

function openCreate() {
  Object.assign(form, { title: '', skillId: '', source: 'manual', priority: 1, dueDate: null })
  createVisible.value = true
}

async function submitCreate() {
  if (!form.title.trim()) {
    ElMessage.warning('请填写任务标题')
    return
  }
  creating.value = true
  try {
    await http.post('/tasks', {
      title: form.title,
      skillId: form.skillId || null,
      source: form.source,
      priority: form.priority,
      dueDate: form.dueDate,
    })
    createVisible.value = false
    ElMessage.success('已创建')
    await load()
  } catch (error) {
    ElMessage.error(error.message || '创建失败')
  } finally {
    creating.value = false
  }
}

async function remove(task) {
  try {
    await ElMessageBox.confirm(`删除任务「${task.title}」？`, '删除任务', { type: 'warning' })
  } catch {
    return
  }
  try {
    await http.delete(`/tasks/${task.id}`)
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
    <div class="head">
      <div class="counts">
        <span class="count"><b>{{ counts.todo + counts.doing }}</b> 待完成</span>
        <span class="count"><b>{{ counts.done }}</b> 已完成</span>
        <span class="count muted">来自差距 {{ tasks.filter((t) => t.source === 'gap').length }} / 手动 {{ tasks.filter((t) => t.source === 'manual').length }}</span>
      </div>
      <div class="actions">
        <el-select v-model="statusFilter" size="small" placeholder="全部状态" clearable style="width: 128px" @change="load">
          <el-option v-for="(meta, key) in statusMeta" :key="key" :label="meta.text" :value="key" />
        </el-select>
        <el-select v-model="sourceFilter" size="small" placeholder="全部来源" clearable style="width: 128px" @change="load">
          <el-option label="来自差距" value="gap" />
          <el-option label="手动" value="manual" />
        </el-select>
        <el-button type="primary" @click="openCreate">新建任务</el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="任务大多来自「岗位匹配 → 生成学习任务」；完成时把技能等级一并更新，系统会立刻重算分数。"
    />

    <el-table v-loading="loading" :data="tasks" border stripe>
      <el-table-column label="任务" min-width="260">
        <template #default="{ row }">
          <span :class="{ done: row.status === 'done' }">{{ row.title }}</span>
          <el-tag v-if="row.source === 'gap'" size="small" type="warning" effect="plain" class="tag">差距</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="技能" width="160">
        <template #default="{ row }">
          <span v-if="row.skillName">{{ row.skillName }}</span>
          <span v-else class="cp-muted">通用任务</span>
        </template>
      </el-table-column>
      <el-table-column label="优先级" width="88">
        <template #default="{ row }"><span class="cp-num">{{ row.priority }}</span></template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="statusMeta[row.status]?.type || 'info'" effect="plain">
            {{ statusMeta[row.status]?.text || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="完成时间" width="120">
        <template #default="{ row }">{{ row.completedAt ? row.completedAt.slice(0, 10) : '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status !== 'done'" link type="primary" @click="openComplete(row)">完成</el-button>
          <el-button v-if="row.status === 'todo'" link @click="patch(row, 'doing')">开始</el-button>
          <el-button v-if="row.status === 'doing'" link @click="patch(row, 'paused')">搁置</el-button>
          <el-button v-if="row.status === 'paused'" link @click="patch(row, 'todo')">恢复</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && !tasks.length" description="还没有任务：去岗位匹配页按差距生成，或手动建一个" />

    <el-dialog v-model="completeVisible" title="完成任务" width="460px">
      <p class="dialog-tip">完成后会自动重算相关岗位的匹配分数，方便你马上看到变化。</p>
      <el-form label-position="top">
        <el-form-item label="是否更新这项技能的自评等级">
          <el-switch v-model="completeForm.updateSkillLevel" :disabled="!current?.skillId" />
          <span v-if="!current?.skillId" class="cp-muted inline-tip">该任务没有关联技能</span>
        </el-form-item>
        <el-form-item v-if="completeForm.updateSkillLevel" :label="`更新到 L${completeForm.level}（1–5，宁低勿高）`">
          <el-slider v-model="completeForm.level" :min="1" :max="5" :step="1" show-stops />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="completeVisible = false">取消</el-button>
        <el-button type="primary" @click="submitComplete">确认完成</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createVisible" title="新建任务" width="520px">
      <el-form :model="form" label-position="top">
        <el-form-item label="任务标题" required>
          <el-input v-model="form.title" maxlength="128" placeholder="如 完成一个 Spring Boot 用户中心模块" />
        </el-form-item>
        <div class="grid">
          <el-form-item label="关联技能（可空）">
            <el-select v-model="form.skillId" filterable clearable placeholder="不关联">
              <el-option v-for="item in skills" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="优先级">
            <el-input-number v-model="form.priority" :min="0" :max="999" controls-position="right" style="width: 100%" />
          </el-form-item>
          <el-form-item label="截止日期">
            <el-date-picker v-model="form.dueDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
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

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.counts {
  display: flex;
  align-items: baseline;
  gap: 16px;
  font-size: 12px;
  color: var(--cp-text-2);
}

.count b {
  font-size: 17px;
  color: var(--cp-primary);
  font-variant-numeric: tabular-nums;
}

.muted {
  color: var(--cp-text-3);
}

.actions {
  display: flex;
  gap: 8px;
}

.done {
  color: var(--cp-text-3);
  text-decoration: line-through;
}

.tag {
  margin-left: 8px;
}

.dialog-tip {
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--cp-text-2);
}

.inline-tip {
  margin-left: 10px;
  font-size: 11.5px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 0 16px;
}
</style>
