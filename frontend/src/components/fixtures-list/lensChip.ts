/** The FX lens chip's face — shape and its two tones — shared by the cell and the key that teaches it. */
export const LENS_CHIP_FACE =
  'inline-flex h-3 items-center gap-0.5 rounded-sm px-0.5 text-[9px] leading-none font-bold whitespace-nowrap tabular-nums'

export function lensChipTone(held: boolean): string {
  return held ? 'bg-background text-muted-foreground line-through ring-1 ring-violet-500/60 ring-inset' : 'bg-violet-500 text-white'
}
