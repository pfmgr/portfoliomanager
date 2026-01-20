import { createRouter, createWebHistory } from 'vue-router'
import LoginView from './views/LoginView.vue'
import RulesetsView from './views/RulesetsView.vue'
import ReclassificationsView from './views/ReclassificationsView.vue'
import RebalancerView from './views/RebalancerView.vue'
import RebalancerHistoryView from './views/RebalancerHistoryView.vue'
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
  { path: '/rebalancer', component: RebalancerView },
  { path: '/rebalancer/history', component: RebalancerHistoryView },
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
