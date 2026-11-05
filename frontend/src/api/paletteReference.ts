/**
 * A value's palette reference, as the desk answers it beside the resolved value (lighting7
 * palette-references plan D6). `unresolved` says why the palette no longer resolves there; the value
 * beside it is then the last one it did. No client resolves one.
 */
export interface PaletteReference {
  paletteId: number
  paletteUuid: string
  name: string
  unresolved?: string
}
