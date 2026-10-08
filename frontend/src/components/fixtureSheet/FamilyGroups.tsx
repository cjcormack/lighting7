import { EditorLabel } from '../editor/EditorLabel'
import { PropertyRow } from './PropertyRow'
import { SHEET_FAMILY_LABELS, type SheetRowGroup } from './sheetRows'

/** The rows under their family labels — Intensity · Colour · Position · Beam · Controls (D3). */
export function FamilyGroups({ groups, headKey }: { groups: readonly SheetRowGroup[]; headKey: string }) {
  return (
    <>
      {groups.map((group) => (
        <section key={group.family} data-family={group.family}>
          <div className="flex items-center gap-2 px-3 pt-3 pb-0.5">
            <EditorLabel>{SHEET_FAMILY_LABELS[group.family]}</EditorLabel>
            <span className="h-px flex-1 bg-border" />
          </div>
          {group.rows.map((row) => (
            <PropertyRow key={row.id} row={row} headKey={headKey} />
          ))}
        </section>
      ))}
    </>
  )
}
