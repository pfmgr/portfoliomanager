<template>
  <div>
    <div class="card">
      <h2>Rebalancer</h2>
      <div class="grid grid-2">
        <label>
          As Of Date
          <input class="input" type="date" v-model="asOf" />
        </label>
        <div class="action-col">
          <div class="actions">
            <button class="secondary" :disabled="loading" @click="load">Refresh</button>
            <button class="primary" :disabled="savingRun || loading" @click="saveRun">
              {{ savingRun ? 'Saving…' : 'Save Run' }}
            </button>
          </div>
          <p v-if="saveStatus" class="note save-status">{{ saveStatus }}</p>
        </div>
      </div>
      <div v-if="constraintChecks.length" class="constraint-block">
        <h4>Constraint checks</h4>
        <ul class="note">
          <li v-for="constraint in constraintChecks" :key="constraint.name">
            <strong>{{ constraint.name }}:</strong> {{ constraint.details }}
            <span :class="['status', constraint.ok ? 'ok' : 'warn']">
              {{ constraint.ok ? 'OK' : 'Violation' }}
            </span>
          </li>
        </ul>
      </div>
      <div v-if="toast" :class="['toast', toastType]">{{ toast }}</div>
      <p v-if="loading" class="note">Running rebalancer...</p>
    </div>

    <div class="card">
      <h3>No financial advice</h3>
      <p>The rebalancer output does not constitute financial advice.</p>
      <p>Review the suggestions critically and use them with due caution.</p>
    </div>

    <div class="card">
      <h3>Layer Allocations</h3>
      <div class="table-wrap">
        <table class="table">
          <caption class="sr-only">Layer allocation summary.</caption>
          <thead>
              <tr>
                <th scope="col">Layer</th>
                <th scope="col" class="num">Value €</th>
                <th scope="col" class="num">Weight %</th>
              </tr>
            </thead>
            <tbody>
              <template v-if="loading">
                <tr class="sr-only">
                  <td colspan="3">Loading layer allocations...</td>
                </tr>
                <tr v-for="n in 3" :key="`layer-skeleton-${n}`" class="skeleton-row" aria-hidden="true">
                  <td><span class="skeleton-block"></span></td>
                  <td class="num"><span class="skeleton-block"></span></td>
                  <td class="num"><span class="skeleton-block"></span></td>
                </tr>
              </template>
              <template v-else-if="summary.layerAllocations.length === 0">
                <tr>
                  <td colspan="3">No layer allocations available.</td>
                </tr>
              </template>
              <template v-else>
                <tr v-for="item in summary.layerAllocations" :key="item.label">
                  <th scope="row">{{ layerLabelFromAllocation(item) }}</th>
                  <td class="num">{{ item.valueEur.toFixed(2) }}</td>
                  <td class="num">{{ item.weightPct.toFixed(2) }}</td>
                </tr>
              </template>
            </tbody>
        </table>
      </div>
    </div>

    <div class="card">
      <h3>Asset Class Allocations</h3>
      <div class="table-wrap">
        <table class="table">
          <caption class="sr-only">Asset class allocation summary.</caption>
          <thead>
              <tr>
                <th scope="col">Asset Class</th>
                <th scope="col" class="num">Value €</th>
                <th scope="col" class="num">Weight %</th>
              </tr>
            </thead>
          <tbody>
            <template v-if="loading">
              <tr class="sr-only">
                <td colspan="3">Loading asset class allocations...</td>
              </tr>
              <tr v-for="n in 3" :key="`asset-skeleton-${n}`" class="skeleton-row" aria-hidden="true">
                <td><span class="skeleton-block"></span></td>
                <td class="num"><span class="skeleton-block"></span></td>
                <td class="num"><span class="skeleton-block"></span></td>
              </tr>
            </template>
            <template v-else-if="summary.assetClassAllocations.length === 0">
              <tr>
                <td colspan="3">No asset class allocations available.</td>
              </tr>
            </template>
            <template v-else>
              <tr v-for="item in summary.assetClassAllocations" :key="item.label">
                <th scope="row">{{ item.label || 'Unclassified' }}</th>
                <td class="num">{{ item.valueEur.toFixed(2) }}</td>
                <td class="num">{{ item.weightPct.toFixed(2) }}</td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
    </div>

    <div class="card">
      <h3>Top Positions</h3>
      <div class="table-wrap">
        <table class="table">
          <caption class="sr-only">Top positions by value.</caption>
          <thead>
              <tr>
                <th scope="col">ISIN</th>
                <th scope="col">Name</th>
                <th scope="col" class="num">Value €</th>
                <th scope="col" class="num">Weight %</th>
              </tr>
            </thead>
          <tbody>
            <template v-if="loading">
              <tr class="sr-only">
                <td colspan="4">Loading top positions...</td>
              </tr>
              <tr v-for="n in 3" :key="`top-skeleton-${n}`" class="skeleton-row" aria-hidden="true">
                <td><span class="skeleton-block"></span></td>
                <td><span class="skeleton-block"></span></td>
                <td class="num"><span class="skeleton-block"></span></td>
                <td class="num"><span class="skeleton-block"></span></td>
              </tr>
            </template>
            <template v-else-if="summary.topPositions.length === 0">
              <tr>
                <td colspan="4">No top positions available.</td>
              </tr>
            </template>
            <template v-else>
              <tr v-for="item in summary.topPositions" :key="item.isin">
                <th scope="row">{{ item.isin }}</th>
                <td>{{ item.name }}</td>
                <td class="num">{{ item.valueEur.toFixed(2) }}</td>
                <td class="num">{{ item.weightPct.toFixed(2) }}</td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
    </div>

    <div class="card" v-if="summary.savingPlanSummary">
      <h3>Savings plan Rebalancing</h3>
      <p v-if="profileName">
        Active profile:
        <strong>{{ profileName }}</strong>
        <span v-if="profileKey && profileKey !== profileName">({{ profileKey }})</span>
      </p>
      <p v-if="profileRecommendation" class="note">{{ profileRecommendation }}</p>
      <dl>
        <dt>Total active</dt>
        <dd>
          <b>{{ formatAmount(summary.savingPlanSummary.totalActiveAmountEur) }}</b> EUR
          ({{ summary.savingPlanSummary.activeCount }} plans)
        </dd>
        <dt>Monthly total</dt>
        <dd>
          <b>{{ formatAmount(summary.savingPlanSummary.monthlyTotalAmountEur) }}</b> EUR
          ({{ summary.savingPlanSummary.monthlyCount }} plans)
        </dd>
      </dl>

      <h4>Monthly by Layer</h4>
      <div class="table-wrap">
        <table class="table">
          <caption class="sr-only">Monthly savings plan totals by layer.</caption>
          <thead>
              <tr>
                <th scope="col">Layer</th>
                <th scope="col" class="num">Amount €</th>
                <th scope="col" class="num">Weight %</th>
                <th scope="col" class="num">Plans</th>
              </tr>
            </thead>
          <tbody>
            <template v-if="loading">
              <tr class="sr-only">
                <td colspan="4">Loading saving plan monthly totals...</td>
              </tr>
              <tr v-for="n in 3" :key="`monthly-skeleton-${n}`" class="skeleton-row" aria-hidden="true">
                <td><span class="skeleton-block"></span></td>
                <td class="num"><span class="skeleton-block"></span></td>
                <td class="num"><span class="skeleton-block"></span></td>
                <td class="num"><span class="skeleton-block"></span></td>
              </tr>
            </template>
            <template v-else-if="summary.savingPlanSummary.monthlyByLayer.length === 0">
              <tr>
                <td colspan="4">No monthly layer data available.</td>
              </tr>
            </template>
            <template v-else>
              <tr v-for="row in summary.savingPlanSummary.monthlyByLayer" :key="row.layer">
                <th scope="row">{{ layerLabel(row.layer) }}</th>
                <td class="num">{{ formatAmount(row.amountEur) }}</td>
                <td class="num">{{ formatWeight(row.weightPct) }}</td>
                <td class="num">{{ row.count }}</td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>

      <h4>Target Weights</h4>
      <div class="table-wrap">
        <table class="table">
          <caption class="sr-only">Target weights by layer.</caption>
          <thead>
              <tr>
                <th scope="col">Layer</th>
                <th scope="col" class="num">Target Weight %</th>
              </tr>
            </thead>
          <tbody>
            <template v-if="loading">
              <tr class="sr-only">
                <td colspan="2">Loading target weights...</td>
              </tr>
              <tr v-for="n in 3" :key="`target-skeleton-${n}`" class="skeleton-row" aria-hidden="true">
                <td><span class="skeleton-block"></span></td>
                <td class="num"><span class="skeleton-block"></span></td>
              </tr>
            </template>
            <template v-else-if="summary.savingPlanTargets.length === 0">
              <tr>
                <td colspan="2">No target weights available.</td>
              </tr>
            </template>
            <template v-else>
              <tr v-for="row in summary.savingPlanTargets" :key="row.layer">
                <th scope="row">{{ layerLabel(row.layer) }}</th>
                <td class="num">{{ formatWeight(row.targetWeightPct) }}</td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>

      <div v-if="summary.savingPlanProposal">
        <h4>Rebalancing Proposal (Savings plan amounts, EUR)</h4>
        <p v-if="summary.savingPlanProposal.source">
          Proposal source: <b>{{ formatSource(summary.savingPlanProposal.source) }}</b>
        </p>
        <div v-if="formattedNarrative" class="note narrative" v-html="formattedNarrative"></div>
        <ul v-if="summary.savingPlanProposal.notes && summary.savingPlanProposal.notes.length">
          <li v-for="note in summary.savingPlanProposal.notes" :key="note" class="note">
            {{ note }}
          </li>
        </ul>
        <p class="note">Target totals combine current holdings with projected saving plan contributions over the horizon.</p>
        <p class="note">Target Total %% reflects the projected total distribution (holdings + projected contributions), not the target weights.</p>
        <p class="note">Market price changes are not included in target totals.</p>
        <p class="note">Longer projection horizons keep proposals closer to the current distribution.</p>
        <p class="note">Saving Plan Delta shows proposed minus current amounts.</p>
        <div class="table-wrap">
          <table class="table">
            <caption class="sr-only">Savings plan rebalancing proposal by layer.</caption>
            <thead>
              <tr>
                <th scope="col">Layer</th>
                <th scope="col" class="num">Current €</th>
                <th scope="col" class="num">Proposed €</th>
                <th scope="col" class="num">Saving Plan Delta €</th>
                <th scope="col" class="num">Target Total %%</th>
                <th scope="col" class="num">Target Total Amount €</th>
              </tr>
            </thead>
            <tbody>
              <template v-if="loading">
                <tr class="sr-only">
                  <td colspan="6">Loading rebalancing proposal...</td>
                </tr>
                <tr v-for="n in 3" :key="`proposal-skeleton-${n}`" class="skeleton-row" aria-hidden="true">
                  <td><span class="skeleton-block"></span></td>
                  <td class="num"><span class="skeleton-block"></span></td>
                  <td class="num"><span class="skeleton-block"></span></td>
                  <td class="num"><span class="skeleton-block"></span></td>
                  <td class="num"><span class="skeleton-block"></span></td>
                  <td class="num"><span class="skeleton-block"></span></td>
                </tr>
              </template>
              <template v-else-if="summary.savingPlanProposal.layers.length === 0">
                <tr>
                  <td colspan="6">No rebalancing proposal available.</td>
                </tr>
              </template>
              <template v-else>
                <tr v-for="row in summary.savingPlanProposal.layers" :key="row.layer">
                  <th scope="row">{{ row.layerName }}</th>
                  <td class="num">{{ formatAmount(row.currentAmountEur) }}</td>
                  <td class="num">{{ formatAmount(row.targetAmountEur) }}</td>
                  <td class="num">{{ formatAmount(row.deltaEur) }}</td>
                  <td class="num">{{ formatWeight(row.targetTotalWeightPct) }}</td>
                  <td class="num">{{ formatAmount(row.targetTotalAmountEur) }}</td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>

        <h4>Instrument Proposal</h4>
        <div v-if="instrumentGating && !instrumentGating.knowledgeBaseEnabled" class="note">
          Knowledge Base is disabled; instrument-level proposals are unavailable. The rationale below
          explains how weights are computed once KB data is available.
        </div>
        <div v-else-if="instrumentGating && !instrumentGating.kbComplete" class="note">
          Missing Knowledge Base extractions for:
          <span v-if="instrumentGating.missingIsins && instrumentGating.missingIsins.length">
            {{ instrumentGating.missingIsins.join(', ') }}
          </span>
          <span v-else>unknown instruments</span>. The rationale below explains how weights are
          computed once KB data is complete.
        </div>
        <div v-if="instrumentGating" class="note kb-legend">
          KB status:
          <span class="badge ok">Complete</span> extraction available,
          <span class="badge warn">Missing</span> extraction missing.
        </div>
        <div v-if="instrumentWarnings.length" class="callout warn">
          <strong>Instrument proposal warnings</strong>
          <ul>
            <li v-for="warning in instrumentWarnings" :key="warning">{{ warning }}</li>
          </ul>
        </div>
        <div class="instrument-rationale">
          <strong>Instrument rationale</strong>
          <p v-if="instrumentRationaleNote" class="note">{{ instrumentRationaleNote }}</p>
          <dl class="reason-legend">
            <dt>KB-weighted</dt>
            <dd>
              Weights favor lower TER, lower overlap (benchmark, regions, top holdings), and stronger
              valuation signals (earnings yield, EV/EBITDA, profitability in EUR, and P/B when available). Scores are normalized
              within each layer. See the valuation glossary below for definitions.
            </dd>
            <dt>New suggestion (gap detection)</dt>
            <dd>Suggested new saving plan instruments to close coverage gaps when a layer moves from 0 to a positive budget.</dd>
            <dt>Equal weight</dt>
            <dd>Used when KB factors are missing or only one instrument is in the layer.</dd>
            <dt>Dropped (below minimum)</dt>
            <dd>
              Proposed amount falls below the minimum saving plan size, so it is set to 0 and the
              layer budget is redistributed.
            </dd>
            <dt>Layer budget 0</dt>
            <dd>Layer has no budget, so all instruments are proposed at 0.</dd>
            <dt>No change (within tolerance)</dt>
            <dd>Current amounts are kept because the plan is within tolerance.</dd>
            <dt>No change (below minimum rebalancing)</dt>
            <dd>Adjustments below the minimum rebalancing amount are not proposed.</dd>
          </dl>
          <details class="note kb-glossary">
            <summary>Valuation glossary</summary>
            <dl class="reason-legend">
              <dt>Earnings yield (LT/current)</dt>
              <dd>
                Inverse of long-term P/E (smoothed EPS) or current P/E; higher implies cheaper valuation.
              </dd>
              <dt>EV/EBITDA</dt>
              <dd>Enterprise value divided by EBITDA; lower implies cheaper valuation.</dd>
              <dt>Profitability (EUR)</dt>
              <dd>EBITDA, AFFO, FFO, NOI, or net rent normalized to EUR for cross-currency comparisons.</dd>
              <dt>P/B (current)</dt>
              <dd>Price-to-book ratio when available; lower implies cheaper valuation.</dd>
              <dt>P/E (TTM holdings)</dt>
              <dd>Holdings-weighted P/E based on trailing twelve months earnings.</dd>
            </dl>
          </details>
        </div>
        <div v-if="instrumentProposals.length" class="actions">
          <label class="field">
            Group
            <select v-model="instrumentGroupMode">
              <option value="none">None</option>
              <option value="warnings" :disabled="instrumentWarningDetails.length === 0">By warnings</option>
            </select>
          </label>
          <label class="field">
            Sort
            <select v-model="instrumentSortMode">
              <option value="layer">Layer</option>
              <option value="isin">ISIN</option>
              <option value="name">Name</option>
              <option value="proposed">Proposed €</option>
              <option value="delta">Delta €</option>
            </select>
          </label>
        </div>
        <div class="table-wrap">
          <table class="table">
            <caption class="sr-only">Instrument-level savings plan proposal.</caption>
            <thead>
              <tr>
                <th scope="col">ISIN</th>
                <th scope="col">Name</th>
                <th scope="col">Layer</th>
                <th scope="col">KB</th>
                <th scope="col" class="num">Current €</th>
                <th scope="col" class="num">Proposed €</th>
                <th scope="col" class="num">Delta €</th>
                <th scope="col">Reasons</th>
              </tr>
            </thead>
            <tbody>
              <template v-if="loading">
                <tr class="sr-only">
                  <td colspan="8">Loading instrument proposals...</td>
                </tr>
                <tr v-for="n in 3" :key="`instrument-skeleton-${n}`" class="skeleton-row" aria-hidden="true">
                  <td><span class="skeleton-block"></span></td>
                  <td><span class="skeleton-block"></span></td>
                  <td><span class="skeleton-block"></span></td>
                  <td><span class="skeleton-block"></span></td>
                  <td class="num"><span class="skeleton-block"></span></td>
                  <td class="num"><span class="skeleton-block"></span></td>
                  <td class="num"><span class="skeleton-block"></span></td>
                  <td><span class="skeleton-block"></span></td>
                </tr>
              </template>
              <template v-else-if="instrumentGating && !instrumentGating.knowledgeBaseEnabled">
                <tr>
                  <td colspan="8">Instrument proposals are gated because the Knowledge Base is disabled.</td>
                </tr>
              </template>
              <template v-else-if="instrumentGating && !instrumentGating.kbComplete">
                <tr>
                  <td colspan="8">
                    Instrument proposals are gated; missing extractions for
                    <span v-if="instrumentGating.missingIsins && instrumentGating.missingIsins.length">
                      {{ instrumentGating.missingIsins.join(', ') }}
                    </span>
                    <span v-else>unknown instruments</span>.
                  </td>
                </tr>
              </template>
              <template v-else-if="instrumentProposals.length === 0">
                <tr>
                  <td colspan="8">No instrument proposals available.</td>
                </tr>
              </template>
              <template v-else>
                <template v-for="group in groupedInstrumentProposals" :key="group.key">
                  <tr class="group-row">
                    <th scope="rowgroup" colspan="8">{{ group.label }}</th>
                  </tr>
                  <tr v-if="group.items.length === 0">
                    <td colspan="8">No instruments for this warning group.</td>
                  </tr>
                  <tr v-for="row in group.items" :key="row.isin">
                    <th scope="row">{{ row.isin }}</th>
                    <td>{{ formatInstrumentName(row.instrumentName) }}</td>
                    <td>{{ layerLabel(row.layer) }}</td>
                    <td>
                      <span :class="['badge', kbStatusClass(row.isin)]">
                        {{ kbStatusLabel(row.isin) }}
                      </span>
                    </td>
                    <td class="num">{{ formatAmount(row.currentAmountEur) }}</td>
                    <td class="num">{{ formatProposedAmount(row.proposedAmountEur) }}</td>
                    <td class="num">{{ formatAmount(row.deltaEur) }}</td>
                    <td>{{ formatReasons(row.reasonCodes) }}</td>
                  </tr>
                </template>
              </template>
            </tbody>
          </table>
        </div>
        <p v-if="instrumentProposals.length" class="note">
          Net saving plan delta: {{ formatSignedAmount(instrumentNetDelta) }} EUR ({{ instrumentNetDeltaLabel }}).
        </p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref, computed } from 'vue'
import { apiRequest } from '../api'
import { formatNarrative } from '../utils/narrativeFormat'

const summary = ref(emptySummary())
const asOf = ref('')
const toast = ref('')
const toastType = ref('success')
const loading = ref(false)
const savingRun = ref(false)
const saveStatus = ref('')
const jobState = ref(null)
let pollTimer = null
const layerNames = ref({
  1: 'Global Core',
  2: 'Core-Plus',
  3: 'Themes',
  4: 'Individual Stocks',
  5: 'Unclassified'
})

const constraintChecks = computed(() => summary.value.savingPlanProposal?.constraints || [])
const profileName = computed(() => summary.value.savingPlanProposal?.selectedProfileDisplayName)
const profileKey = computed(() => summary.value.savingPlanProposal?.selectedProfileKey)
const profileRecommendation = computed(() => summary.value.savingPlanProposal?.recommendation)
const formattedNarrative = computed(() => formatNarrative(summary.value.savingPlanProposal?.narrative))
const instrumentGating = computed(() => summary.value.savingPlanProposal?.gating)
const instrumentProposals = computed(() => summary.value.savingPlanProposal?.instrumentProposals || [])
const instrumentWarnings = computed(() => summary.value.savingPlanProposal?.instrumentWarnings || [])
const instrumentWarningCodes = computed(() => summary.value.savingPlanProposal?.instrumentWarningCodes || [])
const instrumentNetDelta = computed(() =>
  instrumentProposals.value.reduce((total, row) => {
    const delta = Number(row?.deltaEur)
    if (!Number.isFinite(delta)) {
      return total
    }
    return total + delta
  }, 0)
)
const instrumentHasDiscarded = computed(() =>
  instrumentProposals.value.some((row) =>
    row?.reasonCodes?.some((code) => code === 'MIN_AMOUNT_DROPPED' || code === 'LAYER_BUDGET_ZERO')
  )
)
const instrumentNetDeltaLabel = computed(() => {
  const delta = instrumentNetDelta.value
  if (delta > 0) {
    return 'increased'
  }
  if (delta < 0) {
    return 'decreased'
  }
  if (instrumentHasDiscarded.value) {
    return 'discarded instruments'
  }
  return 'unchanged'
})
const missingIsins = computed(() => new Set(instrumentGating.value?.missingIsins || []))
const instrumentRationaleNote = computed(() => {
  if (!summary.value.savingPlanProposal) {
    return ''
  }
  if (instrumentGating.value && !instrumentGating.value.knowledgeBaseEnabled) {
    return 'Instrument proposals are disabled because the Knowledge Base is off.'
  }
  if (instrumentGating.value && !instrumentGating.value.kbComplete) {
    return 'Instrument proposals need complete Knowledge Base extractions for all ISINs.'
  }
  return 'Reasons are deterministic and describe how each layer budget is split.'
})
const instrumentGroupMode = ref('none')
const instrumentSortMode = ref('layer')
const instrumentWarningDetails = computed(() => buildWarningDetails(instrumentWarningCodes.value, instrumentWarnings.value))
const sortedInstrumentProposals = computed(() => sortInstrumentProposals(instrumentProposals.value))
const groupedInstrumentProposals = computed(() => groupInstrumentProposals(sortedInstrumentProposals.value))

async function load() {
  await startRebalancerRun({ saveRun: false })
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

async function saveRun() {
  await startRebalancerRun({ saveRun: true })
}

async function startRebalancerRun({ saveRun }) {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  saveStatus.value = ''
  if (saveRun) {
    toast.value = ''
    toastType.value = 'success'
    savingRun.value = true
    saveStatus.value = 'Saving run...'
  }
  loading.value = true
  try {
    const payload = {}
    if (asOf.value) {
      payload.asOf = asOf.value
    }
    if (saveRun) {
      payload.saveRun = true
    }
    const response = await apiRequest('/rebalancer/run', {
      method: 'POST',
      body: JSON.stringify(payload)
    })
    jobState.value = response
    await pollRebalancerJob(response?.job_id, saveRun)
  } catch (err) {
    loading.value = false
    savingRun.value = false
    toastType.value = 'error'
    toast.value = err.message
  }
}

async function pollRebalancerJob(jobId, saveRun) {
  if (!jobId) {
    loading.value = false
    savingRun.value = false
    toastType.value = 'error'
    toast.value = 'Rebalancer job was not started.'
    return
  }
  try {
    const response = await apiRequest(`/rebalancer/run/${jobId}`)
    jobState.value = response
    const status = response?.status
    if (status === 'DONE') {
      const result = response?.result
      applySummary(result?.summary)
      loading.value = false
      savingRun.value = false
      if (saveRun) {
        const runId = result?.saved_run?.runId
        if (runId) {
          toast.value = `Rebalancer run #${runId} saved. See Rebalancer History.`
          saveStatus.value = `Saved run #${runId}.`
        } else {
          toast.value = 'Rebalancer run saved. See Rebalancer History.'
          saveStatus.value = 'Run saved.'
        }
      } else {
        saveStatus.value = ''
      }
      return
    }
    if (status === 'FAILED') {
      loading.value = false
      savingRun.value = false
      toastType.value = 'error'
      toast.value = response?.error || 'Rebalancer run failed.'
      if (saveRun) {
        saveStatus.value = 'Save failed. Please try again.'
      }
      return
    }
    pollTimer = setTimeout(() => pollRebalancerJob(jobId, saveRun), 1200)
  } catch (err) {
    loading.value = false
    savingRun.value = false
    toastType.value = 'error'
    toast.value = err.message
    if (saveRun) {
      saveStatus.value = 'Save failed. Please try again.'
    }
  }
}

function applySummary(nextSummary) {
  if (!nextSummary) {
    summary.value = emptySummary()
    return
  }
  summary.value = nextSummary
}

function emptySummary() {
  return {
    layerAllocations: [],
    assetClassAllocations: [],
    topPositions: [],
    savingPlanSummary: null,
    savingPlanTargets: [],
    savingPlanProposal: null
  }
}

function formatAmount(value) {
  if (value === null || value === undefined) {
    return 'n/a'
  }
  return value.toFixed(2)
}

function formatSignedAmount(value) {
  if (value === null || value === undefined) {
    return 'n/a'
  }
  const numeric = Number(value)
  if (!Number.isFinite(numeric)) {
    return 'n/a'
  }
  const amount = Math.abs(numeric).toFixed(2)
  if (numeric > 0) {
    return `+${amount}`
  }
  if (numeric < 0) {
    return `-${amount}`
  }
  return amount
}

function formatProposedAmount(value) {
  if (value === null || value === undefined) {
    return 'n/a'
  }
  const numeric = Number(value)
  const amount = numeric.toFixed(2)
  return numeric <= 0 ? `${amount} (Discard)` : amount
}

function formatInstrumentName(value) {
  if (!value) {
    return 'n/a'
  }
  return value
}

function formatWeight(value) {
  if (value === null || value === undefined) {
    return 'n/a'
  }
  return value.toFixed(2)
}

function formatSource(value) {
  if (!value) {
    return 'n/a'
  }
  return value === 'llm' ? 'LLM' : 'Targets'
}

function resolveLayerKey(value) {
  if (value == null) {
    return null
  }
  const numeric = Number(value)
  if (Number.isFinite(numeric)) {
    return numeric
  }
  const digits = String(value).replace(/\D/g, '')
  if (!digits) {
    return null
  }
  const backup = Number(digits)
  return Number.isFinite(backup) ? backup : null
}

function layerLabel(layer) {
  const key = resolveLayerKey(layer)
  if (key == null) {
    return layer || 'Unclassified'
  }
  return layerNames.value[key] || `Layer ${key}`
}

function layerLabelFromAllocation(item) {
  if (!item?.label) {
    return 'Unclassified'
  }
  const key = resolveLayerKey(item.label)
  if (key == null) {
    return item.label
  }
  return layerNames.value[key] || item.label
}


function formatReasons(reasons) {
  if (!reasons || reasons.length === 0) {
    return 'n/a'
  }
  const labels = {
    NO_CHANGE_WITHIN_TOLERANCE: 'No change (within tolerance)',
    MIN_AMOUNT_DROPPED: 'Dropped (below minimum)',
    MIN_REBALANCE_AMOUNT: 'No change (below minimum rebalancing)',
    KB_WEIGHTED: 'KB-weighted',
    KB_GAP_SUGGESTION: 'New suggestion (gap detection)',
    EQUAL_WEIGHT: 'Equal weight',
    LAYER_BUDGET_ZERO: 'Layer budget 0'
  }
  return reasons.map((code) => labels[code] || code).join(', ')
}

function buildWarningDetails(codes, messages) {
  const list = []
  const max = Math.max(codes.length, messages.length)
  for (let index = 0; index < max; index += 1) {
    const code = codes[index] || 'UNKNOWN'
    const message = messages[index] || warningLabelFromCode(code) || code
    list.push({ key: `${code}-${index}`, code, message })
  }
  return list
}

function warningLabelFromCode(code) {
  const labels = {
    LAYER_NO_INSTRUMENTS: 'Layer budget has no active instruments',
    LAYER_ALL_BELOW_MINIMUM: 'All instruments below minimum saving plan size',
    RISK_NOT_ACCEPTABLE: 'Existing instrument exceeds acceptable risk'
  }
  return labels[code] || null
}

function groupInstrumentProposals(items) {
  if (instrumentGroupMode.value !== 'warnings' || instrumentWarningDetails.value.length === 0) {
    return [
      {
        key: 'all',
        label: 'All instruments',
        items
      }
    ]
  }
  const groups = []
  const used = new Set()
  for (const detail of instrumentWarningDetails.value) {
    const matchers = warningReasonCodes(detail.code)
    const groupItems = matchers.length
      ? items.filter((row) => row.reasonCodes && row.reasonCodes.some((code) => matchers.includes(code)))
      : []
    groupItems.forEach((row) => used.add(row.isin))
    groups.push({
      key: detail.key,
      label: `Warning: ${detail.message}`,
      items: groupItems
    })
  }
  const remaining = items.filter((row) => !used.has(row.isin))
  if (remaining.length) {
    groups.push({
      key: 'other',
      label: 'Other instruments',
      items: remaining
    })
  }
  return groups
}

function warningReasonCodes(code) {
  const mapping = {
    LAYER_ALL_BELOW_MINIMUM: ['MIN_AMOUNT_DROPPED']
  }
  return mapping[code] || []
}

function sortInstrumentProposals(items) {
  const sorted = [...items]
  sorted.sort((a, b) => {
    switch (instrumentSortMode.value) {
      case 'isin':
        return compareText(a.isin, b.isin)
      case 'name':
        return compareText(a.instrumentName, b.instrumentName)
      case 'proposed':
        return compareNumberDesc(a.proposedAmountEur, b.proposedAmountEur) || compareText(a.isin, b.isin)
      case 'delta':
        return compareNumberDesc(a.deltaEur, b.deltaEur) || compareText(a.isin, b.isin)
      default:
        return compareNumber(a.layer, b.layer) || compareText(a.isin, b.isin)
    }
  })
  return sorted
}

function compareText(a, b) {
  const left = (a || '').toString()
  const right = (b || '').toString()
  return left.localeCompare(right)
}

function compareNumber(a, b) {
  const left = Number.isFinite(Number(a)) ? Number(a) : Number.POSITIVE_INFINITY
  const right = Number.isFinite(Number(b)) ? Number(b) : Number.POSITIVE_INFINITY
  return left - right
}

function compareNumberDesc(a, b) {
  return compareNumber(b, a)
}

function kbStatusLabel(isin) {
  if (!isin) {
    return 'n/a'
  }
  return missingIsins.value.has(isin) ? 'Missing' : 'Complete'
}

function kbStatusClass(isin) {
  if (!isin) {
    return 'neutral'
  }
  return missingIsins.value.has(isin) ? 'warn' : 'ok'
}

onMounted(() => {
  load()
  loadLayerNames()
})
</script>
<style scoped>
.action-col {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.35rem;
}

.actions {
  display: flex;
  align-items: flex-end;
  gap: 0.8rem;
}

.save-status {
  margin: 0;
}

.constraint-block {
  margin-top: 0.8rem;
}

.skeleton-row td {
  padding-top: 0.6rem;
  padding-bottom: 0.6rem;
}

.skeleton-block {
  display: block;
  height: 0.75rem;
  border-radius: 999px;
  background: linear-gradient(90deg, #e6e6e6 25%, #f2f2f2 50%, #e6e6e6 75%);
  background-size: 200% 100%;
  animation: skeleton-shimmer 1.2s ease-in-out infinite;
}

.skeleton-row td.num .skeleton-block {
  margin-left: auto;
  width: 70%;
}

.skeleton-row td .skeleton-block {
  width: 90%;
}

@keyframes skeleton-shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
</style>
