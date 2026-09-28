import type { CompileResult, ScriptType } from '@/store/scripts'
import type { ProjectScriptDetail } from '@/api/projectApi'

/**
 * **The Scripts sheet's Check column** (library-sheets plan D8): the last Compile of a script in
 * this tab. The results live in the route as a `Map<scriptId, ScriptCheck>` and are never stored.
 *
 * Each result carries **the text and type it compiled**, and [checkFor] answers only while the
 * script still has them — so a script edited after its check reads *not checked* again without an
 * invalidation anywhere: the list refetches with the new text, and the old result no longer
 * matches it. A rename does not touch either, so it keeps its check.
 */
export type ScriptCheck = { script: string; scriptType: ScriptType } & ScriptCheckState

export type ScriptCheckState =
  /** In the batch, waiting its turn — the batch compiles one at a time. */
  | { status: 'queued' }
  | { status: 'busy' }
  | { status: 'ok'; warnings: number }
  | { status: 'error'; errors: number; line: number | null; message: string }
  /** The request itself failed — the desk was unreachable, or refused the compile. */
  | { status: 'failed'; reason: string }

/** A compile's answer as a check: ✓, or the error count and where the first one is. */
export function checkFromResult(result: CompileResult): ScriptCheckState {
  const errors = result.messages.filter((m) => m.severity === 'ERROR')
  if (result.success && errors.length === 0) {
    return { status: 'ok', warnings: result.messages.filter((m) => m.severity === 'WARNING').length }
  }
  const first = errors[0] ?? result.messages[0]
  return {
    status: 'error',
    errors: Math.max(errors.length, 1),
    line: lineOf(first?.location),
    message: first?.message ?? 'Does not compile',
  }
}

/** `14:5` → 14. The desk reports a diagnostic's start as `line:col`, already in the user's lines. */
function lineOf(location: string | undefined): number | null {
  if (!location) return null
  const line = Number.parseInt(location.split(':')[0], 10)
  return Number.isFinite(line) ? line : null
}

/** This script's check, or null where it has none — never checked, or edited since. */
export function checkFor(
  script: Pick<ProjectScriptDetail, 'id' | 'script' | 'scriptType'>,
  checks: ReadonlyMap<number, ScriptCheck>,
): ScriptCheck | null {
  const check = checks.get(script.id)
  if (check == null) return null
  return check.script === script.script && check.scriptType === script.scriptType ? check : null
}

/** How many lines a script is — a trailing newline does not make one more. */
export function lineCount(text: string): number {
  if (text === '') return 0
  return text.replace(/\n$/, '').split('\n').length
}
