import { describe, expect, it } from 'vitest'

const SOURCES = import.meta.glob<string>(['/src/**/*.ts', '/src/**/*.tsx'], { query: '?raw', import: 'default', eager: true })

describe('lib/sentry', () => {
  it('is imported by main.tsx alone, so no test can start Sentry', () => {
    const importers = Object.entries(SOURCES)
      .filter(([, text]) => /from\s+['"](?:\.{1,2}\/(?:\.\.\/)*lib|@\/lib|\.)\/sentry['"]/.test(text))
      .map(([path]) => path)
    expect(Object.keys(SOURCES).length).toBeGreaterThan(500)
    expect(importers).toEqual(['/src/main.tsx'])
  })
})
