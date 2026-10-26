import type { CueTarget } from '@/api/cuesApi'
import { lightingApi } from '@/api/lightingApi'
import type { Fixture } from '@/store/fixtures'
import { isFollowingDesk } from '@/lib/deskFollow'
import { publishIncludeSelection } from '@/store/includeSelection'
import { setDeskSelection } from '@/store/selection'

/**
 * **Select heads in this window from outside its list** — the FX tab's *All* row press, a running
 * FX's card with nothing selected and the subject's *Only* (FX editor plan §4.1, follow-ups D1, D2).
 * The selection is the window's: the desk's while it follows, its mask kept, where the list's bridge
 * brings the frame back as rows; else the programmer list's own, through Include's request
 * (`store/includeSelection.ts`), which opens the groups and fixtures the heads sit in and clears the
 * filter. A group goes as its row and as its members, since a flat list has no group row.
 */
export function selectWindowHeads(targets: readonly CueTarget[], fixtures: readonly Fixture[]): void {
  if (isFollowingDesk()) {
    setDeskSelection(targets, lightingApi.selection.getState()?.families ?? null)
    return
  }
  const groupKeys = targets.filter((t) => t.type === 'group').map((t) => t.key)
  const fixtureKeys = [
    ...targets.filter((t) => t.type === 'fixture').map((t) => t.key),
    ...fixtures.filter((f) => f.groups.some((g) => groupKeys.includes(g))).map((f) => f.key),
  ]
  publishIncludeSelection([...new Set(fixtureKeys)], groupKeys)
}
