<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

// 导航只放核心链路：概览 → 岗位 → 我的能力 → 学习任务。
// 投递与面试是二期简化模块，收进「更多」并标二期——它们和核心平起平坐时，
// 第一眼就分不清主线在哪（七个入口里有俩点开还是空页）。
const menus = [
  { path: '/dashboard', label: '概览', icon: 'Odometer' },
  { path: '/jobs', label: '岗位', icon: 'Document' },
  { path: '/profile', label: '我的能力', icon: 'User' },
  { path: '/tasks', label: '学习任务', icon: 'Notebook' },
]

const laterMenus = [
  { path: '/applications', label: '投递清单', icon: 'Briefcase' },
  { path: '/interviews', label: '面试复盘', icon: 'ChatLineSquare' },
]

const activeMenu = computed(() => route.meta.activeMenu || route.path)
const pageTitle = computed(() => route.meta.title || '职航 CareerPath')

function signOut() {
  auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <el-container class="layout">
    <el-aside class="aside" width="212px">
      <div class="brand">
        <div class="brand-mark">职</div>
        <div class="brand-text">
          <span class="brand-name">职航 CareerPath</span>
          <span class="brand-sub">岗位能力画像与求职适配</span>
        </div>
      </div>

      <el-menu :default-active="activeMenu" router class="menu">
        <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </el-menu-item>

        <el-sub-menu index="more" popper-class="more-menu">
          <template #title>
            <el-icon><MoreFilled /></el-icon>
            <span>更多</span>
          </template>
          <el-menu-item v-for="item in laterMenus" :key="item.path" :index="item.path">
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
            <el-tag class="later-tag" size="small" type="info" effect="plain">二期</el-tag>
          </el-menu-item>
        </el-sub-menu>
      </el-menu>

      <div class="aside-foot">
        <el-tag size="small" type="warning" effect="plain">示例数据</el-tag>
        <span class="foot-note">权重与目标等级为演示值，非企业标准</span>
      </div>
    </el-aside>

    <el-container>
      <el-header class="header" height="56px">
        <div class="header-left">
          <h1 class="header-title">{{ pageTitle }}</h1>
          <el-tag v-if="route.meta.phase" size="small" effect="plain">
            {{ route.meta.phase }} 交付
          </el-tag>
        </div>
        <div class="header-right">
          <template v-if="auth.isLoggedIn">
            <el-dropdown trigger="click">
              <span class="user">
                <el-avatar :size="26">{{ (auth.displayName || 'U').slice(0, 1).toUpperCase() }}</el-avatar>
                <span class="user-name">{{ auth.displayName || '同学' }}</span>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="signOut">退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
          <el-button v-else text type="primary" @click="router.push('/login')">登录 / 注册</el-button>
        </div>
      </el-header>

      <el-main class="main">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  min-height: 100vh;
}

.aside {
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--cp-border);
  background: #fff;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 56px;
  padding: 0 16px;
  border-bottom: 1px solid var(--cp-border);
}

.brand-mark {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: var(--cp-primary);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
}

.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.25;
}

.brand-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--cp-text);
}

.brand-sub {
  font-size: 11px;
  color: var(--cp-text-3);
}

.menu {
  flex: 1;
  border-right: none;
  padding: 8px;
}

.later-tag {
  margin-left: 6px;
}

.menu :deep(.el-menu-item) {
  height: 40px;
  margin-bottom: 2px;
  border-radius: 8px;
  font-size: 13.5px;
}

.aside-foot {
  padding: 12px 16px 16px;
  border-top: 1px solid var(--cp-border);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.foot-note {
  font-size: 11px;
  line-height: 1.5;
  color: var(--cp-text-3);
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--cp-border);
  background: #fff;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.header-title {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.header-right {
  display: flex;
  align-items: center;
}

.user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  outline: none;
}

.user-name {
  font-size: 13px;
  color: var(--cp-text-2);
}

.main {
  padding: 20px 22px 40px;
  background: var(--cp-bg);
}

@media (max-width: 860px) {
  .aside {
    width: 64px !important;
  }
  .brand-text,
  .aside-foot,
  .menu :deep(.el-menu-item span) {
    display: none;
  }
}
</style>
