import { describe, expect, it, vi } from 'vitest'
import { InternalEventType } from './internalApi'
import { fakeWsConnection } from '../test/fakeWsConnection'
import { createSpeedMastersWsApi, type SpeedMasterLiveState } from './speedMastersWsApi'

const MASTER_1: SpeedMasterLiveState = {
  uuid: 'aaaaaaaa-0000-0000-0000-000000000001',
  index: 1,
  name: 'Master 1',
  bpm: 120,
  isRunning: true,
  source: 'MANUAL',
}

const MASTER_2: SpeedMasterLiveState = {
  uuid: 'aaaaaaaa-0000-0000-0000-000000000002',
  index: 2,
  name: 'Master 2',
  bpm: 60,
  isRunning: true,
  source: 'MANUAL',
}

function stateFrame(masters: SpeedMasterLiveState[] = [MASTER_1, MASTER_2]) {
  return { type: 'speedMasters.state', masters }
}

describe('createSpeedMastersWsApi', () => {
  it('asks for no bank snapshot on open — the server pushes one per connection', () => {
    const { conn, sent, fire } = fakeWsConnection()
    createSpeedMastersWsApi(conn)

    fire(InternalEventType.open, new Event('open'))

    expect(sent).toEqual([])
  })

  it('holds the latest bank and exposes it via getState', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createSpeedMastersWsApi(conn)

    expect(api.getState()).toEqual([])
    frame(stateFrame())
    expect(api.getState()).toEqual([MASTER_1, MASTER_2])
  })

  it('patches one master on speedMasters-changed without touching the others', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createSpeedMastersWsApi(conn)
    frame(stateFrame())
    const untouchedBefore = api.getState()[0]

    frame({
      type: 'speedMasters.changed',
      masterUuid: MASTER_2.uuid,
      index: 2,
      bpm: 90,
      source: 'TAP',
      timestampMs: 1,
    })

    const [m1, m2] = api.getState()
    expect(m2.bpm).toBe(90)
    expect(m2.source).toBe('TAP')
    // Identity of the untouched master is preserved — this is what keeps the strip's
    // field-wise cache patch from re-rendering every tile per tap.
    expect(m1).toBe(untouchedBefore)
  })

  it('notifies live subscribers on state and changed frames, list subscribers on CRUD only', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createSpeedMastersWsApi(conn)
    const liveFn = vi.fn()
    const listFn = vi.fn()
    api.subscribe(liveFn)
    api.subscribeList(listFn)

    frame(stateFrame())
    frame({
      type: 'speedMasters.changed',
      masterUuid: MASTER_1.uuid,
      index: 1,
      bpm: 128,
      source: 'TAP',
      timestampMs: 1,
    })
    expect(liveFn).toHaveBeenCalledTimes(2)
    expect(listFn).not.toHaveBeenCalled()

    frame({ type: 'speedMasters.listChanged' })
    expect(listFn).toHaveBeenCalledTimes(1)
    // A tempo change must never masquerade as CRUD — that is the invalidation-storm rule.
    expect(liveFn).toHaveBeenCalledTimes(2)
  })

  it('re-requests the bank when membership changes', () => {
    const { conn, sent, frame } = fakeWsConnection()
    createSpeedMastersWsApi(conn)

    frame({ type: 'speedMasters.listChanged' })

    expect(sent).toContainEqual({ type: 'speedMasters.state' })
  })

  it('sends keyed and unkeyed tempo writes', () => {
    const { conn, sent } = fakeWsConnection()
    const api = createSpeedMastersWsApi(conn)

    api.setBpm(MASTER_2.uuid, 72)
    api.tap(null)

    expect(sent).toEqual([
      { type: 'speedMasters.setBpm', masterUuid: MASTER_2.uuid, bpm: 72 },
      { type: 'speedMasters.tap' },
    ])
  })

  it('routes a beat frame only to the subscribers of that master', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createSpeedMastersWsApi(conn)

    const onM1 = vi.fn()
    const onM2 = vi.fn()
    // Master 1 by its real uuid: that is what its frames carry once the bank has loaded.
    // (The null key is only reachable pre-load — see the case below.)
    api.subscribeBeat(MASTER_1.uuid, onM1)
    api.subscribeBeat(MASTER_2.uuid, onM2)

    frame({
      type: 'speedMasters.beat',
      masterUuid: MASTER_2.uuid,
      index: 2,
      beatNumber: 16,
      bpm: 60,
      timestampMs: 1,
    })

    // The whole point of keying: a master-2 beat must not pulse a master-1 indicator.
    expect(onM2).toHaveBeenCalledTimes(1)
    expect(onM2.mock.calls[0][0]).toMatchObject({ index: 2, beatNumber: 16, bpm: 60 })
    expect(onM1).not.toHaveBeenCalled()

    frame({
      type: 'speedMasters.beat',
      masterUuid: MASTER_1.uuid,
      index: 1,
      beatNumber: 32,
      bpm: 120,
      timestampMs: 2,
    })
    expect(onM1).toHaveBeenCalledTimes(1)
    expect(onM2).toHaveBeenCalledTimes(1)
  })

  it('routes the pre-load master 1 frame, which carries no uuid, to the null key', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createSpeedMastersWsApi(conn)

    // Before the bank loads, slot 0 is the synthetic master 1 and has no row to name it. An
    // indicator that mounted that early subscribed with null, and must still be pulsed.
    const onM1 = vi.fn()
    api.subscribeBeat(null, onM1)

    frame({
      type: 'speedMasters.beat',
      masterUuid: null,
      index: 1,
      beatNumber: 16,
      bpm: 120,
      timestampMs: 1,
    })

    expect(onM1).toHaveBeenCalledTimes(1)
  })

  it('requests an immediate beat when a subscriber attaches', () => {
    const { conn, sent } = fakeWsConnection()
    const api = createSpeedMastersWsApi(conn)

    // Frames are throttled to one every 16 beats, so without this a freshly-mounted
    // indicator would sit unsynced for ~8s at 120 BPM.
    api.subscribeBeat(MASTER_2.uuid, () => {})
    api.subscribeBeat(null, () => {})

    expect(sent).toEqual([
      { type: 'speedMasters.requestBeat', masterUuid: MASTER_2.uuid },
      { type: 'speedMasters.requestBeat' },
    ])
  })

  it('requests a beat without resubscribing', () => {
    const { conn, sent } = fakeWsConnection()
    const api = createSpeedMastersWsApi(conn)

    // For a subscriber whose local timer went stale (a backgrounded tab) but whose
    // subscription is still good — re-subscribing to get the frame would be the long way
    // round, and re-binds a subscription that was never the problem.
    api.requestBeat(MASTER_2.uuid)

    expect(sent).toEqual([{ type: 'speedMasters.requestBeat', masterUuid: MASTER_2.uuid }])
  })

  it('re-requests beats for every subscribed master on reconnect', () => {
    const { conn, sent, fire } = fakeWsConnection()
    const api = createSpeedMastersWsApi(conn)
    api.subscribeBeat(MASTER_2.uuid, () => {})
    api.subscribeBeat(null, () => {})
    sent.length = 0

    fire(InternalEventType.open, new Event('open'))

    // The server's pending-request set is per-connection, so a reconnect drops every
    // one-shot request; without re-asking, an indicator free-runs at the pre-drop tempo
    // until the next throttled frame.
    expect(sent).toEqual([
      { type: 'speedMasters.requestBeat', masterUuid: MASTER_2.uuid },
      { type: 'speedMasters.requestBeat' },
    ])
  })

  it('stops re-requesting beats for a master nothing watches any more', () => {
    const { conn, sent, fire } = fakeWsConnection()
    const api = createSpeedMastersWsApi(conn)
    const kept = api.subscribeBeat(MASTER_2.uuid, () => {})
    const dropped = api.subscribeBeat(null, () => {})
    dropped.unsubscribe()
    // Twice: an unsubscribe running again must not disturb the map (React can call a cleanup
    // more than once, and the key may since have been re-pooled for a live subscriber).
    dropped.unsubscribe()
    sent.length = 0

    fire(InternalEventType.open, new Event('open'))

    // Only the master still on screen. Without pruning, the map holds every master ever
    // displayed and every reconnect asks the desk for beats nothing is listening to.
    expect(sent).toEqual([{ type: 'speedMasters.requestBeat', masterUuid: MASTER_2.uuid }])
    kept.unsubscribe()
  })

  it('re-pools a master a later subscriber comes back to', () => {
    const { conn, sent, frame } = fakeWsConnection()
    const api = createSpeedMastersWsApi(conn)
    api.subscribeBeat(MASTER_2.uuid, () => {}).unsubscribe()

    const onBeat = vi.fn()
    api.subscribeBeat(MASTER_2.uuid, onBeat)
    frame({
      type: 'speedMasters.beat',
      masterUuid: MASTER_2.uuid,
      index: 2,
      beatNumber: 16,
      bpm: 60,
      timestampMs: 1,
    })

    expect(onBeat).toHaveBeenCalledTimes(1)
    expect(sent).toContainEqual({ type: 'speedMasters.requestBeat', masterUuid: MASTER_2.uuid })
  })

  it('stops delivering beats after unsubscribe', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createSpeedMastersWsApi(conn)
    const onBeat = vi.fn()
    const sub = api.subscribeBeat(MASTER_2.uuid, onBeat)

    sub.unsubscribe()
    frame({
      type: 'speedMasters.beat',
      masterUuid: MASTER_2.uuid,
      index: 2,
      beatNumber: 16,
      bpm: 60,
      timestampMs: 1,
    })

    expect(onBeat).not.toHaveBeenCalled()
  })

  it('ignores unrelated frames without parsing trouble', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createSpeedMastersWsApi(conn)
    frame(stateFrame())

    frame({ type: 'channelState', changes: [] })
    frame({ type: 'fxState', activeEffects: [] })

    expect(api.getState()).toEqual([MASTER_1, MASTER_2])
  })
})

describe('speedMasters.error', () => {
  it('reaches subscribeError with the server phrasing intact', () => {
    // The message is the server's single operator-facing sentence, shared with the MIDI log —
    // clients display it rather than composing their own from the code.
    const { conn, frame } = fakeWsConnection()
    const api = createSpeedMastersWsApi(conn)
    const onError = vi.fn()
    api.subscribeError(onError)

    frame({
      type: 'speedMasters.error',
      masterUuid: MASTER_2.uuid,
      code: 'SPEED_MASTER_FOLLOWER',
      message: 'Master 2 follows Master 1 at 1/2 — unlink it in the speed-master sheet to set its tempo',
    })

    expect(onError).toHaveBeenCalledTimes(1)
    expect(onError.mock.calls[0][0]).toMatchObject({
      masterUuid: MASTER_2.uuid,
      code: 'SPEED_MASTER_FOLLOWER',
    })
  })

  it('leaves the bank alone — the state frame that follows carries the truth', () => {
    const { conn, frame } = fakeWsConnection()
    const api = createSpeedMastersWsApi(conn)
    frame(stateFrame())

    frame({
      type: 'speedMasters.error',
      masterUuid: MASTER_2.uuid,
      code: 'SPEED_MASTER_UNKNOWN',
      message: 'No speed master with that uuid — the write was dropped',
    })

    expect(api.getState()).toEqual([MASTER_1, MASTER_2])
  })
})
