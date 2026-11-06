/**
 * The remote-frame contract (lighting7 `docs/cloud-protocol.md` §"The remote frame"): the
 * `postMessage` messages between Lighting7 Cloud's shell, this SPA framed in it, and the shell's
 * service worker. `cloud-protocol/src/test/resources/protocol/remote-frame/messages.json` is the
 * fixture both sides test against.
 */

export const REMOTE_FRAME_PROTOCOL = 1

/** The meta the desk adds to `index.html` on its public listener, naming the shell's origin. */
export const REMOTE_PARENT_META = 'lighting7-remote-parent'

export type RemoteFrameMessage =
  | { type: 'hello'; protocol: number }
  | { type: 'port' }
  | { type: 'open'; path?: string }
  | { type: 'send'; data: string }
  | { type: 'message'; data: string }
  | { type: 'close'; code: number; reason: string }
  | { type: 'session.end' }
  | { type: 'auth.unlinked' }
  | { type: 'port.request' }

const TYPES = new Set<string>([
  'hello',
  'port',
  'open',
  'send',
  'message',
  'close',
  'session.end',
  'auth.unlinked',
  'port.request',
])

/** A message of a type this contract names; anything else (another app's postMessage) is ignored. */
export function asRemoteFrameMessage(data: unknown): RemoteFrameMessage | null {
  if (typeof data !== 'object' || data == null) return null
  const type = (data as { type?: unknown }).type
  return typeof type === 'string' && TYPES.has(type) ? (data as RemoteFrameMessage) : null
}
