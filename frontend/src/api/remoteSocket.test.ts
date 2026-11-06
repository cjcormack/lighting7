// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { FakePort, fixtureMessage, framedWindow } from '@/test/remoteFrameFakes'
import { createRemoteLink } from '@/lib/remoteMode'
import { InternalEventType } from './internalApi'
import { createRemoteSocketConnection, OPEN_TIMEOUT_MS, PORT_LOST_CLOSE_CODE } from './remoteSocket'

const reconnects = vi.hoisted(() => [] as string[])
vi.mock('../lib/sentryMetrics', () => ({ countWsReconnect: (cause: string) => reconnects.push(cause) }))

const SHELL = 'https://lighting7.net'

function connect() {
  const framed = framedWindow()
  const port = new FakePort()
  const link = createRemoteLink(framed.win, SHELL, port.asPort())
  const connection = createRemoteSocketConnection('/api/', link)
  const events: { type: InternalEventType; ev: Event; message: unknown }[] = []
  connection.subscribe((type, ev, message) => events.push({ type, ev, message }))
  return { ...framed, port, connection, events }
}

const shellOpen = () => fixtureMessage((e) => e.from === 'shell' && e.message.type === 'open')
const shellClose = () => fixtureMessage((e) => e.from === 'shell' && e.message.type === 'close')

beforeEach(() => {
  vi.useFakeTimers()
})
afterEach(() => {
  vi.useRealTimers()
})

describe('RemoteSocket', () => {
  it('opens with the fixture’s open, and is OPEN with a real open event once the shell says so', () => {
    const { port, connection, events } = connect()
    expect(port.messages).toEqual([fixtureMessage((e) => e.from === 'spa' && e.message.type === 'open')])
    expect(connection.readyState()).toBe(WebSocket.CONNECTING)
    expect(connection.send('{"type":"fxState"}')).toBe(false)

    port.deliver(shellOpen())
    expect(connection.readyState()).toBe(WebSocket.OPEN)
    expect(events.map((e) => e.type)).toEqual([InternalEventType.open])
    expect(events[0].ev).toBeInstanceOf(Event)
  })

  it('carries frames both ways as the fixture spells them, each inbound one a real MessageEvent parsed once', () => {
    const { port, connection, events } = connect()
    port.deliver(shellOpen())

    const send = fixtureMessage((e) => e.message.type === 'send')
    expect(connection.send(send.data as string)).toBe(true)
    expect(port.messages.at(-1)).toEqual(send)

    const inbound = fixtureMessage((e) => e.message.type === 'message')
    port.deliver(inbound)
    const last = events.at(-1)!
    expect(last.type).toBe(InternalEventType.message)
    expect(last.ev).toBeInstanceOf(MessageEvent)
    expect((last.ev as MessageEvent).data).toBe(inbound.data)
    expect(last.message).toEqual(JSON.parse(inbound.data as string))
  })

  it('sends only text', () => {
    const { port, connection } = connect()
    port.deliver(shellOpen())
    vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(connection.send(new Uint8Array([1, 2]))).toBe(false)
    expect(port.messages).toHaveLength(1)
  })

  it('closes on the desk’s 4401 with a real CloseEvent and does not reconnect', () => {
    const { port, connection, events } = connect()
    port.deliver(shellOpen())
    port.deliver(shellClose())

    const close = events.at(-1)!
    expect(close.ev).toBeInstanceOf(CloseEvent)
    expect((close.ev as CloseEvent).code).toBe(4401)
    expect((close.ev as CloseEvent).reason).toBe('session ended')
    expect(connection.readyState()).toBe(WebSocket.CLOSED)

    vi.advanceTimersByTime(60_000)
    expect(port.messages).toHaveLength(1)
  })

  it('reopens after any other close with internalApi’s backoff', () => {
    const { port, connection } = connect()
    port.deliver(shellOpen())
    port.deliver({ type: 'close', code: 1011, reason: 'desk restarting' })
    expect(connection.readyState()).toBe(WebSocket.CLOSED)

    vi.advanceTimersByTime(999)
    expect(port.messages).toHaveLength(1)
    vi.advanceTimersByTime(1)
    expect(port.messages.at(-1)).toEqual({ type: 'open', path: '/api' })
    expect(connection.readyState()).toBe(WebSocket.CONNECTING)

    // The open failed: the next try waits twice as long.
    port.deliver({ type: 'close', code: 1006, reason: '' })
    vi.advanceTimersByTime(1_999)
    expect(port.messages).toHaveLength(2)
    vi.advanceTimersByTime(1)
    expect(port.messages).toHaveLength(3)
  })

  it('takes an open the shell never answers as a gone port, and reopens on a fresh one from a hello', async () => {
    // A browser that fires no `close` on a port the shell let go says so only by the silence.
    const { port, parent, deliver, connection, events } = connect()
    const close = vi.spyOn(port, 'close')
    vi.advanceTimersByTime(OPEN_TIMEOUT_MS - 1)
    expect(connection.readyState()).toBe(WebSocket.CONNECTING)
    vi.advanceTimersByTime(1)
    expect((events.at(-1)!.ev as CloseEvent).code).toBe(PORT_LOST_CLOSE_CODE)
    expect(connection.readyState()).toBe(WebSocket.CLOSED)

    vi.advanceTimersByTime(1_000)
    expect(port.messages).toEqual([{ type: 'open', path: '/api' }])
    expect(parent.posted.map((p) => p.message)).toEqual([{ type: 'hello', protocol: 1 }])
    const next = new FakePort()
    deliver({ type: 'port' }, { origin: SHELL, ports: [next] })
    await vi.waitFor(() => expect(next.messages).toEqual([{ type: 'open', path: '/api' }]))
    expect(close).toHaveBeenCalled()

    next.deliver(shellOpen())
    expect(connection.readyState()).toBe(WebSocket.OPEN)
    // An answered open sets no timer that could close it later.
    vi.advanceTimersByTime(OPEN_TIMEOUT_MS * 2)
    expect(connection.readyState()).toBe(WebSocket.OPEN)
  })

  it('counts each re-open as internalApi does, and not the first open', () => {
    reconnects.length = 0
    const { port, connection } = connect()
    port.deliver(shellOpen())
    expect(reconnects).toEqual([])
    port.deliver({ type: 'close', code: 1011, reason: '' })
    vi.advanceTimersByTime(1_000)
    port.deliver(shellOpen())
    connection.reconnect(true)
    port.deliver(shellOpen())
    expect(reconnects).toEqual(['dropped', 'forced'])
  })

  it('closes the old socket with the fixture’s close on a forced reconnect, and opens a new one', () => {
    const { port, connection, events } = connect()
    port.deliver(shellOpen())
    connection.reconnect(true)
    expect(port.messages.slice(-2)).toEqual([
      fixtureMessage((e) => e.from === 'spa' && e.message.type === 'close'),
      { type: 'open', path: '/api' },
    ])
    expect((events.at(-1)!.ev as CloseEvent).code).toBe(1000)
    expect(connection.readyState()).toBe(WebSocket.CONNECTING)

    connection.reconnect()
    expect(port.messages).toHaveLength(3)
  })

  it('closes when the port closes, and on reconnect asks the shell for a fresh port first', async () => {
    const { port, parent, deliver, connection, events } = connect()
    port.deliver(shellOpen())
    port.closeFromShell()

    expect((events.at(-1)!.ev as CloseEvent).code).toBe(PORT_LOST_CLOSE_CODE)
    expect(connection.readyState()).toBe(WebSocket.CLOSED)

    vi.advanceTimersByTime(1_000)
    expect(parent.posted.map((p) => p.message)).toEqual([{ type: 'hello', protocol: 1 }])
    const next = new FakePort()
    deliver({ type: 'port' }, { origin: SHELL, ports: [next] })
    await vi.waitFor(() => expect(next.messages).toEqual([{ type: 'open', path: '/api' }]))

    next.deliver(shellOpen())
    expect(connection.readyState()).toBe(WebSocket.OPEN)
  })
})
