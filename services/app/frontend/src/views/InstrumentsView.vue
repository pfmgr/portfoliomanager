<template>
  <section class="panel">
    <h2>Effective Instruments</h2>
    <p class="hint">Search and review effective fields with overrides applied.</p>

    <form class="form inline" @submit.prevent="reload">
      <label class="field">
        <span>Search</span>
        <input v-model="filters.query" placeholder="ISIN or name" />
      </label>
      <label class="checkbox">
        <input type="checkbox" v-model="filters.onlyOverrides" />
        Only with overrides
      </label>
      <label class="field">
        <span>Page size</span>
        <select v-model.number="pagination.limit">
          <option :value="25">25</option>
          <option :value="50">50</option>
          <option :value="100">100</option>
        </select>
      </label>
      <button class="ghost" type="submit">Refresh</button>
    </form>

    <div class="table-wrap">
      <table class="table">
        <caption class="sr-only">Effective instruments with overrides.</caption>
        <thead>
          <tr class="row header">
            <th scope="col">ISIN</th>
            <th scope="col">Name</th>
            <th scope="col">Instrument Type</th>
            <th scope="col">Asset Class</th>
            <th scope="col">Sub Class</th>
            <th scope="col">Layer</th>
            <th scope="col">Layer Notes</th>
            <th scope="col">Override</th>
            <th scope="col">Actions</th>
          </tr>
        </thead>
        <tbody>
          <template v-if="loading">
            <tr>
              <td colspan="9">Loading instruments...</td>
            </tr>
          </template>
          <template v-else-if="items.length === 0">
            <tr>
              <td colspan="9">No instruments available.</td>
            </tr>
          </template>
          <template v-else>
            <tr v-for="instrument in items" :key="instrument.isin" class="row">
              <th scope="row">{{ instrument.isin }}</th>
              <td>
                <template v-if="editingIsin === instrument.isin">
                  <input v-model="overrideForm.name" class="input" aria-label="Override Name" placeholder="Override name" />
                </template>
                <template v-else>{{ instrument.effectiveName }}</template>
              </td>
              <td>
                <template v-if="editingIsin === instrument.isin">
                  <input
                    v-model="overrideForm.instrumentType"
                    class="input"
                    aria-label="Override Instrument Type"
                    placeholder="ETF, Stock, Fund"
                  />
                </template>
                <template v-else>{{ instrument.effectiveInstrumentType || '-' }}</template>
              </td>
              <td>
                <template v-if="editingIsin === instrument.isin">
                  <input
                    v-model="overrideForm.assetClass"
                    class="input"
                    aria-label="Override Asset Class"
                    placeholder="Equity, Bond, Cash"
                  />
                </template>
                <template v-else>{{ instrument.effectiveAssetClass || '-' }}</template>
              </td>
              <td>
                <template v-if="editingIsin === instrument.isin">
                  <input
                    v-model="overrideForm.subClass"
                    class="input"
                    aria-label="Override Sub Class"
                    placeholder="Global, Tech, Europe"
                  />
                </template>
                <template v-else>{{ instrument.effectiveSubClass || '-' }}</template>
              </td>
              <td>
                <template v-if="editingIsin === instrument.isin">
                  <input
                    v-model.number="overrideForm.layer"
                    class="input"
                    aria-label="Override Layer"
                    type="number"
                    min="1"
                    max="5"
                  />
                </template>
                <template v-else>{{ instrument.effectiveLayer ?? '-' }}</template>
              </td>
              <td>
                <template v-if="editingIsin === instrument.isin">
                  <input
                    v-model="overrideForm.layerNotes"
                    class="input"
                    aria-label="Override Layer Notes"
                    placeholder="Optional notes"
                  />
                </template>
                <template v-else>{{ instrument.effectiveLayerNotes || '-' }}</template>
              </td>
              <td>{{ instrument.hasOverride ? 'Yes' : 'No' }}</td>
              <td class="actions-cell">
                <template v-if="editingIsin === instrument.isin">
                  <button class="primary" type="button" @click="saveOverride">Save</button>
                  <button class="ghost" type="button" @click="cancelOverride">Cancel</button>
                </template>
                <template v-else>
                  <button class="ghost" @click="editOverride(instrument)">Edit</button>
                  <button v-if="instrument.hasOverride" class="ghost danger" @click="clearOverride(instrument)">
                    Clear
                  </button>
                </template>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>

    <div class="actions">
      <span class="muted">Total: {{ pagination.total }}</span>
      <button class="ghost" :disabled="pagination.offset === 0" @click="prevPage">Prev</button>
      <button class="ghost" :disabled="pagination.offset + pagination.limit >= pagination.total" @click="nextPage">
        Next
      </button>
    </div>

    <p v-if="error" class="toast error">{{ error }}</p>
  </section>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { apiRequest } from '../api'

const filters = ref({
  query: '',
  onlyOverrides: false
})
const pagination = ref({
  limit: 50,
  offset: 0,
  total: 0
})
const items = ref([])
const error = ref('')
const loading = ref(false)
const editingIsin = ref('')
const overrideForm = ref({
  isin: '',
  name: '',
  instrumentType: '',
  assetClass: '',
  subClass: '',
  layer: null,
  layerNotes: ''
})

async function reload() {
  error.value = ''
  loading.value = true
  const params = new URLSearchParams()
  if (filters.value.query) {
    params.append('q', filters.value.query)
  }
  if (filters.value.onlyOverrides) {
    params.append('onlyOverrides', 'true')
  }
  params.append('limit', String(pagination.value.limit))
  params.append('offset', String(pagination.value.offset))
  try {
    const result = await apiRequest(`/instruments/effective?${params.toString()}`)
    items.value = result.items || []
    pagination.value.total = result.total || 0
  } catch (err) {
    error.value = err.message || 'Failed to load instruments'
  } finally {
    loading.value = false
  }
}

function nextPage() {
  pagination.value.offset += pagination.value.limit
  reload()
}

function prevPage() {
  pagination.value.offset = Math.max(0, pagination.value.offset - pagination.value.limit)
  reload()
}

function editOverride(instrument) {
  editingIsin.value = instrument.isin
  overrideForm.value = {
    isin: instrument.isin,
    name: instrument.effectiveName || '',
    instrumentType: instrument.effectiveInstrumentType || '',
    assetClass: instrument.effectiveAssetClass || '',
    subClass: instrument.effectiveSubClass || '',
    layer: instrument.effectiveLayer ?? null,
    layerNotes: instrument.effectiveLayerNotes || ''
  }
}

function cancelOverride() {
  editingIsin.value = ''
  overrideForm.value = {
    isin: '',
    name: '',
    instrumentType: '',
    assetClass: '',
    subClass: '',
    layer: null,
    layerNotes: ''
  }
}

async function saveOverride() {
  try {
    const payload = {
      name: overrideForm.value.name || null,
      instrumentType: overrideForm.value.instrumentType || null,
      assetClass: overrideForm.value.assetClass || null,
      subClass: overrideForm.value.subClass || null,
      layer: overrideForm.value.layer || null,
      layerNotes: overrideForm.value.layerNotes || null
    }
    await apiRequest(`/overrides/${overrideForm.value.isin}`, {
      method: 'PUT',
      body: JSON.stringify(payload)
    })
    cancelOverride()
    reload()
  } catch (err) {
    error.value = err.message || 'Failed to save override'
  }
}

async function clearOverride(instrument) {
  if (!confirm(`Clear override for ${instrument.isin}?`)) {
    return
  }
  try {
    await apiRequest(`/overrides/${instrument.isin}`, { method: 'DELETE' })
    reload()
  } catch (err) {
    error.value = err.message || 'Failed to clear override'
  }
}

watch(() => [filters.value.query, filters.value.onlyOverrides, pagination.value.limit], () => {
  pagination.value.offset = 0
})

onMounted(reload)
</script>
