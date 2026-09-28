import type { AttributeFamily } from '@/lib/attributeFamily'

/**
 * A **Template**: a named value for one attribute family, applied to a selection.
 *
 * The other half of the split `looksApi` used to serve alone. A Look *composes cues* — any families,
 * its own fixtures, its own effects, added to a stack as a layer. A Template composes **one named
 * thing** of exactly one family, with no targets of its own, applied to whatever you have selected.
 *
 * Backend contract in `lighting7/models/templates.kt` and
 * `docs/lighting-composition-model.md` §"A template holds a value *or* an effect".
 *
 * That "one named thing" is a **value or an effect, never both** (fx-templates D1) — a colour *and*
 * a chase is a Look, which already holds rows plus deferred effects. Which one a template holds is
 * its identity, like its family: the write boundary refuses a PUT that would flip it. Two rules keep
 * the effect half narrow: **one effect** (D2 — several together is, again, a Look), and **always
 * generic** (D3 — an effect template carries no target of any kind and fans over whatever the layer
 * names, so `isGeneric` is true for every one of them).
 *
 * Two absences remain the design rather than omissions: no fixture type (the values are **intents**,
 * resolved per head at cook), and no stored family — it is derived from the rows, or for an effect
 * template from its effect's library `category`, and validated to be exactly one at the write
 * boundary.
 */

/** The `targetType` marking a row as **generic** — it takes its targets from whatever applies it. */
export const DEFERRED_TARGET_TYPE = 'deferred' as const

/**
 * One stored template row.
 *
 * `targetType` is `deferred` for a **generic** template (one value, any head) or `fixture` for a
 * **per-fixture** one (a focus position: eight heads aimed at one spot hold eight different
 * pan/tilts). Never `group` — a template names no targets of its own, so the only reason a row names
 * a head is that its value is specific to that head, which a group cannot be.
 */
export interface TemplateRow {
  targetType: typeof DEFERRED_TARGET_TYPE | 'fixture'
  targetKey: string
  /** One of `TEMPLATE_PROPERTIES` — the closed vocabulary; see `templateIntent.ts`. */
  propertyName: string
  /** A serialised intent: `#FF9D4A;policy=extract`, `pct:75`, `deg:45,12.5`, `on`. */
  value: string
  sortOrder?: number
  /** Resolved server-side on read; ignored on write. */
  health?: string
}

/**
 * The one effect an **effect template** holds.
 *
 * Mirrors `TemplateEffectDto` in `lighting7/models/templates.kt` field for field.
 *
 * Deliberately **not** a reuse of `looksApi`'s `LookEffect`, which is otherwise the same shape: that
 * one carries `targetType`, `targetKey` and `sortOrder`, and a template effect has none of the three
 * (D3 — always generic, so no target; D2 — one effect, so no order). Sharing the type would put
 * three fields within reach of a `...spread` that the write boundary would then have to refuse.
 *
 */
export interface TemplateEffect {
  /** `FxRegistry` id — matches `EffectLibraryEntry.name`. */
  effectType: string
  /**
   * The effect's library category, and what the template's **family** is derived from (D4):
   * `dimmer` → Intensity, `colour` → Colour, `position` → Position. It is a denormalisation of the
   * library's own answer, and the write boundary checks the two agree.
   */
  category: string
  /** Null for a template effect — it names no target, so it names no property on one. */
  propertyName?: string | null
  /** Beats for a `BEAT` effect, seconds for a `WALL_CLOCK` one — see {@link timingSource}. */
  beatDivision: number
  blendMode: string
  distribution: string
  phaseOffset?: number
  elementMode?: string | null
  elementFilter?: string | null
  stepTiming?: boolean | null
  /** May hold a `tmpl:{uuid}` colour reference — but never to this template's own uuid (D12). */
  parameters: Record<string, string>
  /** Stamped at authoring time from the family's usage master (D8); null still means master 1. */
  speedMasterUuid?: string | null
  rateSpeedMasterUuid?: string | null
  /**
   * `BEAT` or `WALL_CLOCK` — **resolved server-side on read and ignored on write**, the same
   * contract `TemplateRow.health` has.
   *
   * It is what makes {@link beatDivision} readable: `2` is two beats or two seconds depending on
   * this, and the two readings are a tempo apart. Sending a value is harmless and pointless — the
   * registry is the authority and the server answers with the real one.
   *
   * Null where the stored `effectType` no longer resolves in the registry (an import from a desk
   * with extra script-registered effects). Say nothing about the speed then rather than guess.
   *
   * **A draft has none**, because a draft has not been near the server. The editor reads its
   * `EffectLibraryEntry` directly instead — it has one in hand to render the parameters at all —
   * so the rule is: *a draft asks the library, a saved template carries the answer*.
   */
  timingSource?: string | null
}

/**
 * A template, **rows included** — unlike `LookSummary`, which carries derived counts and a preview
 * instead.
 *
 * One shape for the list and the editor, because a template is small (one row generic, one per head
 * for a focus position) and has no derived counts that could go stale beside its contents. The
 * library row can therefore preview the real value without a second fetch.
 */
export interface TemplateSummary {
  id: number
  uuid: string
  name: string
  notes: string | null
  /** Fade for every row this template writes; null = the caller's default. */
  fadeDurationMs: number | null
  /**
   * The one family this template is in, **derived** from its rows — or, for an effect template,
   * from its effect's library category. Null only for a template whose contents have all gone,
   * which the write boundary does not allow.
   */
  family: AttributeFamily | null
  /** True when every row is generic; false for the per-fixture case. Always true for an effect. */
  isGeneric: boolean
  /**
   * Which of the two a template holds (D1) — orthogonal to `family`, which says *which* attribute.
   *
   * `value` means `rows` is populated and `effect` is null; `effect` means the reverse. Never both,
   * never neither.
   */
  kind: 'value' | 'effect'
  /**
   * The bundled colour emitters this template names outright — a subset of `white`, `amber`, `uv`,
   * **derived** from its rows the way `family` is, and in that order.
   *
   * The offer filter's other half. A template that names an emitter cannot be served by a head
   * without it — the *whole* template refuses on that head, not just the row — so a surface offering
   * templates has to check this as well as the family, which cannot say: white, amber, UV and the
   * hex are all `COLOUR`. Pair it with `targetEmitters` from `fixtures-list/rowModel`.
   */
  requiredEmitters: string[]
  /**
   * When this template was last **pressed** on this desk, as an ISO-8601 instant; null until it has
   * been.
   *
   * What the programmer's row of recent chips is ordered by. It is a **desk** fact rather than a
   * tab's — the server stamps it, on the chip's click, on ⌥click / hold, on a busk pad press and on
   * a MIDI `pressTemplate` — so every client and every surface agrees on what was reached for, and
   * a press made from the desk's own hardware counts. Toggling a layer **off** is not a press and
   * leaves it where it was.
   *
   * **Order these by parsing, never by comparing the text.** Java's `Instant.toString()` omits the
   * fractional part on an exact second, so `…:34Z` sorts *after* `…:34.500Z` lexicographically.
   * `lib/templateRecents.ts` is the one place that sorts them.
   *
   * **Optional, and that is the wire's doing rather than a convenience.** lighting7 hot-swaps changed
   * handler bodies but not new response fields, so a desk mid-upgrade serves rows without it — which
   * arrives here as `undefined`, not `null`. The type says so: declaring it `string | null` while
   * the comment described `undefined` left the one case the comment exists to warn about outside
   * what `strict` could see, and a future `template.lastPressedAt.length` would have compiled.
   * Every reader treats absent as "never pressed", which is what every template predating the
   * column actually is.
   */
  lastPressedAt?: string | null
  /**
   * **Optional, and that is the wire's doing too.** `encodeDefaults = false` drops a defaulted empty
   * collection from the frame entirely rather than sending `[]` — an *effect* template holds no rows
   * by construction, so it arrives with no `rows` field at all. Declaring this required hid two real
   * crashes from `strict` (`LayerPicker`'s unguarded `.length`, `TemplateEditor`'s unguarded `for…of`)
   * behind the same lie `lastPressedAt`'s doc comment above already warns against; `rows == null` is
   * "no rows", the same answer `templateRowsSwatch` gives it.
   */
  rows?: TemplateRow[]
  effect: TemplateEffect | null
  /**
   * How many **stored cue layers** apply this template. Gates delete.
   *
   * Never counts live programmer usage — that is the delete guard's `runningCount`, which is
   * deliberately not on this shape because it is in-memory only and would go stale in a list cache.
   */
  layerCount: number
  /**
   * How many busk pages hold a pad for this record — the row's "on *n* pages" hint, and the line
   * the delete confirm shows before those pads go with it.
   *
   * A hint, never a guard: it does not gate delete, `layerCount` alone does. Pages, not pads, so
   * two pads on one page count once — the question the operator is really asking is how many
   * places they would have to go and look.
   */
  buskPageCount: number
}

/**
 * Create / update payload.
 *
 * `notes` and `fadeDurationMs` carry an explicit `*Present` flag, because null is a *value* for both
 * (clear the notes; use the caller's default fade) and a PUT has to tell that from "leave it alone".
 *
 * **`rows` and `effect` carry no such flag, and could not use one.** Omitting either means "leave
 * that half alone"; sending one replaces it. Neither half can be *cleared*, because a template with
 * no contents is not a state the write boundary allows — so there is no third meaning for a flag to
 * express. Send **at most one of the two**: which half a template holds is fixed at creation, and a
 * PUT sending the wrong one for the stored kind is a 400 rather than a no-op.
 */
export interface TemplateInput {
  name?: string
  notes?: string | null
  notesPresent?: boolean
  fadeDurationMs?: number | null
  fadeDurationMsPresent?: boolean
  rows?: TemplateRow[]
  effect?: TemplateEffect
}

export interface TemplateTarget {
  type: 'fixture' | 'group'
  key: string
}

/** `POST …/templates/{id}/copy` — `CopyLookRequest`'s shape (library-sheets plan D10). */
export interface CopyTemplateRequest {
  targetProjectId: number
  /** Blank or absent keeps the source's name. */
  newName?: string
}

export interface CopyTemplateResponse {
  templateId: number
  templateName: string
  targetProjectId: number
  targetProjectName: string
  message: string
}

/** 409 body when a template is still applied by a layer. Rendered inline; offers "delete anyway". */
export interface TemplateInUseError {
  error: string
  code: 'TEMPLATE_IN_USE'
  layerCount: number
  cueIds: number[]
  cueNames: string[]
  /**
   * Effect parameters holding a `tmpl:{uuid}` reference to this template.
   *
   * A second kind of usage, and it fails differently from a layer: `force` deletes the *layers* but
   * cannot rewrite a parameter, so a forced delete leaves those effects naming nothing — and an
   * unresolvable reference reads as **white**. The guard has to say so.
   */
  fxReferenceCount?: number
  /**
   * Programmer layers tracking this template **right now** — a third kind of usage, and the only
   * one that is not stored anywhere.
   *
   * Distinct from `layerCount`, which counts saved cue layers. A forced delete stops it everywhere,
   * so this is a consequence to state rather than a reference to repoint.
   */
  runningCount?: number
}

/**
 * What `click` reports.
 *
 * The two arms report in different fields, because they do different things. A **value** template
 * writes literals: `written` counts them and `skipped` names the heads it could not reach. An
 * **effect** template mints detached programmer-band copies instead, so `written` stays 0 and
 * `effectIds` carries the instances — one per **target ref as named**, not one per head, because a
 * group selection stays one group-targeted effect with its distribution intact.
 */
export interface ApplyTemplateResponse {
  written: number
  skipped: { fixtureKey: string; propertyName: string; reason: string }[]
  effectIds?: number[]
}

/**
 * The click: literal values into Local. `families` is the selection's attribute mask (multi-screen
 * plan D4) — a template is one family, so under a mask it lands whole or is refused by name, 400
 * `TEMPLATE_OUTSIDE_MASK`, on both the value and the effect arm. Absent is every attribute.
 */
export interface ApplyTemplateRequest {
  targets: TemplateTarget[]
  fadeMs?: number
  families?: AttributeFamily[]
}

/**
 * ⌥click / hold: a layer that tracks the template. `propertyMask` is what this client believes
 * the layer's mask is; the desk derives the real one and reports it back. `families` is the
 * selection's mask, refused as on [ApplyTemplateRequest] — on the **on** arm only, since a press
 * that takes the layer off comes off under any mask.
 */
export interface ToggleTemplateRequest {
  targets: TemplateTarget[]
  propertyMask?: string
  families?: AttributeFamily[]
}

export interface ToggleTemplateResponse {
  action: 'applied' | 'removed'
  /**
   * Effects the added layer spawned — non-zero for an effect template, 0 for a value one.
   *
   * It was always 0 while a template held no effects, and the field existed only because the toggle
   * shape is shared with a Look's. It now means what it says.
   */
  effectCount: number
  /**
   * The mask the layer actually carries — the template's own family, **derived server-side** from
   * its rows (or its effect's category) rather than echoed from the request. Null only for a
   * template whose contents name no known family. It can therefore disagree with the `propertyMask`
   * sent, which is the point: the send states what this client believed, and a disagreement
   * surfaces here rather than on the rig.
   */
  propertyMask: string | null
  /**
   * How many **sibling** layers this press took off first — a template group's exclusivity, applied
   * to layers on the same target set. 0 on a `removed`, and for an ungrouped template. Optional on
   * the wire: a server that predates groups sends nothing here.
   */
  released?: number
}

/**
 * The editor's live panel, asked against a **draft**: these rows, optionally narrowed to these
 * heads. Empty `targets` means the whole patch an operator is offered — every head but an
 * infrastructure one; a target that names one still resolves it.
 *
 * A draft rather than a saved id because the panel's whole job is to answer "what will this do to my
 * rig?" *before* anything is written.
 */
export interface TemplateResolveRequest {
  rows: TemplateRow[]
  targets?: TemplateTarget[]
}

/** What one head will actually receive. */
export interface TemplateResolution {
  fixtureKey: string
  fixtureName: string
  typeKey: string
  propertyName: string
  /** The property the value landed on — differs from `propertyName` on a colour wheel. */
  resolvedPropertyName: string
  outcome: 'EXACT' | 'CLAMPED' | 'SNAPPED' | 'DEGRADED' | 'UNSUPPORTED'
  /** The clamp, the slot name, or the reason. Null when nothing was lost. */
  detail: string | null
  /** ΔE against the requested colour, on a wheel snap only. */
  deltaE: number | null
  /** The resolved value in the canonical assignment grammar — for a swatch or a number. */
  value: string | null
}

export interface TemplateResolveResponse {
  entries: TemplateResolution[]
}
