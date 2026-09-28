import type { BindingHealth } from "@/api/surfacesApi"

/**
 * Operator-facing reason string for a dead health status. Accepts the superset
 * [BindingHealth]; cue-authoring's 4-variant `AssignmentHealth` is structurally a subset,
 * so its values pass through this function unchanged.
 *
 * Kept separate from chip / banner rendering so it can be reused in tooltips, banner
 * items, and binding rows.
 */
export function describeHealth(health: BindingHealth | undefined): string | null {
  if (!health || health.type === "ok") return null
  switch (health.type) {
    case "missingFixture":
      return `Fixture '${health.fixtureKey}' no longer exists`
    case "missingGroup":
      return `Group '${health.groupName}' no longer exists`
    case "missingProperty":
      return `Property '${health.propertyName}' is not defined on '${health.targetKey}'`
    // Three reference cases stood here — `missingPalette`, `missingPaletteEntry`, and before them
    // `paletteTypeMismatch`. All retired with the `ref:` value grammar (session 4): a row cannot
    // hold a reference, so a reference that resolves to nothing is not a state to describe. Note
    // the `default` arm below still catches them if an older server sends one.
    case "missingStack":
      return `Cue stack #${health.stackId} no longer exists`
    case "missingCue":
      return `Cue #${health.cueId} no longer exists`
    case "unknownBank":
      return `Bank '${health.bankId}' is not defined on device '${health.deviceTypeKey}'`
    case "missingSpeedMaster":
      // Unlike an effect, which degrades to master 1, a dead tempo binding does nothing —
      // silently retuning the global tempo instead would be worse than being visibly dead.
      return "The speed master this controls no longer exists"
    case "unknownProperty":
      // A selection or encoder-bank property, so there is no target to name — what makes it dead
      // is that nothing in the patch has the property at all.
      return `No fixture in this patch has a '${health.propertyName}' property`
    // The record variants. Each names its uuid rather than a resolved name, because this function
    // is pure and has no library to look one up in — the same line `describeTarget` draws.
    case "missingLook":
      return "The Look this button presses no longer exists"
    case "missingTemplate":
      return "The template this button presses no longer exists"
    case "missingPad":
      return "The busk pad this button presses is no longer on any page"
    // A **busk** bank, not the device profile's `unknownBank` above — the two share only the word,
    // and a place binding whose bank went with a layout write is dead in exactly the way a missing
    // pad is. It had no arm here until the hand's bindings made it reachable, so it fell through to
    // the generic line below.
    case "missingBank":
      return "The busk bank this button places into no longer exists"
    case "missingPage":
      return "The busk page this button shows no longer exists"
    // Transient, unlike every arm above it: a window of that name announcing clears it, and the
    // desk re-evaluates on every registry change so the row goes green without a rebind.
    case "missingWindow":
      return `“${health.windowName}” is not signed in on any screen`
    case "lookNeedsSelection":
      // Not missing — still there, and the fix is different: give the effect its targets, or bind
      // the button to something a press can land on by itself.
      return "This Look has a deferred effect, so it needs a selection — a button has none to give"
    case "unknownTarget":
      // The row the backend's tolerant decode kept rather than failing the whole project on. It
      // is dead here and rebindable; on the build that wrote it, it works.
      return `This build does not understand '${health.targetType}' bindings — rebind it`
    default:
      // Not dead code: the backend's health ADT is versioned independently of this client, so
      // a newer server can send a variant TypeScript here has never heard of. Falling through
      // to a generic message keeps an unknown variant *visible* as a problem, which is the
      // point of the diagnostic — returning null would render it as healthy.
      return "This reference no longer resolves"
  }
}
