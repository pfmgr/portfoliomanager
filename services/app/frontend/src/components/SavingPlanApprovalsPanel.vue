<template>
  <section v-if="visibleRows.length || message" class="approval-panel">
	  <div class="approval-panel__header">
      <button
        v-if="visibleRows.length"
        class="primary"
        type="button"
        :disabled="busy"
        :aria-expanded="open ? 'true' : 'false'"
        :aria-controls="panelId"
        @click="toggleOpen"
      >
        Apply Approvals
      </button>
      <p class="note approval-panel__hint">
        Applying approvals does not execute real depot transactions. It only updates the saving plans shown in Portfolio Manager.
      </p>
    </div>

    <div v-if="open && visibleRows.length" :id="panelId" class="approval-panel__body card">
      <div class="approval-panel__summary">
        <p class="note">
          Choose which approved saving-plan proposals to apply.
        </p>
        <p class="note" v-if="selectedCount">
          Selected: <strong>{{ selectedCount }}</strong>
          <span v-if="selectedNeedsDepotCount"> · Depot required: <strong>{{ selectedNeedsDepotCount }}</strong></span>
        </p>
      </div>

      <div class="table-wrap">
        <table class="table">
          <caption class="sr-only">Saving plan proposals ready to apply.</caption>
          <thead>
            <tr>
              <th scope="col">Select</th>
              <th scope="col">Action</th>
              <th scope="col">ISIN</th>
              <th scope="col">Name</th>
              <th scope="col">Layer</th>
              <th scope="col" class="num">Current €</th>
              <th scope="col" class="num">Proposed €</th>
              <th scope="col" class="num">Delta €</th>
              <th scope="col">Depot</th>
              <th scope="col">Why</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in visibleRows" :key="row.key">
              <td>
                <label class="checkbox checkbox--compact">
                  <input
                    :checked="isSelected(row.key)"
                    type="checkbox"
                    :aria-label="`Select proposal for ${row.isin}`"
                    @change="toggleSelection(row.key)"
                  />
                </label>
              </td>
              <td><span :class="['badge', row.actionClass]">{{ row.action }}</span></td>
              <th scope="row">{{ row.isin }}</th>
              <td>{{ row.instrumentName || '—' }}</td>
              <td>{{ row.layer ? layerLabel(row.layer) : '—' }}</td>
              <td class="num">{{ formatAmount(row.currentAmountEur) }}</td>
              <td class="num">{{ formatAmount(row.proposedAmountEur) }}</td>
              <td class="num">{{ formatSigned(row.deltaEur) }}</td>
              <td>
                <template v-if="row.requiresDepotSelection && isSelected(row.key)">
                  <label class="sr-only" :for="`approval-depot-${row.key}`">Choose depot</label>
                  <select
                    :id="`approval-depot-${row.key}`"
                    v-model="depotSelections[row.key]"
                    class="approval-panel__select"
                    :aria-invalid="submitAttempted && !depotSelections[row.key] ? 'true' : 'false'"
                    :aria-describedby="submitAttempted && !depotSelections[row.key] ? `approval-depot-error-${row.key}` : null"
                  >
                    <option value="">Choose depot</option>
                    <option v-for="depot in depots" :key="depot.depotId" :value="String(depot.depotId)">
                      {{ depot.depotCode }} - {{ depot.name }}
                    </option>
                  </select>
                  <span
                    v-if="submitAttempted && !depotSelections[row.key]"
                    :id="`approval-depot-error-${row.key}`"
                    class="note warn"
                  >
                    Select a depot to create this new saving plan.
                  </span>
                </template>
                <template v-else>
                  {{ row.depotName || row.depotCode || (row.requiresDepotSelection ? 'Depot required' : '—') }}
                </template>
              </td>
              <td>{{ row.rationale || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="actions approval-panel__actions">
        <button class="primary" type="button" :disabled="!canApply || busy" @click="applySelected">
          {{ busy ? 'Applying...' : 'Apply selected proposals' }}
        </button>
        <button class="ghost" type="button" :disabled="busy" @click="closePanel">Cancel</button>
      </div>
    </div>
    <p v-if="error" class="toast error">{{ error }}</p>
    <p v-if="message" class="toast success">{{ message }}</p>
  </section>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { apiRequest } from '../api'

const props = defineProps({
  source: {
    type: String,
    required: true
  },
  rows: {
    type: Array,
    default: () => []
  },
  layerLabel: {
    type: Function,
    required: true
  }
})

const emit = defineEmits(['applied'])

const open = ref(false)
const busy = ref(false)
const submitAttempted = ref(false)
const error = ref('')
const message = ref('')
const depots = ref([])
const selected = reactive({})
const depotSelections = reactive({})
const panelId = `approval-panel-${Math.random().toString(36).slice(2)}`
const dismissedKeys = ref(new Set())

const visibleRows = computed(() => props.rows.filter((row) => !dismissedKeys.value.has(row.key)))
const selectedRows = computed(() => visibleRows.value.filter((row) => selected[row.key]))
const selectedCount = computed(() => selectedRows.value.length)
const selectedNeedsDepotCount = computed(() =>
  selectedRows.value.filter((row) => row.requiresDepotSelection && !depotSelections[row.key]).length
)
const canApply = computed(() => selectedCount.value > 0 && selectedNeedsDepotCount.value === 0)

watch(
  () => props.rows,
  (rows) => {
    dismissedKeys.value = new Set()
    const keys = new Set(rows.map((row) => row.key))
    Object.keys(selected).forEach((key) => {
      if (!keys.has(key)) {
        delete selected[key]
      }
    })
    Object.keys(depotSelections).forEach((key) => {
      if (!keys.has(key)) {
        delete depotSelections[key]
      }
    })
    if (rows.length === 0) {
      open.value = false
    }
  },
  { deep: true }
)

async function toggleOpen() {
  open.value = !open.value
  error.value = ''
  message.value = ''
  submitAttempted.value = false
  if (open.value) {
    await ensureDepotsLoaded()
  }
}

function closePanel() {
  open.value = false
  submitAttempted.value = false
  error.value = ''
}

function toggleSelection(key) {
  selected[key] = !selected[key]
}

function isSelected(key) {
  return Boolean(selected[key])
}

async function ensureDepotsLoaded() {
  if (depots.value.length > 0) {
    return
  }
  depots.value = await apiRequest('/depots')
}

async function applySelected() {
  submitAttempted.value = true
  message.value = ''
  error.value = ''
  if (!canApply.value) {
    error.value = selectedCount.value === 0
      ? 'Select at least one proposal to apply.'
      : 'Select a depot for each new saving plan before applying.'
    return
  }
  busy.value = true
  try {
    const items = selectedRows.value.map((row) => ({
      savingPlanId: row.savingPlanId ?? null,
      depotId: row.requiresDepotSelection ? Number(depotSelections[row.key]) : (row.depotId ?? null),
      isin: row.isin,
      instrumentName: row.instrumentName ?? null,
      layer: row.layer ?? null,
      targetAmountEur: Number(row.proposedAmountEur ?? 0),
      rationale: row.rationale ?? null
    }))
    const response = await apiRequest('/sparplans/apply-approvals', {
      method: 'POST',
      body: JSON.stringify({
        source: props.source,
        items
      })
    })
    const appliedKeys = selectedRows.value.map((row) => row.key)
    const nextDismissed = new Set(dismissedKeys.value)
    appliedKeys.forEach((key) => nextDismissed.add(key))
    dismissedKeys.value = nextDismissed
    message.value = buildSuccessMessage(response)
    selectedRows.value.forEach((row) => {
      delete selected[row.key]
      delete depotSelections[row.key]
    })
    emit('applied', { response, appliedKeys })
  } catch (err) {
    error.value = err.message || 'Applying proposals failed.'
  } finally {
    busy.value = false
  }
}

function buildSuccessMessage(response) {
  const applied = Number(response?.applied ?? 0)
  const created = Number(response?.created ?? 0)
  const updated = Number(response?.updated ?? 0)
  const deactivated = Number(response?.deactivated ?? 0)
  return `Applied ${applied} proposal(s): created=${created}, updated=${updated}, deactivated=${deactivated}. No real depot transaction was executed.`
}

function formatAmount(value) {
  const numeric = Number(value ?? 0)
  return Number.isFinite(numeric) ? numeric.toFixed(2) : '0.00'
}

function formatSigned(value) {
  const numeric = Number(value ?? 0)
  if (!Number.isFinite(numeric)) {
    return '0.00'
  }
  const prefix = numeric > 0 ? '+' : ''
  return `${prefix}${numeric.toFixed(2)}`
}
</script>

<style scoped>
.approval-panel {
  display: grid;
  gap: 0.9rem;
}

.approval-panel__header {
  display: grid;
  gap: 0.4rem;
}

.approval-panel__hint {
  margin: 0;
  max-width: 72ch;
}

.approval-panel__body {
  display: grid;
  gap: 0.9rem;
}

.approval-panel__summary {
  display: grid;
  gap: 0.2rem;
}

.approval-panel__summary .note {
  margin: 0;
}

.approval-panel__select {
  min-width: 12rem;
}

.approval-panel__actions {
  justify-content: flex-start;
}

.checkbox--compact {
  margin: 0;
}

@media (max-width: 900px) {
  .approval-panel__select {
    min-width: 10rem;
  }
}
</style>
