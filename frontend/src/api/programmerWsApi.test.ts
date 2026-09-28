import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { InternalEventType } from './internalApi'
import { fakeWsConnection } from '../test/fakeWsConnection'
import type { LayerSource } from './cuesApi'
import {
  createProgrammerApi,
  programmerKey,
  type ProgrammerEntry,
  type ProgrammerLayer,
  type ProvenanceEntry,
} from './programmerWsApi'

const ENTRY: ProgrammerEntry = {
  targetKey: 'hex-1',
  propertyName: 'dimmer',
  value: '200',
  owner: 'web',
  touched: true,
  owners: ['web'],
}

const PROV: ProvenanceEntry = {
  targetKey: 'hex-1',
  propertyName: 'dimmer',
  source: 'PROGRAMMER',
}

const WARM: LayerSource = { kind: 'LOOK', id: 7, uuid: 'u7', name: 'Warm Wash' }
const COOL: LayerSource = { kind: 'LOOK', id: 8, uuid: 'u8', name: 'Cool Wash' }

const LAYER: ProgrammerLayer = {
  layerId: 1,
  source: { kind: 'LOOK', id: 7, uuid: 'u7', name: 'Warm Wash' },
  sortOrder: 0,
  enabled: true,
  targets: [{ type: 'group', key: 'front-wash' }],
  blendMode: 'OVERRIDE',
  amount: 1,
  stomp: false,
}

function stateFrame(entries: ProgrammerEntry[] = [ENTRY], blind = false) {
  return { type: 'programmer.state', blind, entries, channels: [] }
}

/**
 * A `provenanceState` frame, with its entries **typed**.
 *
 * `fakeWsConnection.frame` takes `unknown` — it has to, it serves every bridge — so a fixture
 * written straight into it can name fields `ProvenanceEntry` does not have and still pass. This
 * test file did exactly that for a while, sending a `lookId`/`lookName` pair that had been replaced
 * by `layerSource` on both sides of the wire: the assertions still ran, against a shape production
 * never sees. Going through here makes that a compile error instead.
 */
function provenanceFrame(entries: ProvenanceEntry[], programmerRevision?: number) {
  return programmerRevision === undefined
    ? { type: 'provenanceState', entries }
    : { type: 'provenanceState', entries, programmerRevision }
}

describe('createProgrammerApi', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('asks for no state snapshot on open — the server pushes one per connection', () => {
    const { conn, sent, fire } = fakeWsConnection()
    createProgrammerApi(conn)

    fire(InternalEventType.open, new Event('open'))

    expect(sent).toEqual([])
  })

  it('indexes a state snapshot by (targetKey, propertyName)', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)

    frame(stateFrame())

    expect(api.entryCount()).toBe(1)
    expect(api.getKeyState('hex-1', 'dimmer').entry).toEqual(ENTRY)
    expect(api.getState().entries.get(programmerKey('hex-1', 'dimmer'))).toEqual(ENTRY)
  })

  it('ignores frames of other types without parsing them', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    const spy = vi.fn()
    api.subscribe(spy)

    frame({ type: 'channelState', channels: [{ universe: 0, id: 1, currentLevel: 5 }] })

    expect(spy).not.toHaveBeenCalled()
  })

  it('does not fire when the message event is not a MessageEvent', () => {
    const { conn, fire } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    const spy = vi.fn()
    api.subscribe(spy)

    fire(InternalEventType.message, new Event('message'))

    expect(spy).not.toHaveBeenCalled()
  })

  it('survives a malformed frame that trips the substring fast path', () => {
    const { conn, fire } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    const spy = vi.fn()
    api.subscribe(spy)

    fire(
      InternalEventType.message,
      new MessageEvent('message', { data: '{"type":"programmer.state" oops' }),
    )

    expect(spy).not.toHaveBeenCalled()
  })

  it('applies provenance and exposes it per key', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)

    frame(provenanceFrame([PROV]))

    expect(api.getKeyState('hex-1', 'dimmer').provenance).toEqual(PROV)
    // A key nothing covers is baseline-owned: absent, not a synthesised entry.
    expect(api.getKeyState('hex-1', 'strobe').provenance).toBeUndefined()
  })

  it('re-requests programmer state after provenance, debounced into one refetch', () => {
    const { conn, sent, frame } = fakeWsConnection()
    createProgrammerApi(conn)

    // Provenance is the only broadcast the server sends for programmer activity — including
    // writes made by MIDI surfaces, locate, or another tab — so it drives the refetch.
    frame(provenanceFrame([PROV]))
    frame(provenanceFrame([PROV]))
    frame(provenanceFrame([PROV]))
    expect(sent).toEqual([])

    vi.advanceTimersByTime(100)

    expect(sent).toEqual([{ type: 'programmer.state' }])
  })

  it('skips the refetch while the programmer revision holds — a crossfade cannot move the programmer', () => {
    const { conn, sent, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)

    // The first frame of a connection always refetches, whatever revision it carries.
    frame(provenanceFrame([PROV], 3))
    vi.advanceTimersByTime(200)
    expect(sent).toEqual([{ type: 'programmer.state' }])

    // A running crossfade republishes provenance ~20×/s at an unmoved revision; the timer
    // is advanced past the debounce between frames so a refetch would show if one fired.
    frame(provenanceFrame([PROV], 3))
    vi.advanceTimersByTime(200)
    frame(provenanceFrame([PROV], 3))
    vi.advanceTimersByTime(200)

    expect(sent).toEqual([{ type: 'programmer.state' }])
    // The frames' provenance content is still applied — only the refetch is skipped.
    expect(api.getKeyState('hex-1', 'dimmer').provenance).toEqual(PROV)
  })

  it('refetches when the revision moves mid-fade — an off-connection write got in', () => {
    const { conn, sent, frame } = fakeWsConnection()
    createProgrammerApi(conn)

    frame(provenanceFrame([PROV], 3))
    vi.advanceTimersByTime(200)
    // A MIDI write (or second tab) mid-fade bumps the revision on the server's next frame —
    // even when it changed no provenance winner, so the entries are identical.
    frame(provenanceFrame([PROV], 4))
    vi.advanceTimersByTime(200)
    frame(provenanceFrame([PROV], 4))
    vi.advanceTimersByTime(200)

    expect(sent).toEqual([{ type: 'programmer.state' }, { type: 'programmer.state' }])
  })

  it('refetches on every frame from a server that sends no revision', () => {
    const { conn, sent, frame } = fakeWsConnection()
    createProgrammerApi(conn)

    frame(provenanceFrame([PROV]))
    vi.advanceTimersByTime(200)
    frame(provenanceFrame([PROV]))
    vi.advanceTimersByTime(200)

    expect(sent).toEqual([{ type: 'programmer.state' }, { type: 'programmer.state' }])
  })

  it('re-arms the debounce for provenance that arrives after a refetch', () => {
    const { conn, sent, frame } = fakeWsConnection()
    createProgrammerApi(conn)

    frame(provenanceFrame([]))
    vi.advanceTimersByTime(100)
    frame(provenanceFrame([PROV]))
    vi.advanceTimersByTime(100)

    expect(sent).toHaveLength(2)
  })

  it('notifies only the keys whose state actually changed', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    const dimmer = vi.fn()
    const strobe = vi.fn()
    api.subscribeToKey('hex-1', 'dimmer', dimmer)
    api.subscribeToKey('hex-1', 'strobe', strobe)

    frame(stateFrame())

    expect(dimmer).toHaveBeenCalledTimes(1)
    // The strobe cell must not re-render because an unrelated property was busked.
    expect(strobe).not.toHaveBeenCalled()
  })

  it('notifies a key when its entry is removed', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    frame(stateFrame())

    const dimmer = vi.fn()
    api.subscribeToKey('hex-1', 'dimmer', dimmer)
    frame(stateFrame([]))

    expect(dimmer).toHaveBeenCalledTimes(1)
    expect(api.getKeyState('hex-1', 'dimmer').entry).toBeUndefined()
  })

  it('does not re-notify when a snapshot repeats an entry with identical values', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    frame(stateFrame())

    const dimmer = vi.fn()
    api.subscribeToKey('hex-1', 'dimmer', dimmer)
    // Fresh object, same values. Every frame arrives via JSON.parse and so is always a new
    // allocation — comparing by identity here would mark every key changed on every
    // provenance push and make the per-key channel useless.
    frame(stateFrame([{ ...ENTRY }]))

    expect(dimmer).not.toHaveBeenCalled()
  })

  it('re-notifies when a repeated entry differs in any field', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    frame(stateFrame())

    const dimmer = vi.fn()
    api.subscribeToKey('hex-1', 'dimmer', dimmer)

    frame(stateFrame([{ ...ENTRY, value: '10' }]))
    expect(dimmer).toHaveBeenCalledTimes(1)

    frame(stateFrame([{ ...ENTRY, value: '10', owner: 'locate', owners: ['locate'] }]))
    expect(dimmer).toHaveBeenCalledTimes(2)

    frame(stateFrame([{ ...ENTRY, value: '10', owner: 'locate', owners: ['locate'], touched: false }]))
    expect(dimmer).toHaveBeenCalledTimes(3)
  })

  it('does not re-notify when provenance repeats with identical values', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    frame(provenanceFrame([PROV]))

    const dimmer = vi.fn()
    api.subscribeToKey('hex-1', 'dimmer', dimmer)
    // An unrelated cue firing rebroadcasts the whole provenance table; cells it doesn't touch
    // must stay quiet.
    frame(provenanceFrame([{ ...PROV }]))

    expect(dimmer).not.toHaveBeenCalled()

    frame(provenanceFrame([{ ...PROV, source: 'CUE', cueId: 7 }]))
    expect(dimmer).toHaveBeenCalledTimes(1)
  })

  it('reports the local connection as the owner when its write takes over a property', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    frame(stateFrame([{ ...ENTRY, owner: 'locate', owners: ['locate'], sourceGroup: 'Wash' }]))

    // entryChanged is unicast — it is always confirmation of OUR write, so the winning slot
    // is now `web`, whoever held it before.
    frame({
      type: 'programmer.entryChanged',
      targetType: 'fixture',
      targetKey: 'hex-1',
      propertyName: 'dimmer',
      value: '180',
    })

    const entry = api.getKeyState('hex-1', 'dimmer').entry
    expect(entry?.owner).toBe('web')
    expect(entry?.owners).toEqual(['web', 'locate'])
    expect(entry?.sourceGroup).toBeUndefined()
  })

  it('echoes an own-connection write locally before the state refetch lands', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)

    frame({
      type: 'programmer.entryChanged',
      targetType: 'fixture',
      targetKey: 'hex-1',
      propertyName: 'dimmer',
      value: '120',
    })

    expect(api.getKeyState('hex-1', 'dimmer').entry?.value).toBe('120')
    expect(api.entryCount()).toBe(1)
  })

  // Two tests stood here about the local echo and `ref:` values: that applying a palette recovered
  // the reference uuid from the echoed value (the echo carries only the value, so without it every
  // cell dropped its badge and blanked its blind preview for ~100 ms until the authoritative refetch
  // landed), and that a plain literal write *cleared* the reference metadata rather than carrying a
  // stale palette forward. Both retired with the `ref:` grammar in session 4 — the echo is the whole
  // entry now.

  // The four tests below guard the signature cache the per-key diff compares against. `signedMap`
  // carries it alongside the values rather than recomputing it, so every path that writes an entry
  // outside a state snapshot — the local echo, the single clear, clear-all — has to move both maps.
  // A signature left behind for a key whose value has moved is the bad failure: the diff calls the
  // key unchanged and the cell paints the old value until something unrelated wakes it.
  it('wakes the cell when the authoritative snapshot disagrees with the local echo', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    const dimmer = vi.fn()
    api.subscribeToKey('hex-1', 'dimmer', dimmer)

    frame({
      type: 'programmer.entryChanged',
      targetType: 'fixture',
      targetKey: 'hex-1',
      propertyName: 'dimmer',
      value: '120',
    })
    dimmer.mockClear()
    // The server clamped it, or another surface got in first.
    frame(stateFrame([{ ...ENTRY, value: '90' }]))

    expect(dimmer).toHaveBeenCalledTimes(1)
    expect(api.getKeyState('hex-1', 'dimmer').entry?.value).toBe('90')
  })

  it('does not wake the cell when the snapshot confirms the local echo', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    const dimmer = vi.fn()
    api.subscribeToKey('hex-1', 'dimmer', dimmer)

    frame({
      type: 'programmer.entryChanged',
      targetType: 'fixture',
      targetKey: 'hex-1',
      propertyName: 'dimmer',
      value: '200',
    })
    dimmer.mockClear()
    // `ENTRY` is the echo's own shape — owner `web`, touched, sole owner — so the refetch
    // that lands ~100 ms later says exactly what the echo already painted.
    frame(stateFrame())

    expect(dimmer).not.toHaveBeenCalled()
  })

  it('wakes the cell when a snapshot restores an entry a local clear removed', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    frame(stateFrame())

    const dimmer = vi.fn()
    api.subscribeToKey('hex-1', 'dimmer', dimmer)
    frame({
      type: 'programmer.entryCleared',
      targetType: 'fixture',
      targetKey: 'hex-1',
      propertyName: 'dimmer',
    })
    dimmer.mockClear()
    frame(stateFrame())

    expect(dimmer).toHaveBeenCalledTimes(1)
    expect(api.getKeyState('hex-1', 'dimmer').entry?.value).toBe('200')
  })

  it('wakes the cell when a snapshot restores an entry clear-all removed', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    frame(stateFrame())

    const dimmer = vi.fn()
    api.subscribeToKey('hex-1', 'dimmer', dimmer)
    frame({ type: 'programmer.cleared', cleared: 1, effectsCleared: 0 })
    dimmer.mockClear()
    frame(stateFrame())

    expect(dimmer).toHaveBeenCalledTimes(1)
    expect(api.getKeyState('hex-1', 'dimmer').entry?.value).toBe('200')
  })

  it('drops the entry on an own-connection clear', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    frame(stateFrame())

    frame({
      type: 'programmer.entryCleared',
      targetType: 'fixture',
      targetKey: 'hex-1',
      propertyName: 'dimmer',
    })

    expect(api.entryCount()).toBe(0)
  })

  it('empties the cache on clear-all', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    frame(stateFrame())

    frame({ type: 'programmer.cleared', cleared: 1, effectsCleared: 2 })

    expect(api.entryCount()).toBe(0)
  })

  it('tracks the blind gate from both the snapshot and the toggle reply', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)

    expect(api.isBlind()).toBe(false)
    frame({ type: 'programmer.blindState', blind: true })
    expect(api.isBlind()).toBe(true)
    frame(stateFrame([ENTRY], false))
    expect(api.isBlind()).toBe(false)
  })

  it('reports programmer errors to error subscribers only', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    const errors = vi.fn()
    const keySpy = vi.fn()
    api.subscribeToErrors(errors)
    api.subscribeToKey('hex-1', 'dimmer', keySpy)

    frame({ type: 'programmer.error', message: "Unknown fixture 'nope'" })

    expect(errors).toHaveBeenCalledWith("Unknown fixture 'nope'")
    expect(keySpy).not.toHaveBeenCalled()
  })

  it('sends the documented wire shapes', () => {
    const { conn, sent } = fakeWsConnection()
    const api = createProgrammerApi(conn)

    api.set('fixture', 'hex-1', 'dimmer', '200', 500)
    api.setColour('fixture', 'hex-1', 'rgbColour', { r: 255, g: 0, b: 128 })
    api.setPosition('fixture', 'mover-1', 10, 20)
    api.clearEntry('group', 'Wash', 'dimmer', 1000)
    api.clearAll(2000)
    api.setBlind(true, 250)

    expect(sent).toEqual([
      {
        type: 'programmer.set',
        targetType: 'fixture',
        targetKey: 'hex-1',
        propertyName: 'dimmer',
        value: '200',
        fadeMs: 500,
      },
      {
        type: 'programmer.setColour',
        targetType: 'fixture',
        targetKey: 'hex-1',
        propertyName: 'rgbColour',
        r: 255,
        g: 0,
        b: 128,
      },
      {
        type: 'programmer.setPosition',
        targetType: 'fixture',
        targetKey: 'mover-1',
        pan: 10,
        tilt: 20,
      },
      {
        type: 'programmer.clearEntry',
        targetType: 'group',
        targetKey: 'Wash',
        propertyName: 'dimmer',
        fadeMs: 1000,
      },
      { type: 'programmer.clearAll', fadeMs: 2000 },
      { type: 'programmer.setBlind', blind: true, fadeMs: 250 },
    ])
  })

  it('sends the documented layer wire shapes', () => {
    const { conn, sent } = fakeWsConnection()
    const api = createProgrammerApi(conn)

    api.addLayer({ lookId: 7, targets: [{ type: 'group', key: 'front-wash' }], amount: 0.5 })
    api.addLayer({ lookId: 8 })
    api.removeLayer(3, 500)
    api.moveLayer(3, 0)
    api.patchLayer(3, { enabled: false, amount: 0.25 })

    expect(sent).toEqual([
      {
        type: 'programmer.addLayer',
        lookId: 7,
        targets: [{ type: 'group', key: 'front-wash' }],
        amount: 0.5,
      },
      // Targets default to `[]` rather than being omitted: an absent list and an empty one mean
      // the same thing to the server, and always sending the field keeps the shape one shape.
      { type: 'programmer.addLayer', lookId: 8, targets: [] },
      { type: 'programmer.removeLayer', layerId: 3, fadeMs: 500 },
      { type: 'programmer.moveLayer', layerId: 3, toIndex: 0 },
      { type: 'programmer.patchLayer', layerId: 3, enabled: false, amount: 0.25 },
    ])
  })

  it('never sends a look uuid on addLayer', () => {
    // The backend resolves the Look from its int id. A uuid here would be a second address for the
    // same thing, and the two are minted differently across a sync import.
    const { conn, sent } = fakeWsConnection()
    createProgrammerApi(conn).addLayer({ lookId: 7 })
    expect(JSON.stringify(sent[0])).not.toMatch(/uuid/i)
  })

  it('carries an optional sourceGroup on the set ops', () => {
    // For fan-outs that can't send targetType:'group' — a group virtual dimmer over
    // heterogeneous members, a Highlight release restoring per-fixture values.
    const { conn, sent } = fakeWsConnection()
    const api = createProgrammerApi(conn)

    api.set('fixture', 'hex-1', 'dimmer', '200', undefined, 'front-wash')
    api.setPosition('fixture', 'mover-1', 10, 20, undefined, 'movers')

    expect(sent[0]).toMatchObject({ type: 'programmer.set', sourceGroup: 'front-wash' })
    expect(sent[1]).toMatchObject({ type: 'programmer.setPosition', sourceGroup: 'movers' })
  })

  it('tracks the include target from the state snapshot and the broadcast', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    expect(api.lastIncluded()).toBeNull()

    const target = { kind: 'CUE', cueId: 42, cueStackId: 7, cueName: 'Look 1' }
    frame({ ...stateFrame(), lastIncluded: target })
    expect(api.lastIncluded()).toEqual(target)

    // The include target is broadcast, unlike the other programmer replies: the programmer is
    // shared, so a second tab's Update button must offer the same target.
    frame({ type: 'programmer.includeTarget', target: { kind: 'CUE', cueId: 9 } })
    expect(api.lastIncluded()).toEqual({ kind: 'CUE', cueId: 9 })

    frame({ type: 'programmer.includeTarget', target: null })
    expect(api.lastIncluded()).toBeNull()
  })

  it('forgets the include target when the programmer is cleared', () => {
    // Clear releases everything Include staged, so an Update offering that target would
    // silently write nothing.
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    frame({ ...stateFrame(), lastIncluded: { kind: 'CUE', cueId: 42 } })

    frame({ type: 'programmer.cleared', cleared: 1 })

    expect(api.lastIncluded()).toBeNull()
  })

  it('does not wake per-key subscribers when only the include target changed', () => {
    // `lastIncluded` is deliberately outside `entrySignature`: including a different cue must
    // not re-render every cell in a few-hundred-row sheet.
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    const spy = vi.fn()
    api.subscribeToKey('hex-1', 'dimmer', spy)
    frame(stateFrame())
    expect(spy).toHaveBeenCalledTimes(1)

    frame({ type: 'programmer.includeTarget', target: { kind: 'CUE', cueId: 42 } })

    expect(spy).toHaveBeenCalledTimes(1)
  })

  it('takes the layer stack from a broadcast layerState frame', () => {
    // Broadcast, so this arrives for another tab's reorder as well as our own mutation — which is
    // the whole reason the server sends it rather than replying to the caller.
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    expect(api.layers()).toEqual([])

    frame({ type: 'programmer.layerState', layers: [LAYER] })

    expect(api.layers()).toEqual([LAYER])
    expect(api.getState().layers).toEqual([LAYER])
  })

  it('does not wake per-key subscribers when only the layer stack changed', () => {
    // Same argument as the include target: the stack is the cue-level composition, not per-cell
    // state, and a reorder must not re-render every cell in a few-hundred-row sheet.
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    const spy = vi.fn()
    api.subscribeToKey('hex-1', 'dimmer', spy)
    frame(stateFrame())
    expect(spy).toHaveBeenCalledTimes(1)

    frame({ type: 'programmer.layerState', layers: [LAYER] })

    expect(spy).toHaveBeenCalledTimes(1)
  })

  it('does not wake any subscriber for a provenance frame that changed nothing', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    const coarse = vi.fn()
    api.subscribe(coarse)

    frame(provenanceFrame([PROV], 3))
    expect(coarse).toHaveBeenCalledTimes(1)

    // A fade republishes content-identical provenance ~20×/s; waking every whole-state
    // subscriber per frame was a render storm even with the refetch gone.
    frame(provenanceFrame([{ ...PROV }], 3))
    frame(provenanceFrame([{ ...PROV }], 3))
    expect(coarse).toHaveBeenCalledTimes(1)
  })

  it('wakes coarse subscribers on a layerState frame', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    const spy = vi.fn()
    api.subscribe(spy)

    frame({ type: 'programmer.layerState', layers: [LAYER] })

    expect(spy).toHaveBeenCalledTimes(1)
    expect(spy.mock.calls[0][0].layers).toEqual([LAYER])
  })

  it('takes the layer stack from a state snapshot, and keeps it when one omits the field', () => {
    // `layers` rides `programmer.state` so a fresh connection needs no second round trip. It is
    // optional on the wire, and an absent field means an older server — not an empty stack, which
    // would blank the pane on every refetch.
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)

    frame({ ...stateFrame(), layers: [LAYER] })
    expect(api.layers()).toEqual([LAYER])

    frame(stateFrame())
    expect(api.layers()).toEqual([LAYER])
  })

  it('wakes a cell when only the winning layer changed', () => {
    // `source` stays `CUE` when a cue's value starts coming from one of its layers, so leaving the
    // layer fields out of the provenance signature would leave the cell naming the old answer.
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    const spy = vi.fn()
    api.subscribeToKey('hex-1', 'dimmer', spy)

    frame(provenanceFrame([{ ...PROV, source: 'CUE', cueId: 4, layerId: 1, layerSource: WARM }]))
    expect(spy).toHaveBeenCalledTimes(1)

    frame(provenanceFrame([{ ...PROV, source: 'CUE', cueId: 4, layerId: 2, layerSource: COOL }]))
    expect(spy).toHaveBeenCalledTimes(2)
    expect(spy.mock.calls[1][0].provenance?.layerSource).toEqual(COOL)
  })

  it('wakes a cell when only the winning layer’s *source* changed', () => {
    // The hazard the whole `layerSource` object is in the signature for: a Look and a template can
    // share an int PK, so a key moving from Look 3 to template 3 moves neither `layerId` (the
    // stack slot is the same one) nor `source` (still `CUE`) nor `layerSource.id`. Signing only
    // the id would leave the sheet naming the Look for a value the template now owns.
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    const spy = vi.fn()
    api.subscribeToKey('hex-1', 'dimmer', spy)

    frame(
      provenanceFrame([
        { ...PROV, source: 'CUE', layerId: 1, layerSource: { kind: 'LOOK', id: 3, uuid: 'u3', name: 'Warm Wash' } },
      ]),
    )
    expect(spy).toHaveBeenCalledTimes(1)

    frame(
      provenanceFrame([
        { ...PROV, source: 'CUE', layerId: 1, layerSource: { kind: 'TEMPLATE', id: 3, uuid: 't3', name: 'Sweep' } },
      ]),
    )

    expect(spy).toHaveBeenCalledTimes(2)
    expect(spy.mock.calls[1][0].provenance?.layerSource?.kind).toBe('TEMPLATE')
  })

  it('stops delivering to a key subscriber after it unsubscribes', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createProgrammerApi(conn)
    const spy = vi.fn()
    const sub = api.subscribeToKey('hex-1', 'dimmer', spy)

    frame(stateFrame())
    expect(spy).toHaveBeenCalledTimes(1)

    sub.unsubscribe()
    frame(stateFrame([]))
    expect(spy).toHaveBeenCalledTimes(1)
  })

  // Two `entrySignature` guards stood here, both keyed on palette fields: that a palette *rename*
  // woke the cell (it moves nothing else on the entry, so a signature ignoring it left the cell
  // painting a stale value indistinguishable from a correct one) and that a reference *ceasing to
  // resolve* did too. Those fields left `ProgrammerEntry` with the `ref:` grammar in session 4. The
  // same class of bug is still guarded — see the provenance-signature test below, which is the live
  // instance of it now that a key can move from a cue to one of the cue's layers.

  // "accepts a PALETTE include target" stood here, feeding `paletteId` / `paletteName` /
  // `paletteType`. `IncludedTargetDto` carries none of those — they went with the palette tables,
  // and its `kind` is `CUE` or `LOOK` — so the test pinned a payload the server cannot emit, which
  // certifies the drift rather than catching it.
})
