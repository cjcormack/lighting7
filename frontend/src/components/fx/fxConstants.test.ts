import { describe, expect, it } from 'vitest'
import { elementModeSummary } from './fxConstants'

describe('elementModeSummary', () => {
  it('words Over so a summary never reads it as a target', () => {
    expect(['WHOLE', 'FLAT', 'PER_FIXTURE'].map(elementModeSummary)).toEqual(['over fixtures', 'over elements', 'each fixture'])
  })
})
