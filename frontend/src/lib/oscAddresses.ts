import type { SurfaceBinding } from '@/api/surfacesApi'
import type { OscAddressChange, OscAddressWarning } from '@/api/oscWsApi'

/** The kinds of record an OSC address can name, mirroring `OscRecordKind` in lighting7's `osc/OscAddresses.kt`. */
export type OscRecordKind = 'STACK' | 'ENTRY' | 'CUE' | 'LOOK' | 'PAGE' | 'PAD' | 'MASTER'

export interface OscRef {
  kind: OscRecordKind
  id: number
  uuid: string
}

/** One address the project answers to: what it does, the record it reaches and the binding it dispatches. */
export interface OscAddress {
  address: string
  pattern: string
  args: string
  does: string
  reaches: string
  binding: SurfaceBinding
  /** Absent for an address that names no record (`/blackout`, `/grandmaster`). */
  record?: OscRef | null
}

export interface OscAmbiguity {
  message: string
  records: OscRef[]
  addresses: string[]
}

export interface OscUnaddressed {
  kind: OscRecordKind
  id: number
  uuid: string
  name: string
  reason: string
}

export interface OscPattern {
  pattern: string
  args: string
  does: string
  target: string
  session: number
}

/** `GET /projects/{id}/osc/addresses`, mirroring `OscAddressListDto`: made from the desk's address table and resolver. */
export interface OscAddressList {
  prefix: string
  patterns: OscPattern[]
  addresses: OscAddress[]
  ambiguous: OscAmbiguity[]
  unaddressed: OscUnaddressed[]
}

/** A record a menu copies the addresses of. */
export interface OscRecordKey {
  kind: OscRecordKind
  id: number
}

const sameRecord = (ref: OscRef | null | undefined, key: OscRecordKey) =>
  ref != null && ref.kind === key.kind && ref.id === key.id

/** The addresses that reach any of [keys], in the order the keys are given, then table order. */
export function oscAddressesFor(list: OscAddressList, keys: readonly OscRecordKey[]): OscAddress[] {
  return keys.flatMap((key) => list.addresses.filter((a) => sameRecord(a.record, key)))
}

/**
 * Why none of [keys] has an address: a name that slugs to nothing, an entry with no number, or a slug
 * another record shares — the desk's own words, the first that applies. Null when one has an address
 * or the desk says nothing about them.
 */
export function noOscAddressReason(list: OscAddressList, keys: readonly OscRecordKey[]): string | null {
  if (oscAddressesFor(list, keys).length > 0) return null
  for (const key of keys) {
    const missing = list.unaddressed.find((u) => u.kind === key.kind && u.id === key.id)
    if (missing != null) return missing.reason
    const shared = list.ambiguous.find((a) => a.records.some((r) => sameRecord(r, key)))
    if (shared != null) return shared.message
  }
  return null
}

/** The address's last segment as a verb for a menu row: `go`, `standby`, `on`, `press`. */
export function oscAddressVerb(address: OscAddress): string {
  return address.address.slice(address.address.lastIndexOf('/') + 1)
}

/** One moved address in words: what it reached, and what it reaches now or why nothing. */
export function describeAddressChange(change: OscAddressChange): string {
  const was = change.was != null ? ` (was ${change.was})` : ''
  if (change.now != null) return `${change.address} now reaches ${change.now}${was}`
  return `${change.address} no longer works${was}${change.reason ? ` — ${change.reason}` : ''}`
}

/** The warnings newer than [seen], oldest first, so they are announced in the order they happened. */
export function unseenWarnings(warnings: readonly OscAddressWarning[] | undefined, seen: number): OscAddressWarning[] {
  return (warnings ?? []).filter((w) => w.id > seen).sort((a, b) => a.id - b.id)
}

/** The newest warning's id, or 0 for none. */
export function newestWarningId(warnings: readonly OscAddressWarning[] | undefined): number {
  return (warnings ?? []).reduce((max, w) => Math.max(max, w.id), 0)
}
