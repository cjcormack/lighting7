// @vitest-environment jsdom
import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { TemplateSummary } from '@/api/templatesApi'
import type { AttributeFamily } from '@/lib/attributeFamily'

/**
 * The one press (apply-model plan §4.1): which route a source × gesture × scope sends, the pair it
 * sends, and how it reports. The desk owns every rule, so what is pinned is the send, never a
 * decision this side could get wrong in a way the desk would not catch.
 */
const calls = vi.hoisted(() => ({
  applyTemplate: vi.fn(),
  toggleTemplate: vi.fn(),
  applyLook: vi.fn(),
  toggleLook: vi.fn(),
  absorb: vi.fn(),
  setValue: vi.fn(),
  programmerFrame: vi.fn(),
  removeFx: vi.fn(),
}))
const answers = vi.hoisted(() => ({
  apply: { written: 1, skipped: [] } as Record<string, unknown>,
  look: { action: 'applied', effectCount: 0, skippedFamilies: [] } as Record<string, unknown>,
  absorbOk: true,
}))
function mutation(spy: (args: unknown) => void, answer: () => unknown) {
  return () => [
    (args: unknown) => {
      spy(args)
      return { unwrap: () => Promise.resolve(answer()) }
    },
  ]
}
vi.mock('@/store/templates', () => ({
  useApplyTemplateMutation: mutation(calls.applyTemplate, () => answers.apply),
  useToggleTemplateMutation: mutation(calls.toggleTemplate, () => ({ action: 'applied', effectCount: 0 })),
}))
vi.mock('@/store/looks', () => ({
  useApplyLookMutation: mutation(calls.applyLook, () => ({ written: 2, effectIds: [], skippedFamilies: [] })),
  useToggleLookMutation: mutation(calls.toggleLook, () => answers.look),
  useAbsorbLookEffectsMutation: () => [
    (args: unknown) => {
      calls.absorb(args)
      return { unwrap: () => (answers.absorbOk ? Promise.resolve({ absorbed: 1 }) : Promise.reject(new Error('409'))) }
    },
  ],
}))
vi.mock('@/store/fixtureFx', () => ({
  useRemoveFxMutation: mutation(calls.removeFx, () => undefined),
}))
// Nothing here may reach the programmer's socket: every arm is REST, and a focused Look's arm writes
// the Look. The proxy fails the test the moment anything asks for the programmer API.
vi.mock('@/api/lightingApi', () => ({
  lightingApi: new Proxy({}, { get: (_t, key) => { calls.programmerFrame(key); return undefined } }),
}))

const toast = vi.hoisted(() => ({ success: vi.fn(), warning: vi.fn(), error: vi.fn() }))
vi.mock('sonner', () => ({ toast }))
vi.mock('@/store/errorToastMiddleware', () => ({ ignoreReportedError: () => {} }))

const scope = vi.hoisted(() => ({ current: null as null | { kind: string; layerId?: number } }))
const lookStore = vi.hoisted(() => ({ current: null as unknown }))
vi.mock('@/components/programmer/ProgrammerScope', () => ({ useProgrammerScope: () => scope.current }))
vi.mock('@/components/programmer/LookRowStore', () => ({ useLookRowStore: () => lookStore.current }))

const marquee = vi.hoisted(() => ({ current: null as unknown }))
vi.mock('@/components/fixtures-list/marqueeContext', () => ({ useMarquee: () => marquee.current }))

const desk = vi.hoisted(() => ({
  following: true,
  pair: { targets: [] as { type: string; key: string }[], families: null as AttributeFamily[] | null },
  local: { targets: [] as { type: string; key: string }[], families: null as AttributeFamily[] | null },
}))
vi.mock('@/store/selection', () => ({
  usePressFamilies: (local: AttributeFamily[] | null) => (desk.following ? desk.pair.families : local),
  useSelectionPair: () => (desk.following ? desk.pair : desk.local),
}))

const { usePress, useWindowPaletteFit, useWindowPress } = await import('./usePress')

const AMBER = {
  id: 1,
  name: 'Amber',
  kind: 'value',
  family: 'COLOUR',
  isGeneric: true,
  rows: [],
} as unknown as TemplateSummary
const BREATHE = { ...AMBER, id: 2, name: 'Breathe', kind: 'effect' } as TemplateSummary
const HEX = [{ type: 'fixture' as const, key: 'hex-1' }]

beforeEach(() => {
  for (const spy of Object.values(calls)) spy.mockClear()
  for (const spy of Object.values(toast)) spy.mockClear()
  answers.apply = { written: 1, skipped: [] }
  answers.absorbOk = true
  scope.current = { kind: 'local' }
  lookStore.current = null
  marquee.current = null
  desk.following = true
  desk.pair = { targets: HEX, families: null }
  desk.local = { targets: [], families: null }
})

async function send(...args: Parameters<ReturnType<typeof usePress>['press']>) {
  const { result } = renderHook(() => useWindowPress(1))
  let landed: Awaited<ReturnType<ReturnType<typeof usePress>['press']>> = null
  await act(async () => {
    landed = await result.current.press(...args)
  })
  return landed
}

describe('usePress — the programmer', () => {
  it('a press puts a palette on as a layer that tracks it', async () => {
    await send({ kind: 'template', template: AMBER }, 'press')
    expect(calls.toggleTemplate).toHaveBeenCalledWith({
      projectId: 1,
      templateId: 1,
      targets: HEX,
      propertyMask: 'COLOUR',
      families: undefined,
    })
    expect(calls.applyTemplate).not.toHaveBeenCalled()
  })

  it('Apply as values is the palette’s apply route', async () => {
    await send({ kind: 'template', template: AMBER }, 'asValues')
    expect(calls.applyTemplate).toHaveBeenCalledWith({ projectId: 1, templateId: 1, targets: HEX, families: undefined })
    expect(calls.toggleTemplate).not.toHaveBeenCalled()
  })

  it('a Look presses its toggle, and Apply as values its apply', async () => {
    await send({ kind: 'look', look: { id: 7, name: 'Warm' } }, 'press')
    expect(calls.toggleLook).toHaveBeenCalledWith({ projectId: 1, lookId: 7, targets: HEX, families: undefined })
    await send({ kind: 'look', look: { id: 7, name: 'Warm' } }, 'asValues')
    expect(calls.applyLook).toHaveBeenCalledWith({ projectId: 1, lookId: 7, targets: HEX, families: undefined })
  })

  it('toasts a Look’s skipped families in the rows vocabulary', async () => {
    desk.pair = { targets: HEX, families: ['COLOUR'] }
    answers.look = { action: 'applied', effectCount: 0, skippedFamilies: ['POSITION'] }
    await send({ kind: 'look', look: { id: 7, name: 'Warm' } }, 'press')
    expect(calls.toggleLook).toHaveBeenCalledWith(expect.objectContaining({ families: ['COLOUR'] }))
    expect(toast.warning).toHaveBeenCalledWith('Position rows skipped — the selection is Colour')
  })

  it('refuses a generic palette with no targets, which the palette routes would land nowhere', async () => {
    desk.pair = { targets: [], families: null }
    expect(await send({ kind: 'template', template: AMBER }, 'press')).toBeNull()
    expect(await send({ kind: 'template', template: AMBER }, 'asValues')).toBeNull()
    expect(calls.toggleTemplate).not.toHaveBeenCalled()
    expect(calls.applyTemplate).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith('Select the fixtures this should land on first')
  })

  it('refuses a per-fixture palette applied as values with no targets — the apply has no own heads', async () => {
    desk.pair = { targets: [], families: null }
    expect(await send({ kind: 'template', template: { ...AMBER, isGeneric: false } }, 'asValues')).toBeNull()
    expect(calls.applyTemplate).not.toHaveBeenCalled()
  })

  it('sends a per-fixture palette or a Look with no targets — they name their own heads', async () => {
    desk.pair = { targets: [], families: null }
    await send({ kind: 'template', template: { ...AMBER, isGeneric: false } }, 'press')
    expect(calls.toggleTemplate).toHaveBeenCalledWith(expect.objectContaining({ targets: [] }))
    await send({ kind: 'look', look: { id: 7, name: 'Warm' } }, 'press')
    expect(calls.toggleLook).toHaveBeenCalledWith(expect.objectContaining({ targets: [] }))
  })

  it('says whether the press put the record on or took it off', async () => {
    expect(await send({ kind: 'template', template: AMBER }, 'press')).toBe('on')
    answers.look = { action: 'removed', effectCount: 0, skippedFamilies: [] }
    expect(await send({ kind: 'look', look: { id: 7, name: 'Warm' } }, 'press')).toBe('off')
  })
})

describe('usePress — the pair', () => {
  it('is the desk’s while following and the window’s own when unlinked', async () => {
    await send({ kind: 'template', template: AMBER }, 'press')
    expect(calls.toggleTemplate).toHaveBeenLastCalledWith(expect.objectContaining({ targets: HEX }))

    desk.following = false
    desk.local = { targets: [{ type: 'group', key: 'wash' }], families: ['COLOUR'] }
    await send({ kind: 'template', template: AMBER }, 'press')
    expect(calls.toggleTemplate).toHaveBeenLastCalledWith(
      expect.objectContaining({ targets: [{ type: 'group', key: 'wash' }], families: ['COLOUR'] }),
    )
  })

  it('on the programmer, is the list’s own — an unlinked window presses what it shows', async () => {
    desk.following = false
    marquee.current = {
      press: { targets: [{ type: 'fixture', key: 'par-3' }], askedFamilies: ['INTENSITY'], targetFamilies: ['INTENSITY'], targetEmitters: [] },
    }
    await send({ kind: 'template', template: AMBER }, 'press')
    expect(calls.toggleTemplate).toHaveBeenLastCalledWith(
      expect.objectContaining({ targets: [{ type: 'fixture', key: 'par-3' }], families: ['INTENSITY'] }),
    )
  })

  it('takes a door’s own pair where it has one', async () => {
    const { result } = renderHook(() => usePress(1, { targets: [], families: null }))
    await act(async () => {
      await result.current.press({ kind: 'look', look: { id: 7, name: 'Warm' } }, 'press')
    })
    expect(calls.toggleLook).toHaveBeenCalledWith(expect.objectContaining({ targets: [] }))
  })
})

describe('usePress — a focused Look layer', () => {
  beforeEach(() => {
    scope.current = { kind: 'layer', layerId: 4 }
    lookStore.current = {
      projectId: 1,
      layerId: 4,
      lookId: 9,
      lookName: 'Night',
      targetedKeys: null,
      setValue: calls.setValue,
    }
    answers.apply = {
      written: 1,
      skipped: [],
      values: [{ target: { type: 'fixture', key: 'hex-1' }, propertyName: 'rgbColour', value: '#ff8800' }],
    }
  })

  it('writes a palette’s literals into the Look’s draft and sends no programmer frame', async () => {
    const { result } = renderHook(() => useWindowPress(1))
    expect(result.current.into).toEqual({ kind: 'look', lookName: 'Night' })
    await act(async () => {
      await result.current.press({ kind: 'template', template: AMBER }, 'press')
    })
    expect(calls.applyTemplate).toHaveBeenCalledWith({
      projectId: 1,
      templateId: 1,
      targets: HEX,
      families: undefined,
      write: false,
    })
    expect(calls.setValue).toHaveBeenCalledWith('hex-1', 'rgbColour', '#ff8800')
    expect(calls.toggleTemplate).not.toHaveBeenCalled()
    expect(calls.programmerFrame).not.toHaveBeenCalled()
  })

  it('lands nothing on a head the layer does not target, and says so', async () => {
    lookStore.current = { ...(lookStore.current as object), targetedKeys: new Set(['hex-2']) }
    const landed = await send({ kind: 'template', template: AMBER }, 'press')
    expect(landed).toBeNull()
    expect(calls.setValue).not.toHaveBeenCalled()
    expect(toast.warning).toHaveBeenCalledWith('0 values into Night · 1 could not take it')
  })

  it('absorbs an effect palette’s effect into the Look', async () => {
    answers.apply = { written: 0, skipped: [], effectIds: [31] }
    await send({ kind: 'template', template: BREATHE }, 'press')
    expect(calls.applyTemplate).toHaveBeenCalledWith(expect.not.objectContaining({ write: false }))
    expect(calls.absorb).toHaveBeenCalledWith({ projectId: 1, lookId: 9, effectIds: [31] })
  })

  it('takes the started effects off again when the absorb is refused — nothing is left on stage', async () => {
    answers.apply = { written: 0, skipped: [], effectIds: [31, 32] }
    answers.absorbOk = false
    expect(await send({ kind: 'template', template: BREATHE }, 'press')).toBeNull()
    expect(calls.removeFx).toHaveBeenCalledTimes(2)
    expect(calls.removeFx).toHaveBeenCalledWith(expect.objectContaining({ id: 31 }))
    expect(calls.removeFx).toHaveBeenCalledWith(expect.objectContaining({ id: 32 }))
  })

  it('starts an effect palette only on the heads the layer targets', async () => {
    desk.pair = { targets: [{ type: 'fixture', key: 'hex-1' }, { type: 'fixture', key: 'hex-9' }], families: null }
    lookStore.current = { ...(lookStore.current as object), targetedKeys: new Set(['hex-1']), targets: [] }
    answers.apply = { written: 0, skipped: [], effectIds: [31] }
    await send({ kind: 'template', template: BREATHE }, 'press')
    expect(calls.applyTemplate).toHaveBeenCalledWith(expect.objectContaining({ targets: [{ type: 'fixture', key: 'hex-1' }] }))
  })

  it('refuses a Look, since Looks don’t nest', async () => {
    const landed = await send({ kind: 'look', look: { id: 7, name: 'Warm' } }, 'press')
    expect(landed).toBeNull()
    expect(calls.toggleLook).not.toHaveBeenCalled()
    expect(calls.applyLook).not.toHaveBeenCalled()
    expect(toast.error).toHaveBeenCalledWith(expect.stringMatching(/Looks don’t nest/))
  })

  it('writes Local all the same for a door that asks for the programmer', async () => {
    const { result } = renderHook(() => usePress(1, { targets: HEX, families: null }, { scope: 'programmer' }))
    expect(result.current.into).toEqual({ kind: 'programmer' })
    await act(async () => {
      await result.current.press({ kind: 'template', template: AMBER }, 'press')
    })
    expect(calls.toggleTemplate).toHaveBeenCalled()
    expect(calls.setValue).not.toHaveBeenCalled()
  })
})

describe('useWindowPaletteFit — what the rail’s picker offers', () => {
  const fit = () => renderHook(() => useWindowPaletteFit()).result.current

  it('is the strip’s offer on the programmer: the marquee’s families, else what the heads can take', () => {
    const targets = [{ type: 'fixture', key: 'hex-1' }]
    marquee.current = { press: { targets, askedFamilies: ['COLOUR'], targetFamilies: ['COLOUR', 'BEAM'], targetEmitters: ['white'] } }
    expect(fit()).toEqual({ families: ['COLOUR'], emitters: ['white'] })
    marquee.current = { press: { targets, askedFamilies: null, targetFamilies: ['COLOUR', 'BEAM'], targetEmitters: ['white'] } }
    expect(fit()).toEqual({ families: ['COLOUR', 'BEAM'], emitters: ['white'] })
  })

  it('filters nothing with nothing selected, so a per-fixture palette naming an emitter is still offered', () => {
    marquee.current = { press: { targets: [], askedFamilies: null, targetFamilies: [], targetEmitters: [] } }
    expect(fit()).toEqual({ families: null, emitters: null })
    marquee.current = null
    expect(fit()).toEqual({ families: null, emitters: null })
  })
})
