<template>
  <div>
    <div class="card">
      <h2>Reclassification Rulesets</h2>
      <p>Manage JSON rulesets used to reclassify instruments into Layers 1–5 (and optional fields like asset class and instrument type).</p>
      <div v-if="toast" :class="['toast', toastType]">{{ toast }}</div>

      <div class="grid grid-2">
        <div>
          <label>Name
            <input class="input" v-model="rulesetName" placeholder="default" />
          </label>
        </div>
        <div>
          <label>Upload JSON file
            <input class="input" type="file" accept=".json,application/json" @change="onFileUpload" />
          </label>
        </div>
      </div>

      <label style="display:block; margin-top: 1rem;">Ruleset JSON</label>
      <textarea rows="12" v-model="jsonText"></textarea>

      <div style="display:flex; gap: 0.8rem; align-items:center; margin-top: 1rem;">
        <button class="secondary" @click="validate">Validate</button>
        <button class="primary" @click="save">Save New Version</button>
        <label style="display:flex; align-items:center; gap:0.4rem;">
          <input type="checkbox" v-model="activate" /> Activate
        </label>
      </div>
    </div>

    <div class="card">
      <h3>Existing Rulesets</h3>
      <div class="table-wrap">
        <table class="table">
          <caption class="sr-only">Reclassification ruleset versions and status.</caption>
          <thead>
            <tr>
              <th scope="col">Name</th>
              <th scope="col">Version</th>
              <th scope="col">Active</th>
              <th scope="col">Updated</th>
              <th scope="col">Actions</th>
            </tr>
          </thead>
          <tbody>
            <template v-if="loading">
              <tr>
                <td colspan="5">Loading rulesets...</td>
              </tr>
            </template>
            <template v-else-if="rulesets.length === 0">
              <tr>
                <td colspan="5">No rulesets available.</td>
              </tr>
            </template>
            <template v-else>
              <tr v-for="item in rulesets" :key="item.name + '-' + item.version">
                <th scope="row">{{ item.name }}</th>
                <td>{{ item.version }}</td>
                <td>{{ item.active ? 'yes' : 'no' }}</td>
                <td>{{ formatDate(item.updatedAt) }}</td>
                <td><button class="secondary" @click="loadLatest(item.name)">View</button></td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { apiRequest } from '../api'

const rulesets = ref([])
const rulesetName = ref('default')
const jsonText = ref('')
const activate = ref(true)
const toast = ref('')
const toastType = ref('success')
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    rulesets.value = await apiRequest('/rulesets')
  } finally {
    loading.value = false
  }
}

function onFileUpload(event) {
  const file = event.target.files[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = (e) => {
    jsonText.value = e.target.result
  }
  reader.readAsText(file)
}

async function validate() {
  toast.value = ''
  try {
    const response = await apiRequest(`/rulesets/${rulesetName.value}/validate`, {
      method: 'POST',
      body: JSON.stringify({ contentJson: jsonText.value })
    })
    toastType.value = response.valid ? 'success' : 'error'
    toast.value = response.valid ? 'Ruleset valid.' : response.errors.join('; ')
  } catch (err) {
    toastType.value = 'error'
    toast.value = err.message
  }
}

async function save() {
  toast.value = ''
  try {
    await apiRequest(`/rulesets/${rulesetName.value}`, {
      method: 'PUT',
      body: JSON.stringify({ contentJson: jsonText.value, activate: activate.value })
    })
    toastType.value = 'success'
    toast.value = 'Ruleset saved.'
    await load()
  } catch (err) {
    toastType.value = 'error'
    toast.value = err.message
  }
}

async function loadLatest(name) {
  const response = await apiRequest(`/rulesets/${name}`)
  jsonText.value = response.contentJson
  rulesetName.value = response.name
  activate.value = response.active
}

function formatDate(value) {
  if (!value) return '-'
  return new Date(value).toLocaleString()
}

onMounted(load)
</script>
