import { toast } from 'sonner'
import type {
  IncludeResponse,
  RecordLookResponse,
  RecordResponse,
  UpdateResponse,
} from '@/store/programmerOps'
import { describeSkips } from './maskPicker'
import { describeSceneryWrite } from './RecordSceneryRow'

/**
 * What a programmer write landed, said as a toast once its sheet has closed: Record, Record look,
 * Include and Update all close on success. Reported rather than assumed — a silent Record reads as
 * "everything went in", which is exactly the impression the old lossy snapshot gave — and amber
 * whenever the desk skipped something or warned.
 */
export interface WriteNote {
  title: string
  description?: string
  warn: boolean
}

/** A warning stays long enough to read with the sheet gone — the Sync toasts' precedent. */
const WARNING_DURATION_MS = 10_000

export function announceWrite(note: WriteNote) {
  if (note.warn) toast.warning(note.title, { description: note.description, duration: WARNING_DURATION_MS })
  else toast.success(note.title, { description: note.description })
}

function plural(n: number, noun: string): string {
  return `${n} ${noun}${n === 1 ? '' : 's'}`
}

function joined(parts: readonly (string | null | undefined)[], separator: string): string | undefined {
  const kept = parts.filter((part): part is string => part != null && part !== '')
  return kept.length > 0 ? kept.join(separator) : undefined
}

export function recordNote(result: RecordResponse): WriteNote {
  const preserved = result.preserved
  const notes: string[] = []
  if (result.groupRowsEmitted > 0) notes.push(plural(result.groupRowsEmitted, 'group row'))
  if (result.fxWritten > 0) notes.push(plural(result.fxWritten, 'effect'))
  notes.push(...describeSceneryWrite(result.sceneryWritten, result.sceneryRemoved, result.sceneryAlreadyTracked))
  const triggersKept = preserved.triggers ?? 0
  // One sentence for both kinds of timed child: what the operator needs to know is that a timed
  // thing was preserved rather than dropped, not which shape it had.
  const timedKept = (preserved.timedLayers ?? 0) + (preserved.timedAdHocEffects ?? 0)
  const outOfMaskKept = preserved.outOfMaskAssignments ?? 0
  const outOfScopeKept = preserved.outOfScopeAssignments ?? 0
  if (triggersKept > 0) notes.push(`${triggersKept} trigger(s) kept`)
  if (timedKept > 0) notes.push(`${timedKept} timed effect(s) kept`)
  if (outOfMaskKept > 0) notes.push(`${outOfMaskKept} out-of-mask row(s) kept`)
  if (outOfScopeKept > 0) notes.push(`${outOfScopeKept} out-of-selection row(s) kept`)
  if (result.republishedLive) notes.push('the live cue was republished')

  const skipNote = describeSkips(result.skipped ?? [])
  return {
    title:
      `${result.created ? 'Created' : 'Updated'} “${result.cue.name}” — ` +
      `${plural(result.assignmentsWritten, 'value')} written` +
      (result.assignmentsRemoved > 0 ? `, ${result.assignmentsRemoved} removed` : '') +
      '.',
    description: joined([joined(notes, ' · '), skipNote, ...result.warnings], ' — '),
    warn: skipNote != null || result.warnings.length > 0,
  }
}

export function recordLookNote(result: RecordLookResponse): WriteNote {
  const notes: string[] = []
  if (result.groupRowsEmitted > 0) notes.push(plural(result.groupRowsEmitted, 'group row'))
  if (result.programmerKeysRefreshed > 0) {
    notes.push(`${result.programmerKeysRefreshed} live programmer value(s) re-resolved`)
  }
  notes.push(...describeSceneryWrite(result.sceneryWritten, result.sceneryRemoved))
  if (result.cuesRepublished.length > 0) {
    notes.push(`${result.cuesRepublished.length} live cue(s) moved with it`)
  }

  const skipNote = describeSkips(result.skipped ?? [])
  return {
    title:
      `${result.created ? 'Created' : 'Updated'} “${result.look.name}” — ` +
      `${plural(result.rowsWritten, 'row')} written` +
      (result.rowsRemoved > 0 ? `, ${result.rowsRemoved} removed` : '') +
      (result.effectsWritten ? `, ${plural(result.effectsWritten, 'effect')} moved in` : '') +
      '.',
    description: joined([joined(notes, ' · '), skipNote], ' — '),
    warn: skipNote != null,
  }
}

/**
 * Include's own warnings are toasted by `useInclude` as they arrive, so they are not repeated here.
 * The scenery clause is there so a cue that only moves scenery does not read as nothing included.
 */
export function includeNote(result: IncludeResponse): WriteNote {
  const scenery = result.sceneryIncluded ?? 0
  const timedSkipped = result.fxTimedSkipped ?? 0
  const skipNote = describeSkips(result.skipped ?? [])
  return {
    title:
      `Included “${result.name}” — ${plural(result.entriesWritten, 'value')}` +
      (result.fxSpawned > 0 ? `, ${result.fxSpawned} effect(s)` : '') +
      (scenery > 0 ? `, ${plural(scenery, 'scenery change')}` : '') +
      '.',
    description: joined(
      [
        result.fxAlreadyRunning > 0 ? `${result.fxAlreadyRunning} effect(s) were already running on stage.` : null,
        timedSkipped > 0 ? `${timedSkipped} timed effect(s) not included.` : null,
        skipNote,
      ],
      ' ',
    ),
    warn: skipNote != null || timedSkipped > 0,
  }
}

/**
 * One line per cue written and one for a Look, which writes rows rather than assignments and moves
 * every cue layering it — that second number is the rest of the show changing, the payoff of a
 * Look being a reference at all. Only Mode B's checklist writes several, all of them cues, so then
 * the count is the title and the lines the body.
 */
export function updateNote(result: UpdateResponse): WriteNote {
  const skipNote = describeSkips(result.skipped ?? [])
  if (!result.applied) {
    const name = result.lookResult?.lookName
    return {
      title: `Nothing written${name ? ` to “${name}”` : ''}.`,
      description: joined([skipNote, ...result.warnings], ' — '),
      warn: skipNote != null || result.warnings.length > 0,
    }
  }
  const lines = result.results.map(
    (r) =>
      `Updated “${r.cueName}” — ${plural(r.assignmentsWritten, 'value')} written` +
      (r.assignmentsRemoved ? `, ${r.assignmentsRemoved} removed` : '') +
      (r.sceneryWritten ? `, ${plural(r.sceneryWritten, 'scenery change')}` : '') +
      (r.republishedLive ? ' (live cue republished)' : '') +
      '.',
  )
  const look = result.lookResult
  if (look) {
    lines.push(
      `Updated “${look.lookName}” — ${plural(look.rowsWritten, 'row')} written` +
        (look.rowsRemoved ? `, ${look.rowsRemoved} removed` : '') +
        (look.effectsWritten ? `, ${plural(look.effectsWritten, 'effect')}` : '') +
        (look.sceneryWritten ? `, ${plural(look.sceneryWritten, 'scenery change')}` : '') +
        (look.cuesRepublished.length > 0 ? `, ${look.cuesRepublished.length} live cue(s) moved with it` : '') +
        '.',
    )
  }
  const single = lines.length <= 1
  return {
    title: single ? (lines[0] ?? 'Updated.') : `Updated ${plural(lines.length, 'cue')}.`,
    description: joined([single ? undefined : lines.join(' '), skipNote, ...result.warnings], ' — '),
    warn: skipNote != null || result.warnings.length > 0,
  }
}
