export interface BeamPreset {
  name: string
  deg: number
}

export const BEAM_PRESETS: readonly BeamPreset[] = [
  { name: 'Spot', deg: 14 },
  { name: 'Narrow', deg: 26 },
  { name: 'Medium', deg: 36 },
  { name: 'Wide', deg: 50 },
  { name: 'Flood', deg: 70 },
]
