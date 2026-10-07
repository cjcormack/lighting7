import type { StageElementDto } from '../api/stageElementApi'
import type { LiveScenery, LiveSceneryEntry, PreviewScenery, ProgrammerScenery, ProgrammerSceneryEntry, SceneryChange, ScenerySource, SceneryState } from '../api/sceneryApi'
import { elementFlies, elementStates } from '../components/stage3d/scene/sceneParts'

/**
 * Scenery, pure (stage-view plan session 8): what a scenery state means for one element, how a move
 * between two states is drawn, and how a change reads. The desk resolves *which* state each element
 * is in (`show/SceneryResolver.kt`); this side only draws it and names it.
 */

export type SceneryKey = 'visible' | 'open' | 'trimM'

/**
 * The states [element] can take: `visible` always, `open` for a drawn drape, `trimM` for a flown
 * piece — the desk's `sceneryKeysOf`, and the two must agree.
 */
export function sceneryKeysOf(element: Pick<StageElementDto, 'kind' | 'params'>): SceneryKey[] {
  const keys: SceneryKey[] = ['visible']
  if (element.kind === 'DRAPE' && String(element.params.operation ?? '').toUpperCase() === 'DRAW') keys.push('open')
  if (elementFlies(element)) keys.push('trimM')
  return keys
}

/** Sine in-out: the curve scenery moves on. The desk's `SceneryService.ease`, and the two must agree. */
export function easeSceneryT(t: number): number {
  const c = Math.min(1, Math.max(0, t))
  return 0.5 - 0.5 * Math.cos(Math.PI * c)
}

/**
 * Where [entry] is drawn at [nowMs] (`performance.now()`): numeric states eased from `from` to
 * `state`; a piece appearing shows at once, one disappearing goes at the end — the desk's
 * `displayedAt`, so a retarget mid-move starts where the piece was drawn.
 */
export function sceneryAt(entry: Pick<LiveSceneryEntry, 'state' | 'from' | 'startedAtMs' | 'durationMs'>, nowMs: number): SceneryState {
  const t = entry.durationMs > 0 ? (nowMs - entry.startedAtMs) / entry.durationMs : 1
  if (t >= 1) return entry.state
  const e = easeSceneryT(t)
  const lerp = (a: number | undefined, b: number | undefined) => (a == null || b == null ? b : a + (b - a) * e)
  const out: SceneryState = {}
  const visible = entry.state.visible === true ? true : (entry.from.visible ?? entry.state.visible)
  if (visible != null) out.visible = visible
  const open = lerp(entry.from.open, entry.state.open)
  if (open != null) out.open = open
  const trimM = lerp(entry.from.trimM, entry.state.trimM)
  if (trimM != null) out.trimM = trimM
  return out
}

/** When the last move in [scenery] lands, on the `performance.now()` clock; -Infinity when nothing moves. */
export function sceneryLandsAt(scenery: LiveScenery): number {
  let last = -Infinity
  for (const entry of Object.values(scenery.entries)) {
    if (entry.durationMs > 0) last = Math.max(last, entry.startedAtMs + entry.durationMs)
  }
  return last
}

/** [element] with [state] laid over its own `params.states` — what the builders and the beam reach read. */
export function withScenery(element: StageElementDto, state: SceneryState): StageElementDto {
  const own = (element.params.states ?? {}) as Record<string, unknown>
  return { ...element, params: { ...element.params, states: { ...own, ...state } } }
}

/**
 * The scene's elements with the live scenery laid over them at [nowMs] ([final] draws every move
 * landed — a one-frame render). An element the scenery leaves where its base is comes back as the
 * **same object**, and so does one whose drawn state has not changed since the last call ([cache]),
 * so the build cache keyed on element objects rebuilds only what actually moved.
 */
export function sceneryElements(
  elements: readonly StageElementDto[],
  scenery: LiveScenery,
  nowMs: number,
  cache: SceneryOverlayCache,
  final = false,
): StageElementDto[] {
  if (Object.keys(scenery.entries).length === 0) return elements as StageElementDto[]
  return elements.map((element) => {
    const entry = scenery.entries[element.uuid]
    if (entry == null) return element
    const state = final ? entry.state : sceneryAt(entry, nowMs)
    const signature = JSON.stringify(state)
    const cached = cache.get(element)
    if (cached != null && cached.signature === signature) return cached.element
    const overlaid = sameAsBase(element, state) ? element : withScenery(element, state)
    cache.set(element, { signature, element: overlaid })
    return overlaid
  })
}

export type SceneryOverlayCache = WeakMap<StageElementDto, { signature: string; element: StageElementDto }>

function sameAsBase(element: StageElementDto, state: SceneryState): boolean {
  const base = elementStates(element)
  return (
    (state.visible == null || state.visible === (base.visible ?? true)) &&
    (state.open == null || state.open === (base.open ?? 0)) &&
    (state.trimM == null || state.trimM === (base.trimM ?? element.positionZ))
  )
}

/** A Next GO preview's scenery as live scenery, its moves starting at [startedAtMs]. */
export function previewScenery(preview: readonly PreviewScenery[] | undefined, projectId: number | null, startedAtMs: number): LiveScenery {
  const entries: Record<string, LiveSceneryEntry> = {}
  for (const p of preview ?? []) {
    entries[p.elementUuid] = { elementUuid: p.elementUuid, state: p.state, from: p.from, startedAtMs, durationMs: p.durationMs }
  }
  return { projectId, entries }
}

// — authoring ————————————————————————————————————————————————————————————————————

/** One state an editor row offers for an element: a label and the state it writes. */
export interface SceneryChoice {
  id: string
  label: string
  state: SceneryState
}

/** A flown piece's two trims: its own Z, where it plays (in), and its stored trim, usually out. */
export function trimsOf(element: Pick<StageElementDto, 'kind' | 'params' | 'positionZ'>): { inM: number; outM: number | null } {
  const stored = elementStates(element).trimM
  return { inM: element.positionZ, outM: stored != null && Math.abs(stored - element.positionZ) > 1e-6 ? stored : null }
}

/** The states an editor row offers for [element], by its kind. */
export function sceneryChoices(element: StageElementDto): SceneryChoice[] {
  const keys = sceneryKeysOf(element)
  const out: SceneryChoice[] = []
  if (keys.includes('open')) {
    out.push(
      { id: 'open:0', label: 'Closed', state: { open: 0 } },
      { id: 'open:0.5', label: 'Half open', state: { open: 0.5 } },
      { id: 'open:1', label: 'Drawn', state: { open: 1 } },
    )
  }
  if (keys.includes('trimM')) {
    const { inM, outM } = trimsOf(element)
    out.push({ id: 'trim:in', label: `Trim · in (${formatMetres(inM)})`, state: { trimM: inM } })
    if (outM != null) out.push({ id: 'trim:out', label: `Trim · out (${formatMetres(outM)})`, state: { trimM: outM } })
  }
  out.push(
    { id: 'visible:true', label: 'Shown', state: { visible: true } },
    { id: 'visible:false', label: 'Hidden', state: { visible: false } },
  )
  return out
}

/**
 * The part of [state] that [element] can take, or null when none of it is — a row moved to another
 * element keeps what still fits. `visible` and `open` carry over as they are; a trim only where it
 * is one of the new piece's own (its in or its out), since a height means nothing on another piece.
 */
export function statesFor(element: StageElementDto, state: SceneryState): SceneryState | null {
  const keys = sceneryKeysOf(element)
  const out: SceneryState = {}
  if (state.visible != null) out.visible = state.visible
  if (state.open != null && keys.includes('open')) out.open = state.open
  if (state.trimM != null && keys.includes('trimM')) {
    const { inM, outM } = trimsOf(element)
    const near = (m: number | null) => m != null && Math.abs(m - state.trimM!) < 1e-6
    if (near(inM) || near(outM)) out.trimM = state.trimM
  }
  return Object.keys(out).length > 0 ? out : null
}

export function sameState(a: SceneryState, b: SceneryState): boolean {
  const close = (x?: number, y?: number) => (x == null ? y == null : y != null && Math.abs(x - y) < 1e-6)
  return a.visible === b.visible && close(a.open, b.open) && close(a.trimM, b.trimM)
}

function formatMetres(m: number): string {
  return `${Number(m.toFixed(2))} m`
}

/**
 * How [state] reads for [element] — `drawn`, `closed`, `open 40%`, `trim · out`, `trim · 4.2 m`,
 * `hidden` — joined by ` · ` where a change sets several. [element] may be missing (deleted, or a
 * list not loaded yet); the numbers then read plainly.
 */
export function describeSceneryState(element: StageElementDto | undefined, state: SceneryState): string {
  const parts: string[] = []
  if (state.open != null) {
    parts.push(state.open <= 0 ? 'closed' : state.open >= 1 ? 'drawn' : `open ${Math.round(state.open * 100)}%`)
  }
  if (state.trimM != null) {
    const trims = element ? trimsOf(element) : null
    const near = (m: number | null | undefined) => m != null && Math.abs(m - state.trimM!) < 1e-6
    parts.push(trims && near(trims.inM) ? 'trim · in' : trims && near(trims.outM) ? 'trim · out' : `trim · ${formatMetres(state.trimM)}`)
  }
  if (state.visible != null) parts.push(state.visible ? 'shown' : 'hidden')
  return parts.join(' · ') || 'no state'
}

/**
 * [state]'s short words for [element] — `closed`, `drawn`, `open 40%`, `in`, `out`, `4.2 m`,
 * `shown`, `hidden` — joined by `, `; empty when it states nothing. The short form of
 * [describeSceneryState], without its `trim ·` prefix, for a row with no room for it: the Looks
 * library's summary and a cue change's line. [element] may be missing; the numbers then read plainly.
 */
export function shortSceneryWords(element: StageElementDto | undefined, state: SceneryState): string {
  const parts: string[] = []
  if (state.open != null) {
    parts.push(state.open <= 0 ? 'closed' : state.open >= 1 ? 'drawn' : `open ${Math.round(state.open * 100)}%`)
  }
  if (state.trimM != null) {
    const trims = element ? trimsOf(element) : null
    const near = (m: number | null | undefined) => m != null && Math.abs(m - state.trimM!) < 1e-6
    parts.push(trims && near(trims.inM) ? 'in' : trims && near(trims.outM) ? 'out' : formatMetres(state.trimM))
  }
  if (state.visible != null) parts.push(state.visible ? 'shown' : 'hidden')
  return parts.join(', ')
}

/**
 * One element's scenery in a library row's words — `Moon in`, `Tabs drawn`, `Sofa shown`,
 * `Cloth 4.2 m, hidden` ([shortSceneryWords] after the name).
 */
export function summariseSceneryState(element: StageElementDto | undefined, name: string, state: SceneryState): string {
  const words = shortSceneryWords(element, state)
  return words ? `${name} ${words}` : name
}

/** A cue change as its card reads it: what it does to the element, and on whose clock. */
export function describeCueChange(element: StageElementDto | undefined, state: SceneryState, transitionMs: number | null | undefined): string {
  return `${describeSceneryState(element, state)} · ${clockWords(transitionMs) ?? 'with the cue'}`
}

/** Milliseconds as a read-out says them: `4 s`, `0.5 s`. */
function seconds(ms: number): string {
  return `${Number((ms / 1000).toFixed(2))} s`
}

/** A cue change's own clock in words — `snap`, `4 s` — or null on the cue's fade; one wording for every reader. */
function clockWords(transitionMs: number | null | undefined): string | null {
  return transitionMs == null ? null : transitionMs === 0 ? 'snap' : seconds(transitionMs)
}

/**
 * A cue change as one line of the cue table and the Prompt Book (scenery-programmer plan D13, D14):
 * `Tabs → closed · 4 s` on its own clock, `Moon → in` on the cue's fade (the mock-ups' copy: the
 * cue's own clock goes unsaid), `Sofa → hidden · snap`. [name] is the element's.
 */
export function cueChangeLine(element: StageElementDto | undefined, name: string, state: SceneryState, transitionMs: number | null | undefined): string {
  const clock = clockWords(transitionMs)
  return `${name} → ${shortSceneryWords(element, state) || 'no state'}${clock == null ? '' : ` · ${clock}`}`
}

/**
 * A cue's own changes ([changes], off the stack list's `CueStackCueEntry.scenery`) as their lines,
 * in the cue's order. Names come from the scene ([byUuid]), so a renamed element reads its new name;
 * once the scene is known, a change whose element it no longer has is left out — a delete sweeps the
 * element's changes and announces the stack list, but the two lists refetch apart, and a line for a
 * piece that is gone should not flash in between. [byUuid] null is a scene not yet loaded: every
 * change is read with the name it was stored under.
 */
export function cueSceneryLines(
  changes: readonly SceneryChange[] | undefined,
  byUuid: ReadonlyMap<string, StageElementDto> | null,
): string[] {
  if (changes == null || changes.length === 0) return []
  const out: string[] = []
  for (const change of [...changes].sort((a, b) => a.sortOrder - b.sortOrder)) {
    const element = byUuid?.get(change.elementUuid)
    if (byUuid != null && element == null) continue
    out.push(cueChangeLine(element, element?.name ?? change.elementName, change.state, change.transitionMs))
  }
  return out
}

/** One piece the next GO moves, as the Prompt Book's *On GO* line names it (scenery-programmer plan D14). */
export interface OnGoMove {
  elementUuid: string
  name: string
  /** What it does, in a DSM's words: `close`, `draw`, `to 40%`, `in`, `out`, `to 4.2 m`, `appears`, `hides`. */
  words: string
  /** How long the GO takes over it; 0 lands it. */
  durationMs: number
}

/**
 * What the next GO moves (D14): the Next GO preview's scenery ([preview] — whole, an element it
 * leaves out shows its base) compared with the live stage ([live] — likewise), key by key over each
 * element's own states with its base filled in, so a piece the preview names but live does not, or
 * live names but the preview does not, is compared against where it really is. A piece whose states
 * all agree is not moving and is left out; one that goes from hidden to shown **appears**, and from
 * shown to hidden **hides**. An element the scene does not have is left out (nothing to fill its base
 * from). Ordered by name. [preview] undefined is no preview: nothing moves.
 */
export function onGoMoves(
  preview: readonly PreviewScenery[] | undefined,
  live: LiveScenery,
  elements: readonly StageElementDto[],
): OnGoMove[] {
  if (preview == null) return []
  const byUuid = new Map(elements.map((e) => [e.uuid, e]))
  const next = new Map(preview.map((p) => [p.elementUuid, p]))
  const uuids = new Set([...next.keys(), ...Object.keys(live.entries)])
  const out: OnGoMove[] = []
  for (const uuid of uuids) {
    const element = byUuid.get(uuid)
    if (element == null) continue
    const before = shownSceneryState(element, live.entries[uuid]?.state)
    const target = next.get(uuid)
    const after = shownSceneryState(element, target?.state)
    if (sameState(before, after)) continue
    const words: string[] = []
    const close = (a?: number, b?: number) => a != null && b != null && Math.abs(a - b) < 1e-6
    if (after.open != null && !close(before.open, after.open)) {
      words.push(after.open <= 0 ? 'close' : after.open >= 1 ? 'draw' : `to ${Math.round(after.open * 100)}%`)
    }
    if (after.trimM != null && !close(before.trimM, after.trimM)) {
      const { inM, outM } = trimsOf(element)
      words.push(close(after.trimM, inM) ? 'in' : close(after.trimM, outM ?? undefined) ? 'out' : `to ${formatMetres(after.trimM)}`)
    }
    if (after.visible !== before.visible) words.push(after.visible ? 'appears' : 'hides')
    out.push({ elementUuid: uuid, name: element.name, words: words.join(', '), durationMs: target?.durationMs ?? 0 })
  }
  return out.sort((a, b) => a.name.localeCompare(b.name))
}

/** [moves] as the *On GO* line says them: `House tabs close 4 s · Moon in · Sofa appears`; empty when nothing moves. */
export function describeOnGo(moves: readonly OnGoMove[]): string {
  return moves.map((m) => `${m.name} ${m.words}${m.durationMs > 0 ? ` ${seconds(m.durationMs)}` : ''}`).join(' · ')
}

// — the control ————————————————————————————————————————————————————————————————————

/**
 * One step a `SceneryControl` offers as a preset (scenery-programmer plan D5): today's editor steps,
 * named as the mock-ups name them — *Closed · Half · Drawn* for a drawn drape, *In · Out* for a
 * flown piece. Visibility is its own row (*Shown · Hidden*), since every element takes it.
 */
export interface SceneryPreset {
  id: string
  label: string
  state: SceneryState
}

/** The travel presets [element] takes — none for a piece that only shows and hides. */
export function sceneryPresetsOf(element: StageElementDto): SceneryPreset[] {
  const keys = sceneryKeysOf(element)
  if (keys.includes('open')) {
    return [
      { id: 'open:0', label: 'Closed', state: { open: 0 } },
      { id: 'open:0.5', label: 'Half', state: { open: 0.5 } },
      { id: 'open:1', label: 'Drawn', state: { open: 1 } },
    ]
  }
  if (keys.includes('trimM')) {
    const { inM, outM } = trimsOf(element)
    const out: SceneryPreset[] = [{ id: 'trim:in', label: 'In', state: { trimM: inM } }]
    if (outM != null) out.push({ id: 'trim:out', label: 'Out', state: { trimM: outM } })
    return out
  }
  return []
}

/** The preset [state] sits on, by its travel key, or null between two (a slider's 0.3). */
export function presetOf(presets: readonly SceneryPreset[], state: SceneryState): SceneryPreset | null {
  const close = (x?: number, y?: number) => x != null && y != null && Math.abs(x - y) < 1e-6
  return presets.find((p) => (p.state.open != null ? close(p.state.open, state.open) : close(p.state.trimM, state.trimM))) ?? null
}

/**
 * The range a `SceneryControl`'s slider rides for [element] (D5): `open` from 0 to 1, shown as a
 * percentage, or `trimM` between the piece's in (its Z, where it plays) and its out (its stored
 * trim), shown in metres — low end first, whichever of the two that is. Null for a piece with no
 * travel, and for a flown piece with no out to travel to (its stored trim is its Z).
 */
export interface SceneryRange {
  key: 'open' | 'trimM'
  min: number
  max: number
  step: number
  unit: '%' | 'm'
}

export function sceneryRangeOf(element: StageElementDto): SceneryRange | null {
  const keys = sceneryKeysOf(element)
  if (keys.includes('open')) return { key: 'open', min: 0, max: 1, step: 0.01, unit: '%' }
  if (keys.includes('trimM')) {
    const { inM, outM } = trimsOf(element)
    if (outM == null) return null
    return { key: 'trimM', min: Math.min(inM, outM), max: Math.max(inM, outM), step: 0.01, unit: 'm' }
  }
  return null
}

/**
 * Every state [element] takes, as the stage would show it with [over] laid on its base: the base
 * from its own `params.states` (shown, closed, at its stored trim or Z), then each layer in order —
 * the live entry's target, then a blind staged one. What a control draws, whole.
 */
export function shownSceneryState(element: StageElementDto, ...over: ReadonlyArray<SceneryState | undefined>): SceneryState {
  const keys = sceneryKeysOf(element)
  const base = elementStates(element)
  const out: SceneryState = { visible: base.visible ?? true }
  if (keys.includes('open')) out.open = base.open ?? 0
  if (keys.includes('trimM')) out.trimM = base.trimM ?? element.positionZ
  for (const layer of over) {
    if (layer == null) continue
    if (layer.visible != null) out.visible = layer.visible
    if (layer.open != null && keys.includes('open')) out.open = layer.open
    if (layer.trimM != null && keys.includes('trimM')) out.trimM = layer.trimM
  }
  return out
}

/** What [element] is, in a row's words: *drawn drape*, *flown drape*, *flown piece*, else its kind. */
export function sceneryKindLabel(element: Pick<StageElementDto, 'kind' | 'params'>): string {
  const keys = sceneryKeysOf(element)
  if (element.kind === 'DRAPE') return keys.includes('open') ? 'drawn drape' : keys.includes('trimM') ? 'flown drape' : 'drape'
  if (element.kind === 'OBJECT' && keys.includes('trimM')) return 'flown piece'
  return element.kind.toLowerCase()
}

/** Does [element] move — draw or fly — rather than only show and hide? The tab's *Moving only*. */
export function sceneryMoves(element: Pick<StageElementDto, 'kind' | 'params'>): boolean {
  return sceneryKeysOf(element).length > 1
}

/**
 * What holds a piece where it is, as a row reads it (D4): *held by the programmer*, *held by Night,
 * pressed*, *tracked from Q14*, *held by Main's set*, *at its base*. From `scenery.state`'s
 * `source`, which names the top tier only — nothing says what the tier below would hold. An element
 * the desk's frame does not list shows its base; an older desk names no source at all, and the row
 * says nothing rather than guess.
 */
export function describeScenerySource(source: ScenerySource | undefined, listed: boolean): string | null {
  if (!listed) return 'at its base'
  if (source == null) return null
  switch (source.kind) {
    case 'programmer':
      return 'held by the programmer'
    case 'programmerLook':
      return `held by ${source.name ?? 'a Look'}, pressed`
    case 'cueLook':
      return `held by ${source.name ?? 'a Look'} in a live cue`
    case 'cue':
      return `tracked from ${cueName(source.label)}`
    case 'set':
      return source.name ? `held by ${source.name}'s set` : "held by a stack's set"
    case 'base':
      return 'at its base'
  }
}

/** A cue label as the desk reads it out: a number is *Q14*, a name is itself. */
function cueName(label: string | undefined): string {
  if (label == null || label === '') return 'a cue'
  return /^\d/.test(label) ? `Q${label}` : label
}

/** [value] as its field shows it: `open` as a whole percentage, `trimM` in metres to the centimetre. */
export function rangeFieldValue(range: SceneryRange, value: number): number {
  return range.unit === '%' ? Math.round(value * 100) : Math.round(value * 100) / 100
}

/** A typed field value back to the state's own unit, clamped into the range. */
export function rangeFromField(range: SceneryRange, typed: number): number {
  const raw = range.unit === '%' ? typed / 100 : typed
  return Math.min(range.max, Math.max(range.min, raw))
}

/**
 * What the programmer holds of [projectId]'s scenery: the overlay's elements when the overlay is
 * that project's — or names no project yet, before its first frame — and nothing otherwise. The one
 * rule for every reader (Clear's count, the rail's, Record's gates and the Record sheets' row), so a
 * switch mid-frame cannot leave Record enabled on one surface and not another.
 */
export function heldSceneryIn(scenery: ProgrammerScenery, projectId: number): readonly ProgrammerSceneryEntry[] {
  return scenery.projectId == null || scenery.projectId === projectId ? scenery.elements : []
}

// — the stage's own reads ——————————————————————————————————————————————————————————

/**
 * The live scenery a vis source draws (scenery-programmer plan D12): **Output** draws the stage as
 * it is; **Output + Programmer** and **Programmer** draw it with Blind's staged moves laid over —
 * what leaving Blind would land — the way their channels preview the blind programmer. With nothing
 * staged (not blind, an older desk) every source draws live, and the frame comes back as the same
 * object, so a canvas reading it rebuilds nothing. Next GO is not this function's: it draws the
 * preview's scenery.
 */
export function sceneryForSource(live: LiveScenery, source: 'output' | 'outputProgrammer' | 'programmer'): LiveScenery {
  if (source === 'output' || live.staged == null || Object.keys(live.staged).length === 0) return live
  return { projectId: live.projectId, entries: { ...live.entries, ...live.staged } }
}

/**
 * Whether a click on [element] in the Stage view opens its scenery popover (D11): a piece that
 * travels — a drawn drape, a flown drape or object — or anything on the Set layer, which can at
 * least be shown and hidden. The venue's fixed pieces (a room, the proscenium, a dead leg) are
 * walls a click passes through to nothing, as it always has.
 */
export function isSceneryPickable(element: Pick<StageElementDto, 'kind' | 'params' | 'layer'>): boolean {
  return element.kind !== 'ROOM' && element.kind !== 'SEATING' && (sceneryMoves(element) || element.layer === 'SET')
}

/** One owner that moves an element, as *Moves with* lists it. */
export interface MovesWithEntry {
  kind: 'cue' | 'set' | 'look'
  /** The owner's id: a cue's, a stack's (its set) or a Look's. */
  id: number
  /** The owner, in the list's words: *Q14*, *Main's set*, *Night*. */
  owner: string
  /** What it does to the piece: *trim · out · 4 s*, *hidden*. */
  what: string
}

/**
 * The read (`GET stage-elements/{id}/scenery`) as *Moves with* lists it: cues first in show order,
 * each with its clock, then the stacks' sets, then the Looks — the resolver's own order from the
 * bottom tier the read can name to the top. [stackName] names a cue's stack where the show has more
 * than one, so two *Q1*s read apart.
 */
export function movesWithEntries(
  element: StageElementDto | undefined,
  read: {
    cues: ReadonlyArray<{ stackId: number; cueId: number; label: string; state: SceneryState; transitionMs: number | null }>
    sets: ReadonlyArray<{ stackId: number; name: string; state: SceneryState }>
    looks: ReadonlyArray<{ lookId: number; name: string; state: SceneryState }>
  },
  stackName?: (stackId: number) => string | undefined,
): MovesWithEntry[] {
  const stacks = new Set(read.cues.map((c) => c.stackId))
  return [
    ...read.cues.map((c) => {
      const stack = stacks.size > 1 ? stackName?.(c.stackId) : undefined
      return {
        kind: 'cue' as const,
        id: c.cueId,
        owner: stack ? `${stack} · ${cueName(c.label)}` : cueName(c.label),
        what: describeCueChange(element, c.state, c.transitionMs),
      }
    }),
    ...read.sets.map((s) => ({ kind: 'set' as const, id: s.stackId, owner: `${s.name}'s set`, what: describeSceneryState(element, s.state) })),
    ...read.looks.map((l) => ({ kind: 'look' as const, id: l.lookId, owner: l.name, what: describeSceneryState(element, l.state) })),
  ]
}

/**
 * What of the scene the Positions panel's plan draws (scenery-programmer plan D16): every drape —
 * tabs, legs, borders, a cyc, which the venue usually owns — and the Set layer, the pieces that
 * change where light lands. Not the room, the proscenium or the seats: the plan is the rig's, and a
 * hall drawn round it would only cover it. A module function, so `Stage3D`'s filtered list keeps
 * its identity across renders.
 */
export function plansScenery(element: Pick<StageElementDto, 'kind' | 'layer'>): boolean {
  return element.kind === 'DRAPE' || element.layer === 'SET'
}
