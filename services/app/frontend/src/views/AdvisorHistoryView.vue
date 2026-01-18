<template>
  <div>
    <div class="card">
      <h2>Advisor History</h2>
      <p class="note">Saved advisor runs with narrative snapshots.</p>
    </div>

    <div class="card">
      <h3>Saved Runs</h3>
      <div class="table-wrap">
        <table class="table">
          <caption class="sr-only">Saved advisor runs.</caption>
          <thead>
            <tr>
              <th scope="col">Run</th>
              <th scope="col">Created</th>
              <th scope="col">As Of</th>
              <th scope="col">Depot Scope</th>
              <th scope="col">Actions</th>
            </tr>
          </thead>
          <tbody>
            <template v-if="loading">
              <tr>
                <td colspan="5">Loading advisor runs...</td>
              </tr>
            </template>
            <template v-else-if="runs.length === 0">
              <tr>
                <td colspan="5">No saved advisor runs yet.</td>
              </tr>
            </template>
            <template v-else>
              <tr v-for="run in runs" :key="run.runId">
                <th scope="row">#{{ run.runId }}</th>
                <td>{{ formatDateTime(run.createdAt) }}</td>
                <td>{{ run.asOfDate || 'n/a' }}</td>
                <td>{{ formatScope(run.depotScope) }}</td>
                <td>
                  <button class="secondary" @click="loadRun(run.runId)">View</button>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
    </div>

    <div class="card" v-if="selectedRun">
      <h3>Run #{{ selectedRun.runId }}</h3>
      <p class="note">
        Created {{ formatDateTime(selectedRun.createdAt) }},
        as of {{ selectedRun.asOfDate || 'n/a' }}.
      </p>
      <p v-if="selectedRun.narrativeMd">
        {{ selectedRun.narrativeMd }}
      </p>
      <p v-else class="note">No narrative stored for this run.</p>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { apiRequest } from '../api'

const runs = ref([])
const selectedRun = ref(null)
const loading = ref(false)

async function loadRuns() {
  loading.value = true
  try {
    runs.value = await apiRequest('/advisor/runs')
  } finally {
    loading.value = false
  }
}

async function loadRun(runId) {
  selectedRun.value = await apiRequest(`/advisor/runs/${runId}`)
}

function formatDateTime(value) {
  if (!value) {
    return 'n/a'
  }
  return new Date(value).toLocaleString()
}

function formatScope(scope) {
  if (!scope || scope.length === 0) {
    return 'n/a'
  }
  return scope.join(', ')
}

onMounted(loadRuns)
</script>
