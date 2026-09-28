import { describe, expect, it } from 'vitest'
import { canClaimInSync, resolveProgrammerSource } from './programmerSource'
import type { ProgrammerSourceInput } from './programmerSource'

const base: ProgrammerSourceInput = {
  target: null,
  entryCount: 0,
  programmerFxCount: 0,
  dirty: null,
}

const CUE = { kind: 'CUE', cueId: 5, cueStackId: 2, cueNumber: 'Q4', cueName: 'Warm Wash' } as const

describe('resolveProgrammerSource', () => {
  it('is empty with no target, no values and no effects', () => {
    expect(resolveProgrammerSource(base)).toEqual({ kind: 'empty' })
  })

  it('is busking, not empty, when only an effect is running', () => {
    // A busking pad can leave an effect with no value entry behind it. Calling that empty would
    // offer Record on nothing while the rig is visibly doing something.
    expect(resolveProgrammerSource({ ...base, programmerFxCount: 1 })).toEqual({
      kind: 'busking',
      valueCount: 0,
    })
  })

  it('is busking with values and no source', () => {
    expect(resolveProgrammerSource({ ...base, entryCount: 12 })).toEqual({
      kind: 'busking',
      valueCount: 12,
    })
  })

  it('names the cue, its stack and its position', () => {
    expect(
      resolveProgrammerSource({
        ...base,
        target: CUE,
        entryCount: 9,
        dirty: 3,
        cueLocation: { stackName: 'Act 1', position: { index: 4, total: 14 } },
      }),
    ).toEqual({
      kind: 'cue',
      number: 'Q4',
      name: 'Warm Wash',
      stackName: 'Act 1',
      position: { index: 4, total: 14 },
      dirty: 3,
      missing: false,
    })
  })

  it('marks a cue missing when it has gone from the stack list', () => {
    // The one ambient conflict the client CAN see: someone deleted the cue being edited.
    const result = resolveProgrammerSource({ ...base, target: CUE, entryCount: 9, cueLocation: null })
    expect(result).toMatchObject({ kind: 'cue', missing: true })
  })

  it('carries a Look’s derived families', () => {
    expect(
      resolveProgrammerSource({
        ...base,
        target: { kind: 'LOOK', lookId: 2, lookName: 'Amber Key' },
        entryCount: 4,
        dirty: 1,
        lookFamilies: 'Colour',
      }),
    ).toEqual({ kind: 'look', name: 'Amber Key', families: 'Colour', dirty: 1, missing: false })
  })

  // A case pinning that a stale `PALETTE` target resolved to a named `look` source stood here. The
  // arm it covered is gone: `IncludedTargetDto` dropped its `palette*` fields with the palette
  // tables, so the frame it fed cannot arrive and the name it asserted cannot be sent.
})

describe('canClaimInSync', () => {
  it('is true only for a clean source with a baseline', () => {
    const clean = resolveProgrammerSource({ ...base, target: CUE, entryCount: 9, dirty: 0,
      cueLocation: { stackName: 'Act 1' } })
    expect(canClaimInSync(clean)).toBe(true)
  })

  it('is false without a baseline, even though nothing is known to have changed', () => {
    // THE rule of this band. `dirty: null` means the tab reloaded, or opened after the Include —
    // it cannot tell. Claiming "in sync" there tells an operator their work is written when it is
    // not, and costs them the cue.
    const unknown = resolveProgrammerSource({ ...base, target: CUE, entryCount: 9, dirty: null,
      cueLocation: { stackName: 'Act 1' } })
    expect(unknown).toMatchObject({ dirty: null })
    expect(canClaimInSync(unknown)).toBe(false)
  })

  it('is false when dirty, missing, busking or empty', () => {
    const dirty = resolveProgrammerSource({ ...base, target: CUE, dirty: 2, cueLocation: {} })
    const missing = resolveProgrammerSource({ ...base, target: CUE, dirty: 0, cueLocation: null })
    expect(canClaimInSync(dirty)).toBe(false)
    expect(canClaimInSync(missing)).toBe(false)
    expect(canClaimInSync(resolveProgrammerSource({ ...base, entryCount: 3 }))).toBe(false)
    expect(canClaimInSync(resolveProgrammerSource(base))).toBe(false)
  })
})
