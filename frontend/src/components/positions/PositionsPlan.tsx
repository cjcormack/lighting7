import { useCallback } from 'react'
import { Stage3D, type Selection } from '../stage3d/Stage3D'
import { StageChannelSourceProvider } from '../../hooks/useChannelSource'
import { clearDeskSelection, setDeskSelection } from '../../store/selection'

/**
 * The Positions panel's **Plan** tab (`Positions.dc.html` §4): the Stage view's plan section, drawn
 * by the one renderer — beams, pools and the label layer included — not a second 2D surface. It is
 * its own chunk (`PositionsPanel` imports it lazily), because the panel is mounted on every route
 * and three.js, fiber and drei are the Stage route's weight, not the app shell's.
 *
 * A second camera on the scene, so it keeps no pose (`persistCamera` off) and never moves the
 * Stage view's. A fixture clicked here sets the desk selection, as a chip does — and, as a chip
 * does, a click on the one fixture selected clears it, since a click on empty space here selects
 * nothing (the view edits nothing, so a miss is not a gesture). The highlight follows the desk's
 * first selected fixture.
 */
export default function PositionsPlan({
  projectId,
  selectedKey,
  soleSelected,
}: {
  projectId: number
  selectedKey: string | null
  /** The desk selection is exactly [selectedKey] — a click on it then clears. */
  soleSelected: boolean
}) {
  const selection: Selection = selectedKey == null ? null : { kind: 'patch', patchKey: selectedKey }
  const onSelectionChange = useCallback(
    (next: Selection) => {
      if (next?.kind !== 'patch') return
      if (soleSelected && next.patchKey === selectedKey) clearDeskSelection()
      else setDeskSelection([{ type: 'fixture', key: next.patchKey }])
    },
    [soleSelected, selectedKey],
  )
  return (
    <StageChannelSourceProvider>
      <Stage3D
        projectId={projectId}
        viewpoint="plan"
        editMode={false}
        selection={selection}
        hidePatchSelectionInfo
        onSelectionChange={onSelectionChange}
      />
    </StageChannelSourceProvider>
  )
}
