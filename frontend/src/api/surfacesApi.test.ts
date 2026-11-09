import { describe, expect, it } from 'vitest'
import { createSurfacesWsApi } from './surfacesApi'
import { InternalEventType, type InternalApiConnection, type InternalEventHandler } from './internalApi'
import { Status } from './statusApi'

/** A connection the test drives: it delivers parsed frames and records what is sent. */
function fakeConnection() {
  const handlers = new Set<InternalEventHandler>()
  const sent: unknown[] = []
  const conn: InternalApiConnection = {
    baseUrl: '',
    readyState: () => Status.OPEN,
    send: (data) => {
      sent.push(JSON.parse(data as string))
      return true
    },
    subscribe: (fn) => {
      handlers.add(fn)
      return { unsubscribe: () => handlers.delete(fn) }
    },
    reconnect: () => {},
  }
  const message = (frame: unknown) =>
    handlers.forEach((fn) => fn(InternalEventType.message, new Event('message'), frame))
  return { conn, sent, message }
}

// Two identical surfaces share their type's bindings but each keeps its own layer (D16).
describe('active banks per device', () => {
  it('patches only the device a surfaceBank.changed names, not its type-mates', () => {
    const fake = fakeConnection()
    const api = createSurfacesWsApi(fake.conn)
    fake.message({ type: 'surfaceBank.state', activeBanks: { 'xtc-left': 'a', 'xtc-right': 'a' } })
    fake.message({
      type: 'surfaceBank.changed',
      displayKey: 'xtc-right',
      deviceTypeKey: 'xtc',
      previousBank: 'a',
      newBank: 'b',
    })
    expect(api.getBanks()).toEqual({ 'xtc-left': 'a', 'xtc-right': 'b' })
    fake.message({
      type: 'surfaceBank.changed',
      displayKey: 'xtc-left',
      deviceTypeKey: 'xtc',
      previousBank: 'a',
      newBank: null,
    })
    expect(api.getBanks()).toEqual({ 'xtc-right': 'b' })
  })

})

describe('the served map', () => {
  it('keeps each device type\'s latest frame, replacing only the type a frame names', () => {
    const fake = fakeConnection()
    const api = createSurfacesWsApi(fake.conn)
    fake.message({ type: 'surfaceMap.state', projectId: 1, deviceTypeKey: 'xtc', layers: { a: {} } })
    fake.message({ type: 'surfaceMap.state', projectId: 1, deviceTypeKey: 'other', layers: {} })
    fake.message({ type: 'surfaceMap.state', projectId: 1, deviceTypeKey: 'xtc', layers: { b: {} } })
    expect(Object.keys(api.getMaps() ?? {})).toEqual(['xtc', 'other'])
    expect(api.getMaps()?.xtc.layers).toEqual({ b: {} })
  })

  it('has no frame that switches a device\'s layer', () => {
    const api = createSurfacesWsApi(fakeConnection().conn) as unknown as Record<string, unknown>
    expect(api.setBank).toBeUndefined()
  })
})

describe('the uses index', () => {
  it('keeps the latest frame whole and replays it to a late subscriber', () => {
    const fake = fakeConnection()
    const api = createSurfacesWsApi(fake.conn)
    expect(api.getUses()).toBeNull()
    fake.message({ type: 'surfaceUses.state', projectId: 1, uses: { 'group:a': [] } })
    fake.message({ type: 'surfaceUses.state', projectId: 1, uses: { 'group:b': [] } })
    expect(api.getUses()).toEqual({ projectId: 1, uses: { 'group:b': [] } })
    const seen: unknown[] = []
    api.subscribeUses((frame) => seen.push(frame))
    expect(seen).toEqual([{ projectId: 1, uses: { 'group:b': [] } }])
  })
})

describe('identify', () => {
  it('asks for a touch and relays its wait and its capture', () => {
    const fake = fakeConnection()
    const api = createSurfacesWsApi(fake.conn)
    const events: unknown[] = []
    api.subscribeIdentify((event) => events.push(event))
    api.beginIdentify('xtc')
    expect(fake.sent).toEqual([{ type: 'surfaceIdentify.begin', deviceTypeKey: 'xtc' }])
    fake.message({ type: 'surfaceIdentify.state', sessionId: 's', waiting: true, deadlineMs: 5, remainingMs: 30000 })
    fake.message({ type: 'surfaceIdentify.captured', sessionId: 's', deviceTypeKey: 'xtc', displayKey: 'xtc-1', controlId: 'btn-1', part: 'MAIN' })
    expect(events).toEqual([
      { type: 'state', sessionId: 's', waiting: true, deadlineMs: 5, remainingMs: 30000 },
      { type: 'captured', sessionId: 's', deviceTypeKey: 'xtc', displayKey: 'xtc-1', controlId: 'btn-1', part: 'MAIN' },
    ])
  })
})
