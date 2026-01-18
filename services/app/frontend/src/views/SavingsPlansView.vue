<template>
  <section class="panel">
    <h2>Savings plans</h2>

    <div class="actions">
      <button class="ghost" @click="downloadCsv">Export CSV</button>
      <label class="ghost file">
        Import CSV
        <input type="file" @change="importCsv" />
      </label>
    </div>

    <div class="table-wrap">
      <table class="table">
        <caption class="sr-only">Savings plans list.</caption>
        <thead>
          <tr>
            <th scope="col">Depot</th>
            <th scope="col">ISIN</th>
            <th scope="col">Name</th>
            <th scope="col">Layer</th>
            <th scope="col">Amount</th>
            <th scope="col">Frequency</th>
            <th scope="col">Day</th>
            <th scope="col">Active</th>
            <th scope="col">Last Changed</th>
            <th scope="col">Actions</th>
          </tr>
        </thead>
        <tbody>
            <template v-if="loading">
              <tr>
                <td colspan="10">Loading savings plans...</td>
              </tr>
            </template>
            <template v-else-if="savingPlans.length === 0">
              <tr>
                <td colspan="10">No savings plans available.</td>
              </tr>
            </template>
            <template v-else>
              <tr v-for="row in savingPlans" :key="row.savingPlanId">
                <td>{{ row.depotCode }}</td>
                <th scope="row">{{ row.isin }}</th>
                <td>{{ row.name }}</td>
                <td>{{ layerLabel(row.layer) }}</td>
              <td>{{ row.amountEur }}</td>
              <td>{{ row.frequency }}</td>
              <td>{{ row.dayOfMonth || '-' }}</td>
              <td>{{ row.active ? 'Yes' : 'No' }}</td>
              <td>{{ row.lastChanged || '-' }}</td>
              <td class="actions-cell">
                <button class="ghost" @click="editRow(row)">Edit</button>
                <button class="ghost danger" @click="deleteRow(row)">Delete</button>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>

    <h3>{{ formMode }} Savings plan</h3>
    <form class="form" @submit.prevent="submitForm">
      <label class="field">
        <span>Depot</span>
        <select v-model="form.depotId" :disabled="formMode === 'Update'" required>
          <option value="" disabled>Select depot</option>
          <option v-for="depot in depots" :key="depot.depotId" :value="depot.depotId">
            {{ depot.depotCode }} - {{ depot.name }}
          </option>
        </select>
      </label>
      <label class="field">
        <span>ISIN</span>
        <input v-model="form.isin" :disabled="formMode === 'Update'" required />
      </label>
      <label class="field">
        <span>Name</span>
        <input v-model="form.name" />
      </label>
      <label class="field">
        <span>Amount (EUR)</span>
        <input v-model="form.amountEur" type="number" step="0.01" required />
      </label>
      <label class="field">
        <span>Frequency</span>
        <input v-model="form.frequency" placeholder="monthly" />
      </label>
      <label class="field">
        <span>Day of Month</span>
        <input v-model="form.dayOfMonth" type="number" min="1" max="31" />
      </label>
      <label class="checkbox">
        <input type="checkbox" v-model="form.active" />
        Active
      </label>
      <label class="field">
        <span>Last Changed</span>
        <input v-model="form.lastChanged" type="date" />
      </label>

      <div class="actions">
        <button class="primary" type="submit" :disabled="busy">
          {{ formMode === 'Create' ? 'Create' : 'Update' }}
        </button>
        <button class="ghost" type="button" @click="resetForm">Reset</button>
      </div>
    </form>

    <p v-if="message" class="toast success">{{ message }}</p>
    <p v-if="error" class="toast error">{{ error }}</p>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { apiDownload, apiRequest, apiUpload } from '../api'

const savingPlans = ref([])
const depots = ref([])
const message = ref('')
const error = ref('')
const busy = ref(false)
const editingId = ref(null)
const loading = ref(false)
const layerNames = ref({
  1: 'Global Core',
  2: 'Core-Plus',
  3: 'Themes',
  4: 'Individual Stocks',
  5: 'Unclassified'
})

const form = ref({
  depotId: '',
  isin: '',
  name: '',
  amountEur: '',
  frequency: 'monthly',
  dayOfMonth: '',
  active: true,
  lastChanged: ''
})

const formMode = computed(() => (editingId.value ? 'Update' : 'Create'))

async function loadAll() {
  loading.value = true
  try {
    savingPlans.value = await apiRequest('/sparplans')
    depots.value = await apiRequest('/depots')
    await loadLayerNames()
  } finally {
    loading.value = false
  }
}

function editRow(row) {
  editingId.value = row.savingPlanId
  form.value = {
    depotId: row.depotId,
    isin: row.isin,
    name: row.name || '',
    amountEur: row.amountEur,
    frequency: row.frequency,
    dayOfMonth: row.dayOfMonth || '',
    active: row.active,
    lastChanged: row.lastChanged || ''
  }
}

function resetForm() {
  editingId.value = null
  form.value = {
    depotId: '',
    isin: '',
    name: '',
    amountEur: '',
    frequency: 'monthly',
    dayOfMonth: '',
    active: true,
    lastChanged: ''
  }
}

async function submitForm() {
  busy.value = true
  message.value = ''
  error.value = ''
  try {
    const payload = {
      depotId: Number(form.value.depotId),
      isin: form.value.isin,
      name: form.value.name || null,
      amountEur: Number(form.value.amountEur),
      frequency: form.value.frequency || 'monthly',
      dayOfMonth: form.value.dayOfMonth ? Number(form.value.dayOfMonth) : null,
      active: form.value.active,
      lastChanged: form.value.lastChanged || null
    }
    if (editingId.value) {
      await apiRequest(`/sparplans/${editingId.value}`, {
        method: 'PUT',
        body: JSON.stringify(payload)
      })
      message.value = 'Savings plan updated.'
    } else {
      await apiRequest('/sparplans', {
        method: 'POST',
        body: JSON.stringify(payload)
      })
      message.value = 'Savings plan created.'
    }
    await loadAll()
    resetForm()
  } catch (err) {
    error.value = err.message || 'Save failed'
  } finally {
    busy.value = false
  }
}

async function deleteRow(row) {
    if (!confirm(`Delete saving plan ${row.isin}?`)) {
    return
  }
  busy.value = true
  message.value = ''
  error.value = ''
  try {
    await apiRequest(`/sparplans/${row.savingPlanId}`, { method: 'DELETE' })
    message.value = 'Savings plan deleted.'
    await loadAll()
  } catch (err) {
    error.value = err.message || 'Delete failed'
  } finally {
    busy.value = false
  }
}

async function importCsv(event) {
  const file = event.target.files[0]
  if (!file) {
    return
  }
  busy.value = true
  message.value = ''
  error.value = ''
  try {
    const data = new FormData()
    data.append('file', file)
    const result = await apiUpload('/sparplans/import', data)
    message.value = `Imported saving plans: created=${result.created}, updated=${result.updated}, skippedMissing=${result.skippedMissing}.`
    await loadAll()
  } catch (err) {
    error.value = err.message || 'Import failed'
  } finally {
    busy.value = false
    event.target.value = ''
  }
}

async function downloadCsv() {
  try {
    const response = await apiDownload('/sparplans/export')
    const blob = await response.blob()
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'savingPlans.csv'
    link.click()
    window.URL.revokeObjectURL(url)
  } catch (err) {
    error.value = err.message || 'Download failed'
  }
}

async function loadLayerNames() {
  try {
    const data = await apiRequest('/layer-targets')
    if (data.layerNames) {
      layerNames.value = data.layerNames
    }
  } catch (err) {
    console.warn('Failed to load layer names', err)
  }
}

function layerLabel(layer) {
  const key = Number(layer)
  if (!Number.isFinite(key)) {
    return layer || 'Unclassified'
  }
  return layerNames.value[key] || `Layer ${key}`
}

onMounted(loadAll)
</script>
