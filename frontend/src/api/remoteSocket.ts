import { addBreadcrumb } from '@sentry/react'
import { countWsReconnect, type ReconnectCause } from '../lib/sentryMetrics'
import type { RemoteLink } from '../lib/remoteMode'
import type { RemoteFrameMessage } from '../lib/remoteFrame'
import { createConnectionEvents, type InternalApiConnection, UNAUTHENTICATED_CLOSE_CODE } from './internalApi'

const CONNECTING = 0
const OPEN = 1
const CLOSED = 3

/** The close code a lost port reads as: the browser's own for a socket that went without a close. */
export const PORT_LOST_CLOSE_CODE = 1006

/** How long the shell has to answer an `open` before the port is taken as gone. */
export const OPEN_TIMEOUT_MS = 15_000

/**
 * The desk's socket in remote mode (cloud-p1 plan D9): an [InternalApiConnection] over the shell's
 * `MessagePort`, which bridges it to a WebSocket on the desk's public listener through the sidecar.
 * It emits real `Event`s, `MessageEvent`s and `CloseEvent`s, so every bridge, `statusApi`'s
 * readyState and the 4401 path behave as they do over a WebSocket.
 *
 * One socket at a time (`docs/cloud-protocol.md` §"The remote frame"): `open {path}` replaces any
 * socket the shell holds for this port; the shell's `close` ends the one last opened, and while the
 * SPA waits for `open` it means that one did not open. The port outlives its sockets. One that is
 * gone — said by its `close` event where the browser fires one, else by an `open` unanswered for
 * [OPEN_TIMEOUT_MS] — ends the socket, and the reconnect asks the shell for a fresh port first. The
 * backoff, the 4401 rule and the reconnect metric are `internalApi`'s.
 */
export function createRemoteSocketConnection(baseUrl: string, link: RemoteLink, path = '/api'): InternalApiConnection {
  const events = createConnectionEvents()
  let readyState = CONNECTING
  let reconnectDelay = 1000
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  // Why the next open is a reconnect; null until this page's first socket has opened.
  let reopenCause: ReconnectCause | null = null
  let openTimer: ReturnType<typeof setTimeout> | null = null
  // The port went unanswered or gone: the next connect asks the shell for a fresh one first.
  let renewFirst = false

  function clearOpenTimer() {
    if (openTimer) clearTimeout(openTimer)
    openTimer = null
  }

  function closed(code: number, reason: string) {
    clearOpenTimer()
    readyState = CLOSED
    addBreadcrumb({ category: 'websocket', message: 'close', data: { code, remote: true } })
    events.notify(new CloseEvent('close', { code, reason, wasClean: code === 1000 }))
    if (code !== UNAUTHENTICATED_CLOSE_CODE) scheduleReconnect()
  }

  link.subscribe((message: RemoteFrameMessage) => {
    switch (message.type) {
      case 'open':
        if (readyState !== CONNECTING) return
        clearOpenTimer()
        readyState = OPEN
        reconnectDelay = 1000
        addBreadcrumb({ category: 'websocket', message: 'open', data: { remote: true } })
        if (reopenCause != null) countWsReconnect(reopenCause)
        reopenCause = 'dropped'
        events.notify(new Event('open'))
        return
      case 'message':
        if (readyState !== OPEN || typeof message.data !== 'string') return
        events.notify(new MessageEvent('message', { data: message.data }))
        return
      case 'close':
        if (readyState === CLOSED) return
        closed(typeof message.code === 'number' ? message.code : PORT_LOST_CLOSE_CODE, message.reason ?? '')
        return
    }
  })
  link.onLost(() => {
    renewFirst = true
    if (readyState !== CLOSED) closed(PORT_LOST_CLOSE_CODE, 'The remote frame closed')
  })

  function scheduleReconnect() {
    if (reconnectTimer) return
    addBreadcrumb({ category: 'websocket', message: 'reconnect scheduled', data: { delayMs: reconnectDelay, remote: true } })
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      reconnectDelay = Math.min(reconnectDelay * 2, 30_000)
      connect()
    }, reconnectDelay)
  }

  function postOpen(): boolean {
    if (!link.post({ type: 'open', path })) return false
    clearOpenTimer()
    openTimer = setTimeout(() => {
      openTimer = null
      if (readyState !== CONNECTING) return
      renewFirst = true
      closed(PORT_LOST_CLOSE_CODE, 'The remote frame did not answer')
    }, OPEN_TIMEOUT_MS)
    return true
  }

  function connect() {
    readyState = CONNECTING
    if (!renewFirst && postOpen()) return
    renewFirst = false
    void link.renew().then((renewed) => {
      if (readyState !== CONNECTING) return
      if (renewed && postOpen()) return
      renewFirst = true
      closed(PORT_LOST_CLOSE_CODE, 'The remote frame closed')
    })
  }

  connect()

  return {
    baseUrl,
    readyState: () => readyState,
    send(data) {
      if (readyState !== OPEN) return false
      if (typeof data !== 'string') {
        console.error('RemoteSocket: only text frames cross the remote frame')
        return false
      }
      return link.post({ type: 'send', data })
    },
    reconnect(force = false) {
      if (reconnectTimer) {
        clearTimeout(reconnectTimer)
        reconnectTimer = null
      }
      const isLive = readyState === OPEN || readyState === CONNECTING
      if (!force && isLive) return
      reconnectDelay = 1000
      addBreadcrumb({ category: 'websocket', message: 'reconnect', data: { force, remote: true } })
      if (reopenCause != null) reopenCause = force ? 'forced' : 'dropped'
      if (isLive) {
        // As `internalApi` does: subscribers see the old socket close, and only its replacement reconnects.
        clearOpenTimer()
        link.post({ type: 'close', code: 1000, reason: '' })
        readyState = CLOSED
        events.notify(new CloseEvent('close', { code: 1000, reason: '', wasClean: true }))
      }
      connect()
    },
    subscribe: events.subscribe,
  }
}
