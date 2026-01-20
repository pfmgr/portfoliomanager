<template>
  <div>
    <div class="card">
      <h2>Reclassifications</h2>
      <p>Review current vs proposed vs policy-adjusted classifications. KB suggestions appear under the current name when available.</p>
      <div v-if="toast" :class="['toast', toastType]">{{ toast }}</div>
      <div class="grid grid-2">
        <label>
          Min Confidence
          <input class="input" type="number" step="0.05" min="0" max="1" v-model.number="minConfidence" />
        </label>
        <label>
          Show only changes
          <select class="input" v-model="onlyDifferent" aria-describedby="only-different-help">
            <option :value="true">Only instruments that would change</option>
            <option :value="false">All instruments</option>
          </select>
          <span id="only-different-help" class="note">
            Filters the list to instruments where the policy-adjusted classification differs from the current one (i.e., applying would be a real change).
          </span>
        </label>
      </div>
      <div style="display:flex; gap: 0.8rem; margin-top: 1rem;">
        <button class="secondary" @click="load">Refresh</button>
        <button class="primary" @click="dryRun">Dry Run Apply</button>
        <button class="primary" @click="apply">Confirm Apply</button>
      </div>
    </div>

    <div class="card">
      <div class="table-wrap">
        <table class="table">
          <caption class="sr-only">Reclassification candidates by confidence and impact.</caption>
          <thead>
            <tr>
              <th scope="col">Select</th>
              <th scope="col">ISIN</th>
              <th scope="col">Name / KB</th>
              <th scope="col">Current</th>
              <th scope="col">Proposed</th>
              <th scope="col">Policy Adjusted</th>
              <th scope="col">Confidence</th>
              <th scope="col">Impact €</th>
            </tr>
          </thead>
          <tbody>
            <template v-if="loading">
              <tr>
                <td colspan="8">Loading reclassifications...</td>
              </tr>
            </template>
            <template v-else-if="items.length === 0">
              <tr>
                <td colspan="8">No reclassifications available.</td>
              </tr>
            </template>
            <template v-else>
              <tr v-for="item in items" :key="item.isin">
                <td>
                  <input
                    type="checkbox"
                    v-model="selected"
                    :value="item.isin"
                    :aria-label="`Select ${item.isin}`"
                  />
                </td>
                <th scope="row">{{ item.isin }}</th>
                <td>
                  <div class="name-cell">
                    <span class="name-current">
                      <span class="sr-only">Current name:</span>
                      <span :title="item.name">{{ item.name }}</span>
                    </span>
                    <span
                      v-if="item.suggestedName && item.suggestedName !== item.name"
                      class="kb-suggestion"
                      :title="item.suggestedName"
                    >
                      <span class="kb-tag" aria-hidden="true">KB</span>
                      <span class="sr-only">Knowledge Base suggestion:</span>
                      <span class="kb-text">{{ item.suggestedName }}</span>
                    </span>
                  </div>
                </td>
                <td>{{ formatClassification(item.current) }}</td>
                <td>{{ formatClassification(item.proposed) }}</td>
                <td>{{ formatClassification(item.policyAdjusted) }}</td>
                <td>{{ item.confidence.toFixed(2) }}</td>
                <td>{{ item.impact.valueEur.toFixed(2) }}</td>
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

const items = ref([])
const selected = ref([])
const minConfidence = ref(0.7)
const onlyDifferent = ref(true)
const toast = ref('')
const toastType = ref('success')
const rulesetName = 'default'
const loading = ref(false)

async function load() {
  toast.value = ''
  loading.value = true
  try {
    const query = `?minConfidence=${minConfidence.value}&onlyDifferent=${onlyDifferent.value}`
    items.value = await apiRequest(`/rebalancer/reclassifications${query}`)
    selected.value = []
  } finally {
    loading.value = false
  }
}

function formatClassification(entry) {
  return `${entry.instrumentType || '-'} / ${entry.assetClass || '-'} / ${entry.subClass || '-'} / L${entry.layer || '-'}`
}

async function dryRun() {
  await applyRequest(true)
}

async function apply() {
  await applyRequest(false)
}

async function applyRequest(dryRun) {
  if (selected.value.length === 0) {
    toastType.value = 'error'
    toast.value = 'Select at least one instrument.'
    return
  }
  try {
    await apiRequest(`/rulesets/${rulesetName}/apply`, {
      method: 'POST',
      body: JSON.stringify({ dryRun, isins: selected.value })
    })
    toastType.value = 'success'
    toast.value = dryRun ? 'Dry run completed.' : 'Changes applied.'
    await load()
  } catch (err) {
    toastType.value = 'error'
    toast.value = err.message
  }
}

onMounted(load)
</script>

<style scoped>
.name-cell {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
}

.name-current {
  overflow-wrap: anywhere;
}

.kb-suggestion {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  color: #6a6761;
  font-size: 0.85rem;
}

.kb-tag {
  border: 1px solid #c6c2bb;
  border-radius: 6px;
  padding: 0.05rem 0.35rem;
  font-size: 0.75rem;
  letter-spacing: 0.02em;
  text-transform: uppercase;
}

.kb-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: min(260px, 40vw);
}
</style>
