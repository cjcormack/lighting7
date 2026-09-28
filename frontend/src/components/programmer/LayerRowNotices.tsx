import { Link } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { useProgrammerLayersQuery } from '@/store/programmer'
import { useLookRowStore } from './LookRowStore'
import { useFocusedTemplateLayer } from './FocusedTemplateLayer'
import { focusedLayerId, useProgrammerScope } from './ProgrammerScope'

/**
 * What the focused layer holds that a per-fixture grid cannot draw.
 *
 * Three cases, and all three are *stated* rather than silently missing — a layer that looks empty
 * because the grid cannot render it is the worst of the available answers.
 *
 * **A focused *value* template layer shows no rows at all**, and it is the case an operator is most
 * likely to meet. A template is one family of intents, resolved per head at cook; projecting its
 * generic row onto every targeted row would silently convert it to a per-fixture one on the first
 * edit — a change to what the template *is*, made by someone who was only adjusting a value. That is
 * the same argument the deferred-row case below already made, and it is why a template is edited in
 * its own family-native editor instead. The notice links there.
 *
 * **A focused *effect* template layer shows the live value instead**, ringed, with the wave on the
 * cells the effect drives. An effect is one rule for every head rather than a per-head resolution,
 * so there is nothing per-fixture to hide — but there *is* something worth watching, which is what
 * it is currently producing. Neither is typeable: both go through the template.
 *
 * **Deferred rows** on a *Look* are the legacy of that split: a Look row cannot be deferred any more,
 * so this arm only fires for a row an older database left behind. Kept because such a row still
 * *applies*, and an operator looking at a grid that does not show it deserves to know.
 *
 * **Element rows** address one element of a multi-element fixture, and compose nowhere:
 * `CueComposer.applyLayer` drops them, so they contribute nothing to a cue that layers this Look
 * (`FU-LOOK-ELEMENT-ROWS`). Saying so is the honest position until that follow-up lands.
 */
export function LayerRowNotices({ projectId }: { projectId: number }) {
  const scope = useProgrammerScope()
  const store = useLookRowStore()
  const focusedTemplate = useFocusedTemplateLayer()
  const { data: layers } = useProgrammerLayersQuery()
  const layerId = focusedLayerId(scope)
  const layer = layerId == null ? undefined : layers?.find((l) => l.layerId === layerId)

  if (scope?.kind !== 'layer') return null

  // A template layer has no `LookRowStore` at all — the store only engages for a LOOK source — so
  // this arm has to come before the store check below.
  if (layer?.source.kind === 'TEMPLATE') {
    return (
      <div className="flex flex-wrap items-center gap-2 rounded-md bg-muted/40 px-2 py-1.5 text-xs">
        <Badge variant="outline" className="shrink-0 px-1.5 py-0 text-[10px]">
          template
        </Badge>
        <span className="text-muted-foreground">
          {focusedTemplate?.kind === 'effect'
            ? `“${layer.source.name}” runs one effect for the whole family — the grid shows the live value under it, and the wave marks the cells it drives.`
            : `“${layer.source.name}” holds one value for the whole family, resolved per head — so there is nothing to show per fixture.`}
        </span>
        {/* `projectId` from the host, not from the row store: the store does not engage for a
            template layer at all, so reading it here would build `/projects//templates`. */}
        <Link
          to={`/projects/${projectId}/templates`}
          className="underline underline-offset-2 hover:text-foreground"
        >
          Edit template
        </Link>
      </div>
    )
  }

  if (!store) return null

  const { deferredRows, elementRows, serverRows, loaded } = store
  const boundRowCount = serverRows.size
  const fullyDeferred = loaded && boundRowCount === 0 && deferredRows.length > 0

  if (deferredRows.length === 0 && elementRows.length === 0) return null

  return (
    <div className="flex flex-wrap items-center gap-2 rounded-md bg-muted/40 px-2 py-1.5 text-xs">
      {deferredRows.length > 0 && (
        <>
          <Badge variant="outline" className="shrink-0 px-1.5 py-0 text-[10px]">
            {deferredRows.length} deferred
          </Badge>
          <span className="text-muted-foreground">
            {fullyDeferred
              ? 'Every row in this look takes its target from the layer, so there is nothing to show per fixture. A value you point at a selection is a template now — re-create it there.'
              : 'Rows that take their target from the layer. They apply, but belong to no one head; a value like this belongs in a template.'}
          </span>
        </>
      )}
      {elementRows.length > 0 && (
        <>
          <Badge variant="outline" className="shrink-0 px-1.5 py-0 text-[10px]">
            {elementRows.length} per-element
          </Badge>
          <span className="text-muted-foreground">
            Rows addressing a single element. They are stored, but nothing composes them yet.
          </span>
        </>
      )}
    </div>
  )
}
