import type { Cue, CueInput } from '@/api/cuesApi'
import { formatMs } from './formatMs'

const CURVE_LABELS: Record<string, string> = {
  LINEAR: 'LIN',
  EASE_IN_OUT: 'SINE',
  SINE_IN_OUT: 'SINE',
  CUBIC_IN_OUT: 'CUB',
  EASE_IN: '\u2191',
  EASE_OUT: '\u2193',
}

/** Format fade duration + curve into a compact label like "2.0s SINE" or "SNAP". */
export function formatFadeText(fadeDurationMs: number | null, fadeCurve: string): string {
  if (fadeDurationMs != null && fadeDurationMs > 0) {
    return `${formatMs(fadeDurationMs)} ${CURVE_LABELS[fadeCurve] ?? ''}`.trim()
  }
  return 'SNAP'
}

/** The curve half of `formatFadeText` on its own — for layouts that make the duration editable. */
export function formatFadeCurve(fadeCurve: string): string {
  return CURVE_LABELS[fadeCurve] ?? ''
}

/** The duration half of `formatFadeText`: "2.0s", "500ms", or '' for a snap. */
export function formatFadeDuration(fadeDurationMs: number | null): string {
  if (fadeDurationMs != null && fadeDurationMs > 0) return formatMs(fadeDurationMs)
  return ''
}

/**
 * Parse a fade duration typed by an operator into milliseconds.
 *
 * Accepts an explicit unit ("500ms", "2s", "1.5m") or a bare number, which is read
 * as **seconds** — the console convention, and the unit the table shows fades in.
 * Empty / "0" / "snap" mean no fade.
 *
 * Returns `null` for a snap and `undefined` when the text can't be parsed, so
 * callers can distinguish "clear the fade" from "reject this input".
 */
export function parseFadeDuration(raw: string): number | null | undefined {
  const text = raw.trim()
  if (text === '' || /^snap$/i.test(text)) return null
  const match = /^(\d*\.?\d+)\s*(ms|msec|s|sec|secs|m|min)?$/i.exec(text)
  if (!match) return undefined
  const value = Number(match[1])
  if (!Number.isFinite(value) || value < 0) return undefined
  const unit = (match[2] ?? 's').toLowerCase()
  const ms =
    unit === 'ms' || unit === 'msec'
      ? value
      : unit === 'm' || unit === 'min'
        ? value * 60_000
        : value * 1000
  const rounded = Math.round(ms)
  return rounded > 0 ? rounded : null
}

/** Find the next available name of the form "{base}", "{base} 2", "{base} 3"… */
export function nextAvailableName(base: string, taken: Set<string>): string {
  if (!taken.has(base)) return base
  for (let i = 2; i < 10_000; i++) {
    const candidate = `${base} ${i}`
    if (!taken.has(candidate)) return candidate
  }
  return `${base} ${Date.now()}`
}

/** Build a CueInput snapshot from a Cue (for inline editing mutations). */
export function buildCueInput(cue: Cue): CueInput {
  return {
    name: cue.name,
    layers: cue.layers.map((layer) => ({
      lookId: layer.lookId,
      // Session 3: a layer applies a Look **or** a template. Missing from this rebuild it would be
      // dropped on every inline cue edit — and because both ids are optional now, the compiler would
      // not have said a word. Exactly the failure the comment below describes.
      templateId: layer.templateId,
      sortOrder: layer.sortOrder,
      enabled: layer.enabled,
      targets: layer.targets,
      propertyMask: layer.propertyMask,
      blendMode: layer.blendMode,
      amount: layer.amount,
      stomp: layer.stomp,
      // Rebuilt field-by-field (unlike adHocEffects' spread) because the detail row carries
      // `source`, which the input type must not. The cost of that shape is that every new field
      // must be added HERE too, or every inline cue edit silently strips it — which is what a
      // regression test in cueUtils.test.ts pins, field by field.
      speedMasterUuid: layer.speedMasterUuid,
      rateSpeedMasterUuid: layer.rateSpeedMasterUuid,
      delayMs: layer.delayMs,
      intervalMs: layer.intervalMs,
      randomWindowMs: layer.randomWindowMs,
    })),
    adHocEffects: cue.adHocEffects.map((e) => ({ ...e })),
    propertyAssignments: cue.propertyAssignments.map((a) => ({ ...a })),
    triggers: cue.triggers.map((t) => ({
      triggerType: t.triggerType,
      delayMs: t.delayMs,
      intervalMs: t.intervalMs,
      randomWindowMs: t.randomWindowMs,
      scriptId: t.scriptId,
      sortOrder: t.sortOrder,
    })),
    cueStackId: cue.cueStackId,
    sortOrder: cue.sortOrder,
    autoAdvance: cue.autoAdvance,
    autoAdvanceDelayMs: cue.autoAdvanceDelayMs,
    fadeDurationMs: cue.fadeDurationMs,
    fadeCurve: cue.fadeCurve,
    cueNumber: cue.cueNumber,
    notes: cue.notes,
    // The cue-level flag, not the per-layer one rebuilt above. Nothing on the desk sets it today,
    // but a cue can arrive carrying it (sync import, the AI tools, another client) and the PUT
    // route overwrites what it is not sent — so an inline edit must hand it back.
    stomp: cue.stomp,
    // Round-tripped so a PUT can't reinterpret a MARKER as a STANDARD cue. (The server ignores
    // cueType on PUT, but sending the truth keeps the payload honest if that ever changes.)
    cueType: cue.cueType,
  }
}

// `reorderCueLayers` and `densifyCueLayerOrder` stood here, restating every `sortOrder` from array
// position so a client-side layer drag could not leave two layers tied. Both were deleted rather
// than kept a third time: the drag they served went with `LayersPane` in session 2a, the programmer
// stack deliberately leaves that order to the server (`ProgrammerLookStack.onMove`), and nothing but
// their own tests had called them since. The rule they encoded is stated where it binds — `sortOrder`
// is the authoritative playback order, and a tie in it hands the cook step's precedence to insertion
// order, which is the accident the layer model exists to remove.
