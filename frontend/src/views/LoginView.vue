<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const mode = ref('login') // login | register
const form = reactive({ username: '', password: '', confirm: '' })
const error = ref('')
const submitting = ref(false)

const isRegister = computed(() => mode.value === 'register')

const canSubmit = computed(() => {
  if (!form.username.trim() || !form.password) return false
  if (isRegister.value && form.confirm !== form.password) return false
  return true
})

function switchMode(next) {
  mode.value = next
  error.value = ''
}

async function submit() {
  if (!canSubmit.value || submitting.value) return
  error.value = ''
  submitting.value = true
  try {
    const username = form.username.trim()
    if (isRegister.value) {
      await auth.register(username, form.password)
    } else {
      await auth.login(username, form.password)
    }
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/analysis'
    router.replace(redirect)
  } catch (e) {
    error.value = e.message || '操作失败，请重试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="auth">
    <div class="auth-card card">
      <div class="auth-head">
        <svg class="mark" viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="12" cy="12" r="9.4" fill="none" stroke="currentColor" stroke-width="1.3" opacity="0.3" />
          <circle cx="12" cy="12" r="5.6" fill="none" stroke="currentColor" stroke-width="1.3" opacity="0.55" />
          <path d="M12 12 18.9 5.1" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
          <circle cx="15.4" cy="8.6" r="2.1" fill="currentColor" />
        </svg>
        <h1 class="title">职能雷达</h1>
        <p class="subtitle">对照真实岗位要求做技能自评，看清差距和补齐顺序</p>
      </div>

      <div class="tabs">
        <button
          type="button"
          class="tab"
          :class="{ on: !isRegister }"
          @click="switchMode('login')"
        >
          登录
        </button>
        <button
          type="button"
          class="tab"
          :class="{ on: isRegister }"
          @click="switchMode('register')"
        >
          注册
        </button>
      </div>

      <form class="auth-form" @submit.prevent="submit">
        <label class="field">
          <span class="field-label">用户名</span>
          <input
            v-model="form.username"
            class="input"
            type="text"
            autocomplete="username"
            placeholder="3–32 位字母、数字或下划线"
            maxlength="32"
          />
        </label>

        <label class="field">
          <span class="field-label">密码</span>
          <input
            v-model="form.password"
            class="input"
            type="password"
            :autocomplete="isRegister ? 'new-password' : 'current-password'"
            placeholder="至少 6 位"
          />
        </label>

        <label v-if="isRegister" class="field">
          <span class="field-label">确认密码</span>
          <input
            v-model="form.confirm"
            class="input"
            type="password"
            autocomplete="new-password"
            placeholder="再输一次"
          />
          <span v-if="form.confirm && form.confirm !== form.password" class="field-hint err">
            两次输入不一致
          </span>
        </label>

        <div v-if="error" class="alert alert-error">{{ error }}</div>

        <button class="btn btn-primary btn-block" type="submit" :disabled="!canSubmit || submitting">
          {{ submitting ? '处理中…' : isRegister ? '注册并进入' : '登录' }}
        </button>
      </form>

      <p class="foot">数据仅用于本机演示，不对外共享</p>
    </div>
  </div>
</template>

<style scoped>
.auth {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 32px 16px;
  background:
    radial-gradient(900px 480px at 50% -12%, #e9eaff 0%, rgba(233, 234, 255, 0) 62%),
    var(--bg);
}

.auth-card {
  width: 100%;
  max-width: 392px;
  padding: 34px 32px 26px;
  box-shadow: 0 1px 2px rgba(20, 24, 40, 0.04), 0 20px 48px -24px rgba(20, 24, 40, 0.28);
}

.auth-head {
  text-align: center;
}

.mark {
  width: 34px;
  height: 34px;
  color: var(--primary);
}

.title {
  margin-top: 10px;
  font-size: 21px;
  font-weight: 650;
  letter-spacing: -0.02em;
}

.subtitle {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-3);
}

.tabs {
  display: flex;
  gap: 2px;
  margin: 24px 0 20px;
  padding: 3px;
  border-radius: 9px;
  background: #eef0f6;
}

.tab {
  flex: 1;
  height: 32px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--text-2);
  font-size: 13.5px;
  font-weight: 500;
  transition: background 0.15s, color 0.15s, box-shadow 0.15s;
}

.tab.on {
  background: var(--surface);
  color: var(--text);
  box-shadow: var(--shadow-sm);
}

.auth-form .alert {
  margin-top: 16px;
}

.auth-form .btn-block {
  margin-top: 20px;
  height: 42px;
}

.err {
  color: var(--red);
}

.foot {
  margin-top: 20px;
  text-align: center;
  font-size: 11.5px;
  color: var(--text-3);
}
</style>
