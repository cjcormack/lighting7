import { EditorLabel } from '../editor/EditorLabel'
import { PickPropertyRow } from './PickPropertyRow'
import { PropertyRow } from './PropertyRow'
import { SHEET_FAMILY_LABELS, type SheetRowGroup } from './sheetRows'
import type { PickRowGroup, PickWrite } from './sheetPick'

/** The rows under their family labels — Intensity · Colour · Position · Beam · Controls (D3). */
export function FamilyGroups({ groups, headKey }: { groups: readonly SheetRowGroup[]; headKey: string }) {
  return (
    <>
      {groups.map((group) => (
        <section key={group.family} data-family={group.family}>
          <FamilyLabel family={group.family} />
          {group.rows.map((row) => (
            <PropertyRow key={row.id} row={row} headKey={headKey} />
          ))}
        </section>
      ))}
    </>
  )
}

/**
 * The rows over the head strip's pick (D13), under the same family labels. One picked head — or
 * one picked member, its writes carrying the group — draws that head's own rows (`PropertyRow`),
 * exactly as a single fixture's; several draw `PickPropertyRow`, and so does a group's *All*, which
 * writes one group entry even over one member.
 */
export function PickFamilyGroups({ groups, write }: { groups: readonly PickRowGroup[]; write: PickWrite }) {
  return (
    <>
      {groups.map((group) => (
        <section key={group.family} data-family={group.family}>
          <FamilyLabel family={group.family} />
          {group.rows.map((pickRow) => {
            const only = pickRow.heads.length === 1 && write.kind === 'heads' ? pickRow.heads[0] : null
            return only != null ? (
              <PropertyRow key={`${only.key}:${pickRow.row.id}`} row={only.row} headKey={only.key} headName={only.name} sourceGroup={write.kind === 'heads' ? write.sourceGroup : undefined} />
            ) : (
              <PickPropertyRow key={pickRow.row.id} pickRow={pickRow} write={write} />
            )
          })}
        </section>
      ))}
    </>
  )
}

function FamilyLabel({ family }: { family: SheetRowGroup['family'] }) {
  return (
    <div className="flex items-center gap-2 px-3 pt-3 pb-0.5">
      <EditorLabel>{SHEET_FAMILY_LABELS[family]}</EditorLabel>
      <span className="h-px flex-1 bg-border" />
    </div>
  )
}
