import { useCallback } from 'react'
import { Stage3D, type Selection } from '../stage3d/Stage3D'
import { useWorkLights } from '../stage3d/scene/workLights'
import { useSceneLayers } from '../stage3d/scene/sceneView'
import { StageChannelSourceProvider } from '../../hooks/useChannelSource'
import { clearDeskSelection, setDeskSelection } from '../../store/selection'
import { plansScenery } from '../../lib/scenery'

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
 * first selected fixture. Work lights are the window's (stage-view menu plan D8): every canvas in
 * the window follows the one switch, so the rig reads here as it does on the Stage view.
 *
 * **It draws the scenery that moves the light** (scenery-programmer plan D16): the drapes and the
 * Set layer — of those, what the window's Venue and Set layers show — at the state the window's vis source has them — live, or Blind's staged moves on a
 * programmer source, or the Next GO preview's — so a closed tab or a flown piece shows where a beam
 * stops. Through the same `StageChannelSourceProvider` and the Stage view's own overlay; a click on
 * a piece here opens nothing (`sceneryPopover` is the Stage route's).
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
  const workLights = useWorkLights()
  // The window's Venue · Set · Haze, as the Stage view's View menu sets them: the plan's drapes and
  // Set pieces follow them like the work lights do (Chris, 2026-10-07).
  const layers = useSceneLayers()
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
        camera="plan"
        showScene
        sceneSubset={plansScenery}
        layers={layers}
        editMode={false}
        selection={selection}
        hidePatchSelectionInfo
        workLights={workLights}
        onSelectionChange={onSelectionChange}
      />
    </StageChannelSourceProvider>
  )
}
