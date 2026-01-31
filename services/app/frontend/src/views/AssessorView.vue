<template>
  <div>
    <div class="card">
      <h2>Assessor</h2>
      <div
        :class="[
          'assessor-form',
          assessmentType === 'one_time'
            ? 'assessor-form--onetime'
            : assessmentType === 'instrument_one_time'
              ? 'assessor-form--instrument'
              : 'assessor-form--saving'
        ]"
      >
        <label class="field assessor-field assessor-field--type">
          Assessment type
          <select class="input" v-model="assessmentType">
            <option value="saving_plan">Saving Plan</option>
            <option value="one_time">One-Time Invest</option>
            <option value="instrument_one_time">Instrument One-Time Invest</option>
          </select>
        </label>
        <label v-if="assessmentType === 'instrument_one_time'" class="field assessor-field assessor-field--instruments">
          Instruments (ISINs)
          <input
            class="input"
            type="text"
            v-model="instrumentIsinsInput"
            placeholder="DE0000000001, LU1234567890"
          />
          <p class="hint">Comma-separated list of ISINs to assess.</p>
        </label>
        <label v-if="assessmentType === 'saving_plan'" class="field assessor-field assessor-field--gap-policy">
          Gap Detection Policy
          <select class="input" v-model="gapDetectionPolicy">
            <option value="saving_plan_gaps">Saving Plan Gaps (default)</option>
            <option value="portfolio_gaps">Portfolio Gaps</option>
          </select>
          <p class="hint">
            Saving Plan Gaps checks existing saving plans. Portfolio Gaps checks effective instruments and ignores
            gaps already covered by plans.
          </p>
        </label>
        <label class="field assessor-field assessor-field--amount">
          {{ amountLabel }}
          <input
            class="input"
            type="number"
            :min="amountDeltaMin"
            step="1"
            v-model="amountDelta"
            :placeholder="amountDeltaPlaceholder"
          />
          <p v-if="assessmentType === 'saving_plan'" class="hint">
            Optional; adds to the current monthly budget for saving plan targets. Minimum:
            {{ savingPlanMinimumDelta }} EUR.
          </p>
          <p v-else-if="assessmentType === 'one_time'" class="hint">
            Required for one-time allocations; must be at least the minimum per instrument.
          </p>
          <p v-else class="hint">
            Required for instrument assessment allocations.
          </p>
          <p v-if="savingPlanDeltaWarning" class="hint warn">{{ savingPlanDeltaWarning }}</p>
          <p v-if="oneTimeDeltaWarning" class="hint warn">{{ oneTimeDeltaWarning }}</p>
          <p v-if="instrumentAmountWarning" class="hint warn">{{ instrumentAmountWarning }}</p>
        </label>
        <label v-if="assessmentType === 'one_time'" class="field assessor-field assessor-field--minimum">
          Minimum amount per instrument (EUR)
          <input
            class="input"
            type="number"
            min="1"
            step="1"
            v-model="minimumInstrumentAmount"
          />
        </label>
        <div class="actions assessor-actions">
          <button class="primary" :disabled="loading || !canRun" @click="runAssessor">
            {{ loading ? 'Running…' : 'Run Assessment' }}
          </button>
          <button class="secondary" :disabled="loading" @click="resetForm">Reset</button>
        </div>
      </div>
      <p v-if="stale" class="note warn">Inputs changed since the last run. Refresh to update results.</p>
      <p v-if="loading" class="note">Running assessment...</p>
      <div v-if="error" class="toast error">{{ error }}</div>
    </div>

    <div v-if="assessment && !loading" class="card">
      <h3>No financal advice</h3>
      <p>These suggestions do not constitute financial advice!</p>
      <p>Examine the suggestions critically and use them with due caution!</p>
    </div>

    <div v-if="assessment" :class="['assessment-results', { 'is-loading': loading }]">
      <div v-if="assessmentType === 'saving_plan'" class="card">
        <h3>Monthly Distribution</h3>
        <p class="note">
          Total monthly budget<span v-if="savingPlanDeltaApplied"> (incl. delta)</span>:
          <strong>{{ formatAmount(assessment.current_monthly_total) }}</strong> EUR
          <span v-if="assessment.as_of_date">as of {{ assessment.as_of_date }}</span>
        </p>
        <div class="table-wrap">
          <table class="table">
            <caption class="sr-only">Current versus target monthly allocation by layer.</caption>
            <thead>
              <tr>
                <th scope="col">Layer</th>
                <th scope="col" class="num">Current €</th>
                <th scope="col" class="num">Target €</th>
                <th scope="col" class="num">Delta €</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in layerRows" :key="row.layer">
                <th scope="row">{{ layerLabel(row.layer) }}</th>
                <td class="num">{{ formatAmount(row.current) }}</td>
                <td class="num">{{ formatAmount(row.target) }}</td>
                <td class="num">{{ formatSigned(row.delta) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div v-if="assessmentType === 'saving_plan'" class="card">
        <h3>Saving Plan Suggestions</h3>
        <div
          v-if="formattedSavingPlanNarrative"
          class="note narrative"
          role="status"
          aria-live="polite"
          v-html="formattedSavingPlanNarrative"
        ></div>
        <p v-if="savingPlanSuggestionRows.length === 0" class="note">
          No saving plan changes proposed.
        </p>
        <div v-else class="table-wrap">
          <p v-if="kbMissingIsins.length" class="kb-tooltip" :title="kbMissingIsinTooltip">
            KB missing ISINs
          </p>
          <table class="table">
            <caption class="sr-only">Suggested saving plan actions.</caption>
            <thead>
              <tr>
                <th scope="col">Action</th>
                <th scope="col">ISIN</th>
                <th scope="col">Name</th>
                <th scope="col">Layer</th>
                <th scope="col">Depot</th>
                <th scope="col" class="num">Old €</th>
                <th scope="col" class="num">New €</th>
                <th scope="col" class="num">Delta €</th>
                <th scope="col">Rationale</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in savingPlanSuggestionRows" :key="row.key">
                <th scope="row">
                  <span :class="['badge', row.actionClass]">{{ row.action }}</span>
                </th>
                <td>{{ row.isin }}</td>
                <td>{{ row.instrumentName ?? '—' }}</td>
                <td>{{ row.layer ? layerLabel(row.layer) : '—' }}</td>
                <td>{{ row.depot }}</td>
                <td class="num">{{ formatAmount(row.oldAmount) }}</td>
                <td class="num">{{ formatAmount(row.newAmount) }}</td>
                <td class="num">{{ formatSigned(row.delta) }}</td>
                <td>{{ row.rationale }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-if="kbSuggestionHint" class="note warn">
          {{ kbSuggestionHint }}
        </p>
      </div>

      <div v-if="assessmentType === 'one_time' && assessment.one_time_allocation" class="card">
        <h3>One-time Allocation</h3>
        <div
          v-if="formattedOneTimeNarrative"
          class="note narrative"
          role="status"
          aria-live="polite"
          v-html="formattedOneTimeNarrative"
        ></div>
        <div v-if="layerAllocationRows.length" class="table-wrap">
          <table class="table">
            <caption class="sr-only">One-time allocation by layer.</caption>
            <thead>
              <tr>
                <th scope="col">Layer</th>
                <th scope="col" class="num">Amount €</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in layerAllocationRows" :key="row.layer">
                <th scope="row">{{ layerLabel(row.layer) }}</th>
                <td class="num">{{ formatAmount(row.amount) }}</td>
              </tr>
            </tbody>
            <tfoot>
              <tr>
                <th scope="row">Total allocated</th>
                <td class="num">{{ formatAmount(layerAllocationTotal) }}</td>
              </tr>
            </tfoot>
          </table>
        </div>
        <p v-else class="note">No one-time allocation buckets to display.</p>

        <div v-if="oneTimeSuggestionRows.length" class="section table-wrap">
          <p v-if="kbMissingIsins.length" class="kb-tooltip" :title="kbMissingIsinTooltip">
            KB missing ISINs
          </p>
          <p class="note">
            <span class="badge neutral">Increase</span> suggests increasing existing positions.
            <span class="badge ok">New</span> suggests adding a new instrument to the portfolio.
          </p>
          <table class="table">
            <caption class="sr-only">One-time instrument suggestions.</caption>
            <thead>
              <tr>
                <th scope="col" :aria-sort="ariaSort(oneTimeSuggestionSort, 'action')">
                  <button
                    type="button"
                    class="sort-button"
                    :aria-label="sortButtonLabel('Action', oneTimeSuggestionSort, 'action')"
                    @click="toggleSort(oneTimeSuggestionSort, 'action')"
                  >
                    Action <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(oneTimeSuggestionSort, 'action') }}</span>
                  </button>
                </th>
                <th scope="col" :aria-sort="ariaSort(oneTimeSuggestionSort, 'isin')">
                  <button
                    type="button"
                    class="sort-button"
                    :aria-label="sortButtonLabel('ISIN', oneTimeSuggestionSort, 'isin')"
                    @click="toggleSort(oneTimeSuggestionSort, 'isin')"
                  >
                    ISIN <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(oneTimeSuggestionSort, 'isin') }}</span>
                  </button>
                </th>
                <th scope="col" :aria-sort="ariaSort(oneTimeSuggestionSort, 'name')">
                  <button
                    type="button"
                    class="sort-button"
                    :aria-label="sortButtonLabel('Name', oneTimeSuggestionSort, 'name')"
                    @click="toggleSort(oneTimeSuggestionSort, 'name')"
                  >
                    Name <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(oneTimeSuggestionSort, 'name') }}</span>
                  </button>
                </th>
                <th scope="col" :aria-sort="ariaSort(oneTimeSuggestionSort, 'layer')">
                  <button
                    type="button"
                    class="sort-button"
                    :aria-label="sortButtonLabel('Layer', oneTimeSuggestionSort, 'layer')"
                    @click="toggleSort(oneTimeSuggestionSort, 'layer')"
                  >
                    Layer <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(oneTimeSuggestionSort, 'layer') }}</span>
                  </button>
                </th>
                <th scope="col" class="num" :aria-sort="ariaSort(oneTimeSuggestionSort, 'amount')">
                  <button
                    type="button"
                    class="sort-button"
                    :aria-label="sortButtonLabel('Amount', oneTimeSuggestionSort, 'amount')"
                    @click="toggleSort(oneTimeSuggestionSort, 'amount')"
                  >
                    Amount € <span class="sort-indicator" aria-hidden="true">{{ sortIndicator(oneTimeSuggestionSort, 'amount') }}</span>
                  </button>
                </th>
                <th scope="col">Rationale</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in oneTimeSuggestionRows" :key="row.key">
                <td><span :class="['badge', row.actionClass]">{{ row.action }}</span></td>
                <th scope="row">{{ row.isin }}</th>
                <td>{{ row.instrumentName ?? '—' }}</td>
                <td>{{ row.layer ? layerLabel(row.layer) : '—' }}</td>
                <td class="num">{{ formatAmount(row.amount) }}</td>
                <td>{{ row.rationale ?? '' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="note">
          Instrument suggestions appear only when the Knowledge Base is enabled and complete for all held instruments.
          Otherwise only layer allocations are shown, and the minimum per-instrument amount must be met.
        </p>
        <p v-if="kbSuggestionHint" class="note warn">
          {{ kbSuggestionHint }}
        </p>
      </div>

      <div v-if="assessmentType === 'instrument_one_time' && instrumentAssessment" class="card">
        <h3>Instrument Assessment</h3>
        <div
          v-if="formattedInstrumentNarrative"
          class="note narrative"
          role="status"
          aria-live="polite"
          v-html="formattedInstrumentNarrative"
        ></div>
        <p v-if="instrumentMissingIsins.length" class="note warn">
          Assessment cannot run. Missing KB extractions for: {{ instrumentMissingIsins.join(', ') }}.
        </p>
        <div v-else-if="instrumentAssessmentItems.length" class="table-wrap">
          <table class="table">
            <caption class="sr-only">Instrument assessment scores and allocation.</caption>
            <thead>
              <tr>
                <th scope="col">ISIN</th>
                <th scope="col">Name</th>
                <th scope="col">Layer</th>
                <th scope="col" class="num">
                  <span class="table-header">Score</span>
                  <span v-if="instrumentRiskThresholdLabel" class="table-header-note">
                    Risk bands: {{ instrumentRiskThresholdLabel }}
                  </span>
                </th>
                <th scope="col">Score breakdown</th>
                <th scope="col" class="num">Allocation €</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in instrumentAssessmentItems" :key="row.isin">
                <th scope="row">{{ row.isin }}</th>
                <td>{{ row.instrument_name ?? '—' }}</td>
                <td>{{ row.layer ? layerLabel(row.layer) : '—' }}</td>
                <td class="num">
                  <span :class="['badge', scoreBadgeClass(row)]">{{ row.score }}</span>
                </td>
                <td>
                  <div
                    v-if="scoreComponentEntries(row.score_components ?? row.scoreComponents).length"
                    class="score-breakdown"
                  >
                    <span
                      v-for="entry in scoreComponentEntries(row.score_components ?? row.scoreComponents)"
                      :key="entry.text"
                      :class="['score-breakdown__item', entry.levelClass]"
                    >
                      {{ entry.text }}
                    </span>
                  </div>
                  <span v-else>—</span>
                </td>
                <td class="num">{{ formatAmount(row.allocation ?? 0) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="note">No instrument assessment results to display.</p>
      </div>

      <div v-if="assessmentType === 'saving_plan'" class="card">
        <h3>Saving plan diagnostics</h3>
        <div class="grid grid-2">
          <dl>
            <dt>Within tolerance</dt>
            <dd>
              <span :class="['badge', diagnostics.within_tolerance ? 'ok' : 'warn']">
                {{ diagnostics.within_tolerance ? 'Yes' : 'No' }}
              </span>
            </dd>
            <dt>Suppressed deltas</dt>
            <dd>{{ diagnostics.suppressed_deltas_count ?? 0 }}</dd>
            <dt>Suppressed amount</dt>
            <dd>{{ formatAmount(diagnostics.suppressed_amount_total ?? 0) }} EUR</dd>
          </dl>
          <div>
            <h4>Redistribution notes</h4>
            <ul v-if="redistributionNotes.length">
              <li v-for="note in redistributionNotes" :key="note">{{ note }}</li>
            </ul>
            <p v-else class="note">No redistribution needed.</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { apiRequest } from '../api'

const layerNames = ref({})
const assessmentType = ref('saving_plan')
const gapDetectionPolicy = ref('saving_plan_gaps')
const amountDelta = ref('')
const instrumentIsinsInput = ref('')
const minimumInstrumentAmount = ref('25')
const minimumSavingPlanSize = ref(15)
const assessment = ref(null)
const loading = ref(false)
const error = ref('')
const stale = ref(false)
const jobState = ref(null)
let pollTimer = null

const suggestions = computed(() => assessment.value?.saving_plan_suggestions ?? [])
const savingPlanNewInstruments = computed(() => assessment.value?.saving_plan_new_instruments ?? [])
const diagnostics = computed(() => assessment.value?.diagnostics ?? {})
const redistributionNotes = computed(() => diagnostics.value.redistribution_notes ?? [])
const kbMissingIsins = computed(() => diagnostics.value.missing_kb_isins ?? [])
const kbSuggestionHint = computed(() => {
  if (!assessment.value) {
    return ''
  }
  const kbEnabled = diagnostics.value.kb_enabled
  const kbComplete = diagnostics.value.kb_complete
  if (kbEnabled === false) {
    return 'Knowledge Base is disabled; instrument suggestions are unavailable.'
  }
  if (kbComplete === false) {
    if (kbMissingIsins.value.length > 0) {
      return `Knowledge Base is missing coverage for: ${kbMissingIsins.value.join(', ')}.`
    }
    return 'Knowledge Base coverage is incomplete for held instruments.'
  }
  return ''
})
const kbMissingIsinTooltip = computed(() => {
  if (kbMissingIsins.value.length === 0) {
    return ''
  }
  return `Missing KB ISINs: ${kbMissingIsins.value.join(', ')}`
})
const formattedSavingPlanNarrative = computed(() => formatNarrative(assessment.value?.saving_plan_narrative))
const formattedOneTimeNarrative = computed(() => formatNarrative(assessment.value?.one_time_narrative))
const instrumentAssessment = computed(() => assessment.value?.instrument_assessment ?? null)
const instrumentAssessmentItems = computed(() => instrumentAssessment.value?.items ?? [])
const instrumentMissingIsins = computed(() => instrumentAssessment.value?.missing_kb_isins ?? [])
const instrumentScoreCutoff = computed(() => instrumentAssessment.value?.score_cutoff ?? 85)
const formattedInstrumentNarrative = computed(() => formatNarrative(instrumentAssessment.value?.narrative))
const instrumentRiskThresholds = computed(() =>
  instrumentAssessment.value?.risk_thresholds ?? instrumentAssessment.value?.riskThresholds ?? null
)
const instrumentRiskThresholdLabel = computed(() => formatRiskThresholdLabel(instrumentRiskThresholds.value))
const oneTimeNewInstruments = computed(() => assessment.value?.one_time_allocation?.new_instruments ?? [])
const oneTimeSuggestionSort = ref({ key: 'action', direction: 'asc' })
const savingPlanMinimumDelta = computed(() => {
  const minimum = Number(minimumSavingPlanSize.value)
  if (Number.isNaN(minimum) || minimum < 1) {
    return 1
  }
  return Math.floor(minimum)
})

const amountLabel = computed(() =>
  assessmentType.value === 'instrument_one_time' ? 'Amount (EUR)' : 'Amount delta (EUR)'
)

const amountDeltaMin = computed(() => {
  if (assessmentType.value === 'one_time') {
    const minimum = Number(minimumInstrumentAmount.value)
    if (Number.isNaN(minimum) || minimum < 1) {
      return 1
    }
    return Math.floor(minimum)
  }
  if (assessmentType.value === 'instrument_one_time') {
    return 1
  }
  return savingPlanMinimumDelta.value
})

const amountDeltaPlaceholder = computed(() =>
  assessmentType.value === 'saving_plan' ? 'Optional' : 'Required'
)

const savingPlanDeltaApplied = computed(() => {
  if (assessmentType.value !== 'saving_plan') {
    return false
  }
  const amount = Number(amountDelta.value)
  return !Number.isNaN(amount) && amount >= savingPlanMinimumDelta.value
})

const savingPlanDeltaWarning = computed(() => {
  if (assessmentType.value !== 'saving_plan') {
    return ''
  }
  if (amountDelta.value === '') {
    return ''
  }
  const amount = Number(amountDelta.value)
  if (Number.isNaN(amount)) {
    return 'Enter a valid amount delta.'
  }
  if (amount < savingPlanMinimumDelta.value) {
    return `Amount delta must be at least ${savingPlanMinimumDelta.value} EUR (minimum saving plan size).`
  }
  return ''
})

const oneTimeDeltaWarning = computed(() => {
  if (assessmentType.value !== 'one_time') {
    return ''
  }
  if (amountDelta.value === '') {
    return ''
  }
  const amount = Number(amountDelta.value)
  if (Number.isNaN(amount)) {
    return 'Enter a valid amount delta.'
  }
  if (amount < amountDeltaMin.value) {
    return `Amount delta must be at least ${amountDeltaMin.value} EUR to meet the minimum per instrument.`
  }
  return ''
})

const instrumentAmountWarning = computed(() => {
  if (assessmentType.value !== 'instrument_one_time') {
    return ''
  }
  if (amountDelta.value === '') {
    return ''
  }
  const amount = Number(amountDelta.value)
  if (Number.isNaN(amount)) {
    return 'Enter a valid amount.'
  }
  if (amount < 1) {
    return 'Amount must be at least 1 EUR.'
  }
  return ''
})

const canRun = computed(() => {
  const amount = Number(amountDelta.value)
  if (assessmentType.value === 'one_time') {
    const minimum = Number(minimumInstrumentAmount.value)
    return !Number.isNaN(amount) && amount >= amountDeltaMin.value && !Number.isNaN(minimum) && minimum >= 1
  }
  if (assessmentType.value === 'instrument_one_time') {
    const isins = parseInstrumentIsins()
    return !Number.isNaN(amount) && amount >= 1 && isins.length > 0
  }
  if (amountDelta.value === '') {
    return true
  }
  return !Number.isNaN(amount) && amount >= savingPlanMinimumDelta.value
})

const layerRows = computed(() => {
  if (!assessment.value) return []
  const current = assessment.value.current_layer_distribution ?? {}
  const target = assessment.value.target_layer_distribution ?? {}
  return [1, 2, 3, 4, 5].map((layer) => ({
    layer,
    current: Number(current[layer] ?? 0),
    target: Number(target[layer] ?? 0),
    delta: Number(target[layer] ?? 0) - Number(current[layer] ?? 0)
  }))
})

const savingPlanSuggestionRows = computed(() => {
  const rows = suggestions.value.map((item) => ({
    key: `${item.isin}-${item.depot_id ?? 'na'}-${item.type ?? 'adjust'}`,
    action: formatAction(item.type),
    actionClass: 'neutral',
    isin: item.isin,
    instrumentName: item.instrument_name,
    layer: item.layer,
    depot: item.depot_name ?? item.depot_id ?? '—',
    oldAmount: Number(item.old_amount ?? 0),
    newAmount: Number(item.new_amount ?? 0),
    delta: Number(item.delta ?? 0),
    rationale: item.rationale ?? ''
  }))
  savingPlanNewInstruments.value.forEach((item) => {
    const amount = Number(item.amount ?? 0)
    rows.push({
      key: `new-${item.isin}`,
      action: 'New',
      actionClass: 'ok',
      isin: item.isin,
      instrumentName: item.instrument_name,
      layer: item.layer,
      depot: '—',
      oldAmount: 0,
      newAmount: amount,
      delta: amount,
      rationale: item.rationale ?? ''
    })
  })
  return rows
})

const layerAllocationRows = computed(() => {
  const buckets = assessment.value?.one_time_allocation?.layer_buckets ?? {}
  return Object.entries(buckets)
    .map(([layer, amount]) => ({
      layer: Number(layer),
      amount: Number(amount)
    }))
    .sort((a, b) => a.layer - b.layer)
})

const layerAllocationTotal = computed(() =>
  layerAllocationRows.value.reduce((sum, row) => sum + Number(row.amount ?? 0), 0)
)

const instrumentAllocationRows = computed(() => {
  const detailed = assessment.value?.one_time_allocation?.instrument_buckets_detailed ?? []
  if (detailed.length > 0) {
    return detailed
      .map((item) => ({
        isin: item.isin,
        amount: Number(item.amount),
        instrumentName: item.instrument_name,
        layer: item.layer
      }))
      .sort((a, b) => a.isin.localeCompare(b.isin))
  }
  const buckets = assessment.value?.one_time_allocation?.instrument_buckets ?? {}
  return Object.entries(buckets)
    .map(([isin, amount]) => ({
      isin,
      amount: Number(amount),
      instrumentName: null,
      layer: null
    }))
    .sort((a, b) => a.isin.localeCompare(b.isin))
})

const oneTimeSuggestionSorters = {
  action: (row) => row.actionKey,
  isin: (row) => row.isin,
  name: (row) => row.instrumentName ?? '',
  layer: (row) => row.layer ?? 99,
  amount: (row) => Number(row.amount ?? 0)
}

const oneTimeSuggestionRows = computed(() => {
  const rows = []
  instrumentAllocationRows.value.forEach((row) => {
    rows.push({
      key: `increase-${row.isin}`,
      actionKey: 'increase',
      action: 'Increase',
      actionClass: 'neutral',
      isin: row.isin,
      instrumentName: row.instrumentName,
      layer: row.layer,
      amount: row.amount,
      rationale: null
    })
  })
  oneTimeNewInstruments.value.forEach((item) => {
    const actionKey = (item.action ?? 'new').toLowerCase()
    rows.push({
      key: `new-${item.isin}`,
      actionKey,
      action: formatAction(actionKey),
      actionClass: actionKey === 'increase' ? 'neutral' : 'ok',
      isin: item.isin,
      instrumentName: item.instrument_name,
      layer: item.layer,
      amount: Number(item.amount),
      rationale: item.rationale
    })
  })
  return sortItems(rows, oneTimeSuggestionSort.value, oneTimeSuggestionSorters)
})

onMounted(loadLayerTargets)

onBeforeUnmount(() => {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
})

watch([assessmentType, gapDetectionPolicy, amountDelta, minimumInstrumentAmount, instrumentIsinsInput], () => {
  if (assessment.value) {
    stale.value = true
  }
})

watch(assessmentType, (nextType) => {
  if (amountDelta.value !== '') {
    amountDelta.value = ''
  }
  if (nextType === 'saving_plan') {
    gapDetectionPolicy.value = 'saving_plan_gaps'
  }
  if (nextType !== 'instrument_one_time') {
    instrumentIsinsInput.value = ''
  }
  error.value = ''
})

async function loadLayerTargets() {
  try {
    const data = await apiRequest('/layer-targets')
    layerNames.value = data.layerNames ?? {}
    minimumSavingPlanSize.value = data.minimumSavingPlanSize ?? minimumSavingPlanSize.value
  } catch (err) {
    error.value = err.message
  }
}

function parseInstrumentIsins() {
  if (!instrumentIsinsInput.value) {
    return []
  }
  return instrumentIsinsInput.value
    .split(',')
    .map((item) => item.trim().toUpperCase())
    .filter((item) => item.length > 0)
}

async function runAssessor() {
  loading.value = true
  error.value = ''
  try {
    const payload = {}
    const amount = Number(amountDelta.value)
    const hasAmount = amountDelta.value !== ''
    if (assessmentType.value === 'one_time') {
      if (Number.isNaN(amount) || amount <= 0) {
        error.value = 'Enter a positive amount delta for one-time allocation.'
        loading.value = false
        return
      }
      const minimum = Number(minimumInstrumentAmount.value)
      if (Number.isNaN(minimum) || minimum < 1) {
        error.value = 'Minimum amount per instrument must be at least 1 EUR.'
        loading.value = false
        return
      }
      const minimumRounded = Math.floor(minimum)
      if (amount < minimumRounded) {
        error.value = 'Amount delta must be at least the minimum amount per instrument.'
        loading.value = false
        return
      }
      payload.oneTimeAmountEur = amount
      payload.minimumInstrumentAmountEur = minimumRounded
    } else if (assessmentType.value === 'instrument_one_time') {
      if (Number.isNaN(amount) || amount <= 0) {
        error.value = 'Enter a positive amount for the instrument assessment.'
        loading.value = false
        return
      }
      const isins = parseInstrumentIsins()
      if (isins.length === 0) {
        error.value = 'Enter at least one ISIN for the assessment.'
        loading.value = false
        return
      }
      payload.assessmentType = 'instrument_one_time'
      payload.instruments = isins
      payload.instrumentAmountEur = Math.floor(amount)
    } else if (hasAmount) {
      if (Number.isNaN(amount)) {
        error.value = 'Enter a valid amount delta for saving plan assessments.'
        loading.value = false
        return
      }
      const minimumSavingPlan = savingPlanMinimumDelta.value
      if (amount < minimumSavingPlan) {
        error.value = `Amount delta must be at least ${minimumSavingPlan} EUR (minimum saving plan size).`
        loading.value = false
        return
    }
    payload.savingPlanAmountDeltaEur = amount
    }
    if (assessmentType.value === 'saving_plan') {
      payload.gapDetectionPolicy = gapDetectionPolicy.value
    }
    const response = await apiRequest('/assessor/run', {
      method: 'POST',
      body: JSON.stringify(payload)
    })
    jobState.value = response
    await pollAssessorJob(response?.job_id)
  } catch (err) {
    error.value = err.message
    loading.value = false
  }
}

async function pollAssessorJob(jobId) {
  if (!jobId) {
    loading.value = false
    error.value = 'Assessor job was not started.'
    return
  }
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  try {
    const response = await apiRequest(`/assessor/run/${jobId}`)
    jobState.value = response
    const status = response?.status
    if (status === 'DONE') {
      assessment.value = response?.result ?? null
      stale.value = false
      loading.value = false
      return
    }
    if (status === 'FAILED') {
      loading.value = false
      error.value = response?.error || 'Assessment failed.'
      return
    }
    pollTimer = setTimeout(() => pollAssessorJob(jobId), 1200)
  } catch (err) {
    loading.value = false
    error.value = err.message
  }
}

function resetForm() {
  assessmentType.value = 'saving_plan'
  gapDetectionPolicy.value = 'saving_plan_gaps'
  amountDelta.value = ''
  instrumentIsinsInput.value = ''
  minimumInstrumentAmount.value = '25'
  assessment.value = null
  error.value = ''
  stale.value = false
  jobState.value = null
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function layerLabel(layer) {
  return layerNames.value?.[layer] ?? `Layer ${layer}`
}

function scoreBadgeClass(row) {
  const category = normalizeRiskCategory(row?.risk_category ?? row?.riskCategory)
  if (category === 'high') {
    return 'warn'
  }
  if (category === 'medium') {
    return 'caution'
  }
  if (category === 'low') {
    return 'ok'
  }
  const score = Number(row?.score)
  if (!Number.isNaN(score) && score >= instrumentScoreCutoff.value) {
    return 'warn'
  }
  return 'ok'
}

function normalizeRiskCategory(value) {
  if (!value) {
    return ''
  }
  return String(value).trim().toLowerCase()
}

function formatRiskThresholdLabel(thresholds) {
  if (!thresholds) {
    return ''
  }
  const lowMax = Number(thresholds.lowMax ?? thresholds.low_max)
  const highMin = Number(thresholds.highMin ?? thresholds.high_min)
  if (!Number.isFinite(lowMax) || !Number.isFinite(highMin)) {
    return ''
  }
  const mediumMin = Math.min(100, lowMax + 1)
  const mediumMax = Math.max(0, highMin - 1)
  if (mediumMax < mediumMin) {
    return `Low <= ${lowMax}, High >= ${highMin}`
  }
  return `Low <= ${lowMax}, Medium ${mediumMin}-${mediumMax}, High >= ${highMin}`
}

function scoreComponentEntries(components) {
  if (!Array.isArray(components) || components.length === 0) {
    return []
  }
  return components
    .map((component) => {
      const label = component?.criterion ?? ''
      if (!label) {
        return null
      }
      const value = Number(component?.points ?? 0)
      const numeric = Number.isNaN(value) ? 0 : value
      const formatted = formatAmount(Math.abs(numeric))
      const sign = numeric > 0 ? '+' : numeric < 0 ? '-' : ''
      return {
        text: `${label} ${sign}${formatted}`.trim(),
        levelClass: scoreComponentLevelClass(numeric)
      }
    })
    .filter((entry) => entry)
}

function scoreComponentLevelClass(value) {
  if (value <= 0) {
    return 'is-neutral'
  }
  if (value <= 5) {
    return 'is-low'
  }
  if (value <= 15) {
    return 'is-medium'
  }
  return 'is-high'
}

function formatAmount(value) {
  const num = Number(value ?? 0)
  if (Number.isNaN(num)) {
    return '0'
  }
  const rounded = Math.round(num)
  if (Math.abs(num - rounded) < 0.0001) {
    return String(rounded)
  }
  return num.toFixed(2)
}

function formatSigned(value) {
  const num = Number(value ?? 0)
  if (Number.isNaN(num)) {
    return '0'
  }
  const formatted = formatAmount(Math.abs(num))
  if (num > 0) {
    return `+${formatted}`
  }
  if (num < 0) {
    return `-${formatted}`
  }
  return formatted
}

function formatAction(action) {
  if (!action) return 'Adjust'
  if (action.toLowerCase() === 'keep') return 'Keep amount'
  return action.charAt(0).toUpperCase() + action.slice(1)
}

function toggleSort(sortState, key, defaultDirection = 'asc') {
  if (!sortState) return
  if (sortState.key === key) {
    sortState.direction = sortState.direction === 'asc' ? 'desc' : 'asc'
    return
  }
  sortState.key = key
  sortState.direction = defaultDirection
}

function ariaSort(sortState, key) {
  if (!sortState || sortState.key !== key) return 'none'
  return sortState.direction === 'asc' ? 'ascending' : 'descending'
}

function sortIndicator(sortState, key) {
  if (!sortState || sortState.key !== key) return ''
  return sortState.direction === 'asc' ? '^' : 'v'
}

function sortButtonLabel(label, sortState, key) {
  if (!sortState || sortState.key !== key) return `Sort by ${label}`
  return `Sort by ${label} (${sortState.direction === 'asc' ? 'ascending' : 'descending'})`
}

function sortItems(items, sortState, accessors) {
  if (!sortState || !items) {
    return items ?? []
  }
  const accessor = accessors?.[sortState.key]
  if (!accessor) {
    return items
  }
  const direction = sortState.direction === 'desc' ? -1 : 1
  return [...items].sort((a, b) => compareValues(accessor(a), accessor(b), direction))
}

function compareValues(a, b, direction) {
  if (a == null && b == null) return 0
  if (a == null) return -1 * direction
  if (b == null) return 1 * direction
  if (typeof a === 'number' && typeof b === 'number') {
    return (a - b) * direction
  }
  return String(a).localeCompare(String(b)) * direction
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;')
}

function formatInline(value) {
  const escaped = escapeHtml(value)
  return escaped.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>').replace(/\n/g, '<br>')
}

function formatNarrative(text) {
  if (!text) {
    return ''
  }
  const normalized = text.replace(/\r\n/g, '\n')
  const bulletPrepared = normalized
    .replace(/:\s*-\s+/g, ':\n- ')
    .replace(/([^\n]) - (?=\*\*)/g, '$1\n- ')
  const lines = bulletPrepared.split('\n')
  let html = ''
  let listOpen = false

  lines.forEach((rawLine) => {
    const line = rawLine.trim()
    if (!line) {
      if (listOpen) {
        html += '</ul>'
        listOpen = false
      }
      return
    }
    if (line.startsWith('- ')) {
      if (!listOpen) {
        html += '<ul>'
        listOpen = true
      }
      html += `<li>${formatInline(line.slice(2))}</li>`
    } else {
      if (listOpen) {
        html += '</ul>'
        listOpen = false
      }
      html += `<p>${formatInline(line)}</p>`
    }
  })

  if (listOpen) {
    html += '</ul>'
  }

  return html
}
</script>

<style scoped>
.sort-button {
  background: none;
  border: none;
  padding: 0;
  font: inherit;
  color: inherit;
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  cursor: pointer;
}

.sort-button:focus-visible {
  outline: 2px solid #14131a;
  outline-offset: 2px;
}

.table-header {
  display: block;
}

.table-header-note {
  display: block;
  font-size: 0.7rem;
  font-weight: 500;
  color: #6b645d;
  margin-top: 0.2rem;
}

.sort-indicator {
  font-size: 0.75rem;
  opacity: 0.7;
}

.kb-tooltip {
  display: inline-block;
  margin-bottom: 0.35rem;
  font-size: 0.75rem;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: #4b4b4b;
  cursor: help;
}

.score-breakdown {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
}

.score-breakdown__item {
  background: #f7f2e8;
  border: 1px solid #e6e3dc;
  border-radius: 999px;
  padding: 0.1rem 0.55rem;
  font-size: 0.75rem;
  font-weight: 600;
  letter-spacing: 0.01em;
  color: #3b3732;
  white-space: nowrap;
}

.score-breakdown__item.is-neutral {
  background: #f1f0eb;
  border-color: #d9d6cc;
  color: #5a544c;
}

.score-breakdown__item.is-low {
  background: #e6f5d7;
  border-color: #b9e3a1;
  color: #2d5c28;
}

.score-breakdown__item.is-medium {
  background: #f8e6c7;
  border-color: #e7c28c;
  color: #6d4a14;
}

.score-breakdown__item.is-high {
  background: #fde6e4;
  border-color: #f2b1a7;
  color: #7a1f1c;
}

@media (max-width: 720px) {
  .score-breakdown {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
