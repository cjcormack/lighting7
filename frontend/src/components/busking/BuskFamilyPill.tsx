import { SelectionMaskPill } from '@/components/programmer/SelectionMaskPill'
import type { AttributeFamily } from '@/lib/attributeFamily'

/**
 * The busk selection's family pill — one component for the rig band's row, the rig strip and the
 * pad row in Pads, so the three cannot draw the mask two ways. It is the mask's **picker**
 * (apply-model plan D3, `SelectionMaskPill`), drawn whenever something is selected or a mask is
 * set, `All` while unmasked: a picker that appeared only once a mask existed could never set the
 * first one, since the rig has no marquee. With neither, there is nothing to say and nothing is
 * drawn. [marker] is the host's test handle (`data-rig-family`, `data-pad-family`).
 */
export function BuskFamilyPill({
  families,
  onChange,
  selected,
  marker,
}: {
  families: AttributeFamily[] | null
  /** The mask's write: `useBuskingSelection`'s `setFamilies`, the targets kept. */
  onChange: (families: AttributeFamily[] | null) => void
  /** Whether anything is selected — the picker's other reason to be drawn. */
  selected: boolean
  marker?: string
}) {
  const masked = families != null && families.length > 0
  if (!selected && !masked) return null
  return <SelectionMaskPill families={families} onChange={onChange} tone="busk" className="shrink-0" marker={marker} />
}
