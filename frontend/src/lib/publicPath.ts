/**
 * Whether `pathname` is one of the pages reachable without an account, and without the show being
 * up: the two phone-facing QR pages. `/reset/<token>` belongs to someone locked out of the desk;
 * `/device/<token>` is a phone being signed in from it. Neither has a session, so neither `AuthGate`
 * nor `BootGate` may stand in front of them.
 *
 * Matched as routes, not as a bare prefix: `/device/` with no token would otherwise render a blank
 * screen with both gates off.
 *
 * **The `i` flag is not decoration.** React Router matches routes case-insensitively unless a route
 * sets `caseSensitive: true`, which none of ours do — so `/Device/<token>` renders `DeviceLoginPage`
 * either way. A case-sensitive test here would leave both gates armed in front of it, and a phone
 * keyboard that auto-capitalises the first letter of a hand-typed URL is exactly how that happens.
 */
export function isPublicPath(pathname: string): boolean {
  return /^\/(reset|device)\/[^/]+\/?$/i.test(pathname)
}
