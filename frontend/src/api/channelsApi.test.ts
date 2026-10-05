// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { createChannelsApi } from './channelsApi'
import { InternalEventType, type InternalApiConnection, type InternalEventHandler } from './internalApi'

/** A connection the test drives: it delivers parsed frames. */
function fakeConnection() {
  const handlers = new Set<InternalEventHandler>()
  const conn: InternalApiConnection = {
    baseUrl: '',
    readyState: () => 1,
    send: () => true,
    subscribe: (fn) => {
      handlers.add(fn)
      return { unsubscribe: () => handlers.delete(fn) }
    },
    reconnect: () => {},
  }
  const emit = (type: InternalEventType, message: unknown = null) =>
    handlers.forEach((fn) => fn(type, new Event(type), message))
  return {
    conn,
    open: () => emit(InternalEventType.open),
    channels: (level: number, snapshot?: boolean) =>
      emit(InternalEventType.message, {
        type: 'channelState',
        channels: [{ universe: 0, id: 1, currentLevel: level }],
        ...(snapshot ? { snapshot } : {}),
      }),
  }
}

// The Stage view's travel easing lands every axis across a whole-buffer frame (`lib/travel.ts`): a
// replacement, not a move. The desk marks one `snapshot: true`; arrival order says nothing, since a
// delta can reach a fresh socket before its snapshot.
describe('the wire’s snapshot counter', () => {
  it('counts the frames the desk marks as whole-buffer, and no delta', () => {
    const fake = fakeConnection()
    const api = createChannelsApi(fake.conn)
    expect(api.snapshotEpoch()).toBe(0)
    fake.channels(10) // a delta that beat the snapshot to a fresh socket
    expect(api.snapshotEpoch()).toBe(0)
    fake.channels(20, true) // the snapshot
    expect(api.snapshotEpoch()).toBe(1)
    fake.channels(30)
    fake.open() // a reconnect alone counts nothing
    expect(api.snapshotEpoch()).toBe(1)
    fake.channels(40, true) // its snapshot, or a resync reply
    expect(api.snapshotEpoch()).toBe(2)
    expect(api.get(0, 1)).toBe(40)
  })
})
