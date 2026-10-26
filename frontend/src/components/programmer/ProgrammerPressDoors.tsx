import { describeLookContents, paletteSwatch } from '@/components/busking/padFace'
import { lookIsApplied } from '@/components/busking/lookPresence'
import { pressPresence } from '@/components/press/pressPresence'
import { usePress, useWindowPaletteFit, useWindowPressPair, type PressPair } from '@/hooks/usePress'
import { formatFamilyList } from '@/lib/attributeFamily'
import { paletteFits, splitByMask } from '@/lib/paletteOffer'
import { useFxLibraryQuery } from '@/store/fixtureFx'
import { useLookListQuery } from '@/store/looks'
import { usePaletteListQuery } from '@/store/palettes'
import { useProgrammerAppliedQuery } from '@/store/programmer'
import { countOf, useCommandDoor, type CommandPageItem } from './commandDoors'
import type { PaletteSummary } from '@/api/palettesApi'
import type { FxPresence } from '@/components/busking/buskingTypes'

/** A Look lands whole: its press names no targets and no mask (absolute-looks plan D1). */
const WHOLE: PressPair = { targets: [], families: null }

/** A pad's ring, in words. */
const PRESENCE_HINT: Record<FxPresence, string | undefined> = { all: 'on', some: 'on some', none: undefined }

/**
 * ⌘K's *Add a Look…* on the programmer (programmer-toolbar plan D13): a sub-page of the Looks, each
 * pressed whole as row A's + Look presses it — a tap a layer, ⌥ its values. Mounted by the tables'
 * frame; draws nothing.
 */
export function ProgrammerLookDoor({ projectId }: { projectId: number }) {
  const { press, into } = usePress(projectId, WHOLE)
  const { data: looks } = useLookListQuery({ projectId })
  const { data: applied } = useProgrammerAppliedQuery()
  useCommandDoor('look', () => [
    {
      id: 'programmer.addLook',
      group: 'programmer',
      label: 'Add a Look…',
      hint: 'lands whole',
      keywords: ['look', 'press', 'layer'],
      // Looks don't nest: with a Look layer focused every press here would be refused.
      canAct: into.kind !== 'look',
      page: () => ({
        title: 'Add a Look',
        footer: '⏎ press as a layer · ⌥⏎ as values',
        empty: 'No Looks yet. Record one from the programmer.',
        sections: [
          {
            heading: 'Looks',
            note: 'a Look lands whole: no selection, no mask',
            items: (looks ?? []).map<CommandPageItem>((look) => ({
              id: `look-${look.id}`,
              label: look.name,
              hint: [describeLookContents(look), applied != null && lookIsApplied(applied, look.id) ? 'on' : null]
                .filter(Boolean)
                .join(' · '),
              press: (asValues) => void press({ kind: 'look', look }, asValues ? 'asValues' : 'press'),
            })),
          },
        ],
      }),
    },
  ])
  return null
}

/**
 * ⌘K's *Apply a palette…* on the programmer (programmer-toolbar plan D13): a sub-page of the
 * palettes that fit the window's selection — the picker's offer (`ApplyPicker`, *+ Look or
 * palette*), the ones its mask leaves out dimmed with the reason — each pressed as the picker
 * presses it, through `usePress` on the window's pair. Mounted by the tables' frame; draws nothing.
 */
export function ProgrammerPaletteDoor({ projectId }: { projectId: number }) {
  const pair = useWindowPressPair()
  const fit = useWindowPaletteFit()
  const { press, into } = usePress(projectId, pair)
  const { data: palettes } = usePaletteListQuery({ projectId })
  const { data: fxLibrary } = useFxLibraryQuery()
  const { data: applied } = useProgrammerAppliedQuery()

  useCommandDoor('palette', () => {
    const heads = countOf(pair.targets.length, 'head')
    const mask = pair.families != null && pair.families.length > 0 ? pair.families : null
    const item = (palette: PaletteSummary, dimmed: string | null): CommandPageItem => ({
      id: `palette-${palette.id}`,
      label: palette.name,
      hint: PRESENCE_HINT[pressPresence(into, null, applied, pair.targets, 'PALETTE', palette.id)],
      swatch: paletteSwatch(palette),
      dimmed,
      press: (asValues) => void press({ kind: 'palette', palette }, asValues ? 'asValues' : 'press'),
    })
    return [
      {
        id: 'selection.applyPalette',
        group: 'selection',
        label: 'Apply a palette…',
        hint: into.kind === 'look' ? `${heads} · into ${into.lookName}` : mask != null ? `${heads} · ${formatFamilyList(mask, ' · ')}` : heads,
        keywords: ['palette', 'press', 'colour', 'position', 'beam', 'intensity'],
        canAct: pair.targets.length > 0,
        page: () => {
          const { inside, outside } = splitByMask(
            (palettes ?? []).filter((p) => paletteFits(p, fit, { library: fxLibrary, palettes })),
            mask,
          )
          return {
            title: 'Apply a palette',
            footer: '⏎ press as a layer · ⌥⏎ as values',
            empty: 'No palette fits what is selected.',
            sections: [
              { heading: 'Fits the selection', note: mask != null ? `${heads} · ${formatFamilyList(mask, ' · ')}` : heads, items: inside.map((p) => item(p, null)) },
              {
                heading: mask != null ? `Outside the ${formatFamilyList(mask, ' · ')} mask` : 'Outside the mask',
                note: 'refused under this mask — change the mask first',
                items: outside.map(({ palette, reason }) => item(palette, reason)),
              },
            ],
          }
        },
      },
    ]
  })
  return null
}
