import { describe, expect, it } from 'vitest'
import {
  bindingsForScreens,
  createSurfacesWsApi,
  type ControlSurfaceBinding,
  type ControlPart,
} from './surfacesApi'
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

function row(id: number, controlId: string, part: ControlPart, bank: string | null = null): ControlSurfaceBinding {
  return {
    id,
    projectId: 1,
    deviceTypeKey: 'xtc',
    controlId,
    part,
    bank,
    binding: { does: { type: 'go' }, on: { type: 'record', kind: 'STACK', uuid: 's' } },
    target: { type: 'cueStackGo', stackId: 1, stackUuid: 's' },
    targetType: 'cueStackGo',
    takeoverPolicy: null,
    sortOrder: id,
    health: { type: 'ok' },
  }
}

describe('bindingsForScreens', () => {
  it('shows one row per control and layer: the main part, else the push', () => {
    const rows = [
      row(1, 'enc-1', 'PUSH'),
      row(2, 'enc-1', 'MAIN'),
      row(3, 'enc-2', 'PUSH'),
      row(4, 'enc-1', 'PUSH', 'layer-b'),
      row(5, 'btn-1', 'MAIN'),
    ]
    expect(bindingsForScreens(rows).map((b) => b.id)).toEqual([2, 3, 4, 5])
  })
})

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

  it('names the device, not its type, when setting a bank', () => {
    const fake = fakeConnection()
    const api = createSurfacesWsApi(fake.conn)
    api.setBank('xtc-right', 'b')
    expect(fake.sent).toEqual([{ type: 'surfaceBank.set', displayKey: 'xtc-right', bank: 'b' }])
  })
})
