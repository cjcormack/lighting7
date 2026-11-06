import { REMOTE_PARENT_META } from './lib/remoteFrame'
import { awaitShellPort, createRemoteLink, enterRemoteMode, SHELL_PORT_TIMEOUT_MS } from './lib/remoteMode'

/**
 * Whether this page runs in remote mode, decided before `./main` loads (cloud-p1 plan D9):
 * `lightingApi` builds its connection as its module is evaluated, and `main.tsx` imports it.
 *
 * Remote needs both a parent frame and the `lighting7-remote-parent` meta, which the desk adds to
 * `index.html` only on its public listener. Then the page says `hello` to the origin the meta names
 * and waits for the shell's `port`; anything else loads the app at once, as it always has.
 */
export type BootMode =
  | { kind: 'local' }
  | { kind: 'remote'; parentOrigin: string; port: MessagePort }
  | { kind: 'no-shell'; parentOrigin: string }

export async function decideBootMode(win: Window, timeoutMs = SHELL_PORT_TIMEOUT_MS): Promise<BootMode> {
  const parentOrigin = remoteParentOrigin(win)
  if (parentOrigin == null) return { kind: 'local' }
  const port = await awaitShellPort(win, parentOrigin, timeoutMs)
  return port == null ? { kind: 'no-shell', parentOrigin } : { kind: 'remote', parentOrigin, port }
}

/** The meta's origin, if the page is framed and carries it. */
export function remoteParentOrigin(win: Window): string | null {
  if (win.parent === win) return null
  const content = win.document.querySelector(`meta[name="${REMOTE_PARENT_META}"]`)?.getAttribute('content')?.trim()
  if (!content) return null
  try {
    const origin = new URL(content).origin
    return origin === 'null' ? null : origin
  } catch {
    return null
  }
}

export async function runBoot(win: Window, loadMain: () => Promise<unknown>, timeoutMs = SHELL_PORT_TIMEOUT_MS): Promise<BootMode> {
  const mode = await decideBootMode(win, timeoutMs)
  if (mode.kind === 'no-shell') {
    showNoShell(win.document)
    return mode
  }
  if (mode.kind === 'remote') enterRemoteMode(createRemoteLink(win, mode.parentOrigin, mode.port))
  await loadMain()
  return mode
}

function showNoShell(doc: Document) {
  const root = doc.getElementById('root') ?? doc.body
  const message = doc.createElement('p')
  message.setAttribute('role', 'alert')
  message.style.cssText = 'font: 14px system-ui, sans-serif; padding: 24px; color: #a1a1aa;'
  message.textContent = 'Lighting7 Cloud did not answer this desk page. Reload it from the portal.'
  root.replaceChildren(message)
}
