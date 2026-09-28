import { toast } from 'sonner'
import { lightingApi } from '../api/lightingApi'

/**
 * Shared sonner id for every `programmer.error` frame.
 *
 * A single drag sends one `programmer.set` per pointer move, so a property that resolves to no
 * DMX channels produces a *burst* of identical errors. Reusing one id makes sonner replace rather
 * than stack, exactly as `errorToastMiddleware` does per endpoint — the operator sees one toast
 * saying the thing that is wrong, not forty.
 */
export const PROGRAMMER_ERROR_TOAST_ID = 'programmer-error'

/**
 * Bridge `programmer.error` into a toast.
 *
 * The WS write path was the one place a failed operator action was invisible by default:
 * `subscribeToErrors` existed and had no production subscriber, so an unknown fixture or group, an
 * unparseable value, an `addLayer` with an unresolvable source, and — most visibly — "Property X on
 * Y resolves to no DMX channels" all arrived and were dropped. The slider moved, the rig didn't,
 * and nothing was said.
 *
 * Deliberately *not* routed through `errorToastMiddleware`: that keys on rejected RTK Query
 * actions, and nothing here is one. The message is already the server's own prose, so it needs no
 * `formatError`.
 *
 * Safe to raise unconditionally because the server replies to the acting socket
 * (`SocketScope.send`, from the `programmer.*` handler) rather than broadcasting — a second tab
 * never toasts for someone else's mistake.
 *
 * Called from `main.tsx` rather than on import, for the reason `startLooksBridge` documents at
 * length: touching `lightingApi` at module-eval time from anything on the early render path is a
 * runtime import cycle that every static check passes. This module has no such importer today, and
 * starting it beside its siblings keeps it that way.
 */
export function startProgrammerErrorBridge() {
  lightingApi.programmer.subscribeToErrors((message) => {
    toast.error(message, { id: PROGRAMMER_ERROR_TOAST_ID })
  })
}
