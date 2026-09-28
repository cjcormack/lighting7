import { describe, expect, it } from 'vitest'
import { describeSkips, MASK_GROUPS } from './maskPicker'
import { ATTRIBUTE_FAMILIES, FAMILY_LABELS } from '@/lib/attributeFamily'
import type { ProgrammerSkip } from '@/store/programmerOps'

const skip = (reason: ProgrammerSkip['reason'], targetKey = 'hex-1'): ProgrammerSkip => ({
  targetKey,
  propertyName: 'dimmer',
  reason,
})

describe('describeSkips', () => {
  it('says nothing when nothing was skipped', () => {
    expect(describeSkips([])).toBeNull()
  })

  it('groups by reason and counts', () => {
    const note = describeSkips([
      skip('MASKED_OUT', 'hex-1'),
      skip('MASKED_OUT', 'hex-2'),
      skip('MISSING_FIXTURE', 'gone-1'),
    ])
    expect(note).toContain('2 entries outside the attribute mask')
    expect(note).toContain('1 fixtures no longer in the patch')
  })

  it('names the unrecordable raw-channel case', () => {
    // The one skip an operator can't fix by re-recording: a channel with no backing property
    // has no (target, property) a cue assignment could name.
    expect(describeSkips([{ universe: 0, channel: 100, reason: 'NO_BACKING_PROPERTY' }])).toContain(
      'raw channels with no backing property',
    )
  })
})

describe('mask groups vs attribute families', () => {
  it('offers exactly the four attribute families, in the same order', () => {
    // Not a coincidence to be kept in step by hand — an attribute family *is* a
    // `PropertyMaskGroup` on the backend. A COLOUR-masked layer asserts exactly what a COLOUR
    // family covers, so if these two lists ever diverged one of them would be describing a thing
    // that doesn't exist.
    expect(MASK_GROUPS.map((group) => group.value)).toEqual([...ATTRIBUTE_FAMILIES])
  })

  it('labels them the same way the look library does', () => {
    for (const group of MASK_GROUPS) {
      expect(group.label).toBe(FAMILY_LABELS[group.value].singular)
    }
  })
})
