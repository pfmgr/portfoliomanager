import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ImportsExportsView from '../../src/views/ImportsExportsView.vue'
import { apiRequest } from '../../src/api'

vi.mock('../../src/api', () => ({
  apiDownload: vi.fn(),
  apiRequest: vi.fn(),
  apiUpload: vi.fn()
}))

const flushPromises = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('ImportsExportsView', () => {
  beforeEach(() => {
    apiRequest.mockReset()
    apiRequest.mockResolvedValue([
      { depotId: 1, depotCode: 'deka', name: 'Deka Depot' }
    ])
  })

  it('shows full backup warnings and KB backup exclusions', async () => {
    const wrapper = mount(ImportsExportsView)
    await flushPromises()

    expect(wrapper.text()).toContain('include unencrypted LLM API keys')
    expect(wrapper.text()).toContain('Store them securely and do not share them.')
    expect(wrapper.text()).toContain('Older backups without saved LLM settings leave the current LLM configuration unchanged.')
    expect(wrapper.text()).toContain('do not include LLM configuration or API keys')
    expect(wrapper.text()).toContain('I understand that importing a full backup may replace LLM configuration and API keys when they are present in the backup.')
  })
})
