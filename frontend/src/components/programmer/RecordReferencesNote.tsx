import { Palette } from 'lucide-react'
import { referencesSentence, useLocalReferences, type RecordScope } from './recordReferences'

/**
 * The Record sheets' line about references (palette-references plan D16, board C1): how many of the
 * values are references and that they stay references, so a retune of the palette moves what is
 * recorded. Nothing while the Record would write none ([RecordScope]).
 */
export function RecordReferencesNote({ destination, scope }: { destination: 'cue' | 'look'; scope: RecordScope }) {
  const references = useLocalReferences(scope)
  if (references.total === 0) return null
  return (
    <div
      data-record-references
      className="flex items-start gap-2.5 rounded-lg border border-teal-400/45 bg-teal-400/[0.06] p-3 text-xs leading-snug"
    >
      <span className="flex shrink-0 flex-col gap-1">
        {references.palettes.map((palette) => (
          <span
            key={palette.uuid}
            className="inline-flex h-5 items-center gap-1 rounded-full bg-teal-400/15 px-2 text-[11px] font-semibold whitespace-nowrap text-teal-700 dark:text-teal-300"
          >
            <Palette aria-hidden className="size-[11px]" />
            {palette.name}
          </span>
        ))}
      </span>
      <span className="text-muted-foreground">{referencesSentence(references, destination)}</span>
    </div>
  )
}
