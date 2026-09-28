/**
 * `m:ss` for a millisecond countdown, floored at zero.
 *
 * Shared by the three surfaces that render a token's time left — the QR reset sheet, the
 * reset-link history list, and the device-login sheet. Clamped rather than allowed to go
 * negative because the backend owns expiry: a client whose clock runs fast would otherwise
 * show "-0:03" for a link the server still considers live.
 */
export function formatCountdown(msRemaining: number): string {
  const total = Math.max(0, Math.floor(msRemaining / 1000))
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return `${minutes}:${seconds.toString().padStart(2, "0")}`
}
