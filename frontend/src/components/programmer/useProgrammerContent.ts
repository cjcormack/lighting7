import { useActiveFxQuery } from '@/store/fixtureFx'
import { useProgrammerLayersQuery, useProgrammerSummaryQuery } from '@/store/programmer'
import { useHeldSceneryCount } from './ProgrammerSceneryList'
import { useHeldTempoCount } from './ProgrammerTempoList'

/** What the programmer holds, by kind, and whether it holds anything Record could write or Clear take off. */
export interface ProgrammerContent {
  entryCount: number
  programmerFxCount: number
  sceneryCount: number
  tempoCount: number
  layerCount: number
  hasContent: boolean
}

/**
 * The programmer's content, counted the one way Clear and Record both read it.
 *
 * Values are not the whole of it. A busking FX is a programmer-band FX with no value entry behind
 * it; held scenery is written by Record and dropped by Clear (scenery-programmer plan D1, D7); and a
 * Look of only scenery puts a layer on the programmer and no value; a held tempo is Record's and
 * Clear's too (fx-speed plan D8, D10). Any one of them is content.
 */
export function useProgrammerContent(projectId: number): ProgrammerContent {
  // Narrowed to the counts, so a host re-renders when one moves rather than on every FX refetch.
  const { entryCount } = useProgrammerSummaryQuery(undefined, {
    selectFromResult: ({ data }) => ({ entryCount: data?.entryCount ?? 0 }),
  })
  const { programmerFxCount } = useActiveFxQuery(undefined, {
    selectFromResult: ({ data }) => ({
      programmerFxCount: data?.filter((e) => e.programmerOwned).length ?? 0,
    }),
  })
  const sceneryCount = useHeldSceneryCount(projectId)
  const tempoCount = useHeldTempoCount(projectId)
  const { layerCount } = useProgrammerLayersQuery(undefined, {
    selectFromResult: ({ data }) => ({ layerCount: data?.length ?? 0 }),
  })
  return {
    entryCount,
    programmerFxCount,
    sceneryCount,
    tempoCount,
    layerCount,
    hasContent: entryCount > 0 || programmerFxCount > 0 || sceneryCount > 0 || tempoCount > 0 || layerCount > 0,
  }
}
