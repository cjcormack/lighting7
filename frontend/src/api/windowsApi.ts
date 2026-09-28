import { InternalApiConnection, InternalEventType } from './internalApi'
import { Subscription } from './subscription'
import { createWsSubscribable } from './wsSubscriptionFactory'
import { sendGesture } from './wsGesture'
import { Status } from './statusApi'

/**
 * The `windows.*` family — the desk's registry of signed-in browser windows, and the five
 * commands one window sends another (multi-screen plan §3.4, busk-further plan §3.5; lighting7
 * `plugins/WindowsSocket.kt`, which is the wire contract wherever this comment and the plan's
 * sketch differ).
 *
 * **A row per socket, keyed by the socket.** The desk mints each row's `id` from the connection and
 * that is what every command addresses and what a `selection.state` `source` carries; the
 * `windowId` a row also carries is this tab's own `sessionStorage` uuid (`lib/windowIdentity.ts`),
 * and is how a client recognises its own row across a reload and a reconnect. A duplicated tab
 * copies its storage, so two rows can share a `windowId`; they never share an `id`.
 *
 * **The announce carries exactly `windowId`, `name`, `view`, `fullscreen`, `follows` — and
 * `viewOptions` only when the view contributes any.** The desk's `Json` is bare — no
 * `ignoreUnknownKeys` — so one extra key makes the whole frame undeserializable and it is dropped
 * with a server-side log line only. The symptom is a window that never appears in
 * `windows.state`, which is why [WindowAnnounce] is spelled out field by field below and
 * `windowsApi.test.ts` pins the key set: five keys plus `type`, or six with `viewOptions`. `id`
 * and `user` are the server's to say (D7's rule applied again): a window that could send either
 * could claim to be another window or another operator.
 *
 * **`viewOptions` is a free `string → string` map** (busk-further plan D13): the busk view's
 * `focus`, `sheet` and page facts (the split's height is this window's pixels and is not
 * announced), and — under every live view — `immersive`
 * (busk-chrome plan D9, `lib/immersive.ts`), announced so a Screens sheet on another window can
 * draw them, and carried back verbatim on `windows.state`. The registry never learns a view's
 * vocabulary — `lib/windowViews.ts` describes it (`announcedViewOptions` says which keys go out
 * under which view) and `lib/buskWindow.ts` / `lib/immersive.ts` own the values. The fourth
 * command, `windows.viewOptions {targetId, view, options}`, is rebroadcast like the other three;
 * the named window applies `options` to its own tab facts **for that view only** and re-announces.
 *
 * **The fifth, `windows.follow {targetId, on}`, sets a window's selection follow** (desk-follow
 * plan D4; lighting7 3f4fb5b) — `windows.fullscreen`'s exact shape. It is not a view option,
 * because follow is the window's and not a view's, and a Programmer row carries no busk options.
 * Nothing is written server-side: the target applies it — or refuses an `off` its focus forbids —
 * and re-announces `follows`, which is how the registry learns it, as for a rename.
 *
 * **The announce is re-sent on every `open`.** It is the second legitimate `open` branch in this
 * tree (`speedMastersWsApi`'s beat re-requests are the first), and for the same reason: it re-sends
 * what the *server* forgot. The registry keys by socket and a reconnect is a new socket, so a
 * reconnected tab has no row until it says so again. It re-sends only that — no state request,
 * since `windows.state` is a `StateFlow` and the desk pushes the snapshot on every connect.
 *
 * **It is handled only once the show is warm.** The frame sits in the socket's incoming channel
 * through boot, so `windows.state` arrives empty behind the boot overlay and fills itself when the
 * show is ready. There is deliberately no retry timer; the connect burst already carries it.
 *
 * **The commands are rebroadcast to every socket, the sender included** (D11), verbatim. A handler's
 * first act is to compare `targetId` with this window's row id; an id that is not this window's
 * matches nothing, and a command whose target is disconnected is simply lost — visible as that
 * row's `view` not moving (`FU-WINDOWS-SHOW-OFFLINE`). `windows.rename` is **not** applied
 * server-side: the target renames itself and re-announces, which is what makes the new name
 * survive that tab's reload.
 */
export interface DeskWindow {
  /** Socket-minted; what commands address and what `selection.state`'s `source.id` names. */
  id: string
  /** Client-minted, from that tab's `sessionStorage`; not unique across a duplicated tab. */
  windowId: string
  name: string
  /** The route path that window is showing. */
  view: string
  fullscreen: boolean
  follows: boolean
  /** The authenticated display name behind that socket; null on a bootstrap-open desk. */
  user: string | null
  /** That window's per-view options as it last announced them; null where it announced none. */
  viewOptions: Readonly<Record<string, string>> | null
}

/**
 * Exactly the keys the desk's `WindowsAnnounceInMessage` declares, and no more. `viewOptions` is
 * the one optional: absent, the frame carries five keys, exactly as before it existed.
 */
export interface WindowAnnounce {
  windowId: string
  name: string
  view: string
  fullscreen: boolean
  follows: boolean
  viewOptions?: Readonly<Record<string, string>>
}

export type WindowCommand =
  | { type: 'show'; targetId: string; view: string }
  | { type: 'rename'; targetId: string; name: string }
  | { type: 'fullscreen'; targetId: string; on: boolean }
  | { type: 'viewOptions'; targetId: string; view: string; options: Readonly<Record<string, string>> }
  | { type: 'follow'; targetId: string; on: boolean }

export interface WindowsWsApi {
  /** Every signed-in window, on connect and on every change. */
  subscribe(fn: (windows: DeskWindow[]) => void): Subscription
  /** The last state frame, or null before the first — for an RTK Query `queryFn` seeding its entry. */
  getState(): DeskWindow[] | null
  /** The five commands, as rebroadcast — this window's own included. */
  subscribeCommands(fn: (command: WindowCommand) => void): Subscription
  /**
   * Say what this window is. Remembered and re-sent on every `open`; sent now if the socket is up,
   * and silently *not* otherwise, because a socket that is down will re-send it on its way back up
   * — this is not an operator gesture, so it does not toast.
   */
  announce(payload: WindowAnnounce): void
  /** The payload of the last announce, for a test or a caller that wants to re-send a variant. */
  lastAnnounce(): WindowAnnounce | null
  show(targetId: string, view: string): void
  rename(targetId: string, name: string): void
  fullscreen(targetId: string, on: boolean): void
  /** Set a window's per-view options, for the view it is showing. */
  viewOptions(targetId: string, view: string, options: Readonly<Record<string, string>>): void
  /** Link (`on`) or unlink a window's selection from the desk's. */
  follow(targetId: string, on: boolean): void
}

type WindowsInMessage =
  | { type: 'windows.state'; windows: unknown }
  | { type: 'windows.show'; targetId: unknown; view: unknown }
  | { type: 'windows.rename'; targetId: unknown; name: unknown }
  | { type: 'windows.fullscreen'; targetId: unknown; on: unknown }
  | { type: 'windows.viewOptions'; targetId: unknown; view: unknown; options: unknown }
  | { type: 'windows.follow'; targetId: unknown; on: unknown }

/** A `Map<String, String>` as the desk serialises it, or null for anything else. */
function parseStringMap(raw: unknown): Readonly<Record<string, string>> | null {
  if (raw == null || typeof raw !== 'object' || Array.isArray(raw)) return null
  const out: Record<string, string> = {}
  for (const [key, value] of Object.entries(raw as Record<string, unknown>)) {
    if (typeof value !== 'string') return null
    out[key] = value
  }
  return out
}

/** Read one registry row, or null for anything that is not one. */
export function parseDeskWindow(raw: unknown): DeskWindow | null {
  if (raw == null || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  if (
    typeof r.id !== 'string' ||
    typeof r.windowId !== 'string' ||
    typeof r.name !== 'string' ||
    typeof r.view !== 'string'
  ) {
    return null
  }
  return {
    id: r.id,
    windowId: r.windowId,
    name: r.name,
    view: r.view,
    // The desk's Json drops defaults, so an absent field is the declared default.
    fullscreen: r.fullscreen === true,
    follows: r.follows !== false,
    user: typeof r.user === 'string' ? r.user : null,
    viewOptions: parseStringMap(r.viewOptions),
  }
}

function parseCommand(message: Exclude<WindowsInMessage, { type: 'windows.state' }>): WindowCommand | null {
  const { targetId } = message
  if (typeof targetId !== 'string') return null
  switch (message.type) {
    case 'windows.show':
      return typeof message.view === 'string' ? { type: 'show', targetId, view: message.view } : null
    case 'windows.rename':
      return typeof message.name === 'string' ? { type: 'rename', targetId, name: message.name } : null
    case 'windows.fullscreen':
      return typeof message.on === 'boolean' ? { type: 'fullscreen', targetId, on: message.on } : null
    case 'windows.viewOptions': {
      const options = parseStringMap(message.options)
      return typeof message.view === 'string' && options != null
        ? { type: 'viewOptions', targetId, view: message.view, options }
        : null
    }
    case 'windows.follow':
      return typeof message.on === 'boolean' ? { type: 'follow', targetId, on: message.on } : null
  }
}

/**
 * The frame, with the five keys and nothing else — plus `viewOptions` only when the payload
 * carries one. See the module comment for why the key set matters.
 */
export function announceFrame(payload: WindowAnnounce): Record<string, unknown> {
  const frame: Record<string, unknown> = {
    type: 'windows.announce',
    windowId: payload.windowId,
    name: payload.name,
    view: payload.view,
    fullscreen: payload.fullscreen,
    follows: payload.follows,
  }
  if (payload.viewOptions != null) frame.viewOptions = { ...payload.viewOptions }
  return frame
}

export function createWindowsWsApi(conn: InternalApiConnection): WindowsWsApi {
  const state = createWsSubscribable<DeskWindow[]>()
  const commands = createWsSubscribable<WindowCommand>()
  let last: DeskWindow[] | null = null
  let announced: WindowAnnounce | null = null

  const send = () => {
    if (announced == null || conn.readyState() !== Status.OPEN) return
    conn.send(JSON.stringify(announceFrame(announced)))
  }

  conn.subscribe((evType, _ev, frame) => {
    if (evType === InternalEventType.open) {
      // The one thing this branch owes: the row the server lost with the old socket.
      send()
      return
    }
    if (evType !== InternalEventType.message) return
    const message = frame as WindowsInMessage | null
    if (message == null || typeof message !== 'object') return
    if (message.type === 'windows.state') {
      const rows = Array.isArray(message.windows)
        ? message.windows.map(parseDeskWindow).filter((w): w is DeskWindow => w != null)
        : []
      last = rows
      state.notify(rows)
      return
    }
    if (
      message.type === 'windows.show' ||
      message.type === 'windows.rename' ||
      message.type === 'windows.fullscreen' ||
      message.type === 'windows.viewOptions' ||
      message.type === 'windows.follow'
    ) {
      const command = parseCommand(message)
      if (command != null) commands.notify(command)
    }
  })

  return {
    subscribe: (fn) => {
      const sub = state.api.subscribe(fn)
      if (last != null) fn(last)
      return sub
    },
    getState: () => last,
    subscribeCommands: commands.api.subscribe,
    announce: (payload) => {
      announced = { ...payload }
      send()
    },
    lastAnnounce: () => announced,
    show: (targetId, view) => sendGesture(conn, { type: 'windows.show', targetId, view }),
    rename: (targetId, name) => sendGesture(conn, { type: 'windows.rename', targetId, name }),
    fullscreen: (targetId, on) => sendGesture(conn, { type: 'windows.fullscreen', targetId, on }),
    viewOptions: (targetId, view, options) =>
      sendGesture(conn, { type: 'windows.viewOptions', targetId, view, options: { ...options } }),
    follow: (targetId, on) => sendGesture(conn, { type: 'windows.follow', targetId, on }),
  }
}
