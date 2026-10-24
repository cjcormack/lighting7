import { useCallback } from 'react'
import { cn } from '@/lib/utils'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import type { ActiveFx } from '@/store/fixtureFx'
import type { TableView } from '@/lib/programmerTables'
import { setFxTabView, setPickedFx, useFxTabView, type FxTabView } from '@/lib/fxEditorView'
import { AppliedFxTable } from '@/components/applied/AppliedFxTable'
import { selectWindowHeads } from '@/components/fixtures-list/programmerSelectRequest'
import { FxEditorSelection } from './FxEditorSelection'
import { fxTargetsOf } from './fxEditorList'

/**
 * **The programmer's FX tab** (FX editor plan D1, §4.1): *Selection*, the FX editor, or *All*, the
 * table of every running FX — a per-window choice, *Selection* the default. *All*'s row press
 * (⏎, the name's double click, *Edit…*) selects that FX's heads — the window's selection, or the
 * desk's while it follows — and opens it in *Selection*, picked.
 */
export function ProgrammerFxTab({
  projectId,
  view,
  onViewChange,
}: {
  projectId: number
  view: TableView
  onViewChange: (next: TableView) => void
}) {
  const fxView = useFxTabView()
  const { fixtures } = useFixtureLookup()
  const openInSelection = useCallback(
    (effect: ActiveFx) => {
      selectWindowHeads(fxTargetsOf(effect), fixtures ?? [])
      setPickedFx(effect.id)
      setFxTabView('selection')
    },
    [fixtures],
  )
  const viewSwitch = <FxViewSwitch value={fxView} />
  return fxView === 'all' ? (
    <AppliedFxTable projectId={projectId} view={view} onViewChange={onViewChange} viewSwitch={viewSwitch} onOpenFx={openInSelection} />
  ) : (
    <FxEditorSelection projectId={projectId} viewSwitch={viewSwitch} />
  )
}

const VIEWS: readonly { value: FxTabView; label: string; title: string }[] = [
  { value: 'selection', label: 'Selection', title: 'The FX on the selected heads, by family, top first in the order they apply' },
  { value: 'all', label: 'All', title: 'Every running FX' },
]

/** *Selection · All*, after the table tabs on the FX tab's row B. */
function FxViewSwitch({ value }: { value: FxTabView }) {
  return (
    <div role="group" aria-label="FX view" className="flex h-8 shrink-0 items-center rounded-md border p-0.5">
      {VIEWS.map((v) => (
        <button
          key={v.value}
          type="button"
          data-fx-view={v.value}
          aria-pressed={value === v.value}
          title={v.title}
          onClick={() => setFxTabView(v.value)}
          className={cn(
            'h-full rounded px-2 text-xs text-muted-foreground hover:text-foreground',
            value === v.value && 'bg-accent font-medium text-foreground',
          )}
        >
          {v.label}
        </button>
      ))}
    </div>
  )
}
