import { mount } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import ProfileConfigurationView from '../../src/views/ProfileConfigurationView.vue'
import { apiRequest } from '../../src/api'

vi.mock('../../src/api', () => ({
  apiRequest: vi.fn()
}))

const flushPromises = () => new Promise((resolve) => setTimeout(resolve, 0))

const layerTargetsResponse = {
  activeProfileKey: 'BALANCED',
  profiles: {
    BALANCED: {
      displayName: 'Balanced',
      layerTargets: { 1: 0.7, 2: 0.2, 3: 0.08, 4: 0.02, 5: 0 },
      acceptableVariancePct: 3.0,
      minimumSavingPlanSize: 15,
      minimumRebalancingAmount: 10
    }
  },
  layerNames: { 1: 'Global Core', 2: 'Core-Plus', 3: 'Themes', 4: 'Individual Stocks', 5: 'Unclassified' },
  effectiveLayerTargets: { 1: 0.7, 2: 0.2, 3: 0.08, 4: 0.02, 5: 0 },
  acceptableVariancePct: 3.0,
  minimumSavingPlanSize: 15,
  minimumRebalancingAmount: 10,
  customOverridesEnabled: false
}

const qualityGatesResponse = {
  quality_gate_profiles: {
    active_profile: 'BALANCED',
    profiles: {
      BALANCED: {
        display_name: 'Balanced',
        layer_profiles: { 1: 'FUND', 2: 'FUND', 3: 'FUND', 4: 'EQUITY', 5: 'UNKNOWN' },
        evidence_profiles: { FUND: ['price'], EQUITY: ['price'], REIT: ['price'], UNKNOWN: ['price'] }
      }
    }
  }
}

function llmResponse(overrides = {}) {
  return {
    editable: true,
    standard: {
      provider: 'openai',
      base_url: 'https://api.openai.com/v1',
      model: 'gpt-4o-mini',
      api_key_set: true
    },
    websearch: { mode: 'STANDARD', provider: 'openai', base_url: 'https://api.openai.com/v1', model: 'gpt-4o-mini', api_key_set: true, enabled: true },
    extraction: { mode: 'STANDARD', provider: 'openai', base_url: 'https://api.openai.com/v1', model: 'gpt-4o-mini', api_key_set: true, enabled: true },
    narrative: { mode: 'STANDARD', provider: 'openai', base_url: 'https://api.openai.com/v1', model: 'gpt-4o-mini', api_key_set: true, enabled: true },
    ...overrides
  }
}

function mockApi({ llmConfig = llmResponse(), putLlmConfig = llmConfig } = {}) {
  apiRequest.mockImplementation((path, options = {}) => {
    if (path === '/layer-targets') return Promise.resolve(layerTargetsResponse)
    if (path === '/kb/config') return Promise.resolve(qualityGatesResponse)
    if (path === '/llm/config' && !options.method) return Promise.resolve(llmConfig)
    if (path === '/llm/config' && options.method === 'PUT') return Promise.resolve(putLlmConfig)
    return Promise.resolve({})
  })
}

describe('ProfileConfigurationView', () => {
  beforeEach(() => {
    apiRequest.mockReset()
  })

  it('loads allocation tab and includes LLM tab', async () => {
    mockApi()
    const wrapper = mount(ProfileConfigurationView)
    await flushPromises()

    expect(wrapper.text()).toContain('Profile Configuration')
    expect(wrapper.text()).toContain('Allocation & limits')
    expect(wrapper.text()).toContain('LLM-Konfiguration')
  })

  it('shows configured key status and keeps key input write-only after save', async () => {
    mockApi()
    const wrapper = mount(ProfileConfigurationView)
    await flushPromises()

    const llmTab = wrapper.findAll('.tab-button').find((tab) => tab.text() === 'LLM-Konfiguration')
    await llmTab.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('API key configured: Yes')
    const keyInput = wrapper.find('input[type="password"]')
    expect(keyInput.element.value).toBe('')

    await keyInput.setValue('new-secret-key')
    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('Save LLM configuration'))
    await saveButton.trigger('click')
    await flushPromises()

    const putCall = apiRequest.mock.calls.find(([path, options]) => path === '/llm/config' && options?.method === 'PUT')
    const payload = JSON.parse(putCall[1].body)
    expect(payload.standard.api_key).toBe('new-secret-key')
    expect(wrapper.find('input[type="password"]').element.value).toBe('')
  })

  it('shows backup warning copy on the LLM tab', async () => {
    mockApi()
    const wrapper = mount(ProfileConfigurationView)
    await flushPromises()

    const llmTab = wrapper.findAll('.tab-button').find((tab) => tab.text() === 'LLM-Konfiguration')
    await llmTab.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Full database backups include this LLM configuration.')
    expect(wrapper.text()).toContain('Exported backups currently contain LLM API keys in plaintext.')
  })

  it('disables editing when backend marks config as read-only', async () => {
    mockApi({
      llmConfig: llmResponse({
        editable: false,
        editable_reason: 'Missing encryption password on backend.'
      })
    })
    const wrapper = mount(ProfileConfigurationView)
    await flushPromises()

    const llmTab = wrapper.findAll('.tab-button').find((tab) => tab.text() === 'LLM-Konfiguration')
    await llmTab.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Missing encryption password on backend.')
    expect(wrapper.find('input.input').element.disabled).toBe(true)
    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('Save LLM configuration'))
    expect(saveButton.element.disabled).toBe(true)
  })

  it('marks STANDARD function as disabled when standard key is missing', async () => {
    mockApi({
      llmConfig: llmResponse({
        standard: {
          provider: 'openai',
          base_url: 'https://api.openai.com/v1',
          model: 'gpt-4o-mini',
          api_key_set: false
        },
        websearch: { mode: 'STANDARD', provider: 'openai', base_url: 'https://api.openai.com/v1', model: 'gpt-4o-mini', api_key_set: false, enabled: true },
        extraction: { mode: 'CUSTOM', provider: 'openai', base_url: 'https://api.openai.com/v1', model: 'gpt-4o-mini', api_key_set: true, enabled: true },
        narrative: { mode: 'STANDARD', provider: 'openai', base_url: 'https://api.openai.com/v1', model: 'gpt-4o-mini', api_key_set: false, enabled: true }
      })
    })
    const wrapper = mount(ProfileConfigurationView)
    await flushPromises()

    const llmTab = wrapper.findAll('.tab-button').find((tab) => tab.text() === 'LLM-Konfiguration')
    await llmTab.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Standard API key is not configured.')
    expect(wrapper.text()).toContain('Effective: Disabled')
  })

  it('switching CUSTOM to STANDARD and back to CUSTOM does not clear key implicitly', async () => {
    mockApi({
      llmConfig: llmResponse({
        websearch: { mode: 'CUSTOM', provider: 'openai', base_url: 'https://api.openai.com/v1', model: 'gpt-4o-mini', api_key_set: true, enabled: true },
        extraction: { mode: 'STANDARD', provider: 'openai', base_url: 'https://api.openai.com/v1', model: 'gpt-4o-mini', api_key_set: false, enabled: true },
        narrative: { mode: 'STANDARD', provider: 'openai', base_url: 'https://api.openai.com/v1', model: 'gpt-4o-mini', api_key_set: false, enabled: true }
      })
    })
    const wrapper = mount(ProfileConfigurationView)
    await flushPromises()

    const llmTab = wrapper.findAll('.tab-button').find((tab) => tab.text() === 'LLM-Konfiguration')
    await llmTab.trigger('click')
    await flushPromises()

    const modeSelects = wrapper.findAll('select.input')
    const websearchModeSelect = modeSelects.find((entry) => ['STANDARD', 'CUSTOM'].includes(entry.element.value))
    await websearchModeSelect.setValue('STANDARD')
    await websearchModeSelect.setValue('CUSTOM')

    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('Save LLM configuration'))
    await saveButton.trigger('click')
    await flushPromises()

    const putCall = apiRequest.mock.calls.find(([path, options]) => path === '/llm/config' && options?.method === 'PUT')
    const payload = JSON.parse(putCall[1].body)
    expect(payload.websearch.mode).toBe('CUSTOM')
    expect(payload.websearch.api_key).toBeNull()
  })
})
