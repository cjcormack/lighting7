// Shared palette for fixture-body meshes. Pull tonal changes through here so a
// rig-wide nudge doesn't mean editing every per-kind file.

export const BODY_LENS_COLOR = '#fff8d5'

export function housingColor(active: boolean): string {
  return active ? '#cfd6df' : '#7a8390'
}

export function yokeColor(active: boolean): string {
  return active ? '#9aa5b4' : '#5a6470'
}
