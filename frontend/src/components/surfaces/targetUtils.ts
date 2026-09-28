import type {
  BindingTarget,
  ControlDescriptor,
  ControlSurfaceType,
} from "@/store/surfaces"
import { STRIP_FADER_PROPERTY } from "@/lib/surfaceResolve"
import { axisSuffix } from "@/lib/colourAxis"
import { SUBSELECT_MODE_LABELS } from "@/lib/cellsSubSelection"
import type { BuskFocus } from "@/lib/buskWindow"

/** Flash targets wrap an inner continuous target; return the meaningful target. */
export function effectiveTarget(target: BindingTarget): BindingTarget {
  return target.type === "flash" ? target.target : target
}

export type BindingTargetMatcher =
  | { type: "fixtureProperty"; fixtureKey: string; propertyName: string }
  | { type: "groupProperty"; groupName: string; propertyName: string }
  | { type: "fireCue"; cueId: number }
  | { type: "cueStack"; stackId: number }

/**
 * Does this binding drive what the caller is asking about?
 *
 * The colour axis is deliberately **not** compared: a fine-hue or saturation binding still drives
 * that property, and the fixtures-page badge this feeds asks "is this property bound", not how.
 *
 * A **strip** row answers yes for its target's `dimmer` and nothing else. The strip's fader and
 * flash button really do drive the dimmer, always; its encoder drives whichever property the
 * device's encoder bank currently names, which this pure matcher cannot see and which would in any
 * case make a badge on the fixtures page claim a binding that changes under the operator without
 * the page moving. Coverage that shifts with a bank belongs on the surface panel, which redraws
 * when the bank does.
 */
export function matchesBindingTarget(
  target: BindingTarget,
  match: BindingTargetMatcher,
): boolean {
  const eff = effectiveTarget(target)
  switch (match.type) {
    case "fixtureProperty":
      return (
        (eff.type === "fixtureProperty" &&
          eff.fixtureKey === match.fixtureKey &&
          eff.propertyName === match.propertyName) ||
        (eff.type === "strip" &&
          eff.target.type === "fixture" &&
          eff.target.key === match.fixtureKey &&
          match.propertyName === STRIP_FADER_PROPERTY)
      )
    case "groupProperty":
      return (
        (eff.type === "groupProperty" &&
          eff.groupName === match.groupName &&
          eff.propertyName === match.propertyName) ||
        (eff.type === "strip" &&
          eff.target.type === "group" &&
          eff.target.key === match.groupName &&
          match.propertyName === STRIP_FADER_PROPERTY)
      )
    case "fireCue":
      return eff.type === "fireCue" && eff.cueId === match.cueId
    case "cueStack":
      return (
        (eff.type === "cueStackGo" ||
          eff.type === "cueStackBack" ||
          eff.type === "cueStackPause") &&
        eff.stackId === match.stackId
      )
  }
}

export function describeTarget(target: BindingTarget): string {
  switch (target.type) {
    // A colour axis other than hue is spelled out; hue is the default and reads as it always has.
    case "fixtureProperty":
      return `${target.fixtureKey}.${target.propertyName}${axisSuffix(target.colourAxis)}`
    case "groupProperty":
      return `${target.groupName}.${target.propertyName}${axisSuffix(target.colourAxis)}`
    case "cueStackGo":
      return `Go · stack ${target.stackId}`
    case "cueStackBack":
      return `Back · stack ${target.stackId}`
    case "cueStackPause":
      return `Pause · stack ${target.stackId}`
    case "fireCue":
      return `Fire cue #${target.cueId}`
    case "flash":
      return `Flash ${describeTarget(target.target)}`
    case "blackout":
      return "Blackout"
    case "grandMasterToggle":
      return "Grand Master"
    case "setBank":
      return `Bank ${target.bank} (${target.deviceTypeKey})`
    // The uuid is not resolved to a name here: this is a pure function with no access to the
    // live bank, and a call site that wants "M2 · Chorus" can resolve it via
    // useSpeedMasterDisplay. `null` is master 1 by the same convention as everywhere else.
    case "speedMasterBpm":
      return `Speed master BPM · ${target.minBpm}–${target.maxBpm}${
        target.masterUuid == null ? " · M1" : ""
      }`
    case "speedMasterTap":
      return `Speed master tap${target.masterUuid == null ? " · M1" : ""}`
    // The selection-relative arms name no target of their own, so `Sel` is doing real work: it is
    // the whole difference between "this control writes colour on the movers" and "this control
    // writes colour on whatever is selected".
    case "selectionProperty":
      return `Sel · ${target.propertyName}${axisSuffix(target.colourAxis)}`
    case "selectTarget":
      return `${target.mode === "replace" ? "Select only" : "Select"} ${target.target.key}`
    case "clearSelection":
      return "Clear selection"
    case "locateSelection":
      return "Locate selection"
    // A strip row is never dispatched as it stands, so this is what the *row* is rather than what
    // any one control does: for a control's own label the panel resolves through
    // `lib/surfaceResolve.ts` first and describes the derived target.
    case "strip":
      return `Strip · ${target.target.key}`
    case "encoderBankSet":
      return `Encoder bank · ${target.propertyName}${axisSuffix(target.colourAxis)}`
    // The record variants name a **uuid**, and this function deliberately does not resolve it — the
    // same rule `speedMasterBpm` above follows, for the same reason: this is pure and has no
    // library to ask. A caller that wants "Apply Warm Wash" resolves it and says so itself, which
    // is what the inspector's binding card does. The short uuid keeps a badge distinguishable
    // between two bindings of the same kind without pretending to be a name.
    case "applyLook":
      return `Apply Look ${shortUuid(target.lookUuid)}`
    case "pressTemplate":
      return `Press template ${shortUuid(target.templateUuid)}`
    case "pressPad":
      return `Press pad ${shortUuid(target.padUuid)}`
    // The hand's three (multi-screen plan §3.5). Same uuid rule as the record variants above.
    case "pickUpPad":
      return `Pick up pad ${shortUuid(target.padUuid)}`
    case "handPlaceInBank":
      return `Place in bank ${shortUuid(target.bankUuid)}`
    case "handDrop":
      return "Let go"
    case "buskPageNext":
      return "Busk · next page"
    case "buskPagePrev":
      return "Busk · previous page"
    case "buskPageSet":
      return `Busk page ${shortUuid(target.pageUuid)}`
    // The window-addressed pair name the window as the binding does — by registry name, which is
    // the name and needs no resolving — and the focus it sets; the sub-selection three say the
    // mode in the Cells chip's own words, so a button and the chip read as one rule.
    case "buskFocusSet":
      return `Busk focus · ${FOCUS_LABELS[target.focus]} · ${target.windowName}`
    case "buskSheetToggle":
      return `Busk sheet · ${target.windowName}`
    case "selectionNext":
      return "Selection · next"
    case "selectionPrev":
      return "Selection · prev"
    case "selectionCells":
      return `Cells · ${SUBSELECT_MODE_LABELS[target.mode]}`
    // A row this build cannot decode, kept by the tolerant decode so it can be rebound rather than
    // silently dropped. Naming the discriminator is the only useful thing to say about it.
    case "unknown":
      return `Unknown target (${target.targetType})`
    // A runtime fallback that **keeps compile-time exhaustiveness**, which a bare `default` would
    // have thrown away. Both halves are load-bearing and they pull in opposite directions:
    //
    // - Without any arm the function returned `undefined` (`noImplicitReturns` is off) and the
    //   binding drew a **blank** label. `case "unknown"` does not cover this: that variant is the
    //   *backend's* tolerant decode of a newer project, normalised server-side — it says nothing
    //   about a *frontend* older than the desk it is talking to, whose genuinely-new discriminator
    //   is deserialised with no runtime check and matches no case at all.
    // - But a bare `default` also silences the compiler for **first-party** additions, so a new
    //   `BindingTarget` variant would render "Unsupported target" for ever with no build error.
    //
    // Assigning to `never` gets both: it compiles while every case is present, and fails
    // (`TS2322`) the moment one is missing, while the return still answers at runtime.
    default: {
      const unhandled: never = target
      void unhandled
      return "Unsupported target"
    }
  }
}

/** The three focus values as the busk view's segmented control spells them. */
export const FOCUS_LABELS: Record<BuskFocus, string> = {
  split: "Split",
  pads: "Pads",
  rig: "Rig",
}

/**
 * The first group of a uuid — enough to tell two bindings apart in a badge, short enough not to
 * read as a name. Anything that can resolve the record shows the name instead.
 */
function shortUuid(uuid: string): string {
  return uuid.split("-")[0] ?? uuid
}

/**
 * The human label for a control on a given device profile.
 *
 * A **strip id** is looked up second, because a strip binding's `controlId` holds one and strips
 * are not in `controls` — without this arm every strip-bound badge reads its raw `strip-1`. It is
 * named by its own fader ("Strip · Fader 1", "Strip · Master") rather than by parsing the id:
 * the fader's label is real profile data, and it names the thing the operator can put a hand on.
 */
export function controlLabel(
  profile: ControlSurfaceType | null | undefined,
  controlId: string,
): string {
  const control = profile?.controls.find((c: ControlDescriptor) => c.controlId === controlId)
  if (control) return control.label

  const strip = profile?.strips?.find((s) => s.id === controlId)
  if (strip) {
    const fader = profile?.controls.find((c: ControlDescriptor) => c.controlId === strip.fader)
    return fader ? `Strip · ${fader.label}` : `Strip ${strip.id}`
  }

  return controlId
}

/** `"x-touch-compact-standard"` → `"XT"`. Used for chip abbreviations. */
export function shortDeviceLabel(typeKey: string): string {
  const parts = typeKey.split("-").filter((p) => p.length > 0)
  if (parts.length === 0) return typeKey.slice(0, 3).toUpperCase()
  return parts.slice(0, 2).map((p) => p[0]!.toUpperCase()).join("")
}
