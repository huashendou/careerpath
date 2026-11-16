<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api/client'
import SkillSelfPanel from '../components/profile/SkillSelfPanel.vue'
import EvidencePanel from '../components/profile/EvidencePanel.vue'
import ProjectPanel from '../components/profile/ProjectPanel.vue'
import SkillCatalogPanel from '../components/profile/SkillCatalogPanel.vue'

/**
 * 我的能力（模块 1 的档案 + 模块 4 的词典 + 模块 5 的自评/证据/项目）。
 *
 * 五个 tab 对应四件互相正交的事：我会什么（自评）、我拿得出什么（证据）、我做过什么（项目）、
 * 我是谁（档案）、这个方向由哪些能力构成（词典）。
 *
 * 顺序是刻意的：自评排第一，因为它是匹配的前置；档案排到后面，因为「不想填学校」不该挡住
 * 一个只想看匹配度的人。原先是「技能画像」页 + 独立的「技能图谱」页两个导航项，
 * 两个都叫「技能」反而让人不知道该点哪个，核心动作被埋进二级 tab。
 */
const route = useRoute()

const TABS = ['skills', 'evidence', 'projects', 'basic', 'catalog']
const activeTab = ref(TABS.includes(route.query.tab) ? route.query.tab : 'skills')

watch(() => route.query.tab, (tab) => {
  if (TABS.includes(tab)) activeTab.value = tab
})

const loading = ref(true)
const saving = ref(false)
const formRef = ref(null)
const updatedAt = ref(null)

const form = reactive({
  realName: '', school: '', major: '', grade: '',
  targetJobType: '', targetCity: '', phone: '', email: '',
})

const rules = {
  phone: [{ pattern: /^$|^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  realName: [{ max: 32, message: '不超过 32 字', trigger: 'blur' }],
  school: [{ max: 64, message: '不超过 64 字', trigger: 'blur' }],
  major: [{ max: 64, message: '不超过 64 字', trigger: 'blur' }],
}

async function load() {
  loading.value = true
  try {
    const data = await http.get('/profile')
    Object.keys(form).forEach((key) => {
      form[key] = data[key] ?? ''
    })
    updatedAt.value = data.updatedAt || null
  } catch (error) {
    ElMessage.error(error.message || '档案加载失败')
  } finally {
    loading.value = false
  }
}

async function save() {
  if (formRef.value) {
    const valid = await formRef.value.validate().catch(() => false)
    if (!valid) return
  }
  saving.value = true
  try {
    const data = await http.put('/profile', { ...form })
    updatedAt.value = data.updatedAt || null
    ElMessage.success('档案已保存')
  } catch (error) {
    ElMessage.error(error.message || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <el-tabs v-model="activeTab" class="tabs">
      <el-tab-pane label="技能自评" name="skills">
        <SkillSelfPanel />
      </el-tab-pane>

      <el-tab-pane label="证据链" name="evidence">
        <EvidencePanel />
      </el-tab-pane>

      <el-tab-pane label="项目" name="projects">
        <ProjectPanel />
      </el-tab-pane>
      <el-tab-pane label="基本档案" name="basic">
        <section class="block">
          <div class="block-head">
            <div>
              <h2>个人档案</h2>
              <p class="sub">最小必要收集：只收与岗位匹配相关的字段，不上传第三方。学校与专业都填了才算「档案完善」。</p>
            </div>
            <span v-if="updatedAt" class="saved-at">上次保存 {{ updatedAt.slice(0, 10) }}</span>
          </div>

          <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="grid" :disabled="loading" @submit.prevent="save">
            <el-form-item label="姓名" prop="realName">
              <el-input v-model="form.realName" placeholder="可不填" maxlength="32" />
            </el-form-item>
            <el-form-item label="学校" prop="school">
              <el-input v-model="form.school" placeholder="如 示例大学" maxlength="64" />
            </el-form-item>
            <el-form-item label="专业" prop="major">
              <el-input v-model="form.major" placeholder="如 计算机科学与技术" maxlength="64" />
            </el-form-item>
            <el-form-item label="年级" prop="grade">
              <el-input v-model="form.grade" placeholder="如 大三 / 2023级" maxlength="16" />
            </el-form-item>
            <el-form-item label="目标岗位方向">
              <el-input v-model="form.targetJobType" placeholder="如 Java 后端 / Web 前端" maxlength="64" />
            </el-form-item>
            <el-form-item label="目标城市">
              <el-input v-model="form.targetCity" placeholder="如 杭州" maxlength="32" />
            </el-form-item>
            <el-form-item label="手机号" prop="phone">
              <el-input v-model="form.phone" placeholder="仅本人可见，不做短信验证" maxlength="11" />
            </el-form-item>
            <el-form-item label="邮箱" prop="email">
              <el-input v-model="form.email" placeholder="仅本人可见" maxlength="64" />
            </el-form-item>
          </el-form>

          <div class="actions">
            <el-button type="primary" :loading="saving" @click="save">保存档案</el-button>
            <span class="tip">保存是整体覆盖：清空的字段会被真的清空。</span>
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="技能词典" name="catalog">
        <SkillCatalogPanel />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
}

.tabs :deep(.el-tabs__header) {
  margin-bottom: 14px;
}

.block {
  padding: 18px;
  background: #fff;
  border: 1px solid var(--cp-border);
  border-radius: 10px;
}

.block-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.block-head h2 {
  font-size: 14px;
  font-weight: 600;
}

.sub {
  margin-top: 4px;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--cp-text-3);
  max-width: 640px;
}

.saved-at {
  flex: none;
  font-size: 11.5px;
  color: var(--cp-text-3);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 0 16px;
}

.actions {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-top: 4px;
}

.tip {
  font-size: 11.5px;
  color: var(--cp-text-3);
}
</style>
