<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/client'

/**
 * 投递清单（二期模块 2 简化版）。
 *
 * 只做「清单 + 状态切换 + 备注」：漏斗图、跟进提醒、面试日程都是一期范围裁决里砍掉的，
 * 二期也没有加。状态改动会写进 status_log，方便面试复盘时回看时间线。
 */
const router = useRouter()

const loading = ref(true)
const rows = ref([])
const jobs = ref([])
const statusFilter = ref('')
const createVisible = ref(false)
const saving = ref(false)

const form = reactive({ jobPositionId: null, title: '', companyName: '', note: '' })

const statusMeta = {
  todo: { text: '待投', type: 'info' },
  applied: { text: '已投', type: 'primary' },
  interview: { text: '面试中', type: 'warning' },
  closed: { text: '已结束', type: 'success' },
}

async function load() {
  loading.value = true
  try {
    const [list, jobList] = await Promise.all([
      http.get('/applications', { params: { status: statusFilter.value || undefined } }),
      http.get('/jobs', { params: { page: 1, size: 100 } }),
    ])
    rows.value = list.items
    jobs.value = jobList.items
  } catch (error) {
    ElMessage.error(error.message || '投递记录加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, { jobPositionId: null, title: '', companyName: '', note: '' })
  createVisible.value = true
}

function fillFromJob(jobId) {
  const job = jobs.value.find((item) => item.id === jobId)
  if (job) {
    form.title = job.title
    form.companyName = job.companyName || ''
  }
}

async function submit() {
  if (!form.jobPositionId && !form.title.trim()) {
    ElMessage.warning('请选择一个岗位，或手填岗位名称')
    return
  }
  saving.value = true
  try {
    await http.post('/applications', {
      jobPositionId: form.jobPositionId || null,
      title: form.title || null,
      companyName: form.companyName || null,
      note: form.note || null,
    })
    createVisible.value = false
    ElMessage.success('已加入投递清单')
    await load()
  } catch (error) {
    ElMessage.error(error.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function changeStatus(row, status) {
  try {
    await http.patch(`/applications/${row.id}`, { status })
    ElMessage.success(`已置为「${statusMeta[status]?.text || status}」`)
    load()
  } catch (error) {
    ElMessage.error(error.message || '状态更新失败')
    load()
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`从清单里移除「${row.title}」？`, '移除', { type: 'warning' })
  } catch {
    return
  }
  try {
    await http.delete(`/applications/${row.id}`)
    ElMessage.success('已移除')
    load()
  } catch (error) {
    ElMessage.error(error.message || '移除失败')
  }
}

function goInterview(row) {
  router.push({ name: 'interviews', query: { applicationId: row.id } })
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <el-radio-group v-model="statusFilter" size="small" @change="load">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button v-for="(meta, key) in statusMeta" :key="key" :value="key">{{ meta.text }}</el-radio-button>
      </el-radio-group>
      <div class="spacer" />
      <el-button type="primary" @click="openCreate">加入投递清单</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe>
      <el-table-column prop="title" label="岗位" min-width="190" show-overflow-tooltip />
      <el-table-column prop="companyName" label="公司" width="140" show-overflow-tooltip />
      <el-table-column label="状态" width="132">
        <template #default="{ row }">
          <el-select :model-value="row.status" size="small" @change="(value) => changeStatus(row, value)">
            <el-option v-for="(meta, key) in statusMeta" :key="key" :label="meta.text" :value="key" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="时间线" min-width="220">
        <template #default="{ row }">
          <span v-if="!row.statusLog?.length" class="cp-muted">尚未变更</span>
          <span v-for="(log, index) in row.statusLog" :key="index" class="log cp-num">
            {{ (log.at || '').slice(0, 10) }} {{ statusMeta[log.to]?.text || log.to }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="note" label="备注" min-width="150" show-overflow-tooltip />
      <el-table-column label="更新" width="110">
        <template #default="{ row }">{{ (row.updatedAt || '').slice(0, 10) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="goInterview(row)">面试复盘</el-button>
          <el-button link type="danger" @click="remove(row)">移除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && !rows.length" description="清单是空的：从岗位页投递，或在这里手动加入" />

    <el-dialog v-model="createVisible" title="加入投递清单" width="520px">
      <el-form :model="form" label-position="top">
        <el-form-item label="关联岗位（可选，选了会自动带出名称）">
          <el-select v-model="form.jobPositionId" filterable clearable placeholder="从我的岗位里选" style="width: 100%" @change="fillFromJob">
            <el-option v-for="job in jobs" :key="job.id" :label="`${job.title}${job.companyName ? ' · ' + job.companyName : ''}`" :value="job.id" />
          </el-select>
        </el-form-item>
        <div class="grid">
          <el-form-item label="岗位名称">
            <el-input v-model="form.title" maxlength="64" placeholder="不关联岗位时必填" />
          </el-form-item>
          <el-form-item label="公司">
            <el-input v-model="form.companyName" maxlength="64" />
          </el-form-item>
        </div>
        <el-form-item label="备注">
          <el-input v-model="form.note" maxlength="500" placeholder="内推人、投递渠道、截止时间…" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">加入</el-button>
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

.log {
  display: inline-block;
  margin-right: 8px;
  font-size: 11px;
  color: var(--cp-text-3);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 0 16px;
}
</style>
