<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

// 菜单只放 5 个一级页面；岗位匹配是从岗位解析进入的详情页（c3.1：共 7 个页面）
const menus = [
  { path: '/dashboard', label: '概览', icon: 'Odometer' },
  { path: '/jobs', label: '岗位解析', icon: 'Document' },
  { path: '/skills', label: '技能图谱', icon: 'Share' },
  { path: '/profile', label: '技能画像', icon: 'User' },
  { path: '/tasks', label: '学习计划', icon: 'Notebook' },
]

const activeMenu = computed(() => route.meta.activeMenu || route.path)
const pageTitle = computed(() => route.meta.title || '职航 CareerPath')
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
          <!-- T0 无会话：不显示用户名/退出登录，避免与「未实现鉴权」自相矛盾 -->
          <el-button text type="primary" @click="router.push('/login')">登录 / 注册</el-button>
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
