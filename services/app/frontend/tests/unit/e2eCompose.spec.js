import { describe, it, expect } from 'vitest'
import { readFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const resolveComposePath = () => {
  if (import.meta.url.startsWith('file://')) {
    const currentFile = fileURLToPath(import.meta.url)
    return resolve(dirname(currentFile), '../../docker-compose.yml')
  }
  return resolve(process.cwd(), 'docker-compose.yml')
}

describe('e2e docker compose configuration', () => {
  it('requires external JWT secrets for backend startup', async () => {
    const compose = await readFile(resolveComposePath(), 'utf8')

    expect(compose).toMatch(/^\s*JWT_SECRET:\s*\$\{JWT_SECRET:\?required\}\s*$/m)
    expect(compose).toMatch(/^\s*JWT_JTI_HASH_SECRET:\s*\$\{JWT_JTI_HASH_SECRET:\?required\}\s*$/m)
  })
})
