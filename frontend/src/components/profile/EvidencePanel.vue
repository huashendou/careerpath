<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../../api/client'

/**
 * 证据链（模块 5 后半）。
 *
 * 证据**不进分数**：它只回答「这份能力你拿得出材料吗」。所以这一屏和自评是分开的两个 tab，
 * 页面里也不出现「证据加分」这种字眼——一混，用户就会为了提分去堆材料。
 */
const loading = ref(true)
const saving = ref(false)
const evidences = ref([])
const coverage = ref(null)
const skills = ref([])
const dialogVisible = ref(false)
const editingId = ref(null)

const form = reactive({
  evidenceType: 'repo',
  title: '',
  url: '',
  description: '',
  completeness: 60,
  verifyStatus: 'unverified',
  skillRows: [],
})

const typeLabels = {
  course: '课程', project: '项目', internship: '实习',
  certificate: '证书', repo: '仓库', other: '其他',
}
const statusLabels = { unverified: '未核验', self_verified: '自证', pending: '待核验' }
const statusTypes = { unverified: 'info', self_verified: 'success', pending: 'warning' }

const skillOptions = computed(() => skills.value.map((s) => ({ value: s.id, label: s.name })))

async function load() {
  loading.value = true
  try {
    const [list, cov, skillList] = await Promise.all([
      http.get('/evidence'),
      http.get('/evidence/coverage'),
      http.get('/skills'),
    ])
    evidences.value = list.items
    coverage.value = cov
    skills.value = skillList.items
  } catch (error) {
    ElMessage.error(error.message || '证据加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, {
    evidenceType: 'repo', title: '', url: '', description: '',
    completeness: 60, verifyStatus: 'unverified', skillRows: [],
  })
  dialogVisible.value = true
}

function openEdit(row) {
  editingId.value = row.id
  Object.assign(form, {
    evidenceType: row.evidenceType,
    title: row.title,
    url: row.url || '',
    description: row.description || '',
    completeness: row.completeness,
    verifyStatus: row.verifyStatus,
    skillRows: (row.skills || []).map((s) => ({ skillId: s.skillId, relevance: s.relevance, contributionNote: s.contributionNote || '' })),
  })
  dialogVisible.value = true
}

function addSkillRow() {
  form.skillRows.push({ skillId: '', relevance: 70, contributionNote: '' })
}

async function submit() {
  if (!form.title.trim()) {
    ElMessage.warning('请填写证据标题')
    return
  }
  const items = form.skillRows
    .filter((row) => row.skillId)
    .map((row) => ({
      skillId: row.skillId,
      relevance: Number(row.relevance) || 0,
      contributionNote: row.contributionNote || null,
    }))
  const payload = {
    evidenceType: form.evidenceType,
    title: form.title,
    url: form.url || null,
    description: form.description || null,
    completeness: form.completeness,
    verifyStatus: form.verifyStatus,
    skills: items,
  }
  saving.value = true
  try {
    if (editingId.value) {
      await http.put(`/evidence/${editingId.value}`, payload)
      ElMessage.success('已更新')
    } else {
      await http.post('/evidence', payload)
      ElMessage.success('已添加')
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
    await ElMessageBox.confirm(`删除证据「${row.title}」？`, '删除证据', { type: 'warning' })
  } catch {
    return
  }
  try {
    await http.delete(`/evidence/${row.id}`)
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
        <h3>证据链</h3>
        <p class="sub">课程、项目、实习、证书、仓库都能作为材料。证据只影响「覆盖面」，不参与任何分数计算。</p>
      </div>
      <el-button type="primary" @click="openCreate">添加证据</el-button>
    </div>

    <div class="coverage">
      <div class="cov-main">
        <span class="cov-value">{{ coverage?.coverage == null ? '—' : `${coverage.coverage}%` }}</span>
        <span class="cov-label">
          必备技能证据覆盖（{{ coverage?.coveredCount ?? 0 }} / {{ coverage?.requiredTotal ?? 0 }} 项）
        </span>
      </div>
      <div class="cov-side">
        <span class="cov-hint">没有岗位要求时显示「—」，不显示 0%（那不是「一份证据都没有」，是「还无从判断」）</span>
      </div>
    </div>

    <div v-if="coverage?.missingEvidence?.length" class="missing">
      <span class="missing-label">会但拿不出材料：</span>
      <el-tag v-for="item in coverage.missingEvidence" :key="item.skillId" size="small" type="warning" effect="plain">
        {{ item.skillName }} L{{ item.level }}
      </el-tag>
    </div>

    <el-table :data="evidences" border stripe>
      <el-table-column label="类型" width="88">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ typeLabels[row.evidenceType] || row.evidenceType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="标题" min-width="200">
        <template #default="{ row }">
          <a v-if="row.url" :href="row.url" target="_blank" rel="noreferrer" class="link">{{ row.title }}</a>
          <span v-else>{{ row.title }}</span>
          <p v-if="row.description" class="desc">{{ row.description }}</p>
        </template>
      </el-table-column>
      <el-table-column label="覆盖技能" min-width="220">
        <template #default="{ row }">
          <el-tag v-for="skill in row.skills" :key="skill.skillId" size="small" class="skill-tag" effect="plain">
            {{ skill.skillName }} · {{ skill.relevance }}
          </el-tag>
          <span v-if="!row.skills.length" class="cp-muted">未关联技能</span>
        </template>
      </el-table-column>
      <el-table-column label="完整度" width="130">
        <template #default="{ row }">
          <el-progress :percentage="row.completeness" :stroke-width="8" :show-text="false" />
        </template>
      </el-table-column>
      <el-table-column label="核验" width="92">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTypes[row.verifyStatus] || 'info'" effect="plain">
            {{ statusLabels[row.verifyStatus] || row.verifyStatus }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && !evidences.length" description="还没有证据：项目、课程作业、Git 仓库都可以挂上来" />

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑证据' : '添加证据'" width="680px">
      <el-form :model="form" label-position="top">
        <div class="grid">
          <el-form-item label="类型">
            <el-select v-model="form.evidenceType">
              <el-option v-for="(label, value) in typeLabels" :key="value" :label="label" :value="value" />
            </el-select>
          </el-form-item>
          <el-form-item label="核验状态">
            <el-select v-model="form.verifyStatus">
              <el-option v-for="(label, value) in statusLabels" :key="value" :label="label" :value="value" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="128" placeholder="如 校园二手交易平台" />
        </el-form-item>
        <el-form-item label="链接">
          <el-input v-model="form.url" maxlength="512" placeholder="GitHub / 在线文档 / 作品地址，可空" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="你在这份材料里具体做了什么" />
        </el-form-item>
        <el-form-item :label="`材料完整度：${form.completeness}`">
          <el-slider v-model="form.completeness" :max="100" :step="5" />
        </el-form-item>

        <el-form-item label="覆盖的技能">
          <div class="skill-rows">
            <div v-for="(row, index) in form.skillRows" :key="index" class="skill-row">
              <el-select v-model="row.skillId" filterable placeholder="选择技能" size="small" style="flex: 1">
                <el-option v-for="item in skillOptions" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
              <el-input-number v-model="row.relevance" :min="0" :max="100" size="small" controls-position="right" style="width: 110px" />
              <el-input v-model="row.contributionNote" size="small" placeholder="贡献说明（可空）" style="flex: 1" />
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

.coverage {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 16px;
  background: #fafbfd;
  border: 1px solid var(--cp-border);
  border-radius: 9px;
}

.cov-main {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.cov-value {
  font-size: 24px;
  font-weight: 600;
  color: var(--cp-primary);
  font-variant-numeric: tabular-nums;
}

.cov-label {
  font-size: 12px;
  color: var(--cp-text-2);
}

.cov-hint {
  font-size: 11px;
  color: var(--cp-text-3);
}

.missing {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.missing-label {
  font-size: 11.5px;
  color: var(--cp-text-3);
}

.link {
  color: var(--cp-primary);
}

.desc {
  margin-top: 2px;
  font-size: 11px;
  color: var(--cp-text-3);
}

.skill-tag {
  margin: 2px 4px 2px 0;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
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
