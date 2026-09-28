import { SpeedMasters } from './SpeedMasters'
import { CollapsiblePanel } from './CollapsiblePanel'

interface SpeedMasterOverviewPanelProps {
  isVisible: boolean
}

/**
 * The speed-master bank as an overview panel — `PD-SPEED-OVERLAY`.
 *
 * Check 5.3 asked for a tempo readout while editing an effect's timing, and the operator's answer
 * was that the bank should be **summoned** rather than resident. This is that: a fourth panel
 * beside Stage, Fixture and Cue Slots, so the bank is one press away from *every* view and no
 * view — the programmer included — gains chrome of its own. Summoning a surface over the page is
 * a gesture the desk already has; this is another instance of it, not a new one.
 *
 * **It mounts `SpeedMasters` whole**, which is the whole reason it is allowed to exist. The panel
 * it replaces in the registry's history — Effects Overview — was a *narrower* second answer to
 * "what tempo is the desk at": its own beat dot, its own readout, and only ever master 1's.
 * `SpeedMasters.tsx`'s docblock is the record of what the last near-copy of a speed surface cost.
 * So there is no new readout and no new tile here: this is the ShowBar's own component in a wider
 * box, and that component now has two hosts.
 *
 * **The `@container` is this panel's, and `room="dedicated"` is the only thing it tells the
 * component.** `SpeedMasters` picks its three arms from its host's container width and the master
 * count, so the panel declares a container of its own and the ladder runs against the panel's
 * width rather than the bar's. It also has to say *which* ladder: the bar's thresholds are not
 * about whether the tiles fit but about what they cost the live-state block beside them, and this
 * row has nothing beside them — a four-master bank is 464–711px of tiles, and it was being made to
 * clear 1600px. `ARMS.dedicated` is measured against this row instead. The **5+ ceiling is
 * unchanged**, because that one was never a width judgment. Per `ProgrammerWorkspace`'s rule the
 * queried classes must sit on a *descendant* of the element declaring the container, so the row
 * below is a child rather than the container itself.
 *
 * **Visibility persists, per panel and app-wide — a feature and a cost, and both are accepted.**
 * Opened once, this panel stays open on every view and across reloads. On the programmer that is a
 * tempo band back on screen; it is not session 5's band returning, because it is behind a door the
 * operator opened and can close, and no view draws it unasked. On Show and the Prompt Book — the
 * two views with a `ShowBar` — it means the bank is drawn twice until dismissed, and on Busk twice
 * while the side sheet's Speed tab is open. Neither
 * is a defect. Both are written down here so nobody later reads the panel as a regression and
 * "fixes" it by making it per-view, which would put one surface in two states again.
 *
 * **Master 1 and the follower rules are unchanged**, because they are `SpeedMasters`' and this
 * panel adds nothing of its own: the four surfaces that offer TAP and click-to-type are still four
 * — this one *reuses* `MasterTile`/`MasterRow` rather than becoming a fifth. No tempo is computed
 * here either; the live frame is the readout.
 */
export function SpeedMasterOverviewPanel({ isVisible }: SpeedMasterOverviewPanelProps) {
  return (
    <CollapsiblePanel isVisible={isVisible}>
      <SpeedMasterOverviewPanelBody />
    </CollapsiblePanel>
  )
}

/**
 * Below the collapse boundary: `SpeedMasters` subscribes to the live bank and mounts a
 * `BeatIndicator` per master, each free-running a timer between server frames. A collapsed panel
 * has no use for any of it, and it would otherwise run on every route.
 */
function SpeedMasterOverviewPanelBody() {
  return (
    <div className="@container border-b bg-background px-4 py-3">
      <div className="flex flex-wrap items-stretch gap-2">
        <SpeedMasters room="dedicated" />
      </div>
    </div>
  )
}
