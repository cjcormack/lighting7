import { toast } from 'sonner'
import type { InternalApiConnection } from './internalApi'
import { Status } from './statusApi'

/**
 * Shared sonner id for every dropped operator gesture.
 *
 * One drag sends a `programmer.set` per pointer move, and a blackout press that lands during a
 * blip is usually pressed again — so a single dead socket produces a *burst* of identical
 * failures. Reusing one id makes sonner replace rather than stack, exactly as
 * `PROGRAMMER_ERROR_TOAST_ID` does for the server's own error frames: the operator sees one
 * toast saying the desk isn't listening, not forty.
 */
export const WS_GESTURE_DROPPED_TOAST_ID = 'ws-gesture-dropped'

/**
 * The one wording for "the desk is unreachable", shared by the toast below and by every control
 * that disables itself rather than take a press it can't deliver. One string, so the tooltip an
 * operator reads before the press and the toast they'd have got after it say the same thing.
 */
export const DESK_OFFLINE_LABEL = 'Not connected to the desk'

export const WS_GESTURE_DROPPED_MESSAGE = `${DESK_OFFLINE_LABEL} — that did not reach the rig.`

/**
 * Send one operator gesture over the WebSocket, and say so when it goes nowhere.
 *
 * Every WS write used to be `if (readyState === OPEN) ws.send(...)` with no return value, queue,
 * log or toast, so a programmer set, a Blind press, a blackout, a park — all of them — were
 * silently discarded for however long the reconnect backoff was sleeping. Because programmer
 * state is server-driven, the UI simply didn't move: the same failed backend that toasts a REST
 * edit was total silence for a Blind press.
 *
 * This is only for frames the operator *asked for*. State requests (`programmer.state`,
 * `fxState`, `speedMasters.state`, `surfaceDevices.state`, …) keep calling `conn.send` directly:
 * they are idempotent catch-up, every bridge re-issues them on open, and toasting for one would
 * blame the operator for the reconnect machinery doing its job.
 *
 * Toast rather than throw, because these call sites are event handlers on the busk path — a
 * throw there takes out whatever else the handler was doing (a drag's local state, an optimistic
 * highlight) for no gain. Callers that want to do more with the failure get the boolean back.
 *
 * Lives in `src/api/` rather than `src/store/`, alongside the transport it guards, and imports
 * nothing but sonner: `store/programmerErrors.ts` is a store module *because* it reaches back
 * into `lightingApi`, and the reverse dependency would be an import cycle.
 */
export function sendGesture(conn: InternalApiConnection, message: unknown): boolean {
  // Ask before serialising. `conn.send` re-checks and is the authority, but a drag against a
  // socket that is already down sends one frame per pointer move, and stringifying each of them
  // only to throw it away is work nobody will ever see.
  if (conn.readyState() !== Status.OPEN) {
    toast.error(WS_GESTURE_DROPPED_MESSAGE, { id: WS_GESTURE_DROPPED_TOAST_ID })
    return false
  }
  const sent = conn.send(JSON.stringify(message))
  if (!sent) {
    toast.error(WS_GESTURE_DROPPED_MESSAGE, { id: WS_GESTURE_DROPPED_TOAST_ID })
  }
  return sent
}
