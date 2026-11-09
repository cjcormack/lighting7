import type { ControlDescriptor, ControlSurfaceType } from "@/store/surfaces"

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
