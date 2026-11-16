<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/client'

/**
 * 岗位解析（模块 3）。
 *
 * 交互上有一条刻意的取舍：**解析只填充草稿，不直接落库**。用户能逐条改权重、改必备/加分、
 * 删掉误识别的项，点保存才写 job_skill_requirement。这样「算法错了一次」不会变成
 * 「库里多了一份没人确认过的要求」，也是契约里 parse 与 requirements 分成两个接口的原因。
 */
const router = useRouter()

const loading = ref(false)
const jobs = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')

const editing = ref(null)
const editVisible = ref(false)
const saving = ref(false)
const form = reactive({
  title: '', companyName: '', city: '', salaryMin: null, salaryMax: null,
  source: '手动粘贴', url: '', rawDescription: '',
})

const reqVisible = ref(false)
const reqJob = ref(null)
const reqLoading = ref(false)
const reqSaving = ref(false)
const rows = ref([])
const draftMeta = ref(null)
const skills = ref([])
const skillOptions = computed(() => skills.value.map((s) => ({ value: s.id, label: s.name })))

async function load() {
  loading.value = true
  try {
    const data = await http.get('/jobs', {
      params: { page: page.value, size: size.value, keyword: keyword.value || undefined },
    })
    jobs.value = data.items
    total.value = data.total
  } catch (error) {
    ElMessage.error(error.message || '岗位列表加载失败')
  } finally {
    loading.value = false
  }
}

async function loadSkills() {
  try {
    const data = await http.get('/skills')
    skills.value = data.items
  } catch {
    skills.value = []
  }
}

function openCreate() {
  editing.value = null
  Object.assign(form, {
    title: '', companyName: '', city: '', salaryMin: null, salaryMax: null,
    source: '手动粘贴', url: '', rawDescription: '',
  })
  editVisible.value = true
}

async function openEdit(row) {
  editing.value = row
  try {
    const detail = await http.get(`/jobs/${row.id}`)
    Object.assign(form, {
      title: detail.title, companyName: detail.companyName || '', city: detail.city || '',
      salaryMin: detail.salaryMin, salaryMax: detail.salaryMax,
      source: detail.source || '手动粘贴', url: detail.url || '', rawDescription: detail.rawDescription || '',
    })
    editVisible.value = true
  } catch (error) {
    ElMessage.error(error.message || '岗位详情加载失败')
  }
}

async function submitJob() {
  if (!form.title.trim()) {
    ElMessage.warning('请填写岗位名称')
    return
  }
  saving.value = true
  try {
    const payload = { ...form, salaryMin: form.salaryMin ?? null, salaryMax: form.salaryMax ?? null }
    if (editing.value) {
      await http.put(`/jobs/${editing.value.id}`, payload)
      ElMessage.success('已更新')
      editVisible.value = false
      await load()
      return
    }
    const created = await http.post('/jobs', payload)
    editVisible.value = false
    await load()
    ElMessage.success('已创建，接着解析 JD 或手动添加技能要求')
    await openRequirements(created)
  } catch (error) {
    ElMessage.error(error.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`删除「${row.title}」？技能要求与历史报告会一并删除。`, '删除岗位', { type: 'warning' })
  } catch {
    return
  }
  try {
    await http.delete(`/jobs/${row.id}`)
    ElMessage.success('已删除')
    load()
  } catch (error) {
    ElMessage.error(error.message || '删除失败')
  }
}

/** 打开技能要求面板：先读已有要求，再让用户决定是否用解析覆盖。 */
async function openRequirements(row) {
  reqJob.value = row
  reqVisible.value = true
  reqLoading.value = true
  rows.value = []
  draftMeta.value = null
  try {
    const detail = await http.get(`/jobs/${row.id}`)
    reqJob.value = { ...row, ...detail }
    const data = await http.get(`/jobs/${row.id}/requirements`)
    rows.value = data.items.map((item) => ({
      skillId: item.skillId, reqType: item.reqType, weight: item.weight,
      targetLevel: item.targetLevel, evidenceHint: item.evidenceHint, sourceSpan: item.sourceSpan,
    }))
  } catch (error) {
    ElMessage.error(error.message || '要求加载失败')
  } finally {
    reqLoading.value = false
  }
}

/** 解析：只填充草稿，保存前不落库。 */
async function parseNow() {
  if (!reqJob.value?.id) return
  reqLoading.value = true
  try {
    const data = await http.post(`/jobs/${reqJob.value.id}/parse`)
    rows.value = data.requirements.map((item) => ({
      skillId: item.skillId, reqType: item.reqType, weight: item.weight, targetLevel: item.targetLevel,
      evidenceHint: item.evidenceHint, sourceSpan: `${item.sourceStart}-${item.sourceEnd}`,
    }))
    draftMeta.value = {
      required: data.requiredCount, bonus: data.bonusCount,
      algorithm: data.algorithmVersion, dictionary: data.dictionaryVersion,
    }
    ElMessage.success(`解析出 ${data.matchedCount} 项，请逐条确认后保存`)
  } catch (error) {
    ElMessage.error(error.message || '解析失败：确认分析服务已启动')
  } finally {
    reqLoading.value = false
  }
}

function addRow() {
  rows.value.push({ skillId: '', reqType: 'required', weight: 1, targetLevel: 3, evidenceHint: '', sourceSpan: '' })
}

function removeRow(index) {
  rows.value.splice(index, 1)
}

/** 返回是否保存成功：给「保存并去匹配」用，失败时不能跳走。 */
async function saveRequirements() {
  const items = []
  for (const row of rows.value) {
    if (!row.skillId) {
      ElMessage.warning('有未选择技能的行，请先选技能或删除该行')
      return false
    }
    items.push({
      skillId: row.skillId, reqType: row.reqType,
      weight: Number(row.weight) || 1, targetLevel: Number(row.targetLevel) || 3,
      anyOf: [], evidenceHint: row.evidenceHint || null, sourceSpan: row.sourceSpan || null,
    })
  }
  reqSaving.value = true
  try {
    const data = await http.put(`/jobs/${reqJob.value.id}/requirements`, { items })
    rows.value = data.items.map((item) => ({
      skillId: item.skillId, reqType: item.reqType, weight: item.weight, targetLevel: item.targetLevel,
      evidenceHint: item.evidenceHint, sourceSpan: item.sourceSpan,
    }))
    ElMessage.success(`已保存 ${data.items.length} 项要求`)
    load()
    return true
  } catch (error) {
    ElMessage.error(error.message || '保存失败')
    return false
  } finally {
    reqSaving.value = false
  }
}

/**
 * 保存要求后直接去匹配。
 *
 * 早先这里是一个「关掉并去匹配」的纯链接：点它只关弹窗、不保存，用户刚改的十几行
 * 要求全丢，而匹配用的还是旧要求——看起来就是「系统算错了」。改成先保存再跳。
 */
async function saveAndMatch() {
  if (!reqJob.value?.id) return
  const saved = await saveRequirements()
  if (!saved) return
  reqVisible.value = false
  goMatch(reqJob.value)
}

function goMatch(row) {
  router.push({ name: 'match', params: { jobId: row.id } })
}

/** 有技能要求才谈得上匹配：必备和加分都为空时，匹配接口只会回一句「没有要求」。 */
function hasRequirement(row) {
  return (row.requiredCount || 0) + (row.bonusCount || 0) > 0
}

function statusTag(status) {
  const map = {
    pending: { text: '待解析', type: 'info' },
    parsed: { text: '已解析', type: 'warning' },
    confirmed: { text: '已确认', type: 'success' },
  }
  return map[status] || { text: status, type: 'info' }
}

onMounted(() => {
  load()
  loadSkills()
})
</script>

<template>
  <div class="page">
    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索岗位或公司"
        clearable
        style="max-width: 260px"
        @keyup.enter="page = 1; load()"
        @clear="page = 1; load()"
      />
      <el-button @click="page = 1; load()">搜索</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="openCreate">录入岗位</el-button>
    </div>

    <el-table v-loading="loading" :data="jobs" border stripe>
      <el-table-column prop="title" label="岗位" min-width="180" show-overflow-tooltip />
      <el-table-column prop="companyName" label="公司" width="130" show-overflow-tooltip />
      <el-table-column prop="city" label="城市" width="80" />
      <el-table-column label="薪资" width="112">
        <template #default="{ row }">
          <span v-if="row.salaryMin || row.salaryMax" class="cp-num">{{ row.salaryMin ?? '-' }}–{{ row.salaryMax ?? '-' }}</span>
          <span v-else class="cp-muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="要求" width="126">
        <template #default="{ row }">
          <span class="cp-num">必备 {{ row.requiredCount }} / 加分 {{ row.bonusCount }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="96">
        <template #default="{ row }">
          <el-tag size="small" :type="statusTag(row.parseStatus).type" effect="plain">{{ statusTag(row.parseStatus).text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="录入时间" width="110">
        <template #default="{ row }">{{ (row.createdAt || '').slice(0, 10) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="252" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openRequirements(row)">技能要求</el-button>
          <!-- 匹配是本项目的主线动作，所以给实心按钮而不是第四个链接。
               没有技能要求时禁掉：点进去只会得到「该岗位没有技能要求」的报错，
               与其让人撞一次错再回来，不如在这里就说明白。 -->
          <el-tooltip
            :disabled="hasRequirement(row)"
            content="先解析 JD 或手工加技能要求，否则算不出匹配"
            placement="top"
          >
            <span class="match-wrap">
              <el-button size="small" type="primary" :disabled="!hasRequirement(row)" @click="goMatch(row)">
                去匹配
              </el-button>
            </span>
          </el-tooltip>
          <el-button link @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="page"
      :page-size="size"
      :total="total"
      layout="total, prev, pager, next"
      class="pager"
      @current-change="load"
    />

    <el-dialog v-model="editVisible" :title="editing ? '编辑岗位' : '录入岗位'" width="640px">
      <el-form :model="form" label-position="top">
        <div class="grid">
          <el-form-item label="岗位名称" required>
            <el-input v-model="form.title" maxlength="64" placeholder="如 Java 后端开发实习生" />
          </el-form-item>
          <el-form-item label="公司">
            <el-input v-model="form.companyName" maxlength="64" placeholder="如 亚信科技" />
          </el-form-item>
          <el-form-item label="城市">
            <el-input v-model="form.city" maxlength="32" placeholder="如 杭州" />
          </el-form-item>
          <el-form-item label="来源">
            <el-input v-model="form.source" maxlength="32" placeholder="BOSS直聘 / 手动粘贴" />
          </el-form-item>
          <el-form-item label="薪资下限">
            <el-input-number v-model="form.salaryMin" :min="0" :max="1000000" controls-position="right" style="width: 100%" />
          </el-form-item>
          <el-form-item label="薪资上限">
            <el-input-number v-model="form.salaryMax" :min="0" :max="1000000" controls-position="right" style="width: 100%" />
          </el-form-item>
        </div>
        <el-form-item label="JD 原文">
          <el-input
            v-model="form.rawDescription"
            type="textarea"
            :rows="9"
            maxlength="20000"
            show-word-limit
            placeholder="把招聘信息原文整段粘进来，解析结果会更准；原文逐字保存，不会被解析覆盖"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitJob">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="reqVisible" :title="`技能要求 · ${reqJob?.title || ''}`" width="920px" top="6vh">
      <div class="req-head">
        <div class="req-actions">
          <el-button size="small" type="primary" plain :loading="reqLoading" @click="parseNow">解析 JD 原文</el-button>
          <el-button size="small" @click="addRow">手动加一行</el-button>
          <span class="tip">解析只填草稿，保存才落库</span>
        </div>
        <el-tag v-if="draftMeta" size="small" type="warning" effect="plain">
          解析：必备 {{ draftMeta.required }} / 加分 {{ draftMeta.bonus }} · {{ draftMeta.algorithm }}
        </el-tag>
      </div>

      <!-- JD 原文可回溯：下面每一行的「原文片段」都是这段文字里的一句。
           岗位数据来自公开招聘信息，给出出处才谈得上可核验——否则「解析出的要求」
           和「凭印象写的要求」在界面上长得一模一样。 -->
      <el-collapse v-if="reqJob" class="jd-box">
        <el-collapse-item name="jd">
          <template #title>
            <span class="jd-title">JD 原文</span>
            <el-tag v-if="reqJob.source" size="small" effect="plain" class="jd-tag">{{ reqJob.source }}</el-tag>
            <el-link
              v-if="reqJob.url"
              type="primary"
              :underline="false"
              :href="reqJob.url"
              target="_blank"
              class="jd-link"
              @click.stop
            >
              原始链接 ↗
            </el-link>
          </template>
          <pre class="jd-text">{{ reqJob.rawDescription || '这条岗位没有保存 JD 原文。' }}</pre>
          <p class="jd-note">技能要求由这段原文解析而来，表格里每条的「原文片段」都能在文中找到对应句子。</p>
        </el-collapse-item>
      </el-collapse>

      <el-table v-loading="reqLoading" :data="rows" size="small" border max-height="360">
        <el-table-column label="技能" min-width="220">
          <template #default="{ row }">
            <el-select v-model="row.skillId" filterable placeholder="选择技能" size="small" style="width: 100%">
              <el-option v-for="item in skillOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="108">
          <template #default="{ row }">
            <el-select v-model="row.reqType" size="small">
              <el-option label="必备" value="required" />
              <el-option label="加分" value="bonus" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="权重" width="96">
          <template #default="{ row }">
            <el-input-number v-model="row.weight" :min="1" :max="99" size="small" controls-position="right" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="目标等级" width="108">
          <template #default="{ row }">
            <el-input-number v-model="row.targetLevel" :min="1" :max="5" size="small" controls-position="right" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="原文片段" min-width="180">
          <template #default="{ row }">
            <el-input v-model="row.evidenceHint" size="small" placeholder="来自 JD 的哪句" />
          </template>
        </el-table-column>
        <el-table-column label="" width="62">
          <template #default="{ $index }">
            <el-button link type="danger" @click="removeRow($index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <p v-if="!rows.length" class="empty-tip">还没有技能要求：可点「解析 JD 原文」自动识别，或手动加一行。</p>

      <template #footer>
        <span class="foot-left">
          <el-button v-if="reqJob" link type="primary" :disabled="reqSaving" @click="saveAndMatch">
            保存并去匹配
          </el-button>
        </span>
        <el-button @click="reqVisible = false">关闭</el-button>
        <el-button type="primary" :loading="reqSaving" @click="saveRequirements">保存要求</el-button>
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

.pager {
  justify-content: flex-end;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 0 16px;
}

.req-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.req-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tip,
.empty-tip,
.foot-left {
  font-size: 11.5px;
  color: var(--cp-text-3);
}

.empty-tip {
  margin-top: 10px;
}

.foot-left {
  float: left;
}

/* 实心按钮和三个链接混排时对齐基线，否则行内高低不齐 */
.match-wrap {
  display: inline-flex;
  align-items: center;
  margin: 0 8px;
  vertical-align: middle;
}

/* JD 原文区：默认收起，展开后限高滚动，不把要求表格挤出视口 */
.jd-box {
  margin-bottom: 10px;
  border-top: none;
}

.jd-title {
  margin-right: 8px;
  font-size: 13px;
  color: var(--cp-text-2);
}

.jd-tag {
  margin-right: 10px;
}

.jd-link {
  font-size: 12px;
}

.jd-text {
  max-height: 210px;
  overflow: auto;
  margin: 0;
  padding: 10px 12px;
  background: #fafbfe;
  border: 1px solid var(--cp-border);
  border-radius: 8px;
  font-family: var(--cp-font);
  font-size: 12px;
  line-height: 1.75;
  white-space: pre-wrap;
  color: var(--cp-text-2);
}

.jd-note {
  margin-top: 8px;
  font-size: 11.5px;
  color: var(--cp-text-3);
}
</style>
