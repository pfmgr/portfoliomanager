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
      <p v-if="open && visibleRows.length" class="note approval-panel__hint">
        Blacklist decisions take effect immediately from this table and follow the same proposal exclusion rules as the Knowledge Base.
      </p>
    </div>

    <div v-if="open && visibleRows.length" :id="panelId" class="approval-panel__body card">
      <div class="approval-panel__summary">
        <p class="note">Choose how each approved saving-plan proposal should be handled.</p>
        <p class="note">
          Apply: <strong>{{ applyCount }}</strong>
          <span> · Ignore: <strong>{{ ignoreCount }}</strong></span>
          <span> · Saving plan proposals only: <strong>{{ savingPlanBlacklistCount }}</strong></span>
          <span> · All buy proposals: <strong>{{ allProposalBlacklistCount }}</strong></span>
          <span v-if="applyNeedsDepotCount"> · Depot required: <strong>{{ applyNeedsDepotCount }}</strong></span>
        </p>
      </div>

      <div class="table-wrap">
        <table class="table">
          <caption class="sr-only">Saving plan proposals ready to apply or blacklist.</caption>
          <thead>
            <tr>
              <th scope="col">Decision</th>
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
                <label class="sr-only" :for="`approval-decision-${row.key}`">Decision for {{ row.isin }}</label>
                <select
                  :id="`approval-decision-${row.key}`"
                  v-model="decisions[row.key]"
                  class="approval-panel__select"
                  :aria-label="`Decision for ${row.isin}`"
                  @change="handleDecisionChange(row)"
                >
                  <option v-for="option in decisionOptions(row)" :key="option.value" :value="option.value">
                    {{ option.label }}
                  </option>
                </select>
              </td>
              <td><span :class="['badge', row.actionClass]">{{ row.action }}</span></td>
              <th scope="row">{{ row.isin }}</th>
              <td>{{ row.instrumentName || '—' }}</td>
              <td>{{ row.layer ? layerLabel(row.layer) : '—' }}</td>
              <td class="num">{{ formatAmount(row.currentAmountEur) }}</td>
              <td class="num">{{ formatAmount(row.proposedAmountEur) }}</td>
              <td class="num">{{ formatSigned(row.deltaEur) }}</td>
              <td>
                <template v-if="requiresDepot(row)">
                  <label class="sr-only" :for="`approval-depot-${row.key}`">Choose depot for {{ row.isin }}</label>
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
                    Select a depot to apply this new saving plan.
                  </span>
                </template>
                <template v-else>
                  {{ depotLabel(row) }}
                </template>
              </td>
              <td>
                <span>{{ row.rationale || '—' }}</span>
                <span v-if="decisionBadge(row)" :class="['badge', decisionBadge(row).className]">
                  {{ decisionBadge(row).label }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="actions approval-panel__actions">
        <button class="primary" type="button" :disabled="!canSubmit || busy" @click="saveDecisions">
          {{ busy ? 'Saving...' : 'Save decisions' }}
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

const IGNORE = 'IGNORE'
const APPLY = 'APPLY'
const BLACKLIST_SAVING_PLAN_ONLY = 'BLACKLIST_SAVING_PLAN_ONLY'
const BLACKLIST_ALL_PROPOSALS = 'BLACKLIST_ALL_PROPOSALS'

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
const decisions = reactive({})
const depotSelections = reactive({})
const panelId = `approval-panel-${Math.random().toString(36).slice(2)}`
const dismissedKeys = ref(new Set())

const visibleRows = computed(() => props.rows.filter((row) => !dismissedKeys.value.has(row.key)))
const actionableRows = computed(() => visibleRows.value)
const applyRows = computed(() => visibleRows.value.filter((row) => currentDecision(row) === APPLY))
const applyCount = computed(() => applyRows.value.length)
const ignoreCount = computed(() => visibleRows.value.filter((row) => currentDecision(row) === IGNORE).length)
const savingPlanBlacklistCount = computed(() => visibleRows.value.filter((row) => currentDecision(row) === BLACKLIST_SAVING_PLAN_ONLY).length)
const allProposalBlacklistCount = computed(() => visibleRows.value.filter((row) => currentDecision(row) === BLACKLIST_ALL_PROPOSALS).length)
const applyNeedsDepotCount = computed(() => applyRows.value.filter((row) => requiresDepot(row) && !depotSelections[row.key]).length)
const canSubmit = computed(() => visibleRows.value.length > 0 && applyNeedsDepotCount.value === 0)

watch(
  () => props.rows,
  (rows) => {
    dismissedKeys.value = new Set()
    const keys = new Set(rows.map((row) => row.key))
    rows.forEach((row) => {
      if (!decisions[row.key]) {
        decisions[row.key] = defaultDecision(row)
      }
    })
    Object.keys(decisions).forEach((key) => {
      if (!keys.has(key)) {
        delete decisions[key]
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
  { deep: true, immediate: true }
)

async function toggleOpen() {
  open.value = !open.value
  error.value = ''
  message.value = ''
  submitAttempted.value = false
}

function closePanel() {
  open.value = false
  submitAttempted.value = false
  error.value = ''
}

async function ensureDepotsLoaded() {
  if (depots.value.length > 0) {
    return
  }
  depots.value = await apiRequest('/depots')
}

function currentDecision(row) {
  return decisions[row.key] || defaultDecision(row)
}

function defaultDecision(row) {
  return IGNORE
}

async function handleDecisionChange(row) {
  if (requiresDepot(row)) {
    await ensureDepotsLoaded()
  }
}

function requiresDepot(row) {
  return currentDecision(row) === APPLY && row.requiresDepotSelection
}

function depotLabel(row) {
  if (currentDecision(row) !== APPLY) {
    return 'Not needed'
  }
  return row.depotName || row.depotCode || (row.requiresDepotSelection ? 'Depot required' : '—')
}

function decisionOptions(row) {
  const options = [{ value: IGNORE, label: 'Ignore' }]
  if (!row.applyDisabled) {
    options.unshift({ value: APPLY, label: 'Apply' })
  }
  options.push({ value: BLACKLIST_SAVING_PLAN_ONLY, label: 'Saving plan proposals only' })
  options.push({ value: BLACKLIST_ALL_PROPOSALS, label: 'All buy proposals' })
  return options
}

function decisionBadge(row) {
  const decision = currentDecision(row)
  if (decision === BLACKLIST_SAVING_PLAN_ONLY) {
    return { label: 'Saving plan proposals only', className: 'caution' }
  }
  if (decision === BLACKLIST_ALL_PROPOSALS) {
    return { label: 'All buy proposals', className: 'warn' }
  }
  if (row.applyDisabled && decision === IGNORE) {
    return { label: 'Apply unavailable', className: 'warn' }
  }
  return null
}

async function saveDecisions() {
  submitAttempted.value = true
  message.value = ''
  error.value = ''
  if (!canSubmit.value) {
    error.value = 'Select a depot for each apply decision that creates a new saving plan.'
    return
  }
  busy.value = true
  try {
    const items = actionableRows.value.map((row) => ({
      savingPlanId: row.savingPlanId ?? null,
      depotId: requiresDepot(row) ? Number(depotSelections[row.key]) : (row.depotId ?? null),
      isin: row.isin,
      decision: currentDecision(row),
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
    const appliedKeys = actionableRows.value.map((row) => row.key)
    const nextDismissed = new Set(dismissedKeys.value)
    appliedKeys.forEach((key) => nextDismissed.add(key))
    dismissedKeys.value = nextDismissed
    message.value = buildSuccessMessage(response)
    appliedKeys.forEach((key) => {
      delete decisions[key]
      delete depotSelections[key]
    })
    emit('applied', { response, appliedKeys })
  } catch (err) {
    error.value = formatErrorMessage(err?.message)
  } finally {
    busy.value = false
  }
}

function formatErrorMessage(message) {
  const fallback = 'Saving decisions failed.'
  if (!message) {
    return fallback
  }
  if (message.includes('multiple active saving plans exist across depots')) {
    const isinMatch = message.match(/ISIN\s+([A-Z0-9]+)/)
    const isinSuffix = isinMatch ? ` for ${isinMatch[1]}` : ''
    return `Can't apply this proposal${isinSuffix} because it already has active saving plans in more than one depot. Choose the correct depot or resolve the duplicate saving plans first.`
  }
  return message
}

function buildSuccessMessage(response) {
  const applied = Number(response?.applied ?? 0)
  const ignored = Number(response?.ignored ?? 0)
  const savingPlanOnly = Number(response?.blacklistedSavingPlanOnly ?? 0)
  const allProposals = Number(response?.blacklistedAllProposals ?? 0)
  return `Saved decisions: ${applied} applied, ${ignored} ignored, ${savingPlanOnly} saving-plan blacklist, ${allProposals} all-buy blacklist. No real depot transaction was executed.`
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

@media (max-width: 900px) {
  .approval-panel__select {
    min-width: 10rem;
  }
}
</style>
