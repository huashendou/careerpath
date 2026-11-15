<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/client'

/**
 * 个人档案（T1）。
 *
 * 技能自评与证据链属于 T3，本页先保留占位；档案本身已是真实可用的读写，
 * 因此不套 PagePlaceholder——那会让「已经能填的字段」看起来也还没做。
 */
const loading = ref(true)
const saving = ref(false)
const formRef = ref(null)
const completedAt = ref(null)

const form = reactive({
  realName: '',
  school: '',
  major: '',
  grade: '',
  targetJobType: '',
  targetCity: '',
  phone: '',
  email: '',
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
    // 后端对未填写的字段返回 null，表单里统一用空串承载，避免 el-input 显示 "null"
    Object.keys(form).forEach((key) => {
      form[key] = data[key] ?? ''
    })
    completedAt.value = data.updatedAt || null
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
    completedAt.value = data.updatedAt || null
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
    <section class="block">
      <div class="block-head">
        <div>
          <h2>个人档案</h2>
          <p class="sub">最小必要收集：只收与岗位匹配相关的字段，不上传第三方。</p>
        </div>
        <span v-if="completedAt" class="saved-at">上次保存 {{ completedAt.slice(0, 10) }}</span>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        class="grid"
        :disabled="loading"
        @submit.prevent="save"
      >
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

    <section class="block pending">
      <div class="block-head">
        <h2>技能画像与证据链</h2>
        <el-tag size="small" type="info">T3 交付</el-tag>
      </div>
      <p class="sub">
        按 6 个维度维护 0–5 级自评，并为技能挂证据（课程 / 项目 / 实习 / 证书 / 仓库）。
        掌握程度与证据是两条正交信息，永远分开展示——证据不参与算分，只决定「这份能力能不能拿出材料」。
      </p>
      <ul class="plan">
        <li>自评：等级用行为锚定（做过什么），不是「熟练 / 精通」</li>
        <li>证据：记录类型、完整度、相关性、核验状态</li>
        <li>缺证据清单：已掌握但没有任何材料的技能单独列出</li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
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
  font-size: 12px;
  line-height: 1.7;
  color: var(--cp-text-3);
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

.pending {
  border-style: dashed;
}

.plan {
  margin-top: 8px;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.9;
  color: var(--cp-text-2);
}
</style>
