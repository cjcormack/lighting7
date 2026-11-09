import { useMemo } from "react"
import { useLookListQuery } from "@/store/looks"
import { usePaletteListQuery } from "@/store/palettes"
import { useBuskPagesQuery } from "@/store/busk"
import { useProjectCueStackListQuery } from "@/store/cueStacks"
import { useProjectCueListQuery } from "@/store/cues"
import { useSpeedMasterListQuery } from "@/store/speedMasters"
import { isCueRow } from "@/api/cueStacksApi"
import { allBanks, forEachPad } from "@/lib/buskLayout"
import { padFaceOf } from "@/components/busking/padFace"
import type { RecordKind } from "@/api/surfacesApi"

/**
 * The records a binding's *On* can name, by kind, as `{uuid, label}` lists for the form to offer.
 * What a bound record is *called* is the desk's (the map's `subjectLabel`); this is only the menu.
 */
export interface RecordOption {
  uuid: string
  label: string
  /** A second line for a list item — a pad's page and bank, a cue's stack. */
  detail: string | null
}

export type RecordOptions = Readonly<Record<RecordKind, readonly RecordOption[]>>

export const EMPTY_RECORD_OPTIONS: RecordOptions = {
  STACK: [],
  ENTRY: [],
  CUE: [],
  LOOK: [],
  PALETTE: [],
  PAD: [],
  BANK: [],
  PAGE: [],
  MASTER: [],
}

export function useRecordOptions(projectId: number): RecordOptions {
  const { data: looks } = useLookListQuery({ projectId })
  const { data: palettes } = usePaletteListQuery({ projectId })
  const { data: pages } = useBuskPagesQuery(projectId)
  const { data: stacks } = useProjectCueStackListQuery(projectId)
  const { data: cues } = useProjectCueListQuery(projectId)
  const { data: masters } = useSpeedMasterListQuery({ projectId })

  return useMemo(() => {
    const runnable = (stacks ?? []).filter((stack) => stack.type !== "SEPARATOR" && stack.uuid != null)
    return {
      STACK: runnable.map((stack) => ({ uuid: stack.uuid!, label: stack.name, detail: null })),
      ENTRY: runnable.flatMap((stack) =>
        stack.cues
          .filter(isCueRow)
          .filter((entry) => entry.uuid != null)
          .map((entry) => ({
            uuid: entry.uuid!,
            label: entry.cueNumber != null ? `Q${entry.cueNumber} · ${entry.name}` : entry.name,
            detail: stack.name,
          })),
      ),
      CUE: (cues ?? []).filter((cue) => cue.uuid != null).map((cue) => ({ uuid: cue.uuid!, label: cue.name, detail: null })),
      LOOK: (looks ?? []).map((look) => ({ uuid: look.uuid, label: look.name, detail: null })),
      PALETTE: (palettes ?? []).map((palette) => ({
        uuid: palette.uuid,
        label: palette.name,
        detail: palette.family?.toLowerCase() ?? null,
      })),
      PAD: (pages ?? []).flatMap((page) => {
        const options: RecordOption[] = []
        forEachPad(page, (pad, bank) => {
          // A pad this client minted and has not saved has no uuid to bind to yet.
          if (pad.uuid == null) return
          options.push({ uuid: pad.uuid, label: padFaceOf(pad).name, detail: `${page.name} · ${bank.name}` })
        })
        return options
      }),
      BANK: (pages ?? []).flatMap((page) =>
        allBanks(page)
          .filter((bank) => bank.uuid != null)
          .map((bank) => ({ uuid: bank.uuid!, label: bank.name, detail: page.name })),
      ),
      PAGE: (pages ?? []).map((page) => ({ uuid: page.uuid, label: page.name, detail: null })),
      MASTER: (masters ?? []).map((master) => ({
        uuid: master.uuid,
        label: `M${master.masterIndex} · ${master.name}`,
        detail: null,
      })),
    }
  }, [looks, palettes, pages, stacks, cues, masters])
}
