import type {
  BindingTarget,
  ColourAxis,
  ControlSurfaceBinding,
  ControlSurfaceType,
  EncoderBankSelection,
  FixturePropertyTarget,
  GroupPropertyTarget,
  StripDefinition,
} from '../api/surfacesApi'
import { withAxis } from '@/lib/colourAxis'

/**
 * Which binding a control is actually running, and what it behaves as.
 *
 * **This is a mirror of two backend rules, and the only copy of them a browser can reach.**
 * `ControlSurfaceBindingService.resolve` answers the same question for the router, the feedback
 * index and the takeover lookup, and `deriveStripTarget` turns a strip row into the target each of
 * its controls behaves as. Neither is exposed over the wire — the binding list is the raw rows —
 * so the panel, which has to label every control, re-derives both here.
 *
 * That makes this the half of the view whose failure is silent: a precedence read the wrong way
 * round paints a plausible label for a control the desk is driving differently, and nothing on the
 * rig contradicts it. `surfaceResolve.test.ts` therefore replays lighting7's own
 * `ControlSurfaceBindingResolverTest` and `StripDeriveTest` cases, the way `maskPicker.test.ts`
 * pins the family vocabulary against the backend's.
 *
 * Keep in step with `midi/ControlSurfaceBindingService.kt` and `midi/StripDerivation.kt`.
 */

/** The property a strip fader and its flash button always drive (`STRIP_FADER_PROPERTY`). */
export const STRIP_FADER_PROPERTY = 'dimmer'

/** What the encoder bank holds before anything sets it (`EncoderBankState.DEFAULT`). */
export const DEFAULT_ENCODER_BANK: EncoderBankSelection = { propertyName: 'dimmer' }

/** The role a control plays within a strip. */
export type StripRole = 'fader' | 'select' | 'encoder' | 'flash'

/** Role order matters: it is the order the *Fader only…* expansion creates rows in. */
const STRIP_ROLES: StripRole[] = ['fader', 'select', 'encoder', 'flash']

/** A control's place on a strip: which strip claims it, and as what. */
export interface StripControl {
  strip: StripDefinition
  role: StripRole
}

function controlForRole(strip: StripDefinition, role: StripRole): string | null {
  switch (role) {
    case 'fader':
      return strip.fader
    case 'select':
      return strip.select
    case 'encoder':
      return strip.encoder ?? null
    case 'flash':
      return strip.flash ?? null
  }
}

/**
 * Every control the profile's strips claim, keyed by control id — "which strip owns this
 * control", answered once for a whole panel rather than scanned per control per frame.
 *
 * The backend registry refuses a control claimed by two strips, so the last-writer-wins here
 * never fires on a profile that loaded.
 */
export function stripControlsByControlId(
  strips: readonly StripDefinition[],
): Map<string, StripControl> {
  const byControl = new Map<string, StripControl>()
  for (const strip of strips) {
    for (const role of STRIP_ROLES) {
      const controlId = controlForRole(strip, role)
      if (controlId != null) byControl.set(controlId, { strip, role })
    }
  }
  return byControl
}

/**
 * A continuous target on a group or a fixture — the same shape a hand-made binding has, so a
 * derived binding and a fixed one read identically everywhere downstream.
 */
function propertyTarget(
  target: { type: 'group' | 'fixture'; key: string },
  propertyName: string,
  colourAxis?: ColourAxis | null,
): FixturePropertyTarget | GroupPropertyTarget {
  // `withAxis` leaves the field *absent* for hue rather than writing `null`: that is the form
  // every pre-axis row has and the server writes, and `toEqual` in the tests tells the two apart.
  const base: FixturePropertyTarget | GroupPropertyTarget =
    target.type === 'group'
      ? { type: 'groupProperty', groupName: target.key, propertyName }
      : { type: 'fixtureProperty', fixtureKey: target.key, propertyName }
  return withAxis(base, colourAxis)
}

/**
 * What a control on a strip behaves as: the fader and flash on the target's dimmer, the select
 * button a toggling `selectTarget`, the encoder on whichever attribute — and, for a colour,
 * whichever axis — the device's encoder bank currently names.
 */
export function deriveStripTarget(
  role: StripRole,
  target: { type: 'group' | 'fixture'; key: string },
  encoderBank: EncoderBankSelection,
): BindingTarget {
  switch (role) {
    case 'fader':
      return propertyTarget(target, STRIP_FADER_PROPERTY)
    case 'select':
      return { type: 'selectTarget', target, mode: 'toggle' }
    case 'encoder':
      return propertyTarget(target, encoderBank.propertyName, encoderBank.colourAxis)
    case 'flash':
      return { type: 'flash', target: propertyTarget(target, STRIP_FADER_PROPERTY) }
  }
}

/**
 * The row on this slot for **exactly** this bank, with no fallback — what a *write* replaces.
 *
 * Paired with [activeBindingAt] and deliberately not the same question, because reading and
 * writing want opposite answers about the bank-agnostic row. A drop must **create** an exact-bank
 * row rather than patch the global one, or retargeting a control on bank A silently retargets it
 * on every other bank too. `bindingWriteFor` is the caller.
 *
 * A named primitive rather than an inline `index.byControl.get(id)?.get(bank)` at each site: the
 * index's shape (`null` is a real key, so it is a `Map`) is otherwise an implementation detail
 * leaking into two unrelated files, where a partial change to it would silently reintroduce
 * exactly the "moved a binding the operator was not pointing at" bug.
 */
export function exactBindingAt(
  index: SurfaceBindingIndex,
  controlId: string,
  bank: string | null,
): ControlSurfaceBinding | null {
  return index.byControl.get(controlId)?.get(bank) ?? null
}

/**
 * The row **driving** this slot right now: this bank's, else the bank-agnostic one.
 *
 * The *reading* question, and the fallback is the whole of the difference from [exactBindingAt]: a
 * global row really is in force on every bank, so anything that says what a slot is currently doing
 * — the label under a control, the cross that removes it — has to see it. `resolveControl` applies
 * this at both of its levels.
 */
export function activeBindingAt(
  index: SurfaceBindingIndex,
  controlId: string,
  bank: string | null,
): ControlSurfaceBinding | null {
  const byBank = index.byControl.get(controlId)
  return byBank?.get(bank) ?? byBank?.get(null) ?? null
}

/** One control's answer: the row driving it, and the target that row means *for this control*. */
export interface ResolvedControl {
  /** The persisted row — a strip row keeps its own id, bank, policy and health. */
  binding: ControlSurfaceBinding
  /** What this control behaves as: the row's own target, or the strip's derived one. */
  target: BindingTarget
  /** The strip this control was resolved through, or null for a direct binding. */
  via: StripControl | null
}

/**
 * The binding driving `controlId`, or null when it is unbound.
 *
 * The order is **the control's own row across both bank levels, then its strip's across both**:
 *
 *   1. the control's row for the exact bank,
 *   2. the control's bank-agnostic row,
 *   3. the strip's row for the exact bank,
 *   4. the strip's bank-agnostic row.
 *
 * Direct before strip at *both* levels is the load-bearing part, and the one an "obvious"
 * bank-first reading gets backwards: a bank-agnostic strip must lose to an exact-bank binding on
 * one of its controls, which is what makes "any control can still be bound on its own" true
 * regardless of banks.
 *
 * A strip hit answers the strip's row with the target this control behaves as. Health is
 * deliberately not re-evaluated per derived target: an encoder whose bank property no selected
 * head declares reads unbound and drops its turn, which is a fact about the selection rather than
 * a dead binding.
 */
export function resolveControl(
  controlId: string,
  index: SurfaceBindingIndex,
  activeBank: string | null,
  encoderBank: EncoderBankSelection,
): ResolvedControl | null {
  const directHit = activeBindingAt(index, controlId, activeBank)
  if (directHit) return { binding: directHit, target: directHit.target, via: null }

  const onStrip = index.stripControls.get(controlId)
  if (!onStrip) return null
  const stripHit = activeBindingAt(index, onStrip.strip.id, activeBank)
  if (!stripHit) return null

  // A row on a strip id that is not a `strip` target cannot be derived — bind-time validation
  // refuses one, so this is a row written by an older build or by hand. Answer it as it stands
  // rather than dropping it, so the inspector can show it and the operator can rebind it.
  if (stripHit.target.type !== 'strip') {
    return { binding: stripHit, target: stripHit.target, via: onStrip }
  }

  return {
    binding: stripHit,
    target: deriveStripTarget(onStrip.role, stripHit.target.target, encoderBank),
    via: onStrip,
  }
}

/**
 * The bindings of one device type, arranged the way `resolveControl` reads them: control id (or
 * strip id) → bank → row, plus the profile's strip map. Built once per panel render rather than
 * scanned per control, which is the same reason the backend caches its own.
 *
 * `null` is a real key here — it is the bank-agnostic row — so it is a `Map`, not an object.
 */
export interface SurfaceBindingIndex {
  byControl: Map<string, Map<string | null, ControlSurfaceBinding>>
  stripControls: Map<string, StripControl>
}

export function buildBindingIndex(
  bindings: readonly ControlSurfaceBinding[],
  profile: Pick<ControlSurfaceType, 'typeKey' | 'strips'> | null | undefined,
): SurfaceBindingIndex {
  const byControl = new Map<string, Map<string | null, ControlSurfaceBinding>>()
  if (profile) {
    for (const binding of bindings) {
      if (binding.deviceTypeKey !== profile.typeKey) continue
      let byBank = byControl.get(binding.controlId)
      if (!byBank) {
        byBank = new Map()
        byControl.set(binding.controlId, byBank)
      }
      byBank.set(binding.bank, binding)
    }
  }
  return {
    byControl,
    stripControls: stripControlsByControlId(profile?.strips ?? []),
  }
}
