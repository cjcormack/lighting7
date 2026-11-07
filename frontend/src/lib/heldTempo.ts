import type { ProgrammerTempo, ProgrammerTempoEntry } from '@/api/programmerWsApi'

/**
 * The programmer's held tempos as its readers see them (fx-speed plan D8, D11). One rule for the
 * Tempo list, Record's Tempo row, Clear's count and the rail, as `heldSceneryIn` is for scenery.
 */

/**
 * Every master the programmer lists for [projectId]: its holds and, in Blind, a release still to
 * land. Nothing when the frame is another project's; the frame before the first names none.
 */
export function listedTempoIn(tempo: ProgrammerTempo, projectId: number): readonly ProgrammerTempoEntry[] {
  return tempo.projectId == null || tempo.projectId === projectId ? tempo.masters : []
}

/** The masters the programmer holds — what Record writes and Clear releases. */
export function heldTempoIn(tempo: ProgrammerTempo, projectId: number): readonly ProgrammerTempoEntry[] {
  return listedTempoIn(tempo, projectId).filter((m) => !m.released)
}

/** What one master's card says of the programmer: whether it holds it, a staged tempo, a release to land. */
export interface MasterHold {
  held: boolean
  /** The held tempo Blind has not yet put on stage; null when nothing is staged. */
  stagedBpm: number | null
  /** Released in Blind and still running until Blind is left. */
  releasing: boolean
}

export const NOT_HELD: MasterHold = { held: false, stagedBpm: null, releasing: false }

export function masterHoldOf(entries: readonly ProgrammerTempoEntry[], speedMasterUuid: string | null): MasterHold {
  const entry = speedMasterUuid == null ? undefined : entries.find((m) => m.speedMasterUuid === speedMasterUuid)
  if (entry == null) return NOT_HELD
  if (entry.released) return { held: false, stagedBpm: null, releasing: true }
  return { held: true, stagedBpm: entry.staged ? entry.bpm : null, releasing: false }
}

interface NamedMaster {
  uuid: string | null
  index: number
  name: string
}

/** *M2 · Chase*, as the rail names a master; *a master* for one the bank no longer has. */
export function tempoMasterLabel(bank: readonly NamedMaster[] | undefined, speedMasterUuid: string): string {
  const master = bank?.find((m) => m.uuid === speedMasterUuid)
  if (master == null) return 'a master'
  return master.name ? `M${master.index} · ${master.name}` : `M${master.index}`
}

/** What a Record did to the cue's Tempo on GO, as notes for its toast, each only where it happened. */
export function describeTempoWrite(written = 0, removed = 0): string[] {
  const notes: string[] = []
  if (written > 0) notes.push(`${written} tempo${written === 1 ? '' : 's'}`)
  if (removed > 0) notes.push(`${removed} tempo${removed === 1 ? '' : 's'} removed`)
  return notes
}
