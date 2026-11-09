/**
 * What a sender's address says about how many machines it admits (lighting7 venue-rig-fixes plan
 * D1, D3): a range is `a.b.c.d/n` or an IPv6 `addr/n`. The desk parses and stores it; this reads
 * only enough to label it for the chip beside the field, so text the desk would refuse has none.
 */

const RANGE = /^([^/]+)\/(\d{1,3})$/
const OCTET = /^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/

function isIpv4(text: string): boolean {
  const parts = text.split('.')
  return parts.length === 4 && parts.every((p) => OCTET.test(p))
}

/** Hex groups and colons with at most one `::`, optionally ending in a dotted IPv4 tail. */
function isIpv6(text: string): boolean {
  const lastColon = text.lastIndexOf(':')
  if (lastColon < 0) return false
  let head = text
  let tailGroups = 0
  const tail = text.slice(lastColon + 1)
  if (tail.includes('.')) {
    if (!isIpv4(tail)) return false
    head = text.slice(0, lastColon + 1)
    if (!head.endsWith('::')) head = head.slice(0, -1)
    tailGroups = 2
  }
  const halves = head.split('::')
  if (halves.length > 2) return false
  const groups = halves.flatMap((h) => (h === '' ? [] : h.split(':')))
  if (!groups.every((g) => /^[0-9a-f]{1,4}$/i.test(g))) return false
  const count = groups.length + tailGroups
  return halves.length === 2 ? count < 8 : count === 8
}

/** *range · 256 addresses* for a range, null for a single address or text that is neither. */
export function senderRangeLabel(address: string): string | null {
  const match = RANGE.exec(address.trim())
  if (match == null) return null
  const [, host, prefix] = match
  const full = isIpv4(host) ? 32 : isIpv6(host) ? 128 : null
  if (full == null) return null
  const bits = full - Number(prefix)
  if (bits <= 0) return null
  return `range · ${addressCount(bits)} addresses`
}

function addressCount(bits: number): string {
  if (bits <= 20) return (2 ** bits).toLocaleString('en-GB')
  return `2^${bits}`
}
