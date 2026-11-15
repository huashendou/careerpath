<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

const mode = ref('login')
const loading = ref(false)
const form = reactive({ username: '', password: '' })
const formRef = ref(null)

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '3-32 位字母、数字或下划线', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 64, message: '6-64 位', trigger: 'blur' },
  ],
}

async function submit() {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    if (mode.value === 'login') await auth.login(form.username, form.password)
    else await auth.register(form.username, form.password)
    ElMessage.success(mode.value === 'login' ? '登录成功' : '注册成功')
    router.replace(route.query.redirect || '/dashboard')
  } catch (error) {
    // 账号体系在 T1 落地；此刻后端返回 401/404 属预期，如实提示而不是假装成功
    ElMessage.error(error.message || '操作失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login">
    <div class="login-card">
      <div class="brand">
        <div class="brand-mark">职</div>
        <div>
          <h1 class="brand-name">职航 CareerPath</h1>
          <p class="brand-sub">岗位能力画像与智能求职适配</p>
        </div>
      </div>

      <el-alert
        class="notice"
        type="info"
        :closable="false"
        show-icon
        title="账号体系在 T1 交付"
        description="当前为 T0 骨架：登录注册接口尚未实装，页面与路由已就位。"
      />

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="3-32 位字母、数字或下划线" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="6-64 位"
            autocomplete="current-password"
            @keyup.enter="submit"
          />
        </el-form-item>

        <el-button type="primary" class="submit" :loading="loading" @click="submit">
          {{ mode === 'login' ? '登录' : '注册' }}
        </el-button>
      </el-form>

      <div class="switch">
        <span>{{ mode === 'login' ? '还没有账号？' : '已有账号？' }}</span>
        <el-link type="primary" :underline="false" @click="mode = mode === 'login' ? 'register' : 'login'">
          {{ mode === 'login' ? '注册一个' : '去登录' }}
        </el-link>
      </div>

      <p class="disclaimer">
        技能权重与目标等级为示例数据，依据公开招聘信息归纳，不是任何企业官方标准。
      </p>
    </div>
  </div>
</template>

<style scoped>
.login {
  display: grid;
  place-items: center;
  min-height: 100vh;
  padding: 24px;
  background: var(--cp-bg);
}

.login-card {
  width: 100%;
  max-width: 384px;
  padding: 30px 28px 22px;
  background: #fff;
  border: 1px solid var(--cp-border);
  border-radius: 14px;
  box-shadow: 0 12px 40px -24px rgba(20, 24, 40, 0.24);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border-radius: 10px;
  background: var(--cp-primary);
  color: #fff;
  font-size: 18px;
  font-weight: 600;
}

.brand-name {
  font-size: 17px;
  font-weight: 650;
  letter-spacing: -0.01em;
}

.brand-sub {
  font-size: 12px;
  color: var(--cp-text-3);
}

.notice {
  margin-bottom: 18px;
}

.submit {
  width: 100%;
  margin-top: 4px;
}

.switch {
  display: flex;
  justify-content: center;
  gap: 6px;
  margin-top: 14px;
  font-size: 13px;
  color: var(--cp-text-2);
}

.disclaimer {
  margin-top: 18px;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--cp-text-3);
  text-align: center;
}
</style>
