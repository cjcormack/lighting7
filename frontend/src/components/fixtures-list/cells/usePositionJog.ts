import { useMemo } from 'react'
import { useCueRowStore } from '../../cues/editor/CueRowStore'
import { useLiveJogRefusal, useStageJog, type StageJogHost } from '../../editor/StageJog'
import { useEditorContext } from '../../programmer/EditorContext'
import { useFocusedPaletteLayer } from '../../programmer/FocusedPaletteLayer'
import { useLookRowStore } from '../../programmer/LookRowStore'
import { landValuesInLook } from '../../programmer/landValuesInLook'
import { useProgrammerScope } from '../../programmer/ProgrammerScope'
import { parseProgrammerValue } from '../../../lib/programmerValue'
import { paletteTargetsOf, type WriteTarget } from '../rowModel'
import { lookPositionOf } from '../useCellWriters'
import type { AimBase, AimResponse } from '../../../store/programmerOps'

/**
 * The stage jog for a position cell's batch, where the grid is pointed: Local writes live, a focused
 * Look layer and the cue editor's Values are drafts — the desk answers with `write: false` and each
 * head's literal lands in the draft, the jog starting from the row being edited — and Output and a
 * focused palette layer refuse, with the words Spread uses.
 */
export function usePositionJog(targets: readonly WriteTarget[], projectId?: number) {
  const scope = useProgrammerScope()
  const focusedPalette = useFocusedPaletteLayer()
  const lookStore = useLookRowStore()
  const editorContext = useEditorContext()
  const cueStoreRaw = useCueRowStore()
  const cueStore = editorContext.kind === 'cue' ? cueStoreRaw : null
  const layerStore = scope?.kind === 'layer' ? lookStore : null

  const keys = useMemo(() => paletteTargetsOf(targets).map((target) => target.key), [targets])
  const live = useLiveJogRefusal(keys, cueStore == null ? projectId : undefined)

  const draft = useMemo<StageJogHost['draft']>(() => {
    if (cueStore != null) {
      return {
        bases: () => basesFrom(keys, (key) => cueStore.composedValue('fixture', key, 'position')),
        onAnswer: (answer: AimResponse) => {
          for (const write of answer.written ?? []) cueStore.setValue('fixture', write.target.key, 'position', write.value)
        },
      }
    }
    if (layerStore != null) {
      return {
        bases: () => basesFrom(keys, (key) => lookPositionOf(layerStore, key)),
        onAnswer: (answer: AimResponse) =>
          landValuesInLook(
            layerStore,
            (answer.written ?? []).map((write) => ({ target: write.target, propertyName: 'position', value: write.value })),
          ),
      }
    }
    return undefined
  }, [cueStore, layerStore, keys])

  const jog = useStageJog({ projectId: live.projectId, keys, draft })

  const refusal = cueStore?.readOnly
    ? 'This cue is read-only here — unlock the show to edit its values'
    : scope?.kind === 'output'
      ? 'Output is a read of the cook — switch to Local to move these heads'
      : focusedPalette != null
        ? 'This layer applies a palette — switch to Local to move these heads'
        : live.refusal
  return { ...jog, refusal }
}

/** The rows' own positions, as a jog's starting points: each head the row holds a `pan,tilt` for. */
function basesFrom(keys: readonly string[], valueOf: (key: string) => string | undefined): AimBase[] {
  return keys.flatMap((key) => {
    const value = valueOf(key)
    return value != null && parseProgrammerValue(value)?.kind === 'position' ? [{ target: { type: 'fixture', key }, value }] : []
  })
}
