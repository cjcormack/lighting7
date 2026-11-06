import fixture from '../../../cloud-protocol/src/test/resources/protocol/remote-frame/messages.json'

/** The remote-frame fixture (lighting7 `cloud-protocol/…/protocol/remote-frame/messages.json`). */
export interface FixtureEntry {
  from: 'spa' | 'shell' | 'worker'
  to: 'spa' | 'shell' | 'worker'
  over: 'window' | 'port' | 'worker'
  transfer?: string[]
  message: Record<string, unknown> & { type: string }
}

export const remoteFrameFixture = fixture as { protocol: number; messages: FixtureEntry[] }

/** The one fixture message matching [pick]; throws if there is not exactly one. */
export function fixtureMessage(pick: (entry: FixtureEntry) => boolean): FixtureEntry['message'] {
  const found = remoteFrameFixture.messages.filter(pick)
  if (found.length !== 1) throw new Error(`expected one fixture message, found ${found.length}`)
  return found[0].message
}

/** An event with the fields a `MessageEvent` carries, which jsdom cannot build with a fake port. */
export function messageEvent(data: unknown, init: { origin?: string; source?: unknown; ports?: unknown[] } = {}): Event {
  return Object.assign(new Event('message'), { data, origin: init.origin ?? '', source: init.source ?? null, ports: init.ports ?? [] })
}

/** A `MessagePort` the test holds the other end of. */
export class FakePort extends EventTarget {
  readonly posted: { message: unknown; transfer: unknown[] }[] = []
  started = false

  postMessage(message: unknown, options?: { transfer?: unknown[] }) {
    this.posted.push({ message, transfer: options?.transfer ?? [] })
  }

  start() {
    this.started = true
  }

  close() {}

  /** The shell posts [data] on this port. */
  deliver(data: unknown, ports: unknown[] = []) {
    this.dispatchEvent(messageEvent(data, { ports }))
  }

  /** The shell's end goes away. */
  closeFromShell() {
    this.dispatchEvent(new Event('close'))
  }

  get messages(): unknown[] {
    return this.posted.map((p) => p.message)
  }

  asPort(): MessagePort {
    return this as unknown as MessagePort
  }
}

/** A framed window: its parent records what is posted to it, and the test delivers its answers. */
export function framedWindow(doc: Document = document) {
  const parent = { posted: [] as { message: unknown; targetOrigin: string }[], postMessage(message: unknown, targetOrigin: string) {
    this.posted.push({ message, targetOrigin })
  } }
  const target = new EventTarget()
  const win = Object.assign(target, { parent, document: doc, navigator: {} }) as unknown as Window
  return {
    win,
    parent,
    /** A message to this window, by default from the parent. */
    deliver(data: unknown, init: { origin: string; source?: unknown; ports?: unknown[] }) {
      target.dispatchEvent(messageEvent(data, { source: parent, ...init }))
    },
  }
}
