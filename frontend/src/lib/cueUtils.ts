import type { Cue, CueAdHocFx, CueInput, CueLayer } from '@/api/cuesApi'
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

/** The fade curves a cue offers, in the order every curve control lists them. */
export const CUE_CURVE_OPTIONS: readonly { value: string; label: string }[] = [
  { value: 'LINEAR', label: 'Linear' },
  { value: 'SINE_IN_OUT', label: 'Sine In/Out' },
  { value: 'CUBIC_IN_OUT', label: 'Cubic In/Out' },
  { value: 'EASE_IN', label: 'Ease In' },
  { value: 'EASE_OUT', label: 'Ease Out' },
  { value: 'EASE_IN_OUT', label: 'Ease In/Out' },
]

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

/**
 * A follow typed into a field: blank, `off` or `—` is none (`null`); otherwise a delay in the fade
 * grammar, **0 kept** — a follow of 0 fires the next cue the moment this one lands, which
 * [parseFadeDuration] would read as a snap. `undefined` for anything else.
 */
export function parseFollowDelay(raw: string): number | null | undefined {
  const text = raw.trim()
  if (text === '' || /^(off|—|-)$/i.test(text)) return null
  if (/^0*\.?0*\s*(ms|msec|s|sec|secs|m|min)?$/i.test(text) && /\d/.test(text)) return 0
  return parseFadeDuration(text) ?? undefined
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

/**
 * A layer as the cue's write takes it: its write fields, rebuilt one by one — never the read-only
 * `source` or `id`, which the desk answers and ignores on write.
 */
export function cueLayerInput(layer: CueLayer): CueLayer {
  return {
    lookId: layer.lookId,
    // Session 3: a layer applies a Look **or** a template. Missing from this rebuild it would be
    // dropped on every inline cue edit — and because both ids are optional now, the compiler would
    // not have said a word. Exactly the failure the comment below describes.
    paletteId: layer.paletteId,
    sortOrder: layer.sortOrder,
    enabled: layer.enabled,
    targets: layer.targets,
    propertyMask: layer.propertyMask,
    blendMode: layer.blendMode,
    amount: layer.amount,
    stomp: layer.stomp,
    // Rebuilt field-by-field (unlike an effect's spread) because the detail row carries `source`
    // and `id`, which the input type must not. The cost of that shape is that every new field
    // must be added HERE too, or every inline cue edit silently strips it — which is what a
    // regression test in cueUtils.test.ts pins, field by field.
    speedMasterUuid: layer.speedMasterUuid,
    rateSpeedMasterUuid: layer.rateSpeedMasterUuid,
    delayMs: layer.delayMs,
    intervalMs: layer.intervalMs,
    randomWindowMs: layer.randomWindowMs,
  }
}

/** An effect as the cue's write takes it: everything but the read-only `uuid`. */
export function cueFxInput(effect: CueAdHocFx): CueAdHocFx {
  const { uuid: _uuid, ...input } = effect
  return input
}

/** Build a CueInput snapshot from a Cue (for inline editing mutations). */
export function buildCueInput(cue: Cue): CueInput {
  return {
    name: cue.name,
    layers: cue.layers.map(cueLayerInput),
    // `uuid` is the response's name for the stored effect, never a write field: the desk matches an
    // effect by its target, property and type, so a body echoing it back would claim an identity
    // the write does not read.
    adHocFx: cue.adHocFx.map(cueFxInput),
    propertyAssignments: cue.propertyAssignments.map((a) => ({ ...a })),
    triggers: cue.triggers.map((t) => ({
      triggerType: t.triggerType,
      delayMs: t.delayMs,
      intervalMs: t.intervalMs,
      randomWindowMs: t.randomWindowMs,
      scriptId: t.scriptId,
      sortOrder: t.sortOrder,
    })),
    cueStackId: cue.cueStackId ?? undefined,
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
    // A PUT clears the list it is not sent, so an inline edit hands it back like stomp.
    speedMasters: (cue.speedMasters ?? []).map((m) => ({ speedMasterUuid: m.speedMasterUuid, bpm: m.bpm })),
    // Round-tripped so a PUT can't reinterpret a MARKER as a STANDARD cue. (The server ignores
    // cueType on PUT, but sending the truth keeps the payload honest if that ever changes.)
    cueType: cue.cueType,
  }
}

// A client-side reorder of a cue's layers restates every `sortOrder` from array position, so two
// layers can never tie: the cue editor's Looks tab does (`components/cues/editor/cueListEdits.ts`'s
// `restated`); the programmer's Stack table leaves it to the server (`programmerMoveLayer`).
// `sortOrder` is the authoritative playback order, and a tie in it hands the cook step's precedence
// to insertion order, which is the accident the layer model exists to remove.
