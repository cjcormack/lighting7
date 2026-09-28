import { LookPreviewSwatches } from 'lighting-desk-ui'

const LibraryRow = ({
  name,
  meta,
  preview,
}: {
  name: string
  meta: string
  preview: readonly string[]
}) => (
  <div className="flex items-center gap-3 rounded-md border bg-card px-3 py-2">
    <div className="min-w-0 flex-1">
      <div className="truncate text-sm font-medium">{name}</div>
      <div className="text-xs text-muted-foreground">{meta}</div>
    </div>
    <LookPreviewSwatches preview={preview} />
  </div>
)

/** A colour-only Look: the swatches are the whole story. */
export const ColourLook = () => (
  <LookPreviewSwatches preview={['#FF9D4A;w120', '#FF7A1A', '#FFC078', '#1E40AF']} />
)

/** A Look spanning colour, intensity and position mixes swatches with mono chips. */
export const MixedLook = () => (
  <LookPreviewSwatches
    preview={['#FF9D4A', '255', '128,140', '#22C55E', '64', '40,215', '#3B0764;uv255', '200']}
  />
)

export const Empty = () => <LookPreviewSwatches preview={[]} />

/** As the /looks library lists them: name, families, preview. */
export const InLibraryList = () => (
  <div className="space-y-2">
    <LibraryRow name="Warm Wash" meta="Colour · 8 fixtures" preview={['#FF9D4A;w120', '#FFC078', '#FF7A1A']} />
    <LibraryRow
      name="Band walk-on"
      meta="Colour, Intensity, Position · 14 fixtures"
      preview={['#1E40AF', '255', '128,140', '#22C55E', '180', '40,215']}
    />
    <LibraryRow name="Blackout" meta="Intensity · 22 fixtures" preview={['0']} />
    <LibraryRow name="Cyc Deep Blue" meta="No rows yet" preview={[]} />
  </div>
)
