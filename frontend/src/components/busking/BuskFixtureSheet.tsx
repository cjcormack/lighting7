import { createContext, useContext, useEffect, useState } from 'react'
import { Sheet, SheetContent, SheetDescription, SheetTitle } from '@/components/ui/sheet'
import { useEditorForm } from '@/components/editor/EditorSurface'
import { FixtureSheet } from '@/components/fixtureSheet/FixtureSheet'
import { PhoneSheet } from '@/components/fixtureSheet/PhoneSheet'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { useGroupListQuery } from '@/store/groups'
import type { RenderTile } from '@/lib/buskRig'

/** What *Fixture sheet…* opens: a fixture's sheet, or a group's. */
export type BuskSheetTarget = { type: 'fixture'; key: string } | { type: 'group'; name: string }

/**
 * The sheet a rig tile opens (D21): a group tile its group's sheet, every other tile its fixture's —
 * a cell or a run is one fixture's heads, and the sheet's head strip is where a head is picked.
 */
export function sheetTargetOfTile(tile: RenderTile): BuskSheetTarget {
  return tile.kind === 'group' ? { type: 'group', name: tile.group.name } : { type: 'fixture', key: tile.patch.key }
}

/**
 * The rig band's door to the sheet: `RigTile` reads it to draw *Fixture sheet…* in its menu, and
 * draws no item where nothing provides it (a tile mounted outside the band).
 */
export const BuskFixtureSheetContext = createContext<((target: BuskSheetTarget) => void) | null>(null)

export function useOpenBuskFixtureSheet(): ((target: BuskSheetTarget) => void) | null {
  return useContext(BuskFixtureSheetContext)
}

/**
 * **The fixture sheet over the busk view** (fixture-fx-sheets plan D21, `BuskProgrammer.dc.html`):
 * D1's sheet — or the group sheet for a group tile — opened from a rig tile's *Fixture sheet…*, to
 * see one head's layers and clear what it holds without leaving the busk view.
 *
 * Its form is the editor kit's (`useEditorForm`). On a desk it is the pop-up's own 512px right-hand
 * Radix sheet (`host="popup"`) rather than `EditorSurface`'s narrower side form, since the sheet
 * draws its own header. On a phone it is the fixture sheet's **phone** host (`PhoneSheet`, session 6
 * — the Stage view's, so a phone has one fixture sheet): the three-height bottom sheet held upright,
 * the right-hand sheet held landscape, finger-sized rows, modal here since it opens from a menu. A
 * group's member opens its own sheet in place, as the group sheet's Members view does.
 */
export function BuskFixtureSheet({ target, onClose }: { target: BuskSheetTarget | null; onClose: () => void }) {
  const form = useEditorForm()
  const { fixtureByKey } = useFixtureLookup()
  const { data: groups } = useGroupListQuery()
  // A member opened from the group sheet's Members view, in place of the group.
  const [member, setMember] = useState<string | null>(null)
  useEffect(() => setMember(null), [target])
  // What the sheet draws: the target, held through the close — the band clears `target` at once,
  // and Radix animates the sheet out for 300ms, which would otherwise show the bare fallback title.
  const [shown, setShown] = useState<BuskSheetTarget | null>(target)
  if (target != null && target !== shown) setShown(target)

  const fixtureKey = member ?? (shown?.type === 'fixture' ? shown.key : null)
  const fixture = fixtureKey != null ? fixtureByKey.get(fixtureKey) : undefined
  const group = member == null && shown?.type === 'group' ? groups?.find((g) => g.name === shown.name) : undefined
  const host = form === 'popover' ? 'popup' : 'phone'
  const description = 'The values, effects and layers of what this rig tile holds'

  const body =
    fixture != null ? (
      <FixtureSheet key={fixture.key} fixture={fixture} host={host} titleComponent={SheetTitle} />
    ) : group != null ? (
      <FixtureSheet key={group.name} group={group} host={host} titleComponent={SheetTitle} onOpenMember={setMember} />
    ) : (
      <SheetTitle className="p-4">{shown?.type === 'group' ? shown.name : 'Fixture'}</SheetTitle>
    )

  if (form !== 'popover') {
    return (
      <PhoneSheet
        open={target != null}
        onClose={onClose}
        form={form}
        modal
        description={description}
        data-busk-fixture-sheet={form === 'bottom-sheet' ? 'bottom' : 'side'}
      >
        {body}
      </PhoneSheet>
    )
  }
  return (
    <Sheet open={target != null} onOpenChange={(open) => !open && onClose()}>
      <SheetContent side="right" data-busk-fixture-sheet="side" className="flex flex-col gap-0 p-0 sm:max-w-lg">
        <SheetDescription className="sr-only">{description}</SheetDescription>
        {body}
      </SheetContent>
    </Sheet>
  )
}
