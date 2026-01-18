<template>
  <div>
    <div class="card">
      <h2>Advisor Profile</h2>
      <p>Manage the active profile and optional custom overrides.</p>
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
    </div>

    <div class="card">
      <h2>Layer Targets</h2>
      <p>Manage layer target weights, variance tolerance, minimum saving plan size, and minimum rebalancing amount.</p>
      <p v-if="message" :class="['toast', messageType]">{{ message }}</p>
      <p v-if="hasVarianceBreaches" class="toast error">
        One or more layers exceed the acceptable variance tolerance.
      </p>
      <p v-if="customOverridesEnabled" class="note">Custom overrides are active.</p>
      <div class="grid grid-2">
        <div>
          <label for="variance-input">Acceptable Variance (%)</label>
          <input id="variance-input" class="input" type="number" step="0.1" v-model.number="variance" />
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
            v-model.number="minimumRebalancingAmount"
            @input="coerceMinimumRebalancingAmount"
            @blur="normalizeMinimumRebalancingAmount"
          />
        </div>
      </div>
      <p class="note">Current source for variance + minimums: <b>{{ sourceLabel }}</b></p>
      <p class="note">
        Profile defaults: variance {{ formatVariance(profileVariancePct) }},
        minimum size {{ formatMinimum(profileMinimumSavingPlanSize) }} EUR,
        minimum rebalancing {{ formatMinimum(profileMinimumRebalancingAmount) }} EUR.
      </p>
      <p class="note small">Minimum amounts accept whole EUR amounts only.</p>
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
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { apiRequest } from '../api'

const layers = [1, 2, 3, 4, 5]
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
const message = ref('')
const messageType = ref('success')
const loading = ref(false)
const saving = ref(false)
const applyingProfile = ref(false)
const profiles = ref({})
const selectedProfileKey = ref('BALANCED')
const customOverridesEnabled = ref(false)
const PROFILE_ORDER = ['CLASSIC', 'BALANCED', 'GROWTH', 'AGGRESSIVE', 'OPPORTUNITY']

const selectedProfileName = computed(() => profiles.value[selectedProfileKey.value]?.displayName || 'Custom')
const selectedProfileDescription = computed(() => profiles.value[selectedProfileKey.value]?.description || '')
const hasVarianceBreaches = computed(() => layers.some((layer) => deltaExceeded(layer)))
const sourceLabel = computed(() => (customOverridesEnabled.value ? 'Custom overrides' : selectedProfileName.value))
const profileVariancePct = computed(() => {
  const value = profiles.value[selectedProfileKey.value]?.acceptableVariancePct
  return typeof value === 'number' && value > 0 ? value : variance.value
})
const profileMinimumSavingPlanSize = computed(() => {
  const value = profiles.value[selectedProfileKey.value]?.minimumSavingPlanSize
  return Number.isInteger(value) && value > 0 ? value : 15
})
const profileMinimumRebalancingAmount = computed(() => {
  const value = profiles.value[selectedProfileKey.value]?.minimumRebalancingAmount
  return Number.isInteger(value) && value > 0 ? value : 10
})
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

function applyResponse(data) {
  if (!data) {
    return
  }
  profiles.value = data.profiles || profiles.value
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
  const effectiveTargets = mapToTargets(data.effectiveLayerTargets || {})
  profileTargets.value = profile?.layerTargets ? mapToTargets(profile.layerTargets) : effectiveTargets
  const customTargets = mapToTargets(data.customLayerTargets || {})
  if (customOverridesEnabled.value && Object.values(customTargets).some((value) => value !== 0)) {
    targets.value = customTargets
  } else {
    targets.value = effectiveTargets
  }
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
    await submitConfig(
      {
        activeProfile: selectedProfileKey.value,
        customOverridesEnabled: customOverridesEnabled.value,
        layerTargets: customOverridesEnabled.value ? { ...targets.value } : undefined,
        acceptableVariancePct: variance.value,
        minimumSavingPlanSize: minimumSavingPlanSize.value,
        minimumRebalancingAmount: minimumRebalancingAmount.value,
        maxSavingPlansPerLayer: { ...maxSavingPlansPerLayer.value }
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
  if (profile?.acceptableVariancePct) {
    variance.value = profile.acceptableVariancePct
  }
  if (profile?.minimumSavingPlanSize !== undefined && profile?.minimumSavingPlanSize !== null) {
    minimumSavingPlanSize.value = profile.minimumSavingPlanSize
  }
  if (profile?.minimumRebalancingAmount !== undefined && profile?.minimumRebalancingAmount !== null) {
    minimumRebalancingAmount.value = profile.minimumRebalancingAmount
  }
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
  if (!customOverridesEnabled.value && profile?.acceptableVariancePct) {
    variance.value = profile.acceptableVariancePct
  }
  if (!customOverridesEnabled.value && profile?.minimumSavingPlanSize !== undefined && profile?.minimumSavingPlanSize !== null) {
    minimumSavingPlanSize.value = profile.minimumSavingPlanSize
  }
  if (!customOverridesEnabled.value && profile?.minimumRebalancingAmount !== undefined && profile?.minimumRebalancingAmount !== null) {
    minimumRebalancingAmount.value = profile.minimumRebalancingAmount
  }
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

onMounted(loadConfig)
</script>
