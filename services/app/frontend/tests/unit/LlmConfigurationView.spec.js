import { mount } from '@vue/test-utils'
import { beforeEach, describe, it, expect, vi } from 'vitest'
import LlmConfigurationView from '../../src/views/LlmConfigurationView.vue'
import { apiRequest } from '../../src/api'

vi.mock('../../src/api', () => ({
  apiRequest: vi.fn()
}))

const flushPromises = () => new Promise((resolve) => setTimeout(resolve, 0))

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
    if (path === '/llm/config' && !options.method) return Promise.resolve(llmConfig)
    if (path === '/llm/config' && options.method === 'PUT') return Promise.resolve(putLlmConfig)
    return Promise.resolve({})
  })
}

describe('LlmConfigurationView', () => {
  beforeEach(() => {
    apiRequest.mockReset()
  })

  it('keeps API key editors collapsed by default and replaces the standard key only after opening the editor', async () => {
    mockApi()
    const wrapper = mount(LlmConfigurationView)
    await flushPromises()

    expect(wrapper.text()).toContain('LLM Configuration')
    expect(wrapper.text()).toContain('API key configured: Yes')
    expect(wrapper.find('#standard-api-key-editor').exists()).toBe(false)

    const openButton = wrapper.findAll('button').find((button) => button.text() === 'Replace API key')
    await openButton.trigger('click')
    await flushPromises()

    const keyInput = wrapper.find('#standard-api-key-editor input[type="password"]')
    await keyInput.setValue('new-secret-key')
    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('Save LLM configuration'))
    await saveButton.trigger('click')
    await flushPromises()

    const putCall = apiRequest.mock.calls.find(([path, options]) => path === '/llm/config' && options?.method === 'PUT')
    const payload = JSON.parse(putCall[1].body)
    expect(payload.standard.api_key).toBe('new-secret-key')
    expect(wrapper.find('#standard-api-key-editor').exists()).toBe(false)
  })

  it('does not change the standard key after opening and cancelling the editor', async () => {
    mockApi()
    const wrapper = mount(LlmConfigurationView)
    await flushPromises()

    const openButton = wrapper.findAll('button').find((button) => button.text() === 'Replace API key')
    await openButton.trigger('click')
    await flushPromises()
    await wrapper.find('#standard-api-key-editor input[type="password"]').setValue('temporary-secret')

    const cancelButton = wrapper.findAll('#standard-api-key-editor button').find((button) => button.text() === 'Cancel')
    await cancelButton.trigger('click')
    await flushPromises()

    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('Save LLM configuration'))
    await saveButton.trigger('click')
    await flushPromises()

    const putCall = apiRequest.mock.calls.find(([path, options]) => path === '/llm/config' && options?.method === 'PUT')
    const payload = JSON.parse(putCall[1].body)
    expect(payload.standard.api_key).toBeNull()
  })

  it('keeps the existing standard key when the editor is never opened', async () => {
    mockApi()
    const wrapper = mount(LlmConfigurationView)
    await flushPromises()

    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('Save LLM configuration'))
    await saveButton.trigger('click')
    await flushPromises()

    const putCall = apiRequest.mock.calls.find(([path, options]) => path === '/llm/config' && options?.method === 'PUT')
    const payload = JSON.parse(putCall[1].body)
    expect(payload.standard.api_key).toBeNull()
  })

  it('removes the standard key only through the explicit remove action', async () => {
    mockApi()
    const wrapper = mount(LlmConfigurationView)
    await flushPromises()

    const removeButton = wrapper.findAll('button').find((button) => button.text() === 'Mark API key for removal')
    await removeButton.trigger('click')
    await flushPromises()

    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('Save LLM configuration'))
    await saveButton.trigger('click')
    await flushPromises()

    const putCall = apiRequest.mock.calls.find(([path, options]) => path === '/llm/config' && options?.method === 'PUT')
    const payload = JSON.parse(putCall[1].body)
    expect(payload.standard.api_key).toBe('')
  })

  it('shows a warning when switching a custom function back to standard mode', async () => {
    mockApi({
      llmConfig: llmResponse({
        narrative: {
          mode: 'CUSTOM',
          provider: 'openai',
          base_url: 'https://api.openai.com/v1',
          model: 'gpt-4o-mini',
          api_key_set: true,
          enabled: true
        }
      })
    })
    const wrapper = mount(LlmConfigurationView)
    await flushPromises()

    const selects = wrapper.findAll('select.input')
    const narrativeSelect = selects[2]
    await narrativeSelect.setValue('STANDARD')
    await flushPromises()

    expect(wrapper.text()).toContain('Saving this change will remove the saved custom API key.')

    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('Save LLM configuration'))
    await saveButton.trigger('click')
    await flushPromises()

    const putCall = apiRequest.mock.calls.find(([path, options]) => path === '/llm/config' && options?.method === 'PUT')
    const payload = JSON.parse(putCall[1].body)
    expect(payload.narrative.api_key).toBe('')
  })

  it('replaces a function-specific API key only after opening its editor', async () => {
    mockApi({
      llmConfig: llmResponse({
        websearch: {
          mode: 'CUSTOM',
          provider: 'openai',
          base_url: 'https://api.openai.com/v1',
          model: 'gpt-4o-mini',
          api_key_set: true,
          enabled: true
        }
      })
    })
    const wrapper = mount(LlmConfigurationView)
    await flushPromises()

    const functionCards = wrapper.findAll('.llm-function-card')
    const websearchCard = functionCards.find((card) => card.text().includes('Websearch'))
    const replaceButton = websearchCard.findAll('button').find((button) => button.text() === 'Replace API key')
    await replaceButton.trigger('click')
    await flushPromises()

    const functionEditor = websearchCard.find('#websearch-api-key-editor input[type="password"]')
    await functionEditor.setValue('custom-secret-key')

    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('Save LLM configuration'))
    await saveButton.trigger('click')
    await flushPromises()

    const putCall = apiRequest.mock.calls.find(([path, options]) => path === '/llm/config' && options?.method === 'PUT')
    const payload = JSON.parse(putCall[1].body)
    expect(payload.websearch.api_key).toBe('custom-secret-key')
  })

  it('keeps a function-specific key unchanged when its editor stays closed', async () => {
    mockApi({
      llmConfig: llmResponse({
        websearch: {
          mode: 'CUSTOM',
          provider: 'openai',
          base_url: 'https://api.openai.com/v1',
          model: 'gpt-4o-mini',
          api_key_set: true,
          enabled: true
        }
      })
    })
    const wrapper = mount(LlmConfigurationView)
    await flushPromises()

    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('Save LLM configuration'))
    await saveButton.trigger('click')
    await flushPromises()

    const putCall = apiRequest.mock.calls.find(([path, options]) => path === '/llm/config' && options?.method === 'PUT')
    const payload = JSON.parse(putCall[1].body)
    expect(payload.websearch.api_key).toBeNull()
  })

  it('shows read-only state when backend disables editing', async () => {
    mockApi({ llmConfig: llmResponse({ editable: false, editable_reason: 'Missing encryption password on backend.' }) })
    const wrapper = mount(LlmConfigurationView)
    await flushPromises()

    expect(wrapper.text()).toContain('Missing encryption password on backend.')
    expect(wrapper.find('input.input').element.disabled).toBe(true)
  })
})
