<template>
  <div>
    <div class="card">
      <h2>LLM Configuration</h2>
      <p>Configure the standard LLM and optional custom setups for individual functions.</p>
      <p class="note small">API keys are write-only. Edit them only when needed, then save the configuration.</p>
      <p class="toast error">
        Full database backups include this LLM configuration. Exported backups currently contain LLM API keys in
        plaintext.
      </p>
      <p v-if="llmLoading" class="note" role="status" aria-live="polite">Loading LLM configuration...</p>
      <p v-if="llmMessage" :class="['toast', llmMessageType]" :role="llmMessageType === 'error' ? 'alert' : 'status'" :aria-live="llmMessageType === 'error' ? 'assertive' : 'polite'">{{ llmMessage }}</p>
      <p v-if="!llmEditable" class="toast error" role="alert">
        {{ llmEditableReason }}
      </p>

      <h3>Standard configuration</h3>
      <div class="grid grid-2">
        <label class="field">
          <span>Provider</span>
          <input v-model="llmStandard.provider" class="input" :disabled="!canEditLlm" />
        </label>
        <label class="field">
          <span>Base URL</span>
          <input v-model="llmStandard.baseUrl" class="input" :disabled="!canEditLlm" />
        </label>
        <label class="field">
          <span>Model</span>
          <input v-model="llmStandard.model" class="input" :disabled="!canEditLlm" />
        </label>
      </div>
      <div class="llm-status-row">
        <span :class="['llm-badge', standardApiKeyBadgeClass]">
          {{ standardApiKeyBadgeText }}
        </span>
        <button
          type="button"
          class="secondary"
          :ref="(element) => registerKeyButtonRef('standard', element)"
          :aria-expanded="String(llmStandard.apiKeyEditorOpen)"
          aria-controls="standard-api-key-editor"
          :disabled="!canEditLlm"
          @click="openStandardApiKeyEditor"
        >
          {{ llmStandard.apiKeyConfigured ? 'Edit API key' : 'Set API key' }}
        </button>
        <button
          v-if="llmStandard.apiKeyConfigured"
          type="button"
          class="secondary"
          :disabled="!canEditLlm"
          @click="llmStandard.clearApiKey ? keepStandardApiKey() : removeStandardApiKey()"
        >
          {{ llmStandard.clearApiKey ? 'Undo remove API key' : 'Mark API key for removal' }}
        </button>
      </div>
      <p v-if="llmStandard.clearApiKey" class="note small" role="status" aria-live="polite">
        The current API key will be removed when you save this configuration.
      </p>
      <div v-if="llmStandard.apiKeyEditorOpen" id="standard-api-key-editor" class="secret-editor">
        <label class="field">
          <span>API key</span>
          <input
            ref="standardApiKeyInput"
            v-model="llmStandard.apiKeyInput"
            class="input"
            type="password"
            autocomplete="new-password"
            :disabled="!canEditLlm"
            @input="llmStandard.clearApiKey = false"
          />
        </label>
        <div class="actions compact-actions">
          <button type="button" class="secondary" :disabled="!canEditLlm" @click="cancelStandardApiKeyEdit">
            Cancel
          </button>
        </div>
      </div>

      <h3>Function configurations</h3>
      <div v-for="functionKey in llmFunctionKeys" :key="functionKey" class="llm-function-card">
        <h4>{{ llmFunctionLabel(functionKey) }}</h4>
        <label class="field">
          <span>Mode</span>
          <select
            class="input"
            :disabled="!canEditLlm"
            :value="llmFunctions[functionKey].mode"
            @input="setFunctionMode(functionKey, $event.target.value)"
            @change="setFunctionMode(functionKey, $event.target.value)"
          >
            <option value="STANDARD">Use standard configuration</option>
            <option value="CUSTOM">Use custom configuration</option>
          </select>
        </label>

        <div class="llm-status-row">
          <span :class="['llm-badge', functionStatuses[functionKey].badgeClass]">
            {{ functionStatuses[functionKey].label }}
          </span>
          <span class="note" v-if="functionStatuses[functionKey].note">
            {{ functionStatuses[functionKey].note }}
          </span>
        </div>

        <p v-if="llmFunctions[functionKey].mode === 'STANDARD'" class="note small">
          Uses the saved standard provider, model, base URL, and API key.
        </p>
        <p v-if="showsCustomKeyRemovalWarning(functionKey)" class="toast error">
          Saving this change will remove the saved custom API key.
        </p>

        <div v-if="llmFunctions[functionKey].mode === 'CUSTOM'" class="grid grid-2">
          <label class="field">
            <span>Provider</span>
            <input v-model="llmFunctions[functionKey].custom.provider" class="input" :disabled="!canEditLlm" />
          </label>
          <label class="field">
            <span>Base URL</span>
            <input v-model="llmFunctions[functionKey].custom.baseUrl" class="input" :disabled="!canEditLlm" />
          </label>
          <label class="field">
            <span>Model</span>
            <input v-model="llmFunctions[functionKey].custom.model" class="input" :disabled="!canEditLlm" />
          </label>
        </div>
        <div v-if="llmFunctions[functionKey].mode === 'CUSTOM'" class="llm-status-row custom-key-actions">
          <span :class="['llm-badge', functionApiKeyBadgeClass(functionKey)]">
            {{ functionApiKeyBadgeText(functionKey) }}
          </span>
          <button
            type="button"
            class="secondary"
            :ref="(element) => registerKeyButtonRef(functionKey, element)"
            :aria-expanded="String(llmFunctions[functionKey].custom.apiKeyEditorOpen)"
            :aria-controls="`${functionKey}-api-key-editor`"
            :disabled="!canEditLlm"
            @click="openFunctionApiKeyEditor(functionKey)"
          >
            {{ llmFunctions[functionKey].custom.apiKeyConfigured ? 'Edit API key' : 'Set API key' }}
          </button>
          <button
            v-if="llmFunctions[functionKey].custom.apiKeyConfigured"
            type="button"
            class="secondary"
            :disabled="!canEditLlm"
            @click="llmFunctions[functionKey].custom.clearApiKey ? keepFunctionApiKey(functionKey) : removeFunctionApiKey(functionKey)"
          >
            {{ llmFunctions[functionKey].custom.clearApiKey ? 'Undo remove API key' : 'Mark API key for removal' }}
          </button>
        </div>
        <p v-if="llmFunctions[functionKey].custom.clearApiKey" class="note small" role="status" aria-live="polite">
          The current custom API key will be removed when you save this configuration.
        </p>
        <div
          v-if="llmFunctions[functionKey].mode === 'CUSTOM' && llmFunctions[functionKey].custom.apiKeyEditorOpen"
          :id="`${functionKey}-api-key-editor`"
          class="secret-editor"
        >
          <label class="field">
            <span>API key</span>
            <input
              :ref="(element) => registerFunctionApiKeyInput(functionKey, element)"
              v-model="llmFunctions[functionKey].custom.apiKeyInput"
              class="input"
              type="password"
              autocomplete="new-password"
              :disabled="!canEditLlm"
              @input="llmFunctions[functionKey].custom.clearApiKey = false"
            />
          </label>
          <div class="actions compact-actions">
            <button type="button" class="secondary" :disabled="!canEditLlm" @click="cancelFunctionApiKeyEdit(functionKey)">
              Cancel
            </button>
          </div>
        </div>
      </div>

      <div class="actions" style="margin-top: 1rem;">
        <button class="primary" @click="saveLlmConfig" :disabled="llmSaving || llmLoading || !llmEditable">
          {{ llmSaving ? 'Saving…' : 'Save LLM configuration' }}
        </button>
        <button class="secondary" @click="loadLlmConfig" :disabled="llmSaving || llmLoading">
          Reload
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { apiRequest } from '../api'

const llmFunctionKeys = ['websearch', 'extraction', 'narrative']
const LLM_ENDPOINT = '/llm/config'

const llmLoading = ref(false)
const llmSaving = ref(false)
const llmMessage = ref('')
const llmMessageType = ref('success')
const llmEditable = ref(true)
const llmEditableReason = ref('')
const llmStandard = ref(buildDefaultLlmStandard())
const llmFunctions = ref(buildDefaultLlmFunctions())
const standardApiKeyInput = ref(null)
const functionApiKeyInputs = new Map()
const keyActionButtons = new Map()

const canEditLlm = computed(() => llmEditable.value && !llmSaving.value && !llmLoading.value)
const standardApiKeyBadgeText = computed(() => {
  if (llmStandard.value.clearApiKey) {
    return 'API key will be removed on save'
  }
  return `API key configured: ${llmStandard.value.apiKeyConfigured ? 'Yes' : 'No'}`
})
const standardApiKeyBadgeClass = computed(() => (llmStandard.value.clearApiKey ? 'warn' : llmStandard.value.apiKeyConfigured ? 'ok' : 'warn'))
const functionStatuses = computed(() => {
  const statuses = {}
  llmFunctionKeys.forEach((key) => {
    statuses[key] = computeFunctionStatus(llmFunctions.value[key])
  })
  return statuses
})

onMounted(() => {
  loadLlmConfig()
})

async function loadLlmConfig() {
  llmLoading.value = true
  llmMessage.value = ''
  llmMessageType.value = 'success'
  try {
    const data = await apiRequest(LLM_ENDPOINT)
    applyLlmResponse(data)
  } catch (err) {
    llmMessageType.value = 'error'
    llmMessage.value = err?.message || 'Failed to load LLM configuration.'
  } finally {
    llmLoading.value = false
  }
}

async function saveLlmConfig() {
  llmSaving.value = true
  llmMessage.value = ''
  llmMessageType.value = 'success'
  try {
    const payload = buildLlmPayload()
    const result = await apiRequest(LLM_ENDPOINT, { method: 'PUT', body: JSON.stringify(payload) })
    if (result) {
      applyLlmResponse(result)
    } else {
      await loadLlmConfig()
    }
    llmMessage.value = 'LLM configuration saved.'
    clearLlmTransientKeyEditors()
  } catch (err) {
    llmMessageType.value = 'error'
    llmMessage.value = err?.message || 'Failed to save LLM configuration.'
    clearSecretInputsOnly()
  } finally {
    llmSaving.value = false
  }
}

function buildDefaultLlmStandard() {
  return {
    provider: '',
    baseUrl: '',
    model: '',
    apiKeyConfigured: false,
    apiKeyInput: '',
    clearApiKey: false,
    apiKeyEditorOpen: false
  }
}

function buildDefaultLlmCustomConfig() {
  return {
    provider: '',
    baseUrl: '',
    model: '',
    apiKeyConfigured: false,
    apiKeyInput: '',
    clearApiKey: false,
    apiKeyEditorOpen: false,
    savedProvider: '',
    savedBaseUrl: '',
    savedModel: '',
    savedApiKeyConfigured: false
  }
}

function buildDefaultLlmFunctions() {
  const output = {}
  llmFunctionKeys.forEach((key) => {
    output[key] = {
      mode: 'STANDARD',
      savedMode: 'STANDARD',
      custom: buildDefaultLlmCustomConfig(),
      effectiveEnabled: false,
      effectiveReason: ''
    }
  })
  return output
}

function applyLlmResponse(raw) {
  const data = raw || {}
  llmEditable.value = data.editable !== false
  llmEditableReason.value =
    data.editableReason ||
    data.editable_reason ||
    (llmEditable.value ? '' : 'LLM configuration is read-only because no encryption password is configured on the backend.')

  const standardRaw = data.standard || {}
  llmStandard.value = {
    provider: normalizeText(standardRaw.provider),
    baseUrl: normalizeText(standardRaw.baseUrl ?? standardRaw.base_url),
    model: normalizeText(standardRaw.model),
    apiKeyConfigured: Boolean(standardRaw.apiKeyConfigured ?? standardRaw.api_key_configured ?? standardRaw.api_key_set),
    apiKeyInput: '',
    clearApiKey: false,
    apiKeyEditorOpen: false
  }

  const functionsRaw = { websearch: data.websearch || {}, extraction: data.extraction || {}, narrative: data.narrative || {} }
  const nextFunctions = buildDefaultLlmFunctions()
  llmFunctionKeys.forEach((key) => {
    const functionRaw = functionsRaw[key] || {}
    const mode = normalizeLlmMode(functionRaw.mode)
    const customRaw = mode === 'CUSTOM' ? functionRaw : {}
    const effectiveEnabledFromBackend = functionRaw.enabled !== undefined ? Boolean(functionRaw.enabled) : undefined
    const fallbackDisabled = mode === 'STANDARD' && !llmStandard.value.apiKeyConfigured
    const effectiveEnabled = effectiveEnabledFromBackend !== undefined ? effectiveEnabledFromBackend : !fallbackDisabled
    const effectiveReason = normalizeText(functionRaw.disableReason ?? functionRaw.disable_reason ?? functionRaw.reason) || (fallbackDisabled ? 'Standard API key is not configured.' : '')

    nextFunctions[key] = {
      mode,
      savedMode: mode,
      custom: {
        provider: normalizeText(customRaw.provider),
        baseUrl: normalizeText(customRaw.baseUrl ?? customRaw.base_url),
        model: normalizeText(customRaw.model),
        apiKeyConfigured: Boolean(functionRaw.apiKeyConfigured ?? functionRaw.api_key_configured ?? functionRaw.api_key_set),
        apiKeyInput: '',
        clearApiKey: false,
        apiKeyEditorOpen: false,
        savedProvider: normalizeText(customRaw.provider),
        savedBaseUrl: normalizeText(customRaw.baseUrl ?? customRaw.base_url),
        savedModel: normalizeText(customRaw.model),
        savedApiKeyConfigured: Boolean(functionRaw.apiKeyConfigured ?? functionRaw.api_key_configured ?? functionRaw.api_key_set)
      },
      effectiveEnabled,
      effectiveReason
    }
  })
  llmFunctions.value = nextFunctions
}

function buildLlmPayload() {
  const standard = {
    provider: normalizeOptionalText(llmStandard.value.provider),
    base_url: normalizeOptionalText(llmStandard.value.baseUrl),
    model: normalizeOptionalText(llmStandard.value.model),
    api_key: buildStandardApiKeyPayload()
  }
  const functions = {}
  llmFunctionKeys.forEach((key) => {
    functions[key] = buildFunctionPayload(key)
  })
  return {
    standard,
    websearch: functions.websearch,
    extraction: functions.extraction,
    narrative: functions.narrative
  }
}

function buildFunctionPayload(functionKey) {
  const entry = llmFunctions.value[functionKey]
  return {
    mode: normalizeLlmMode(entry.mode),
    provider: normalizeOptionalText(entry.custom.provider),
    base_url: normalizeOptionalText(entry.custom.baseUrl),
    model: normalizeOptionalText(entry.custom.model),
    api_key: buildFunctionApiKeyPayload(functionKey)
  }
}

function buildStandardApiKeyPayload() {
  if (llmStandard.value.clearApiKey) {
    return ''
  }
  if (!llmStandard.value.apiKeyEditorOpen) {
    return null
  }
  return normalizeOptionalText(llmStandard.value.apiKeyInput)
}

function buildFunctionApiKeyPayload(functionKey) {
  const entry = llmFunctions.value[functionKey]
  if (!entry) {
    return null
  }
  if (entry.mode === 'STANDARD') {
    return entry.savedMode === 'CUSTOM' ? '' : null
  }
  if (entry.custom.clearApiKey) {
    return ''
  }
  if (!entry.custom.apiKeyEditorOpen) {
    return null
  }
  return normalizeOptionalText(entry.custom.apiKeyInput)
}

function clearLlmTransientKeyEditors() {
  clearSecretInputsOnly()
  llmStandard.value.apiKeyEditorOpen = false
  llmStandard.value.clearApiKey = false
  llmFunctionKeys.forEach((key) => {
    const entry = llmFunctions.value[key]
    entry.custom.apiKeyEditorOpen = false
    entry.custom.clearApiKey = false
    entry.savedMode = entry.mode
    entry.custom.savedProvider = entry.custom.provider
    entry.custom.savedBaseUrl = entry.custom.baseUrl
    entry.custom.savedModel = entry.custom.model
    entry.custom.savedApiKeyConfigured = entry.custom.apiKeyConfigured
  })
}

function clearSecretInputsOnly() {
  llmStandard.value.apiKeyInput = ''
  llmFunctionKeys.forEach((key) => {
    const entry = llmFunctions.value[key]
    entry.custom.apiKeyInput = ''
  })
}

function openStandardApiKeyEditor() {
  llmStandard.value.apiKeyEditorOpen = true
  llmStandard.value.clearApiKey = false
  focusStandardApiKeyInput()
}

function cancelStandardApiKeyEdit() {
  llmStandard.value.apiKeyInput = ''
  llmStandard.value.clearApiKey = false
  llmStandard.value.apiKeyEditorOpen = false
  focusKeyActionButton('standard')
}

function removeStandardApiKey() {
  llmStandard.value.apiKeyInput = ''
  llmStandard.value.clearApiKey = true
  llmStandard.value.apiKeyEditorOpen = false
}

function keepStandardApiKey() {
  llmStandard.value.clearApiKey = false
}

function setFunctionMode(functionKey, modeValue) {
  const entry = llmFunctions.value[functionKey]
  if (!entry) return
  const nextMode = normalizeLlmMode(modeValue)
  const nextCustom = { ...entry.custom }
  if (nextMode === 'STANDARD') {
    nextCustom.apiKeyInput = ''
    nextCustom.apiKeyEditorOpen = false
    nextCustom.clearApiKey = false
  }
  llmFunctions.value = {
    ...llmFunctions.value,
    [functionKey]: {
      ...entry,
      mode: nextMode,
      custom: nextCustom
    }
  }
}

function openFunctionApiKeyEditor(functionKey) {
  const entry = llmFunctions.value[functionKey]
  if (!entry) return
  entry.custom.apiKeyEditorOpen = true
  entry.custom.clearApiKey = false
  focusFunctionApiKeyInput(functionKey)
}

function cancelFunctionApiKeyEdit(functionKey) {
  const entry = llmFunctions.value[functionKey]
  if (!entry) return
  entry.custom.apiKeyInput = ''
  entry.custom.clearApiKey = false
  entry.custom.apiKeyEditorOpen = false
  focusKeyActionButton(functionKey)
}

function removeFunctionApiKey(functionKey) {
  const entry = llmFunctions.value[functionKey]
  if (!entry) return
  entry.custom.apiKeyInput = ''
  entry.custom.clearApiKey = true
  entry.custom.apiKeyEditorOpen = false
}

function keepFunctionApiKey(functionKey) {
  const entry = llmFunctions.value[functionKey]
  if (!entry) return
  entry.custom.clearApiKey = false
}

function registerFunctionApiKeyInput(functionKey, element) {
  if (element) {
    functionApiKeyInputs.set(functionKey, element)
  } else {
    functionApiKeyInputs.delete(functionKey)
  }
}

function registerKeyButtonRef(key, element) {
  if (element) {
    keyActionButtons.set(key, element)
  } else {
    keyActionButtons.delete(key)
  }
}

function focusStandardApiKeyInput() {
  nextTick(() => {
    standardApiKeyInput.value?.focus?.()
  })
}

function focusFunctionApiKeyInput(functionKey) {
  nextTick(() => {
    functionApiKeyInputs.get(functionKey)?.focus?.()
  })
}

function focusKeyActionButton(key) {
  nextTick(() => {
    keyActionButtons.get(key)?.focus?.()
  })
}

function llmFunctionLabel(functionKey) {
  if (functionKey === 'websearch') return 'Websearch'
  if (functionKey === 'extraction') return 'Extraction'
  if (functionKey === 'narrative') return 'Narrative'
  return functionKey
}

function computeFunctionStatus(entry) {
  if (!entry) {
    return { badgeClass: 'warn', label: 'Saved: standard configuration', note: '' }
  }
  if (!isFunctionDirty(entry)) {
    const savedLabel = entry.mode === 'CUSTOM' ? 'Saved: custom configuration' : 'Saved: standard configuration'
    return {
      badgeClass: entry.effectiveEnabled ? 'ok' : 'warn',
      label: savedLabel,
      note: entry.effectiveReason
    }
  }

  return {
    badgeClass: 'warn',
    label: entry.mode === 'CUSTOM' ? 'Pending: custom configuration' : 'Pending: standard configuration',
    note: 'Save to apply this change.'
  }
}

function isFunctionDirty(entry) {
  if (!entry) return false
  if (entry.mode !== entry.savedMode) return true
  const custom = entry.custom || {}
  return (
    normalizeText(custom.provider) !== normalizeText(custom.savedProvider) ||
    normalizeText(custom.baseUrl) !== normalizeText(custom.savedBaseUrl) ||
    normalizeText(custom.model) !== normalizeText(custom.savedModel) ||
    Boolean(custom.apiKeyConfigured) !== Boolean(custom.savedApiKeyConfigured) ||
    Boolean(custom.apiKeyInput) ||
    Boolean(custom.clearApiKey)
  )
}

function showsCustomKeyRemovalWarning(functionKey) {
  const entry = llmFunctions.value[functionKey]
  return Boolean(entry && entry.savedMode === 'CUSTOM' && entry.mode === 'STANDARD' && entry.custom.apiKeyConfigured)
}

function functionApiKeyBadgeText(functionKey) {
  const entry = llmFunctions.value[functionKey]
  if (!entry) {
    return 'Custom API key configured: No'
  }
  if (entry.custom.clearApiKey) {
    return 'Custom API key will be removed on save'
  }
  return `Custom API key configured: ${entry.custom.apiKeyConfigured ? 'Yes' : 'No'}`
}

function functionApiKeyBadgeClass(functionKey) {
  const entry = llmFunctions.value[functionKey]
  if (!entry) {
    return 'warn'
  }
  return entry.custom.clearApiKey ? 'warn' : entry.custom.apiKeyConfigured ? 'ok' : 'warn'
}

function normalizeText(value) {
  if (value === null || value === undefined) return ''
  return String(value)
}

function normalizeLlmMode(value) {
  return String(value || 'STANDARD').toUpperCase() === 'CUSTOM' ? 'CUSTOM' : 'STANDARD'
}

function normalizeOptionalText(value) {
  const normalized = normalizeText(value).trim()
  return normalized || null
}
</script>

<style scoped>
.llm-function-card {
  border: 1px solid #eee6d8;
  border-radius: 12px;
  padding: 0.9rem;
  margin-top: 1rem;
  background: #fcfaf6;
}

.llm-function-card h4 {
  margin: 0 0 0.6rem;
}

.llm-status-row {
  display: flex;
  flex-wrap: wrap;
  gap: 0.6rem;
  align-items: center;
  margin: 0.5rem 0 0.8rem;
}

.llm-badge {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 0.2rem 0.7rem;
  border: 1px solid #ddd0b7;
  font-size: 0.82rem;
}

.llm-badge.ok {
  background: #e7f6ea;
  border-color: #b8dfc0;
}

.llm-badge.warn {
  background: #f9ede8;
  border-color: #efc2b6;
}

.secret-editor {
  border: 1px dashed #ddd0b7;
  border-radius: 12px;
  padding: 0.9rem;
  margin: 0.5rem 0 1rem;
  background: #fffdfa;
}

.compact-actions {
  margin-top: 0.5rem;
}

.custom-key-actions {
  margin-top: 0.75rem;
}
</style>
