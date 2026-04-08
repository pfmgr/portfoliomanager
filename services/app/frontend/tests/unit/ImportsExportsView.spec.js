import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ImportsExportsView from '../../src/views/ImportsExportsView.vue'
import { apiDownload, apiRequest, apiUpload } from '../../src/api'

vi.mock('../../src/api', () => ({
  apiDownload: vi.fn(),
  apiRequest: vi.fn(),
  apiUpload: vi.fn()
}))

const flushPromises = () => new Promise((resolve) => setTimeout(resolve, 0))

describe('ImportsExportsView', () => {
  beforeEach(() => {
    apiRequest.mockReset()
    apiDownload.mockReset()
    apiUpload.mockReset()
    apiRequest.mockResolvedValue([
      { depotId: 1, depotCode: 'deka', name: 'Deka Depot' }
    ])
    apiDownload.mockResolvedValue({
      blob: async () => new Blob(['backup']),
      headers: { get: vi.fn(() => 'attachment; filename=backup.pmbk') }
    })
    apiUpload.mockResolvedValue({ tablesImported: 5, rowsImported: 123, formatVersion: 2 })
    Object.defineProperty(window.URL, 'createObjectURL', { value: vi.fn(() => 'blob:backup'), writable: true, configurable: true })
    Object.defineProperty(window.URL, 'revokeObjectURL', { value: vi.fn(), writable: true, configurable: true })
  })

  it('shows full backup warnings and password help', async () => {
    const wrapper = mount(ImportsExportsView)
    await flushPromises()

    expect(wrapper.text()).toContain('at least 12 characters to create a protected backup')
    expect(wrapper.text()).toContain('enter the matching password before importing')
    expect(wrapper.text()).toContain('Password-protected backups require the matching password')
    expect(wrapper.text()).toContain('do not include LLM configuration or API keys')
  })

  it('exports a backup with a POST body password', async () => {
    const wrapper = mount(ImportsExportsView)
    await flushPromises()

    await wrapper.find('input[type="password"]').setValue('backup-secret-123')
    const backupSection = wrapper.findAll('.section')[2]
    await backupSection.get('button').trigger('click')
    await flushPromises()

    expect(apiDownload).toHaveBeenCalledWith('/backups/export', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ password: 'backup-secret-123' })
    }))
    expect(wrapper.text()).toContain('Backup exported with password protection (format v2).')
  })

  it('imports encrypted backups with an optional password', async () => {
    const wrapper = mount(ImportsExportsView)
    await flushPromises()

    const backupSection = wrapper.findAll('.section')[2]
    await backupSection.find('input[type="checkbox"]').setValue(true)
    await wrapper.find('input[type="password"]').setValue('backup-secret-123')

    const backupFileInput = backupSection.find('input[accept*=".pmbk"]')
    const file = new File(['backup'], 'database-backup.pmbk', { type: 'application/zip' })
    Object.defineProperty(backupFileInput.element, 'files', { value: [file], configurable: true })
    await backupFileInput.trigger('change')
    await flushPromises()

    const formData = apiUpload.mock.calls[0][1]
    const entries = Array.from(formData.entries())
    expect(entries.find(([name]) => name === 'password')?.[1]).toBe('backup-secret-123')
    expect(wrapper.text()).toContain('Backup imported: tables=5, rows=123, format=v2.')
  })

  it('imports plaintext backups without a password', async () => {
    const wrapper = mount(ImportsExportsView)
    await flushPromises()

    const backupSection = wrapper.findAll('.section')[2]
    await backupSection.find('input[type="checkbox"]').setValue(true)

    const backupFileInput = backupSection.find('input[accept*=".pmbk"]')
    const file = new File(['backup'], 'database-backup.pmbk', { type: 'application/octet-stream' })
    Object.defineProperty(backupFileInput.element, 'files', { value: [file], configurable: true })
    await backupFileInput.trigger('change')
    await flushPromises()

    const formData = apiUpload.mock.calls[0][1]
    const entries = Array.from(formData.entries())
    expect(entries.some(([name]) => name === 'password')).toBe(false)
    expect(wrapper.text()).toContain('Backup imported: tables=5, rows=123, format=v2.')
  })
})
