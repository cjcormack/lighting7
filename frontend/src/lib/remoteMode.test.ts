// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { FakePort, fixtureMessage, framedWindow, messageEvent, remoteFrameFixture } from '@/test/remoteFrameFakes'
import {
  awaitShellPort,
  createRemoteLink,
  endRemoteSession,
  isRemoteMode,
  reportUnlinked,
  setRemoteLinkForTests,
} from './remoteMode'

const SHELL = 'https://lighting7.net'

afterEach(() => {
  setRemoteLinkForTests(null)
  vi.useRealTimers()
})

describe('awaitShellPort', () => {
  it('says hello to the shell’s origin and takes the port only from the parent at that origin', async () => {
    const { win, parent, deliver } = framedWindow()
    const answer = awaitShellPort(win, SHELL, 1_000)
    expect(parent.posted).toEqual([{ message: fixtureMessage((e) => e.message.type === 'hello'), targetOrigin: SHELL }])

    const portMessage = fixtureMessage((e) => e.over === 'window' && e.message.type === 'port')
    const foreign = new FakePort()
    deliver(portMessage, { origin: 'https://evil.example', ports: [foreign] })
    deliver(portMessage, { origin: SHELL, source: {}, ports: [foreign] })
    deliver({ type: 'port' }, { origin: SHELL, ports: [] })
    deliver({ type: 'nonsense' }, { origin: SHELL, ports: [foreign] })

    const port = new FakePort()
    deliver(portMessage, { origin: SHELL, ports: [port] })
    expect(await answer).toBe(port)
  })

  it('answers null when the shell never sends the port', async () => {
    vi.useFakeTimers()
    const { win } = framedWindow()
    const answer = awaitShellPort(win, SHELL, 1_000)
    vi.advanceTimersByTime(1_000)
    expect(await answer).toBeNull()
  })
})

describe('createRemoteLink', () => {
  it('posts on the port, hands contract messages to subscribers and ignores anything else', () => {
    const { win } = framedWindow()
    const port = new FakePort()
    const link = createRemoteLink(win, SHELL, port.asPort())
    expect(port.started).toBe(true)
    const seen: unknown[] = []
    link.subscribe((m) => seen.push(m))

    const shellMessage = fixtureMessage((e) => e.from === 'shell' && e.message.type === 'message')
    port.deliver(shellMessage)
    port.deliver({ type: 'somebody-else' })
    port.deliver('not an object')
    expect(seen).toEqual([shellMessage])

    expect(link.post({ type: 'session.end' })).toBe(true)
    expect(port.messages).toEqual([{ type: 'session.end' }])
  })

  it('goes dead when the port closes, and renews it with a fresh hello', async () => {
    const { win, parent, deliver } = framedWindow()
    const port = new FakePort()
    const link = createRemoteLink(win, SHELL, port.asPort())
    const lost = vi.fn()
    link.onLost(lost)
    const seen: unknown[] = []
    link.subscribe((m) => seen.push(m))

    port.closeFromShell()
    expect(lost).toHaveBeenCalledTimes(1)
    expect(link.post({ type: 'session.end' })).toBe(false)

    const renewed = link.renew()
    expect(parent.posted.map((p) => p.message)).toEqual([{ type: 'hello', protocol: remoteFrameFixture.protocol }])
    const next = new FakePort()
    deliver({ type: 'port' }, { origin: SHELL, ports: [next] })
    expect(await renewed).toBe(true)
    expect(link.post({ type: 'session.end' })).toBe(true)
    expect(next.messages).toEqual([{ type: 'session.end' }])

    next.deliver({ type: 'open' })
    port.deliver({ type: 'open' })
    expect(seen).toEqual([{ type: 'open' }])
  })

  it('answers a restarted worker’s port.request with a fresh port from the shell', () => {
    const { win } = framedWindow()
    const port = new FakePort()
    const container = Object.assign(new EventTarget(), { controller: null, startMessages: vi.fn() })
    createRemoteLink(win, SHELL, port.asPort(), container as unknown as ServiceWorkerContainer)
    expect(container.startMessages).toHaveBeenCalled()

    const worker = { postMessage: vi.fn() }
    container.dispatchEvent(
      messageEvent(fixtureMessage((e) => e.from === 'worker' && e.message.type === 'port.request'), { source: worker }),
    )
    expect(port.messages).toEqual([fixtureMessage((e) => e.from === 'spa' && e.to === 'shell' && e.message.type === 'port.request')])

    const fresh = new FakePort()
    port.deliver(fixtureMessage((e) => e.from === 'shell' && e.over === 'port' && e.message.type === 'port'), [fresh])
    expect(worker.postMessage).toHaveBeenCalledWith(
      fixtureMessage((e) => e.to === 'worker' && e.message.type === 'port'),
      { transfer: [fresh] },
    )

    // A port nobody asked for is closed.
    const stray = Object.assign(new FakePort(), { close: vi.fn() })
    port.deliver({ type: 'port' }, [stray])
    expect(worker.postMessage).toHaveBeenCalledTimes(1)
    expect(stray.close).toHaveBeenCalled()
  })

  it('asks again on the next port for a worker whose request the last port never answered', async () => {
    const { win, deliver } = framedWindow()
    const port = new FakePort()
    const container = Object.assign(new EventTarget(), { controller: null, startMessages: vi.fn() })
    const link = createRemoteLink(win, SHELL, port.asPort(), container as unknown as ServiceWorkerContainer)
    const worker = { postMessage: vi.fn() }

    port.closeFromShell()
    container.dispatchEvent(messageEvent({ type: 'port.request' }, { source: worker }))
    expect(port.messages).toEqual([])

    const renewed = link.renew()
    const next = new FakePort()
    deliver({ type: 'port' }, { origin: SHELL, ports: [next] })
    await renewed
    expect(next.messages).toEqual([{ type: 'port.request' }])

    const fresh = new FakePort()
    next.deliver({ type: 'port' }, [fresh])
    expect(worker.postMessage).toHaveBeenCalledWith({ type: 'port' }, { transfer: [fresh] })
  })
})

describe('the session messages', () => {
  it('posts session.end and auth.unlinked on the port, and nothing outside remote mode', () => {
    endRemoteSession()
    reportUnlinked()
    expect(isRemoteMode()).toBe(false)

    const { win } = framedWindow()
    const port = new FakePort()
    setRemoteLinkForTests(createRemoteLink(win, SHELL, port.asPort()))
    expect(isRemoteMode()).toBe(true)
    endRemoteSession()
    reportUnlinked()
    expect(port.messages).toEqual([
      fixtureMessage((e) => e.message.type === 'session.end'),
      fixtureMessage((e) => e.message.type === 'auth.unlinked'),
    ])
  })
})
