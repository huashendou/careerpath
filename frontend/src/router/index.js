// 一期 7 个页面 = 模块 1 / 3 / 4 / 5 / 6 / 7。
// c3.3 范围裁决：模块 2 求职工作台、8 面试复盘、9 数据分析中心改为**二期**做简化版
// （T7–T9，+2 表 +2 页 /applications、/interviews），一期**不得**为它们预留空页。
// 模块 10 项目成果并入模块 5，作为证据来源，不单设页面。
//
// 页面清单：登录 / 概览 / 岗位解析 / 技能图谱 / 技能画像 / 岗位匹配 / 学习计划。
// 岗位匹配是详情页（从岗位解析进入），因此侧边菜单只有 5 项。
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import AppLayout from '../layouts/AppLayout.vue'

// T1 已交付账号体系，拦截开启。改回 false 只应发生在调试单个页面时，且不得提交。
const AUTH_ENFORCED = true

const routes = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { guest: true, title: '登录' },
  },
  {
    path: '/',
    component: AppLayout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('../views/DashboardView.vue'),
        meta: { title: '概览', phase: 'T1', icon: 'Odometer' },
      },
      {
        path: 'jobs',
        name: 'jobs',
        component: () => import('../views/JobsView.vue'),
        meta: { title: '岗位解析', phase: 'T3', icon: 'Document' },
      },
      {
        path: 'skills',
        name: 'skills',
        component: () => import('../views/SkillsView.vue'),
        meta: { title: '技能图谱', phase: 'T3', icon: 'Share' },
      },
      {
        path: 'profile',
        name: 'profile',
        component: () => import('../views/ProfileView.vue'),
        // 档案部分 T1 已交付，技能自评与证据链在 T3；页面内自行标注，这里按已交付记
        meta: { title: '技能画像', phase: 'T1', icon: 'User' },
      },
      {
        path: 'match/:jobId',
        name: 'match',
        component: () => import('../views/MatchView.vue'),
        meta: { title: '岗位匹配', phase: 'T4', activeMenu: '/jobs' },
      },
      {
        path: 'tasks',
        name: 'tasks',
        component: () => import('../views/TasksView.vue'),
        meta: { title: '学习计划', phase: 'T5', icon: 'Notebook' },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (!AUTH_ENFORCED) return true

  // 已登录还去登录页：直接送回概览，否则用户会在登录页反复提交
  if (to.meta.guest && auth.isLoggedIn) {
    return { name: 'dashboard' }
  }

  if (to.meta.guest) return true

  if (!auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  // 有令牌但没用户信息（刷新页面后内存被清空）：先补一次 /auth/me
  // 失败说明令牌已失效，清掉并回登录页——不能放着不管，否则每个页面都会各自 401 一次
  if (!auth.user) {
    try {
      await auth.fetchMe()
    } catch {
      auth.logout()
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }

  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · 职航 CareerPath` : '职航 CareerPath'
})

export default router
