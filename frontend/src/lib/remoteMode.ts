import { asRemoteFrameMessage, REMOTE_FRAME_PROTOCOL, type RemoteFrameMessage } from './remoteFrame'

/**
 * Remote mode (cloud-p1 plan D8, D9): this SPA framed by Lighting7 Cloud's shell, its socket riding
 * a `MessagePort` the shell hands it. `remoteBoot.ts` decides the mode before `./main` loads, so
 * nothing here may import the app: `api/lightingApi.ts` reads [isRemoteMode] as it builds its
 * connection.
 */

/** How long the shell has to answer a `hello` with the port. */
export const SHELL_PORT_TIMEOUT_MS = 10_000

/** The SPA's end of the shell's port, kept across the shell replacing it. */
export interface RemoteLink {
  /** Post on the port; false once it is gone. */
  post(message: RemoteFrameMessage): boolean
  /** Every contract message the shell posts on the port, this one or the next. */
  subscribe(fn: (message: RemoteFrameMessage) => void): () => void
  /** Called when the port closes, on the browsers that fire `MessagePort`'s `close` event. */
  onLost(fn: () => void): () => void
  /**
   * Ask the shell for a fresh port with a `hello`, as at boot, closing the old one; false if the shell
   * does not answer. The new port supersedes the old, and the shell drops what it held on it.
   */
  renew(): Promise<boolean>
}

/**
 * Post `hello` to the parent at [parentOrigin] and wait for its `port`. A message from another
 * origin, or from anything but the parent, is ignored. Null on a timeout.
 */
export function awaitShellPort(win: Window, parentOrigin: string, timeoutMs = SHELL_PORT_TIMEOUT_MS): Promise<MessagePort | null> {
  return new Promise((resolve) => {
    const onMessage = (ev: MessageEvent) => {
      if (ev.origin !== parentOrigin || ev.source !== win.parent) return
      if (asRemoteFrameMessage(ev.data)?.type !== 'port') return
      const port = ev.ports[0]
      if (port == null) return
      finish(port)
    }
    const timer = setTimeout(() => finish(null), timeoutMs)
    function finish(port: MessagePort | null) {
      clearTimeout(timer)
      win.removeEventListener('message', onMessage)
      resolve(port)
    }
    win.addEventListener('message', onMessage)
    win.parent.postMessage({ type: 'hello', protocol: REMOTE_FRAME_PROTOCOL } satisfies RemoteFrameMessage, parentOrigin)
  })
}

/**
 * The link over [port]. It also answers the shell's service worker: a worker the browser restarted
 * has lost its port and posts `port.request` here; the link asks the shell for a fresh one on its own
 * port and hands the `port` it gets back to that worker, oldest request first.
 */
export function createRemoteLink(
  win: Window,
  parentOrigin: string,
  firstPort: MessagePort,
  serviceWorker: ServiceWorkerContainer | undefined = win.navigator.serviceWorker,
): RemoteLink {
  let port: MessagePort | null = null
  let renewing: Promise<boolean> | null = null
  const subscribers = new Set<(message: RemoteFrameMessage) => void>()
  const lostListeners = new Set<() => void>()
  const waitingWorkers: (MessageEventSource | null)[] = []

  const onPortMessage = (ev: MessageEvent) => {
    const message = asRemoteFrameMessage(ev.data)
    if (message == null) return
    if (message.type === 'port') {
      const fresh = ev.ports[0]
      if (fresh == null) return
      if (waitingWorkers.length === 0) {
        fresh.close()
        return
      }
      const worker = waitingWorkers.shift() ?? null
      const target = worker ?? serviceWorker?.controller ?? null
      target?.postMessage({ type: 'port' } satisfies RemoteFrameMessage, { transfer: [fresh] })
      return
    }
    subscribers.forEach((fn) => {
      try {
        fn(message)
      } catch (err) {
        console.error('Remote frame: a subscriber threw', err)
      }
    })
  }
  const onPortClose = () => {
    detach()
    lostListeners.forEach((fn) => fn())
  }

  function attach(next: MessagePort) {
    port = next
    next.addEventListener('message', onPortMessage)
    next.addEventListener('close', onPortClose)
    next.start()
    // A request asked on a port that went, or while there was none, is asked again on this one.
    waitingWorkers.forEach(() => next.postMessage({ type: 'port.request' } satisfies RemoteFrameMessage))
  }
  function detach() {
    if (port == null) return
    port.removeEventListener('message', onPortMessage)
    port.removeEventListener('close', onPortClose)
    port = null
  }

  attach(firstPort)

  serviceWorker?.addEventListener('message', (ev: MessageEvent) => {
    if (asRemoteFrameMessage(ev.data)?.type !== 'port.request') return
    waitingWorkers.push(ev.source)
    port?.postMessage({ type: 'port.request' } satisfies RemoteFrameMessage)
  })
  // A container's messages wait for `startMessages` unless `onmessage` is set.
  serviceWorker?.startMessages?.()

  return {
    post(message) {
      if (port == null) return false
      port.postMessage(message)
      return true
    },
    subscribe(fn) {
      subscribers.add(fn)
      return () => subscribers.delete(fn)
    },
    onLost(fn) {
      lostListeners.add(fn)
      return () => lostListeners.delete(fn)
    },
    renew() {
      renewing ??= awaitShellPort(win, parentOrigin).then((next) => {
        renewing = null
        if (next == null) return false
        const old = port
        detach()
        old?.close()
        attach(next)
        return true
      })
      return renewing
    },
  }
}

let current: RemoteLink | null = null

/** Called once, by the boot entry, before `./main` loads. */
export function enterRemoteMode(link: RemoteLink): void {
  current = link
}

export function isRemoteMode(): boolean {
  return current != null
}

export function remoteLink(): RemoteLink | null {
  return current
}

/** *Sign out* in remote mode: the shell ends the session. */
export function endRemoteSession(): void {
  current?.post({ type: 'session.end' })
}

/** The account is not linked to a user on this desk; the shell says what to do about it. */
export function reportUnlinked(): void {
  current?.post({ type: 'auth.unlinked' })
}

/** Tests only. */
export function setRemoteLinkForTests(link: RemoteLink | null): void {
  current = link
}
