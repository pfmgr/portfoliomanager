<template>
  <div class="card" style="max-width: 420px; margin: 4rem auto;">
    <h2>Admin Login</h2>
    <p>Authenticate to access reclassification rulesets and rebalancer tools.</p>
    <div v-if="displayMessage" :class="['toast', messageClass]">{{ displayMessage }}</div>
    <form @submit.prevent="submit">
      <label>
        Username
        <input class="input" v-model="username" required />
      </label>
      <label style="display:block; margin-top: 1rem;">
        Password
        <input class="input" type="password" v-model="password" required />
      </label>
      <button class="primary" style="margin-top: 1.2rem; width: 100%;">Login</button>
    </form>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authRequest, storeJwtToken } from '../api'

const router = useRouter()
const route = useRoute()
const username = ref('')
const password = ref('')
const explicitMessage = ref('')
const explicitMessageType = ref('')

const autoMessage = computed(() => route.query.message || '')
const displayMessage = computed(() => explicitMessage.value || autoMessage.value)
const messageClass = computed(() => {
  if (explicitMessageType.value) {
    return explicitMessageType.value
  }
  return autoMessage.value ? 'error' : ''
})

async function submit() {
  explicitMessage.value = ''
  explicitMessageType.value = ''
  try {
    const response = await authRequest('/token', {
      method: 'POST',
      body: JSON.stringify({ username: username.value, password: password.value })
    })
    storeJwtToken(response.token)
    explicitMessageType.value = 'success'
    explicitMessage.value = 'Login successful.'
    setTimeout(() => router.push('/rulesets'), 600)
  } catch (err) {
    explicitMessageType.value = 'error'
    explicitMessage.value = err.message
  }
}
</script>
