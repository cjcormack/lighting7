import type { ControlDescriptor, ControlSurfaceType } from "@/store/surfaces"

/** The human label for a control on a given device profile, else its id. */
export function controlLabel(
  profile: ControlSurfaceType | null | undefined,
  controlId: string,
): string {
  return profile?.controls.find((c: ControlDescriptor) => c.controlId === controlId)?.label ?? controlId
}

/** `"x-touch-compact-standard"` → `"XT"`. Used for chip abbreviations. */
export function shortDeviceLabel(typeKey: string): string {
  const parts = typeKey.split("-").filter((p) => p.length > 0)
  if (parts.length === 0) return typeKey.slice(0, 3).toUpperCase()
  return parts.slice(0, 2).map((p) => p[0]!.toUpperCase()).join("")
}
