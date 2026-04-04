<template>
  <div class="app">
    <header class="topbar">
      <div class="brand">Portfolio Admin</div>
      <nav v-if="showNav" class="nav" aria-label="Primary">
        <ul class="nav-list">
          <li><RouterLink to="/start">Start</RouterLink></li>
          <li><RouterLink to="/rulesets">Reclassification Rulesets</RouterLink></li>
          <li><RouterLink to="/reclassifications">Reclassifications</RouterLink></li>
          <li><RouterLink to="/rebalancer">Rebalancer</RouterLink></li>
          <li><RouterLink to="/rebalancer/history">Rebalancer History</RouterLink></li>
          <li><RouterLink to="/assessor">Assessor</RouterLink></li>
          <li><RouterLink to="/layer-targets">Profile Configuration</RouterLink></li>
          <li><RouterLink to="/llm-configuration">LLM Configuration</RouterLink></li>
          <li><RouterLink to="/instruments">Effective Instruments</RouterLink></li>
          <li><RouterLink to="/knowledge-base">Knowledge Base</RouterLink></li>
          <li><RouterLink to="/savings-plans">Savings plans</RouterLink></li>
          <li><RouterLink to="/imports-exports">Imports & Exports</RouterLink></li>
        </ul>
      </nav>
      <div class="topbar-actions">
        <span v-if="isBackendBlocked" class="topbar-status">
          {{ topbarStatus }}
        </span>
        <button v-else class="ghost" :disabled="logoutDisabled" @click="logout">{{ logoutLabel }}</button>
      </div>
    </header>

    <main class="content">
      <section v-if="!backendReady" class="startup">
        <div class="startup-panel">
          <p class="startup-eyebrow">System check</p>
          <h1>Backend is starting up...</h1>
          <p class="startup-note">
            We're waiting for the backend service to come online. You can keep this tab open and we will reconnect
            automatically.
          </p>
          <div class="startup-actions">
            <button class="primary" type="button" :disabled="healthCheckInFlight" @click="retryNow">
              {{ healthCheckInFlight ? 'Checking...' : 'Retry now' }}
            </button>
            <span class="startup-status" role="status" aria-atomic="true">{{ startupStatus }}</span>
          </div>
          <p v-if="lastError" class="startup-error" role="alert">
            We couldn't reach the backend yet. Last check: {{ lastError }}
          </p>
        </div>
      </section>
      <RouterView v-else />
    </main>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { checkBackendHealth, clearJwtToken } from './api'

const router = useRouter()
const route = useRoute()
const isLoginRoute = computed(() => route.path === '/login')
const backendState = ref('checking')
const lastError = ref('')
const healthCheckInFlight = ref(false)
const retryAttempt = ref(0)
let retryTimer = null

const backendReady = computed(() => backendState.value === 'ready')
const isBackendBlocked = computed(() => !backendReady.value)
const showNav = computed(() => !isLoginRoute.value && !isBackendBlocked.value)
const logoutDisabled = computed(() => isLoginRoute.value || isBackendBlocked.value)
const logoutLabel = computed(() => (isLoginRoute.value ? 'Login' : 'Logout'))
const topbarStatus = computed(() => (backendState.value === 'checking' ? 'Checking backend...' : 'Backend starting...'))
const startupStatus = computed(() => {
  if (backendState.value === 'checking') {
    return 'Checking backend availability...'
  }
  return 'Backend not ready. Retrying shortly.'
})

function nextDelay() {
  const base = 1200
  const max = 8000
  const delay = Math.min(max, Math.round(base * Math.pow(1.6, retryAttempt.value)))
  const jitter = Math.round(delay * (Math.random() * 0.2 - 0.1))
  return Math.max(500, delay + jitter)
}

function scheduleRetry() {
  const delay = nextDelay()
  retryAttempt.value += 1
  if (retryTimer) {
    clearTimeout(retryTimer)
  }
  retryTimer = setTimeout(runHealthCheck, delay)
}

async function runHealthCheck() {
  if (healthCheckInFlight.value || backendReady.value) {
    return
  }
  healthCheckInFlight.value = true
  backendState.value = 'checking'
  const result = await checkBackendHealth({ timeoutMs: 2500 })
  healthCheckInFlight.value = false
  if (result.ok) {
    backendState.value = 'ready'
    lastError.value = ''
    if (retryTimer) {
      clearTimeout(retryTimer)
      retryTimer = null
    }
    return
  }
  lastError.value = result.error || (result.status ? `HTTP ${result.status}` : 'Network error')
  backendState.value = 'retrying'
  scheduleRetry()
}

function retryNow() {
  if (retryTimer) {
    clearTimeout(retryTimer)
    retryTimer = null
  }
  retryAttempt.value = 0
  runHealthCheck()
}

onMounted(() => {
  runHealthCheck()
})

onBeforeUnmount(() => {
  if (retryTimer) {
    clearTimeout(retryTimer)
    retryTimer = null
  }
})

function logout() {
  if (logoutDisabled.value) {
    return
  }
  clearJwtToken()
  router.push('/login')
}
</script>
