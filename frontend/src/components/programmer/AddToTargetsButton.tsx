import { Crosshair } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { programmerPatchLayer } from '@/store/programmer'
import { useLookRowStore } from './LookRowStore'
import { useFocusedTemplateLayer } from './FocusedTemplateLayer'
import type { CueTarget } from '@/api/cuesApi'

/**
 * Bring a row inside the focused layer's targets.
 *
 * This is the *only* way a layer widens, and that is deliberate. In layer scope an untargeted row's
 * cells are shown but not editable, so a marquee dragged across the grid cannot quietly extend a
 * layer to the whole rig — the thing an operator would never be able to attribute to the drag they
 * just made. Widening is a press, on the row, next to Locate and Details.
 *
 * Reads only context, so it costs a mounted row nothing: the layer's target list rides on values the
 * grid is already holding rather than a per-row query subscription. **Both** layer contexts — a
 * focused Look layer's `LookRowStore` and a focused template layer's `FocusedTemplateLayer`, which
 * are mutually exclusive by construction. The template half is not optional: since a template
 * layer's grid became a read, an untargeted row there is painted dashed and non-editable, and
 * without this button that tone would name a state with no way out of it.
 *
 * No optimistic update, matching `ProgrammerLookStack` — the programmer's layer state is a
 * broadcast, and the row un-dims when it lands. Restating it here would be a second opinion on a
 * stack this client does not own.
 */
export function AddToTargetsButton({
  target,
  fixtureKeys,
  name,
}: {
  /** What to append — a group row appends the group, which is what the operator means. */
  target: CueTarget
  /** Every fixture this row covers, so a group already fully targeted offers nothing. */
  fixtureKeys: readonly string[]
  name: string
}) {
  const store = useLookRowStore()
  const focusedTemplate = useFocusedTemplateLayer()
  const layer = store ?? focusedTemplate
  // `targetedKeys === null` is "the source's own targets" — for a Look, every bound row lands where
  // it names, so there is nothing to widen. Reading it as "no targets" would put this button on
  // every row.
  if (!layer || layer.targetedKeys === null) return null
  const targeted = layer.targetedKeys
  if (fixtureKeys.length === 0 || fixtureKeys.every((key) => targeted.has(key))) return null

  const label = `Add ${name} to this layer's targets`
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          variant="outline"
          size="icon"
          className="size-8"
          aria-label={label}
          onClick={() =>
            programmerPatchLayer(layer.layerId, { targets: [...layer.targets, target] })
          }
        >
          <Crosshair className="size-3.5" />
        </Button>
      </TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  )
}
