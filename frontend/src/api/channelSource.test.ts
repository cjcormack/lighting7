import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createOverlayChannelSource,
  createProgrammerChannelSource,
  createPushChannelSource,
  type ChannelSource,
  type ProgrammerChannelState,
  type ProgrammerLike,
} from './channelSource'
import { descriptorsByTarget, type ResolvableEntry } from '../lib/programmerChannels'
import { chan, colourProp, makeFixture, sliderProp } from '../test/fixtureFactories'
import { serializeColour } from '../lib/programmerValue'

// The real lightingApi opens a WebSocket at import time, and this module imports it for
// `outputChannelSource`. Same guard as useRowOwnership.test.ts.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

/** Programmer stub: `set` replaces the state and fires the subscriber, as the real api does. */
function fakeProgrammer(initial: Partial<ProgrammerChannelState> = {}) {
  let state: ProgrammerChannelState = { entries: new Map(), channels: [], ...initial }
  const listeners = new Set<() => void>()
  const programmer: ProgrammerLike = {
    getState: () => state,
    subscribe: (fn) => {
      listeners.add(fn)
      return { unsubscribe: () => listeners.delete(fn) }
    },
  }
  return {
    programmer,
    set(next: Partial<ProgrammerChannelState>) {
      state = { entries: new Map(), channels: [], ...next }
      listeners.forEach((fn) => fn())
    },
    /** A notification carrying the same `entries`/`channels` — a provenance or blind frame. */
    notifyUnchanged() {
      listeners.forEach((fn) => fn())
    },
  }
}

function entryMap(...entries: ResolvableEntry[]): Map<string, ResolvableEntry> {
  return new Map(entries.map((e) => [`${e.targetKey}|${e.propertyName}`, e]))
}

/** A plain map-backed base source, standing in for the wire. */
function fixedSource(values: Record<string, number>): ChannelSource & { push(k: string, v: number): void } {
  const map = new Map(Object.entries(values))
  const listeners = new Map<string, Set<(v: number) => void>>()
  return {
    get: (universe, channelNo) => map.get(`${universe}:${channelNo}`) ?? 0,
    getByKey: (key) => map.get(key) ?? 0,
    subscribeToChannel: (key, fn) => {
      const forKey = listeners.get(key) ?? new Set()
      listeners.set(key, forKey)
      forKey.add(fn)
      return { unsubscribe: () => forKey.delete(fn) }
    },
    push(key, value) {
      map.set(key, value)
      listeners.get(key)?.forEach((fn) => fn(value))
    },
  }
}

const DIMMER = makeFixture('fx-1', [sliderProp('dimmer', 'dimmer', chan(1))])
const COLOUR = makeFixture('fx-2', [colourProp('rgbColour', chan(2), chan(3), chan(4))])
const DESCRIPTORS = descriptorsByTarget([DIMMER, COLOUR])

describe('createProgrammerChannelSource', () => {
  let fake: ReturnType<typeof fakeProgrammer>

  beforeEach(() => {
    fake = fakeProgrammer()
  })

  it('reads 0 for a channel the programmer does not hold', () => {
    const source = createProgrammerChannelSource(fake.programmer, () => DESCRIPTORS)
    expect(source.getByKey('0:1')).toBe(0)
    expect(source.get(0, 1)).toBe(0)
    expect(source.holds('0:1')).toBe(false)
  })

  it('reads a held property entry as channel values', () => {
    const source = createProgrammerChannelSource(fake.programmer, () => DESCRIPTORS)
    fake.set({
      entries: entryMap({ targetKey: 'fx-1', propertyName: 'dimmer', value: '180' }),
    })
    expect(source.getByKey('0:1')).toBe(180)
    expect(source.get(0, 1)).toBe(180)
    expect(source.holds('0:1')).toBe(true)
  })

  it('distinguishes "held at zero" from "not held"', () => {
    // The case blind most needs: an operator parking a dimmer at 0 in the programmer must
    // read as a deliberate 0, not as "fall through to the wire".
    const source = createProgrammerChannelSource(fake.programmer, () => DESCRIPTORS)
    fake.set({ entries: entryMap({ targetKey: 'fx-1', propertyName: 'dimmer', value: '0' }) })
    expect(source.getByKey('0:1')).toBe(0)
    expect(source.holds('0:1')).toBe(true)
  })

  it('notifies only the channels that changed', () => {
    const source = createProgrammerChannelSource(fake.programmer, () => DESCRIPTORS)
    const red = vi.fn()
    const green = vi.fn()
    source.subscribeToChannel('0:2', red)
    source.subscribeToChannel('0:3', green)

    fake.set({
      entries: entryMap({
        targetKey: 'fx-2',
        propertyName: 'rgbColour',
        value: serializeColour(10, 20, 0),
      }),
    })
    expect(red).toHaveBeenCalledExactlyOnceWith(10)
    expect(green).toHaveBeenCalledExactlyOnceWith(20)

    red.mockClear()
    green.mockClear()

    // Only red moves; green must stay asleep or the per-channel split buys nothing.
    fake.set({
      entries: entryMap({
        targetKey: 'fx-2',
        propertyName: 'rgbColour',
        value: serializeColour(99, 20, 0),
      }),
    })
    expect(red).toHaveBeenCalledExactlyOnceWith(99)
    expect(green).not.toHaveBeenCalled()
  })

  it('notifies when a channel becomes held at zero', () => {
    // Absent and held-at-0 are both 0 to this source, so a value-only comparison sees no
    // change — but the overlay dispatches on `holds`, where it matters a great deal.
    const source = createProgrammerChannelSource(fake.programmer, () => DESCRIPTORS)
    const listener = vi.fn()
    source.subscribeToChannel('0:1', listener)

    fake.set({ entries: entryMap({ targetKey: 'fx-1', propertyName: 'dimmer', value: '0' }) })

    expect(listener).toHaveBeenCalledExactlyOnceWith(0)
    expect(source.holds('0:1')).toBe(true)
  })

  it('notifies 0 when an entry is cleared', () => {
    const source = createProgrammerChannelSource(fake.programmer, () => DESCRIPTORS)
    fake.set({ entries: entryMap({ targetKey: 'fx-1', propertyName: 'dimmer', value: '180' }) })

    const listener = vi.fn()
    source.subscribeToChannel('0:1', listener)
    fake.set({ entries: new Map() })

    expect(listener).toHaveBeenCalledExactlyOnceWith(0)
    expect(source.holds('0:1')).toBe(false)
  })

  it('picks up the sideband', () => {
    const source = createProgrammerChannelSource(fake.programmer, () => DESCRIPTORS)
    fake.set({ channels: [{ universe: 1, channel: 42, value: 77 }] })
    expect(source.getByKey('1:42')).toBe(77)
  })

  it('refresh recomputes against descriptors that arrived late', () => {
    // The patch loads after the stage mounts, so the first rebuild sees no descriptors.
    let descriptors = descriptorsByTarget([])
    const source = createProgrammerChannelSource(fake.programmer, () => descriptors)
    fake.set({ entries: entryMap({ targetKey: 'fx-1', propertyName: 'dimmer', value: '180' }) })
    expect(source.getByKey('0:1')).toBe(0)

    descriptors = DESCRIPTORS
    const listener = vi.fn()
    source.subscribeToChannel('0:1', listener)
    source.refresh()

    expect(source.getByKey('0:1')).toBe(180)
    expect(listener).toHaveBeenCalledExactlyOnceWith(180)
  })

  it('skips the rebuild on a notification that changed neither entries nor channels', () => {
    // A provenance frame reassigns only the snapshot wrapper, at up to 10/s — and this source is
    // held by the stage overview panel, so an ungated rebuild was a full parse-and-descriptor-scan
    // per entry on whatever route the operator happened to be on.
    let descriptorReads = 0
    const source = createProgrammerChannelSource(fake.programmer, () => {
      descriptorReads++
      return DESCRIPTORS
    })
    fake.set({ entries: entryMap({ targetKey: 'fx-1', propertyName: 'dimmer', value: '180' }) })
    const readsAfterRealChange = descriptorReads

    fake.notifyUnchanged()
    expect(descriptorReads).toBe(readsAfterRealChange)

    // …but refresh still forces, because a descriptor change is invisible to the guard.
    source.refresh()
    expect(descriptorReads).toBe(readsAfterRealChange + 1)
    expect(source.getByKey('0:1')).toBe(180)
  })

  it('stops rebuilding once disposed', () => {
    const source = createProgrammerChannelSource(fake.programmer, () => DESCRIPTORS)
    source.dispose()
    fake.set({ entries: entryMap({ targetKey: 'fx-1', propertyName: 'dimmer', value: '180' }) })
    expect(source.getByKey('0:1')).toBe(0)
  })
})

describe('createOverlayChannelSource', () => {
  it('prefers the overlay where it holds a channel and the base elsewhere', () => {
    const base = fixedSource({ '0:1': 100, '0:2': 200 })
    const fake = fakeProgrammer({
      entries: entryMap({ targetKey: 'fx-1', propertyName: 'dimmer', value: '5' }),
    })
    const overlay = createProgrammerChannelSource(fake.programmer, () => DESCRIPTORS)
    const merged = createOverlayChannelSource(base, overlay)

    expect(merged.getByKey('0:1')).toBe(5)
    expect(merged.get(0, 1)).toBe(5)
    expect(merged.getByKey('0:2')).toBe(200)
    expect(merged.get(0, 2)).toBe(200)
  })

  it('lets a base change show through where the overlay is silent', () => {
    const base = fixedSource({ '0:2': 200 })
    const fake = fakeProgrammer()
    const overlay = createProgrammerChannelSource(fake.programmer, () => DESCRIPTORS)
    const merged = createOverlayChannelSource(base, overlay)

    const listener = vi.fn()
    merged.subscribeToChannel('0:2', listener)
    base.push('0:2', 210)

    expect(listener).toHaveBeenCalledExactlyOnceWith(210)
  })

  it('goes dark when the programmer takes a channel over at zero', () => {
    // The blind case the whole feature exists for: a cue holds a dimmer at full, the operator
    // zeroes it in a blind programmer, and the stage must go dark rather than keep painting the
    // cue's value.
    const base = fixedSource({ '0:1': 255 })
    const fake = fakeProgrammer()
    const overlay = createProgrammerChannelSource(fake.programmer, () => DESCRIPTORS)
    const merged = createOverlayChannelSource(base, overlay)

    const listener = vi.fn()
    merged.subscribeToChannel('0:1', listener)
    expect(merged.getByKey('0:1')).toBe(255)

    fake.set({ entries: entryMap({ targetKey: 'fx-1', propertyName: 'dimmer', value: '0' }) })

    expect(merged.getByKey('0:1')).toBe(0)
    expect(listener).toHaveBeenLastCalledWith(0)
  })

  it('re-reads when the overlay takes over or releases a channel', () => {
    const base = fixedSource({ '0:1': 100 })
    const fake = fakeProgrammer()
    const overlay = createProgrammerChannelSource(fake.programmer, () => DESCRIPTORS)
    const merged = createOverlayChannelSource(base, overlay)

    const listener = vi.fn()
    merged.subscribeToChannel('0:1', listener)

    fake.set({ entries: entryMap({ targetKey: 'fx-1', propertyName: 'dimmer', value: '5' }) })
    expect(listener).toHaveBeenLastCalledWith(5)

    // Released: the wire value must come back rather than the channel sticking at 0.
    fake.set({ entries: new Map() })
    expect(listener).toHaveBeenLastCalledWith(100)
  })
})

describe('createPushChannelSource', () => {
  const ch = (universe: number, channel: number, value: number) => ({ universe, channel, value })

  it('reads 0 for a channel it was never given', () => {
    const source = createPushChannelSource()
    expect(source.getByKey('0:1')).toBe(0)
    expect(source.get(0, 1)).toBe(0)
    expect(source.holds('0:1')).toBe(false)
  })

  it('distinguishes "pushed at zero" from "not pushed"', () => {
    // The preview endpoint omits channels no cue asserts, so `holds` is the only thing standing
    // between a cue that deliberately darkens a fixture and one that simply says nothing about it.
    const source = createPushChannelSource()
    source.setChannels([ch(0, 1, 0)])
    expect(source.getByKey('0:1')).toBe(0)
    expect(source.holds('0:1')).toBe(true)
    expect(source.holds('0:2')).toBe(false)
  })

  it('notifies only the channels that changed', () => {
    const source = createPushChannelSource()
    source.setChannels([ch(0, 1, 10), ch(0, 2, 20)])

    const one = vi.fn()
    const two = vi.fn()
    source.subscribeToChannel('0:1', one)
    source.subscribeToChannel('0:2', two)

    source.setChannels([ch(0, 1, 10), ch(0, 2, 25)])

    expect(one).not.toHaveBeenCalled()
    expect(two).toHaveBeenCalledExactlyOnceWith(25)
  })

  it('notifies 0 when a channel drops out of the look', () => {
    const source = createPushChannelSource()
    source.setChannels([ch(0, 1, 10)])

    const listener = vi.fn()
    source.subscribeToChannel('0:1', listener)
    source.setChannels([])

    expect(listener).toHaveBeenCalledExactlyOnceWith(0)
    expect(source.holds('0:1')).toBe(false)
  })

  it('overlays the wire, falling back for channels the look is silent about', () => {
    // The Next GO composition: the preview asserts channel 1, so the stage draws the cue's value
    // there and the desk's live value everywhere else.
    const base = fixedSource({ '0:1': 100, '0:2': 200 })
    const preview = createPushChannelSource()
    const merged = createOverlayChannelSource(base, preview)

    const listener = vi.fn()
    merged.subscribeToChannel('0:1', listener)

    preview.setChannels([ch(0, 1, 255)])
    expect(merged.getByKey('0:1')).toBe(255)
    expect(merged.getByKey('0:2')).toBe(200)
    expect(listener).toHaveBeenLastCalledWith(255)

    // Nothing on deck: the wire has to come back rather than the channel sticking at 0.
    preview.setChannels([])
    expect(merged.getByKey('0:1')).toBe(100)
    expect(listener).toHaveBeenLastCalledWith(100)
  })
})
