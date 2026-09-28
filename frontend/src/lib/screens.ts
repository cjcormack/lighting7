/**
 * Opening a new desk window (multi-screen plan §3.4, D10, D13): the URL that names it, the next
 * free *Screen N*, and Chrome's Window Management API behind a feature check. Pure, and a `lib/`
 * module rather than part of the Screens sheet, because the ⌘K commands in `navigation.ts` need
 * the same three things and the sheet imports the project switcher, which imports the registry.
 */

/** Chrome only: `window.getScreenDetails`, secure context, `window-management` permission. */
export function canChooseDisplay(): boolean {
  return typeof window !== 'undefined' && 'getScreenDetails' in window
}

/**
 * The URL a new window is opened at: [view] (a route path, `/` for the app's own landing) with
 * `?window=<name>`, the space spelled `%20` — the spelling the launcher's tray items use
 * (`DeskScreens.screenUrl`), so a window named by either arrives with the same name.
 */
export function newWindowUrl(name: string, view: string = '/', origin: string = window.location.origin): string {
  const path = view.startsWith('/') ? view : `/${view}`
  return `${origin}${path}?window=${encodeURIComponent(name.trim())}`
}

/**
 * A window's **whole setup** as a link (busk-further plan D13, `Screens.dc.html`'s *Copy link for
 * Screen 2*): [newWindowUrl] for its name and view, then the view's options as the query the busk
 * view latches on arrival — `page`, `focus`, `sheet`, in that order, each only when the row has
 * it — and `immersive` (busk-chrome plan D9), **only when it is `on`**: `off` is what every
 * window boots with, so a link saying so would say nothing, and `lib/immersive.ts` consumes the
 * parameter at boot rather than on the view. Opened on another device it arrives as drawn:
 * `?window=` names it (fresh id, this name), `?page=` unlinks it onto that page, `?focus=` /
 * `?sheet=` set its shape, `?immersive=on` hides the app around it. A view whose options carry
 * none of the four copies the plain link.
 */
export function windowSetupUrl(
  name: string,
  view: string,
  options: Readonly<Record<string, string>> | null,
  origin: string = window.location.origin,
): string {
  let url = newWindowUrl(name, view, origin)
  for (const key of ['page', 'focus', 'sheet', 'immersive']) {
    const value = options?.[key]
    if (value == null || value === '') continue
    if (key === 'immersive' && value !== 'on') continue
    url += `&${key}=${encodeURIComponent(value)}`
  }
  return url
}

/** The smallest free *Screen N* over the names in use, so a second window is *Screen 2*. */
export function nextScreenName(taken: readonly string[]): string {
  const used = new Set(taken.map((n) => n.trim().toLowerCase()))
  for (let n = 1; ; n++) {
    const candidate = `Screen ${n}`
    if (!used.has(candidate.toLowerCase())) return candidate
  }
}

/** Whether a hostname names this machine to itself — where a copied link is no use elsewhere. */
export function isLoopbackHost(hostname: string): boolean {
  const bare = hostname.replace(/^\[|\]$/g, '').toLowerCase()
  return bare === 'localhost' || bare === '::1' || bare === '0.0.0.0' || bare.startsWith('127.')
}

export interface DisplayChoice {
  label: string
  left: number
  top: number
  width: number
  height: number
  isCurrent: boolean
}

type ScreenDetailed = {
  availLeft: number
  availTop: number
  availWidth: number
  availHeight: number
  width: number
  height: number
}

type ScreenDetails = { screens: ScreenDetailed[]; currentScreen: ScreenDetailed }

/**
 * The attached displays, through `getScreenDetails` — which prompts for `window-management` on
 * first use and so must be called from a gesture. Throws where the browser refuses.
 */
export async function listDisplays(): Promise<DisplayChoice[]> {
  const details = await (window as unknown as { getScreenDetails: () => Promise<ScreenDetails> }).getScreenDetails()
  return details.screens.map((s, i) => ({
    label: `Display ${i + 1} · ${s.width}×${s.height}`,
    left: s.availLeft,
    top: s.availTop,
    width: s.availWidth,
    height: s.availHeight,
    isCurrent: s === details.currentScreen,
  }))
}

/**
 * Open [url] as a new window filling [display]. **`noopener`**, so the child is a new top-level
 * browsing context rather than an auxiliary one and does not inherit this tab's `sessionStorage`
 * — the `?window=` on the URL names it (D10). No handle comes back: with `noopener` the spec makes
 * `window.open` return null whether the window opened or the popup blocker refused it, so a block
 * is **undetectable here**. The Screens sheet opens from a fresh gesture and is not blocked; the
 * one-gesture ⌘K path can be, silently, and says so in its own docblock.
 */
export function openWindowOn(display: DisplayChoice, url: string): void {
  window.open(
    url,
    '_blank',
    `left=${display.left},top=${display.top},width=${display.width},height=${display.height},noopener`,
  )
}
