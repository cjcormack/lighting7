import { useCallback, useState } from 'react'
import { ProgrammerIndicator } from '@/components/ProgrammerIndicator'
import { OffPlayheadBanner } from '@/components/runner/OffPlayheadBanner'
import { RunMobile } from '@/components/runner/mobile/RunMobile'
import { useCueLocationLabels, useRunnerDisplay } from '@/hooks/useRunnerDisplay'
import { useMakeStackLive } from '@/hooks/useMakeStackLive'
import type { ShowBarState } from '@/hooks/useShowBarProps'
import { useProjectCueStackListQuery, useProjectProgramStateQuery } from '@/store/cueStacks'
import { useEditLock } from '@/hooks/useEditLock'
import type { CueStack } from '@/api/cueStacksApi'

/**
 * What the Show tab needs from the route: the transport and the three cue entries `useShowBarProps` already resolved. `routes/Busk.tsx` holds the one instance and threads
 * it down through `BuskingView` and the sheet, so the tab and a pad press act on one transport.
 */
export type ShowTabSource = Pick<ShowBarState, 'transport' | 'activeCue' | 'standbyCue' | 'nextStack'>

/** Stable no-op for the runner's requeue while it is reading a stack off the playhead. */
const NO_REQUEUE = () => {}

/**
 * The side sheet's **Show tab** — the phone runner mounted in the sheet (busk-chrome plan D1,
 * `ShowTab.dc.html`). It replaced the `ShowBar` on the busk view: the bar was 56px of chrome the
 * view could not spare, and the wrong shape of it besides. Six rules, from the board:
 *
 * 1. **One component, not a copy.** `RunMobile` — the layout Show swaps to below 600px — fed by the
 *    `useShowTransport` instance `routes/Busk.tsx` already holds through `useShowBarProps`, over the
 *    `useRunnerDisplay` both pages call, so the tab and the phone cannot disagree about which cue
 *    is next.
 * 2. **Collapsed by default** (D2), here and — since venue-visit plan S3 took the cards' Stage
 *    body away — on the phone too, so it is `RunMobile`'s own default and no longer a prop. The
 *    Details toggle is still on the card.
 * 3. **Always locked.** The phone runner is the always-locked layout: no inline editing,
 *    click-to-arm on the list, nothing a stray press can change. The show-editing lock is not
 *    consulted, as it never was on this view; GO must work from here whatever Show's lock says.
 * 4. **No transport keys** (D5). A pad is a button and Space activates the focused one, so a key
 *    that also fired GO would be two effects from one press on a live rig. GO is the footer, a
 *    MIDI `go` binding, or the Show view one pill away. Nothing here calls `useTransportKeys`.
 * 5. **The strip carries what the bar carried and nothing more**: stack picker, cue list, the
 *    programmer chip (`ProgrammerIndicator` — the tab's blind report and value count, through
 *    `RunMobile`'s `strip` slot) and the tempo chip. The grand master is the app header's panel, not
 *    the strip's (venue-visit plan D5). The band's `BLIND` pill and the Show glyph's dot
 *    (`BlindMarks.tsx`) report blind beside this, a region apart — the strip is invisible with
 *    the sheet folded or another tab open — and this chip keeps the count; two reporters, no
 *    control.
 * 6. **Show is a live tab from the day it lands**: `show` is in `LIVE_SHEET_TABS`, so `?sheet=show`,
 *    the Screens sheet and a MIDI `buskSheetToggle` reach it by the same door as the other three.
 *
 * **The picker browses, and the banner is what makes browsing honest.** The tab follows the live
 * stack until the picker names another; then it reads that stack, `OffPlayheadBanner` says so,
 * and requeue is inert — exactly the phone branch of `ShowPage`, whose reasoning this borrows: the
 * picker could otherwise leave the operator reading a stack GO does not act on with nothing
 * whatsoever saying so. *Make live* is `useMakeStackLive`, the same sequence Show runs. A browsed
 * stack that becomes live is simply followed again.
 */
export function ShowTab({ projectId, show }: { projectId: number; show: ShowTabSource }) {
  const { transport } = show
  const { data: stacks } = useProjectCueStackListQuery(projectId)
  const { data: programState } = useProjectProgramStateQuery(projectId)
  const activeStackId = programState?.activeStackId ?? null
  // The cards' ⋯ menus follow the shared show-editing lock but cannot lift it: the busk view has no
  // lock control, so a locked action names the Show view, where the lock is. Blind edit still works.
  const { locked: cueActionsLocked } = useEditLock({
    canEdit: programState?.canEdit ?? false,
    isShowActive: activeStackId != null,
  })

  const [browsedStackId, setBrowsedStackId] = useState<number | null>(null)
  const browsed =
    browsedStackId != null && browsedStackId !== activeStackId
      ? stacks?.find((s) => s.id === browsedStackId)
      : undefined
  const stack = browsed ?? transport.activeStack
  const offPlayhead = stack != null && stack.id !== activeStackId
  const runnableStackCount = stacks?.filter((s) => s.type === 'STACK').length ?? 0

  const display = useRunnerDisplay(show)
  const locationByCue = useCueLocationLabels(projectId)
  const makeLive = useMakeStackLive(projectId, activeStackId, transport)

  // `setStandby` rather than `transport`: the transport is a fresh object literal every render.
  const { setStandby } = transport
  const requeue = useCallback((cueId: number) => setStandby(cueId), [setStandby])
  const selectStack = useCallback((target: CueStack) => {
    if (target.type === 'STACK') setBrowsedStackId(target.id)
  }, [])

  return (
    <div data-show-tab className="flex min-h-0 flex-1 flex-col">
      {stack != null && offPlayhead && (
        <OffPlayheadBanner
          liveStackName={transport.activeStack?.name ?? null}
          selectedStackName={stack.name}
          liveCueIsOnStage={transport.serverActiveCueId != null}
          onJumpToLive={() => setBrowsedStackId(null)}
          onMakeLive={() => makeLive(stack)}
        />
      )}
      <RunMobile
        stacks={stacks ?? []}
        selectedStackId={stack?.id ?? null}
        stack={stack}
        multiStack={runnableStackCount > 1}
        display={display}
        onGo={transport.go}
        onBack={transport.back}
        onSelectStack={selectStack}
        // Inert off the playhead, for the reason the phone's is: `setStandby` arms the *playhead's*
        // stack, so a tap here would arm a cue that is not in it.
        onRequeueCue={offPlayhead ? NO_REQUEUE : requeue}
        projectId={projectId}
        fadeStackId={activeStackId}
        activeLocation={display.activeCue ? locationByCue.get(display.activeCue.id) ?? null : null}
        standbyLocation={display.standbyCue ? locationByCue.get(display.standbyCue.id) ?? null : null}
        strip={<ProgrammerIndicator />}
        cueActions={{ locked: cueActionsLocked, lockedReason: 'Locked — unlock the show on the Show view to edit' }}
      />
    </div>
  )
}
