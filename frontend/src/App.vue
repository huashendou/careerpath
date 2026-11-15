<script setup>
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

// 登录页不显示顶部导航，避免"未登录却有导航"的错位感
const bare = computed(() => Boolean(route.meta.guest))

const links = [
  { to: '/profile', label: '技能自评' },
  { to: '/analysis', label: '匹配分析' },
  { to: '/reports', label: '历史报告' },
]

onMounted(async () => {
  if (auth.isLoggedIn && !auth.user) {
    try {
      await auth.fetchMe()
    } catch {
      auth.logout()
    }
  }
})

function signOut() {
  auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="app">
    <header v-if="!bare" class="topbar">
      <div class="topbar-inner">
        <RouterLink to="/analysis" class="brand">
          <svg class="brand-mark" viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="12" cy="12" r="9.4" fill="none" stroke="currentColor" stroke-width="1.3" opacity="0.28" />
            <circle cx="12" cy="12" r="5.6" fill="none" stroke="currentColor" stroke-width="1.3" opacity="0.5" />
            <path d="M12 12 18.9 5.1" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" />
            <circle cx="15.4" cy="8.6" r="2.1" fill="currentColor" />
          </svg>
          <span class="brand-name">职能雷达</span>
        </RouterLink>

        <nav class="nav">
          <RouterLink v-for="l in links" :key="l.to" :to="l.to" class="nav-link">
            {{ l.label }}
          </RouterLink>
        </nav>

        <div class="user">
          <span class="user-name">{{ auth.displayName }}</span>
          <button class="btn btn-sm btn-ghost" type="button" @click="signOut">退出</button>
        </div>
      </div>
    </header>

    <RouterView v-slot="{ Component }">
      <component :is="Component" />
    </RouterView>
  </div>
</template>

<style scoped>
.app {
  min-height: 100vh;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 20;
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: saturate(180%) blur(10px);
  border-bottom: 1px solid var(--border);
}

.topbar-inner {
  display: flex;
  align-items: center;
  gap: 28px;
  max-width: 1180px;
  height: 56px;
  margin: 0 auto;
  padding: 0 24px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--primary);
  flex-shrink: 0;
}

.brand-mark {
  width: 21px;
  height: 21px;
}

.brand-name {
  font-size: 15px;
  font-weight: 650;
  letter-spacing: 0.01em;
  color: var(--text);
}

.nav {
  display: flex;
  gap: 2px;
  flex: 1;
}

.nav-link {
  padding: 6px 12px;
  border-radius: 7px;
  font-size: 13.5px;
  color: var(--text-2);
  transition: background 0.15s, color 0.15s;
}

.nav-link:hover {
  background: #f0f1f6;
  color: var(--text);
}

.nav-link.router-link-active {
  background: var(--primary-weak);
  color: var(--primary-dark);
  font-weight: 550;
}

.user {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.user-name {
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--text-2);
}

@media (max-width: 720px) {
  .topbar-inner {
    gap: 12px;
    padding: 0 14px;
  }
  .brand-name {
    display: none;
  }
  .nav-link {
    padding: 6px 8px;
    font-size: 13px;
  }
  .user-name {
    display: none;
  }
}
</style>
