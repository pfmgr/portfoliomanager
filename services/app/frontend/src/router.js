import { createRouter, createWebHistory } from 'vue-router'
import LoginView from './views/LoginView.vue'
import RulesetsView from './views/RulesetsView.vue'
import ReclassificationsView from './views/ReclassificationsView.vue'
import AdvisorSummaryView from './views/AdvisorSummaryView.vue'
import AdvisorHistoryView from './views/AdvisorHistoryView.vue'
import AssessorView from './views/AssessorView.vue'
import LayerTargetsView from './views/LayerTargetsView.vue'
import ImportsExportsView from './views/ImportsExportsView.vue'
import SavingsPlansView from './views/SavingsPlansView.vue'
import InstrumentsView from './views/InstrumentsView.vue'
import KnowledgeBaseView from './views/KnowledgeBaseView.vue'
import { getJwtToken, clearJwtToken } from './api'

const routes = [
  { path: '/', redirect: '/rulesets' },
  { path: '/login', component: LoginView },
  { path: '/rulesets', component: RulesetsView },
  { path: '/reclassifications', component: ReclassificationsView },
  { path: '/advisor', component: AdvisorSummaryView },
  { path: '/advisor/history', component: AdvisorHistoryView },
  { path: '/assessor', component: AssessorView },
  { path: '/layer-targets', component: LayerTargetsView },
  { path: '/instruments', component: InstrumentsView },
  { path: '/knowledge-base', component: KnowledgeBaseView },
  { path: '/imports-exports', component: ImportsExportsView },
  { path: '/savings-plans', component: SavingsPlansView },
  { path: '/overrides', redirect: '/imports-exports' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const token = getJwtToken()
  if (to.path !== '/login' && !token) {
    return '/login'
  }
  if (to.path === '/login' && token) {
    return '/rulesets'
  }
})

export default router
