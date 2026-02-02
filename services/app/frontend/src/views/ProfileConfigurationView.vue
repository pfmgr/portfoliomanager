<template>
  <div>
    <div class="card">
      <h2>Profile Configuration</h2>
      <p>Manage allocation targets, profile limits, and quality-gate settings.</p>
      <p v-if="selectedProfileName">
        Selected profile:
        <strong>{{ selectedProfileName }}</strong>
        <span>({{ selectedProfileKey }})</span>
      </p>
      <p v-if="selectedProfileDescription" class="note">{{ selectedProfileDescription }}</p>
      <label class="field">
        <span>Profile</span>
        <select v-model="selectedProfileKey">
          <option v-for="entry in orderedProfiles" :key="entry.key" :value="entry.key">
            {{ entry.profile.displayName }}
          </option>
        </select>
      </label>
      <label class="checkbox">
        <input type="checkbox" v-model="customOverridesEnabled" />
        Enable custom overrides
      </label>
      <div class="actions">
        <button class="primary" @click="applyProfile" :disabled="applyingProfile">
          {{ applyingProfile ? 'Applying…' : 'Apply profile' }}
        </button>
      </div>
      <div class="profile-tabs" role="tablist" aria-label="Profile configuration tabs">
        <button
          type="button"
          class="tab-button"
          :class="{ 'tab-active': activePanel === 'ALLOCATIONS' }"
          role="tab"
          :aria-selected="activePanel === 'ALLOCATIONS'"
          @click="activePanel = 'ALLOCATIONS'"
        >
          Allocation &amp; limits
        </button>
        <button
          type="button"
          class="tab-button"
          :class="{ 'tab-active': activePanel === 'QUALITY_GATES' }"
          role="tab"
          :aria-selected="activePanel === 'QUALITY_GATES'"
          @click="activePanel = 'QUALITY_GATES'"
        >
          Quality gates
        </button>
      </div>
    </div>

    <div v-if="activePanel === 'ALLOCATIONS'" class="card" role="tabpanel">
      <h2>Allocation &amp; limits</h2>
      <p>Manage layer target weights, variance tolerance, minimum saving plan size, and minimum rebalancing amount.</p>
      <p v-if="message" :class="['toast', messageType]">{{ message }}</p>
      <p v-if="hasVarianceBreaches" class="toast error">
        One or more layers exceed the acceptable variance tolerance.
      </p>
      <p v-if="customOverridesEnabled" class="note">Custom overrides are active.</p>
      <div class="grid grid-2">
        <div>
          <label for="variance-input">Acceptable Variance (%)</label>
          <input
            id="variance-input"
            class="input"
            type="number"
            step="0.1"
            aria-describedby="variance-help profile-defaults-note"
            v-model.number="variance"
          />
        </div>
        <div>
          <label for="minimum-saving-plan-input">Minimum Saving Plan Size (EUR)</label>
          <input
            id="minimum-saving-plan-input"
            class="input"
            type="number"
            step="1"
            min="1"
            inputmode="numeric"
            pattern="[0-9]*"
            aria-describedby="minimum-amounts-help profile-defaults-note"
            v-model.number="minimumSavingPlanSize"
            @input="coerceMinimumSavingPlanSize"
            @blur="normalizeMinimumSavingPlanSize"
          />
        </div>
        <div>
          <label for="minimum-rebalancing-input">Minimum Rebalancing Amount (EUR)</label>
          <input
            id="minimum-rebalancing-input"
            class="input"
            type="number"
            step="1"
            min="1"
            inputmode="numeric"
            pattern="[0-9]*"
            aria-describedby="minimum-amounts-help profile-defaults-note"
            v-model.number="minimumRebalancingAmount"
            @input="coerceMinimumRebalancingAmount"
            @blur="normalizeMinimumRebalancingAmount"
          />
        </div>
        <div>
          <label for="projection-horizon-input">Projection horizon (months)</label>
          <input
            id="projection-horizon-input"
            class="input"
            type="number"
            step="1"
            min="1"
            max="120"
            inputmode="numeric"
            pattern="[0-9]*"
            aria-describedby="projection-horizon-help profile-defaults-note"
            v-model.number="projectionHorizonMonths"
            @input="coerceProjectionHorizonMonths"
            @blur="normalizeProjectionHorizonMonths"
          />
        </div>
      </div>
      <p id="variance-help" class="note small">Typical range: 1-5%.</p>
      <p id="minimum-amounts-help" class="note small">Minimum amounts must be whole EUR (>= 1).</p>
      <p id="projection-horizon-help" class="note small">Range 1-120 months. Whole months only.</p>
      <p id="profile-defaults-note" class="note">
        Profile defaults: variance {{ formatVariance(profileVariancePct) }},
        minimum size {{ formatMinimum(profileMinimumSavingPlanSize) }} EUR,
        minimum rebalancing {{ formatMinimum(profileMinimumRebalancingAmount) }} EUR,
        projection horizon {{ profileProjectionHorizonMonths }} months.
      </p>
      <h3>Risk thresholds</h3>
      <p class="note">Stored per profile and used for instrument assessment risk bands.</p>
      <div class="grid grid-2">
        <div>
          <label for="risk-low-max-input">Low risk max (score)</label>
          <input
            id="risk-low-max-input"
            class="input"
            type="number"
            step="1"
            min="0"
            max="100"
            inputmode="numeric"
            pattern="[0-9]*"
            v-model.number="riskThresholds.lowMax"
            @input="coerceRiskThresholds"
            @blur="normalizeRiskThresholds"
          />
        </div>
        <div>
          <label for="risk-high-min-input">High risk min (score)</label>
          <input
            id="risk-high-min-input"
            class="input"
            type="number"
            step="1"
            min="0"
            max="100"
            inputmode="numeric"
            pattern="[0-9]*"
            v-model.number="riskThresholds.highMin"
            @input="coerceRiskThresholds"
            @blur="normalizeRiskThresholds"
          />
        </div>
      </div>
      <p class="note small">Medium risk is the band between the low and high thresholds.</p>
      <p class="note">Current source for variance + minimums: <b>{{ sourceLabel }}</b></p>
      <p v-if="loading" class="note">Loading layer targets...</p>

      <div class="table-wrap">
        <table class="table" style="margin-top: 1rem;">
          <caption class="sr-only">Layer target weights and deltas.</caption>
          <thead>
            <tr>
              <th scope="col">Layer</th>
              <th scope="col">Profile Weight (0-1)</th>
              <th scope="col">Custom Weight (0-1)</th>
              <th scope="col">Max Saving Plans</th>
              <th scope="col">Delta vs Profile (pp)</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="layer in layers" :key="layer">
              <th scope="row">{{ layerLabel(layer) }}</th>
              <td>{{ profileTargets[layer]?.toFixed(2) ?? '0.00' }}</td>
              <td>
                <input
                  class="input compact"
                  type="number"
                  step="0.01"
                  :disabled="!customOverridesEnabled"
                  v-model.number="targets[layer]"
                />
              </td>
              <td>
                <input
                  class="input compact"
                  type="number"
                  min="1"
                  step="1"
                  v-model.number="maxSavingPlansPerLayer[layer]"
                />
              </td>
              <td :class="['note', { warn: deltaExceeded(layer) }]">
                {{ formatDelta(layer) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="actions" style="margin-top: 1rem;">
        <button class="primary" @click="save" :disabled="saving">
          {{ saving ? 'Saving…' : 'Save' }}
        </button>
        <button class="secondary" @click="resetToProfileDefault">
          Reset to Profile Default
        </button>
      </div>
      <p class="note small">
        This button only reloads the layer targets, variance, minimum saving plan size, and minimum rebalancing amount from the selected profile. Use "Save" to persist the change.
      </p>
    </div>

    <div v-else-if="activePanel === 'QUALITY_GATES'" class="card" role="tabpanel">
      <h2>Quality gates</h2>
      <p>Configure evidence checks and layer classifications used by the knowledge base.</p>
      <p v-if="qualityGateLoading" class="note">Loading quality-gate profiles...</p>
      <p v-if="qualityGateMessage" :class="['toast', qualityGateMessageType]">{{ qualityGateMessage }}</p>
      <p class="note">
        Defaults follow the active profile ({{ selectedProfileKey }}). Custom overrides apply only when enabled and
        saved for this profile; other profiles continue using their defaults. These categories are always used to
        select evidence rules (not as a fallback after a failed gate).
      </p>
      <p class="note">
        Profile Gate shows the default category used for each layer. Custom Gate is the override category applied when
        overrides are enabled; it changes which evidence rules (FUND/EQUITY/REIT/UNKNOWN) are enforced for that layer.
        REITs are the exception: detected REIT instruments always use REIT rules.
      </p>
      <p v-if="qualityGateCustomOverridesEnabled" class="note">Custom quality-gate overrides are active.</p>
      <label class="checkbox">
        <input type="checkbox" v-model="qualityGateCustomOverridesEnabled" />
        Enable custom overrides
      </label>

      <div class="table-wrap">
        <table class="table" style="margin-top: 1rem;">
          <caption class="sr-only">Quality gate layer profiles.</caption>
          <thead>
            <tr>
              <th scope="col">Layer</th>
              <th scope="col">Profile gate</th>
              <th scope="col">Custom gate</th>
              <th scope="col">Changed</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="layer in layers" :key="layer">
              <th scope="row">{{ layerLabel(layer) }}</th>
              <td>{{ qualityGateCategoryLabel(qualityGateProfileLayer(layer)) }}</td>
              <td>
                <select
                  class="input compact"
                  :disabled="!qualityGateCustomOverridesEnabled"
                  v-model="qualityGateLayerOverrides[layer]"
                >
                  <option v-for="category in qualityGateCategories" :key="category" :value="category">
                    {{ qualityGateCategoryLabel(category) }}
                  </option>
                </select>
              </td>
              <td :class="['note', { warn: qualityGateDelta(layer) }]">
                {{ qualityGateDeltaLabel(layer) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <details class="quality-gate-advanced">
        <summary>Evidence requirements per category</summary>
        <div class="quality-gate-evidence">
          <div v-for="category in qualityGateCategories" :key="category" class="quality-gate-category">
            <h3>{{ qualityGateCategoryLabel(category) }}</h3>
            <div class="grid grid-2">
              <label class="field">
                <span>Profile defaults</span>
                <textarea
                  class="input"
                  rows="6"
                  :value="formatEvidenceList(qualityGateDefaultEvidence(category))"
                  disabled
                ></textarea>
              </label>
              <label class="field">
                <span>Custom overrides</span>
                <textarea
                  class="input"
                  rows="6"
                  v-model="qualityGateEvidenceOverrides[category]"
                  :disabled="!qualityGateCustomOverridesEnabled"
                ></textarea>
              </label>
            </div>
          </div>
          <p class="note small">Keys (one per line): {{ qualityGateEvidenceKeys.join(', ') }}</p>
        </div>
      </details>

      <div class="actions" style="margin-top: 1rem;">
        <button class="primary" @click="saveQualityGates" :disabled="qualityGateSaving || qualityGateLoading">
          {{ qualityGateSaving ? 'Saving…' : 'Save quality gates' }}
        </button>
        <button class="secondary" @click="resetQualityGatesToProfileDefault">
          Reset to Profile Default
        </button>
      </div>
      <p class="note small">Saving aligns quality gates to the selected profile.</p>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { apiRequest } from '../api'

const layers = [1, 2, 3, 4, 5]
const DEFAULT_RISK_THRESHOLDS = { lowMax: 30, highMin: 51 }
const DEFAULT_PROJECTION_HORIZON_MONTHS = 12
const MIN_PROJECTION_HORIZON_MONTHS = 1
const MAX_PROJECTION_HORIZON_MONTHS = 120
const activePanel = ref('ALLOCATIONS')
const qualityGateCategories = ['FUND', 'EQUITY', 'REIT', 'UNKNOWN']
const qualityGateEvidenceKeys = [
  'benchmark_index',
  'ongoing_charges_pct',
  'sri',
  'price',
  'pe_current',
  'pb_current',
  'pe_ttm_holdings',
  'earnings_yield_ttm_holdings',
  'holdings_coverage_weight_pct',
  'holdings_coverage_count',
  'holdings_asof',
  'dividend_per_share',
  'revenue',
  'net_income',
  'ebitda',
  'eps_history',
  'net_rent',
  'noi',
  'affo',
  'ffo',
  'market_cap'
]
const layerNames = ref({
  1: 'Global Core',
  2: 'Core-Plus',
  3: 'Themes',
  4: 'Individual Stocks',
  5: 'Unclassified'
})
const targets = ref({ 1: 0, 2: 0, 3: 0, 4: 0, 5: 0 })
const profileTargets = ref({ 1: 0, 2: 0, 3: 0, 4: 0, 5: 0 })
const maxSavingPlansPerLayer = ref({ 1: 17, 2: 17, 3: 17, 4: 17, 5: 17 })
const variance = ref(2.0)
const minimumSavingPlanSize = ref(15)
const minimumRebalancingAmount = ref(10)
const projectionHorizonMonths = ref(12)
const riskThresholds = ref({ ...DEFAULT_RISK_THRESHOLDS })
const message = ref('')
const messageType = ref('success')
const loading = ref(false)
const saving = ref(false)
const applyingProfile = ref(false)
const profiles = ref({})
const seedProfiles = ref({})
const selectedProfileKey = ref('BALANCED')
const customOverridesEnabled = ref(false)
const PROFILE_ORDER = ['CLASSIC', 'BALANCED', 'GROWTH', 'AGGRESSIVE', 'OPPORTUNITY']

const kbConfig = ref(null)
const qualityGateProfiles = ref({})
const qualityGateCustomProfiles = ref({})
const qualityGateCustomOverridesEnabled = ref(false)
const qualityGateLayerOverrides = ref({ 1: 'UNKNOWN', 2: 'UNKNOWN', 3: 'UNKNOWN', 4: 'UNKNOWN', 5: 'UNKNOWN' })
const qualityGateEvidenceOverrides = ref({ FUND: '', EQUITY: '', REIT: '', UNKNOWN: '' })
const qualityGateMessage = ref('')
const qualityGateMessageType = ref('success')
const qualityGateLoading = ref(false)
const qualityGateSaving = ref(false)

const selectedProfileName = computed(() => profiles.value[selectedProfileKey.value]?.displayName || 'Custom')
const selectedProfileDescription = computed(() => profiles.value[selectedProfileKey.value]?.description || '')
const seedProfile = computed(() => resolveSeedProfile(selectedProfileKey.value))
const hasVarianceBreaches = computed(() => layers.some((layer) => deltaExceeded(layer)))
const sourceLabel = computed(() => (customOverridesEnabled.value ? 'Custom overrides' : selectedProfileName.value))
const qualityGateProfileDefaults = computed(() => resolveQualityGateProfile(qualityGateProfiles.value))
const profileVariancePct = computed(() => {
  const value = seedProfile.value?.acceptableVariancePct
  return typeof value === 'number' && value > 0 ? value : variance.value
})
const profileMinimumSavingPlanSize = computed(() => {
  const value = seedProfile.value?.minimumSavingPlanSize
  return Number.isInteger(value) && value > 0 ? value : 15
})
const profileMinimumRebalancingAmount = computed(() => {
  const value = seedProfile.value?.minimumRebalancingAmount
  return Number.isInteger(value) && value > 0 ? value : 10
})
const profileProjectionHorizonMonths = computed(() => {
  const numeric = Number(seedProfile.value?.projectionHorizonMonths)
  if (!Number.isFinite(numeric)) {
    return DEFAULT_PROJECTION_HORIZON_MONTHS
  }
  if (numeric < MIN_PROJECTION_HORIZON_MONTHS) {
    return MIN_PROJECTION_HORIZON_MONTHS
  }
  if (numeric > MAX_PROJECTION_HORIZON_MONTHS) {
    return MAX_PROJECTION_HORIZON_MONTHS
  }
  return Math.trunc(numeric)
})
const profileRiskThresholdDefaults = computed(() => mapRiskThresholds(seedProfile.value?.riskThresholds))
const orderedProfiles = computed(() => {
  const entries = []
  PROFILE_ORDER.forEach((key) => {
    const profile = profiles.value[key]
    if (profile) {
      entries.push({ key, profile })
    }
  })
  Object.entries(profiles.value).forEach(([key, profile]) => {
    if (!PROFILE_ORDER.includes(key)) {
      entries.push({ key, profile })
    }
  })
  return entries
})

async function loadConfig() {
  loading.value = true
  try {
    const data = await apiRequest('/layer-targets')
    applyResponse(data)
  } finally {
    loading.value = false
  }
}

async function loadQualityGateConfig() {
  qualityGateLoading.value = true
  qualityGateMessage.value = ''
  qualityGateMessageType.value = 'success'
  try {
    const data = await apiRequest('/kb/config')
    kbConfig.value = data
    applyQualityGateConfig(data?.quality_gate_profiles)
  } catch (err) {
    qualityGateMessageType.value = 'error'
    qualityGateMessage.value = err?.message || 'Failed to load quality gates.'
  } finally {
    qualityGateLoading.value = false
  }
}

function applyResponse(data) {
  if (!data) {
    return
  }
  profiles.value = data.profiles || profiles.value
  seedProfiles.value = data.seedProfiles || seedProfiles.value
  if (data.layerNames && Object.keys(data.layerNames).length) {
    layerNames.value = data.layerNames
  }
  variance.value = data.acceptableVariancePct ?? variance.value
  minimumSavingPlanSize.value = data.minimumSavingPlanSize ?? minimumSavingPlanSize.value
  minimumRebalancingAmount.value = data.minimumRebalancingAmount ?? minimumRebalancingAmount.value
  maxSavingPlansPerLayer.value = mapToMaxSavingPlans(data.maxSavingPlansPerLayer || {})
  selectedProfileKey.value = data.activeProfileKey ?? selectedProfileKey.value
  customOverridesEnabled.value = Boolean(data.customOverridesEnabled)
  const profile = profiles.value[selectedProfileKey.value]
  applyProfileRiskThresholds(profile)
  applyProfileProjectionHorizon(profile)
  const effectiveTargets = mapToTargets(data.effectiveLayerTargets || {})
  profileTargets.value = profile?.layerTargets ? mapToTargets(profile.layerTargets) : effectiveTargets
  const customTargets = mapToTargets(data.customLayerTargets || {})
  if (customOverridesEnabled.value && Object.values(customTargets).some((value) => value !== 0)) {
    targets.value = customTargets
  } else {
    targets.value = effectiveTargets
  }
}

function applyQualityGateConfig(raw) {
  const normalized = normalizeQualityGateConfig(raw)
  qualityGateProfiles.value = normalized.profiles
  qualityGateCustomProfiles.value = normalized.customProfiles
  qualityGateCustomOverridesEnabled.value = normalized.customOverridesEnabled
  applyQualityGateProfileOverrides()
}

function normalizeQualityGateConfig(raw) {
  const profiles = normalizeQualityGateProfiles(raw?.profiles)
  const customProfiles = normalizeQualityGateProfiles(raw?.custom_profiles)
  const activeProfile = normalizeProfileKey(raw?.active_profile) || selectedProfileKey.value
  return {
    activeProfile,
    customOverridesEnabled: Boolean(raw?.custom_overrides_enabled),
    profiles,
    customProfiles
  }
}

function normalizeQualityGateProfiles(raw) {
  if (!raw) return {}
  const normalized = {}
  Object.entries(raw).forEach(([key, profile]) => {
    const normalizedKey = normalizeProfileKey(key)
    if (!normalizedKey) return
    normalized[normalizedKey] = {
      displayName: profile?.display_name || profile?.displayName || normalizedKey,
      description: profile?.description || '',
      layerProfiles: normalizeQualityGateLayerProfiles(profile?.layer_profiles || profile?.layerProfiles),
      evidenceProfiles: normalizeQualityGateEvidenceProfiles(profile?.evidence_profiles || profile?.evidenceProfiles)
    }
  })
  return normalized
}

function normalizeQualityGateLayerProfiles(raw) {
  const output = {}
  layers.forEach((layer) => {
    output[layer] = 'UNKNOWN'
  })
  if (!raw) return output
  Object.entries(raw).forEach(([key, value]) => {
    const layer = Number(key)
    if (!Number.isInteger(layer) || layer < 1 || layer > 5) return
    const normalizedValue = normalizeProfileKey(value) || 'UNKNOWN'
    output[layer] = normalizedValue
  })
  return output
}

function normalizeQualityGateEvidenceProfiles(raw) {
  const output = {}
  if (!raw) return output
  Object.entries(raw).forEach(([key, value]) => {
    const normalizedKey = normalizeProfileKey(key)
    if (!normalizedKey) return
    output[normalizedKey] = normalizeEvidenceList(value)
  })
  return output
}

function normalizeEvidenceList(raw) {
  if (!Array.isArray(raw)) return []
  const cleaned = []
  raw.forEach((entry) => {
    if (!entry) return
    const value = String(entry).trim().toLowerCase()
    if (!value) return
    if (!cleaned.includes(value)) cleaned.push(value)
  })
  return cleaned
}

function normalizeProfileKey(key) {
  if (!key) return ''
  return String(key).trim().toUpperCase()
}

function resolveQualityGateProfile(profilesMap, key = selectedProfileKey.value) {
  if (!profilesMap || Object.keys(profilesMap).length === 0) return null
  const normalizedKey = normalizeProfileKey(key)
  return (
    profilesMap[normalizedKey] ||
    profilesMap.BALANCED ||
    profilesMap.DEFAULT ||
    Object.values(profilesMap)[0]
  )
}

function applyQualityGateProfileOverrides() {
  const defaults = qualityGateProfileDefaults.value
  const customProfileKey = normalizeProfileKey(selectedProfileKey.value)
  const customProfile = qualityGateCustomProfiles.value[customProfileKey]
  const hasCustomProfile = Boolean(customProfile)
  const useCustom = qualityGateCustomOverridesEnabled.value && hasCustomProfile
  qualityGateLayerOverrides.value = buildQualityGateLayerOverrides(
    defaults?.layerProfiles,
    customProfile?.layerProfiles,
    useCustom
  )
  qualityGateEvidenceOverrides.value = buildQualityGateEvidenceOverrides(
    defaults?.evidenceProfiles,
    customProfile?.evidenceProfiles,
    useCustom
  )
}

function buildQualityGateLayerOverrides(defaults = {}, custom = {}, useCustom) {
  const output = {}
  layers.forEach((layer) => {
    const defaultValue = normalizeProfileKey(defaults?.[layer]) || 'UNKNOWN'
    const customValue = normalizeProfileKey(custom?.[layer]) || defaultValue
    output[layer] = useCustom ? customValue : defaultValue
  })
  return output
}

function buildQualityGateEvidenceOverrides(defaults = {}, custom = {}, useCustom) {
  const output = {}
  qualityGateCategories.forEach((category) => {
    const defaultList = normalizeEvidenceList(defaults?.[category])
    const customList = normalizeEvidenceList(custom?.[category])
    const list = useCustom ? customList : defaultList
    output[category] = formatEvidenceList(list)
  })
  return output
}

async function submitConfig(payload, successMessage) {
  message.value = ''
  messageType.value = 'success'
  try {
    const data = await apiRequest('/layer-targets', {
      method: 'PUT',
      body: JSON.stringify(payload)
    })
    message.value = successMessage
    applyResponse(data)
  } catch (err) {
    messageType.value = 'error'
    message.value = err.message
  }
}

async function save() {
  saving.value = true
  try {
    normalizeMinimumSavingPlanSize()
    normalizeMinimumRebalancingAmount()
    normalizeProjectionHorizonMonths()
    normalizeRiskThresholds()
    await submitConfig(
      {
        activeProfile: selectedProfileKey.value,
        customOverridesEnabled: customOverridesEnabled.value,
        layerTargets: customOverridesEnabled.value ? { ...targets.value } : undefined,
        acceptableVariancePct: variance.value,
        minimumSavingPlanSize: minimumSavingPlanSize.value,
        minimumRebalancingAmount: minimumRebalancingAmount.value,
        maxSavingPlansPerLayer: { ...maxSavingPlansPerLayer.value },
        profileRiskThresholds: {
          [selectedProfileKey.value]: { ...riskThresholds.value }
        },
        profileProjectionHorizonMonths: {
          [selectedProfileKey.value]: projectionHorizonMonths.value
        }
      },
      'Layer targets saved.'
    )
  } finally {
    saving.value = false
  }
}

async function applyProfile() {
  applyingProfile.value = true
  customOverridesEnabled.value = false
  targets.value = { ...profileTargets.value }
  const profile = profiles.value[selectedProfileKey.value]
  applyProfileRiskThresholds(profile)
  if (profile?.acceptableVariancePct) {
    variance.value = profile.acceptableVariancePct
  }
  if (profile?.minimumSavingPlanSize !== undefined && profile?.minimumSavingPlanSize !== null) {
    minimumSavingPlanSize.value = profile.minimumSavingPlanSize
  }
  if (profile?.minimumRebalancingAmount !== undefined && profile?.minimumRebalancingAmount !== null) {
    minimumRebalancingAmount.value = profile.minimumRebalancingAmount
  }
  applyProfileProjectionHorizon(profile)
  try {
    await submitConfig(
      {
        activeProfile: selectedProfileKey.value,
        customOverridesEnabled: false,
        acceptableVariancePct: variance.value,
        minimumSavingPlanSize: minimumSavingPlanSize.value,
        minimumRebalancingAmount: minimumRebalancingAmount.value,
        maxSavingPlansPerLayer: { ...maxSavingPlansPerLayer.value }
      },
      'Profile applied.'
    )
  } finally {
    applyingProfile.value = false
  }
}

async function resetToProfileDefault() {
  message.value = 'Layer targets reset to profile defaults.'
  messageType.value = 'success'
  customOverridesEnabled.value = false
  const seed = resolveSeedProfile(selectedProfileKey.value)
  if (seed?.layerTargets) {
    targets.value = mapToTargets(seed.layerTargets)
  } else {
    targets.value = { ...profileTargets.value }
  }
  riskThresholds.value = mapRiskThresholds(seed?.riskThresholds)
  if (seed?.acceptableVariancePct) {
    variance.value = seed.acceptableVariancePct
  }
  if (seed?.minimumSavingPlanSize !== undefined && seed?.minimumSavingPlanSize !== null) {
    minimumSavingPlanSize.value = seed.minimumSavingPlanSize
  }
  if (seed?.minimumRebalancingAmount !== undefined && seed?.minimumRebalancingAmount !== null) {
    minimumRebalancingAmount.value = seed.minimumRebalancingAmount
  }
  if (seed?.projectionHorizonMonths !== undefined && seed?.projectionHorizonMonths !== null) {
    projectionHorizonMonths.value = clampProjectionHorizonMonths(Math.trunc(seed.projectionHorizonMonths))
  } else {
    projectionHorizonMonths.value = profileProjectionHorizonMonths.value
  }
}

async function saveQualityGates() {
  qualityGateSaving.value = true
  qualityGateMessage.value = ''
  qualityGateMessageType.value = 'success'
  try {
    if (!kbConfig.value) {
      await loadQualityGateConfig()
    }
    const customProfileKey = normalizeProfileKey(selectedProfileKey.value)
    const customProfiles = { ...qualityGateCustomProfiles.value }
    if (qualityGateCustomOverridesEnabled.value) {
      customProfiles[customProfileKey] = buildQualityGateCustomProfile(customProfileKey)
    }
    qualityGateCustomProfiles.value = customProfiles
    const payload = {
      ...(kbConfig.value || {}),
      quality_gate_profiles: buildQualityGatePayload(customProfiles)
    }
    const data = await apiRequest('/kb/config', {
      method: 'PUT',
      body: JSON.stringify(payload)
    })
    kbConfig.value = data
    applyQualityGateConfig(data?.quality_gate_profiles)
    qualityGateMessage.value = 'Quality gates saved.'
  } catch (err) {
    qualityGateMessageType.value = 'error'
    qualityGateMessage.value = err?.message || 'Failed to save quality gates.'
  } finally {
    qualityGateSaving.value = false
  }
}

function resetQualityGatesToProfileDefault() {
  qualityGateMessage.value = 'Quality gates reset to profile defaults.'
  qualityGateMessageType.value = 'success'
  qualityGateCustomOverridesEnabled.value = false
  applyQualityGateProfileOverrides()
}

function mapToTargets(source) {
  const payload = {}
  layers.forEach((layer) => {
    const value = source?.[layer]
    payload[layer] = value !== undefined ? Number(value) : 0
  })
  return payload
}

function mapToMaxSavingPlans(source) {
  const payload = {}
  layers.forEach((layer) => {
    const value = source?.[layer]
    const normalized = Number.isInteger(value) && value > 0 ? value : 17
    payload[layer] = normalized
  })
  return payload
}

function mapRiskThresholds(source) {
  const lowMax = Number(source?.lowMax)
  const highMin = Number(source?.highMin)
  return {
    lowMax: Number.isFinite(lowMax) ? lowMax : DEFAULT_RISK_THRESHOLDS.lowMax,
    highMin: Number.isFinite(highMin) ? highMin : DEFAULT_RISK_THRESHOLDS.highMin
  }
}

function applyProfileRiskThresholds(profile) {
  riskThresholds.value = mapRiskThresholds(profile?.riskThresholds)
}

function applyProfileProjectionHorizon(profile) {
  const numeric = Number(profile?.projectionHorizonMonths)
  if (!Number.isFinite(numeric)) {
    projectionHorizonMonths.value = profileProjectionHorizonMonths.value
    return
  }
  projectionHorizonMonths.value = clampProjectionHorizonMonths(Math.trunc(numeric))
}

function resolveSeedProfile(key) {
  if (!key) {
    return seedProfiles.value?.BALANCED || profiles.value?.BALANCED || null
  }
  return seedProfiles.value?.[key] || profiles.value?.[key] || null
}

function qualityGateCategoryLabel(category) {
  switch (category) {
    case 'FUND':
      return 'ETF/Fund'
    case 'EQUITY':
      return 'Equity'
    case 'REIT':
      return 'REIT'
    case 'UNKNOWN':
      return 'Unknown'
    default:
      return category || 'Unknown'
  }
}

function qualityGateProfileLayer(layer) {
  const defaults = qualityGateProfileDefaults.value
  return normalizeProfileKey(defaults?.layerProfiles?.[layer]) || 'UNKNOWN'
}

function qualityGateDelta(layer) {
  return normalizeProfileKey(qualityGateLayerOverrides.value[layer]) !== qualityGateProfileLayer(layer)
}

function qualityGateDeltaLabel(layer) {
  return qualityGateDelta(layer) ? 'Custom' : 'Profile'
}

function qualityGateDefaultEvidence(category) {
  const defaults = qualityGateProfileDefaults.value
  return normalizeEvidenceList(defaults?.evidenceProfiles?.[category])
}

function formatEvidenceList(list) {
  if (!Array.isArray(list) || list.length === 0) return ''
  return list.join('\n')
}

function parseEvidenceList(raw) {
  if (!raw) return []
  return raw
    .split(/[\n,]/g)
    .map((entry) => entry.trim().toLowerCase())
    .filter((entry) => entry && qualityGateEvidenceKeys.includes(entry))
}

function buildQualityGateCustomProfile(profileKey) {
  const defaults = qualityGateProfileDefaults.value
  const displayName = defaults?.displayName || selectedProfileName.value
  const description = defaults?.description || ''
  const layerProfiles = {}
  layers.forEach((layer) => {
    layerProfiles[layer] = normalizeProfileKey(qualityGateLayerOverrides.value[layer]) || 'UNKNOWN'
  })
  const evidenceProfiles = {}
  qualityGateCategories.forEach((category) => {
    evidenceProfiles[category] = parseEvidenceList(qualityGateEvidenceOverrides.value[category])
  })
  return {
    displayName,
    description,
    layerProfiles,
    evidenceProfiles
  }
}

function buildQualityGatePayload(customProfiles) {
  return {
    active_profile: normalizeProfileKey(selectedProfileKey.value) || 'BALANCED',
    profiles: serializeQualityGateProfiles(qualityGateProfiles.value),
    custom_overrides_enabled: qualityGateCustomOverridesEnabled.value,
    custom_profiles: serializeQualityGateProfiles(customProfiles)
  }
}

function serializeQualityGateProfiles(raw) {
  const payload = {}
  if (!raw) return payload
  Object.entries(raw).forEach(([key, profile]) => {
    if (!profile) return
    payload[key] = {
      display_name: profile.displayName || key,
      description: profile.description || '',
      layer_profiles: serializeQualityGateLayerProfiles(profile.layerProfiles),
      evidence_profiles: serializeQualityGateEvidenceProfiles(profile.evidenceProfiles)
    }
  })
  return payload
}

function serializeQualityGateLayerProfiles(raw) {
  const payload = {}
  layers.forEach((layer) => {
    payload[String(layer)] = normalizeProfileKey(raw?.[layer]) || 'UNKNOWN'
  })
  return payload
}

function serializeQualityGateEvidenceProfiles(raw) {
  const payload = {}
  if (!raw) return payload
  Object.entries(raw).forEach(([key, list]) => {
    const normalizedKey = normalizeProfileKey(key)
    if (!normalizedKey) return
    const cleaned = normalizeEvidenceList(list)
    payload[normalizedKey] = cleaned
  })
  return payload
}

function formatDelta(layer) {
  const current = targets.value[layer] ?? 0
  const baseline = profileTargets.value[layer] ?? 0
  const delta = (current - baseline) * 100
  const formatted = delta.toFixed(2)
  return delta >= 0 ? `+${formatted}pp` : `${formatted}pp`
}

function formatVariance(value) {
  if (value === null || value === undefined) {
    return 'n/a'
  }
  return `${value.toFixed(1)}%`
}

function formatMinimum(value) {
  if (value === null || value === undefined) {
    return 'n/a'
  }
  return `${value}`
}

function coerceRiskThresholds() {
  const lowMax = Number(riskThresholds.value.lowMax)
  const highMin = Number(riskThresholds.value.highMin)
  if (Number.isFinite(lowMax)) {
    riskThresholds.value.lowMax = Math.trunc(lowMax)
  }
  if (Number.isFinite(highMin)) {
    riskThresholds.value.highMin = Math.trunc(highMin)
  }
}

function normalizeRiskThresholds() {
  const defaults = profileRiskThresholdDefaults.value
  let lowMax = Number(riskThresholds.value.lowMax)
  let highMin = Number(riskThresholds.value.highMin)
  if (!Number.isFinite(lowMax)) {
    lowMax = defaults.lowMax
  }
  if (!Number.isFinite(highMin)) {
    highMin = defaults.highMin
  }
  lowMax = clampRiskValue(Math.trunc(lowMax))
  highMin = clampRiskValue(Math.trunc(highMin))
  if (highMin <= lowMax) {
    highMin = Math.min(100, lowMax + 1)
    if (highMin <= lowMax) {
      lowMax = Math.max(0, highMin - 1)
    }
  }
  riskThresholds.value = { lowMax, highMin }
}

function clampRiskValue(value) {
  if (!Number.isFinite(value)) {
    return 0
  }
  if (value < 0) {
    return 0
  }
  if (value > 100) {
    return 100
  }
  return value
}

function clampProjectionHorizonMonths(value) {
  if (!Number.isFinite(value)) {
    return DEFAULT_PROJECTION_HORIZON_MONTHS
  }
  if (value < MIN_PROJECTION_HORIZON_MONTHS) {
    return MIN_PROJECTION_HORIZON_MONTHS
  }
  if (value > MAX_PROJECTION_HORIZON_MONTHS) {
    return MAX_PROJECTION_HORIZON_MONTHS
  }
  return value
}

function coerceMinimumSavingPlanSize() {
  if (minimumSavingPlanSize.value === null || minimumSavingPlanSize.value === undefined) {
    return
  }
  const numeric = Number(minimumSavingPlanSize.value)
  if (!Number.isFinite(numeric)) {
    return
  }
  minimumSavingPlanSize.value = Math.trunc(numeric)
}

function normalizeMinimumSavingPlanSize() {
  const numeric = Number(minimumSavingPlanSize.value)
  if (!Number.isFinite(numeric) || numeric < 1) {
    minimumSavingPlanSize.value = profileMinimumSavingPlanSize.value
    return
  }
  minimumSavingPlanSize.value = Math.trunc(numeric)
}

function coerceMinimumRebalancingAmount() {
  if (minimumRebalancingAmount.value === null || minimumRebalancingAmount.value === undefined) {
    return
  }
  const numeric = Number(minimumRebalancingAmount.value)
  if (!Number.isFinite(numeric)) {
    return
  }
  minimumRebalancingAmount.value = Math.trunc(numeric)
}

function normalizeMinimumRebalancingAmount() {
  const numeric = Number(minimumRebalancingAmount.value)
  if (!Number.isFinite(numeric) || numeric < 1) {
    minimumRebalancingAmount.value = profileMinimumRebalancingAmount.value
    return
  }
  minimumRebalancingAmount.value = Math.trunc(numeric)
}

function coerceProjectionHorizonMonths() {
  if (projectionHorizonMonths.value === null || projectionHorizonMonths.value === undefined) {
    return
  }
  const numeric = Number(projectionHorizonMonths.value)
  if (!Number.isFinite(numeric)) {
    return
  }
  projectionHorizonMonths.value = clampProjectionHorizonMonths(Math.trunc(numeric))
}

function normalizeProjectionHorizonMonths() {
  const numeric = Number(projectionHorizonMonths.value)
  if (!Number.isFinite(numeric)) {
    projectionHorizonMonths.value = profileProjectionHorizonMonths.value
    return
  }
  projectionHorizonMonths.value = clampProjectionHorizonMonths(Math.trunc(numeric))
}

function layerLabel(layer) {
  return layerNames.value[layer] || `Layer ${layer}`
}

function deltaExceeded(layer) {
  const current = targets.value[layer] ?? 0
  const baseline = profileTargets.value[layer] ?? 0
  const delta = Math.abs((current - baseline) * 100)
  return delta > (variance.value ?? 0)
}

watch(selectedProfileKey, (key) => {
  const profile = profiles.value[key]
  if (profile?.layerTargets) {
    profileTargets.value = mapToTargets(profile.layerTargets)
    if (!customOverridesEnabled.value) {
      targets.value = { ...profileTargets.value }
    }
  }
  applyProfileRiskThresholds(profile)
  applyProfileProjectionHorizon(profile)
  if (!customOverridesEnabled.value && profile?.acceptableVariancePct) {
    variance.value = profile.acceptableVariancePct
  }
  if (!customOverridesEnabled.value && profile?.minimumSavingPlanSize !== undefined && profile?.minimumSavingPlanSize !== null) {
    minimumSavingPlanSize.value = profile.minimumSavingPlanSize
  }
  if (!customOverridesEnabled.value && profile?.minimumRebalancingAmount !== undefined && profile?.minimumRebalancingAmount !== null) {
    minimumRebalancingAmount.value = profile.minimumRebalancingAmount
  }
  applyQualityGateProfileOverrides()
})

watch(customOverridesEnabled, (enabled) => {
  if (!enabled) {
    targets.value = { ...profileTargets.value }
    const profile = profiles.value[selectedProfileKey.value]
    if (profile?.acceptableVariancePct) {
      variance.value = profile.acceptableVariancePct
    }
    if (profile?.minimumSavingPlanSize !== undefined && profile?.minimumSavingPlanSize !== null) {
      minimumSavingPlanSize.value = profile.minimumSavingPlanSize
    }
    if (profile?.minimumRebalancingAmount !== undefined && profile?.minimumRebalancingAmount !== null) {
      minimumRebalancingAmount.value = profile.minimumRebalancingAmount
    }
  }
})

watch(qualityGateCustomOverridesEnabled, () => {
  applyQualityGateProfileOverrides()
})

onMounted(() => {
  loadConfig()
  loadQualityGateConfig()
})
</script>

<style scoped>
.profile-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 0.6rem;
  margin-top: 1rem;
}

.tab-button {
  border: 1px solid #f6c47b;
  background: transparent;
  color: #1b1b1f;
  padding: 0.4rem 0.9rem;
  border-radius: 999px;
  cursor: pointer;
}

.tab-button.tab-active {
  background: #f6c47b;
  color: #14131a;
}

.quality-gate-advanced {
  margin-top: 1rem;
  border-top: 1px solid #eee6d8;
  padding-top: 0.75rem;
}

.quality-gate-advanced summary {
  cursor: pointer;
  font-weight: 600;
}

.quality-gate-category {
  margin-top: 1rem;
}

.note.warn {
  color: #7a1f1c;
  font-weight: 600;
}
</style>
