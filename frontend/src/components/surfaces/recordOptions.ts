import { useMemo } from "react"
import { useLookListQuery } from "@/store/looks"
import { useTemplateListQuery } from "@/store/templates"
import { useBuskPagesQuery } from "@/store/busk"
import { allBanks, forEachPad } from "@/lib/buskLayout"
import { padFaceOf } from "@/components/busking/padFace"
import { describeTarget } from "./targetUtils"
import type { BindingTarget } from "@/api/surfacesApi"

/**
 * The library a **record** binding can name, as `{uuid, label}` lists, plus the one function that
 * turns such a binding back into a name.
 *
 * Two consumers, and they are the reason this is a module rather than two local `useMemo`s: the
 * picker offers the list and the inspector resolves the choice. `describeTarget` deliberately will
 * not do the second — it is pure and has no library to ask, the same line it draws for
 * `speedMasterBpm` — so without one shared owner the resolution would exist twice and the two
 * would name the same uuid differently.
 *
 * Everything is keyed by **uuid**, because that is what a binding carries: an int id does not
 * survive a clone, which is what `FU-SYNC-BINDING-PAYLOAD-UUIDS` records.
 */
export interface RecordOption {
  uuid: string
  label: string
  /** A second line for a list item — a Look's family summary, a pad's page and bank. */
  detail: string | null
  /**
   * Offered for context but not selectable — a Look with a deferred effect, whose `applyLook`
   * press has no targets of its own. `SurfaceLibrary`'s drag chip already excludes one of these
   * outright, by the same rule, to avoid the write boundary's `BINDING_LOOK_NEEDS_SELECTION`; the
   * picker shows it (so "why isn't my Look here?" has an answer) but must not let it be chosen and
   * saved, which would hit the same refusal through the other door.
   */
  disabled?: boolean
}

export interface RecordBindingOptions {
  looks: RecordOption[]
  templates: RecordOption[]
  /** Every pad on every page, flat: a pad is addressed by uuid and its page is only context. */
  pads: RecordOption[]
  /**
   * Every **bank** on every page, flat — what a `handPlaceInBank` binding names.
   *
   * Includes empty banks, unlike [pads], which walks pads: an empty bank is exactly the one an
   * operator is most likely to bind a place button to.
   */
  banks: RecordOption[]
  pages: RecordOption[]
}

/** No library loaded yet, or nothing to resolve — a stable empty answer callers can default to. */
export const EMPTY_RECORD_OPTIONS: RecordBindingOptions = {
  looks: [],
  templates: [],
  pads: [],
  banks: [],
  pages: [],
}
const EMPTY = EMPTY_RECORD_OPTIONS

export function useRecordBindingOptions(projectId: number): RecordBindingOptions {
  const { data: looks } = useLookListQuery({ projectId })
  const { data: templates } = useTemplateListQuery({ projectId })
  const { data: pages } = useBuskPagesQuery(projectId)

  return useMemo(() => {
    if (looks == null && templates == null && pages == null) return EMPTY
    return {
      looks: (looks ?? []).map((look) => ({
        uuid: look.uuid,
        label: look.name,
        // What an `applyLook` binding will actually do on a button, said where the choice is made:
        // a deferred effect has no own targets, so the write boundary refuses it by name.
        detail: look.hasDeferredEffects ? "needs a selection — cannot go on a button" : null,
        disabled: look.hasDeferredEffects,
      })),
      templates: (templates ?? []).map((template) => ({
        uuid: template.uuid,
        label: template.name,
        detail: template.family?.toLowerCase() ?? null,
      })),
      pads: (pages ?? []).flatMap((page) => {
        const options: RecordOption[] = []
        forEachPad(page, (pad, bank) => {
          // A pad this client minted and has not saved has no uuid to bind to yet. It cannot
          // occur in a fetched page, but the type says it can and a `""` uuid would save.
          if (pad.uuid == null) return
          options.push({
            uuid: pad.uuid,
            label: padFaceOf(pad).name,
            detail: `${page.name} · ${bank.name}`,
          })
        })
        return options
      }),
      banks: (pages ?? []).flatMap((page) =>
        allBanks(page)
          // Same reason as a pad's: an unsaved bank has no uuid, and `""` would save.
          .filter((bank) => bank.uuid != null)
          .map((bank) => ({
            uuid: bank.uuid!,
            label: bank.name,
            detail: `${page.name} · ${bank.pads.length} ${bank.pads.length === 1 ? 'pad' : 'pads'}`,
          })),
      ),
      pages: (pages ?? []).map((page) => ({ uuid: page.uuid, label: page.name, detail: null })),
    }
  }, [looks, templates, pages])
}

/**
 * The name behind a record binding's uuid, or null when this target names no record.
 *
 * Answers `"…"` for a uuid the library no longer holds rather than the raw uuid: health already
 * says the binding is dead and names it, and repeating a uuid where a name goes reads as a name.
 */
export function recordTargetName(
  target: BindingTarget,
  options: RecordBindingOptions,
): string | null {
  const find = (list: RecordOption[], uuid: string) =>
    list.find((o) => o.uuid === uuid)?.label ?? "(deleted)"
  switch (target.type) {
    case "applyLook":
      return find(options.looks, target.lookUuid)
    case "pressTemplate":
      return find(options.templates, target.templateUuid)
    case "pressPad":
      return find(options.pads, target.padUuid)
    // The hand's two uuid-bearing targets. `handDrop` names no record and falls to `null` below,
    // exactly as `blackout` does.
    case "pickUpPad":
      return find(options.pads, target.padUuid)
    case "handPlaceInBank":
      return find(options.banks, target.bankUuid)
    case "buskPageSet":
      return find(options.pages, target.pageUuid)
    default:
      return null
  }
}

/**
 * A binding's target, with a record's **name** where `describeTarget` can only give a uuid.
 *
 * The one owner of this resolution, for the reason `recordTargetName` above is: every surface that
 * shows a binding to an operator — the picture (`SurfacePanel`), the table (`BindingMatrix`), and
 * the inspector's own heading and cards (`SurfaceInspector`) — needs the same answer for the same
 * uuid, and a second local copy is exactly how two of them would show different names for one
 * binding. `default` falls back to the bare name rather than a hardcoded "Show page …" — the
 * fourth record kind here is the only one `recordTargetName` doesn't special-case a verb for, and a
 * fifth kind added to `recordTargetName` later should read as its name rather than mislabelled as a
 * page.
 */
export function describeBindingTarget(target: BindingTarget, records: RecordBindingOptions): string {
  const name = recordTargetName(target, records)
  if (name == null) return describeTarget(target)
  switch (target.type) {
    case "applyLook":
      return `Apply ${name}`
    case "pressTemplate":
      return `Press ${name}`
    case "pressPad":
      return `Press pad ${name}`
    case "pickUpPad":
      return `Pick up ${name}`
    case "handPlaceInBank":
      return `Place in ${name}`
    case "buskPageSet":
      return `Show page ${name}`
    default:
      return name
  }
}
