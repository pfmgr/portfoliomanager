<template>
  <div class="app">
    <header class="topbar">
      <div class="brand">Portfolio Admin</div>
      <nav v-if="!isLoginRoute" class="nav" aria-label="Primary">
        <ul class="nav-list">
          <li><RouterLink to="/rulesets">Reclassification Rulesets</RouterLink></li>
          <li><RouterLink to="/reclassifications">Reclassifications</RouterLink></li>
          <li><RouterLink to="/rebalancer">Rebalancer</RouterLink></li>
          <li><RouterLink to="/rebalancer/history">Rebalancer History</RouterLink></li>
          <li><RouterLink to="/assessor">Assessor</RouterLink></li>
          <li><RouterLink to="/layer-targets">Profile Configuration</RouterLink></li>
          <li><RouterLink to="/instruments">Effective Instruments</RouterLink></li>
          <li><RouterLink to="/knowledge-base">Knowledge Base</RouterLink></li>
          <li><RouterLink to="/savings-plans">Savings plans</RouterLink></li>
          <li><RouterLink to="/imports-exports">Imports & Exports</RouterLink></li>
        </ul>
      </nav>
      <button class="ghost" :disabled="logoutDisabled" @click="logout">{{ logoutLabel }}</button>
    </header>

    <main class="content">
      <RouterView />
    </main>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { clearJwtToken } from './api'

const router = useRouter()
const route = useRoute()
const isLoginRoute = computed(() => route.path === '/login')
const logoutDisabled = computed(() => isLoginRoute.value)
const logoutLabel = computed(() => (isLoginRoute.value ? 'Login' : 'Logout'))

function logout() {
  if (logoutDisabled.value) {
    return
  }
  clearJwtToken()
  router.push('/login')
}
</script>
