import { useMemo, useState, type ElementType, type ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { commandsOf, findColourSource, findDimmerProperty, triggersOf, useFixtureTypeListQuery, type Fixture } from '@/store/fixtures'
import { useActiveEffectsQuery } from '@/store/fixtureFx'
import { useIsDeskConnected } from '@/store/status'
import { useProgrammerBlind } from '@/hooks/useProgrammerBlind'
import { useGelIndex } from '@/hooks/useGelIndex'
import { findGel } from '@/lib/gels'
import { CannonPanel } from '../effects/CannonPanel'
import { FixtureCommandsMenu } from '../fixtures/FixtureCommandsMenu'
import { GelSwatch } from '../fixtures/GelSwatch'
import { FixtureBoundControlsRow } from '../surfaces/FixtureBoundControlsRow'
import { ChannelsView } from './ChannelsView'
import { FamilyGroups } from './FamilyGroups'
import { FxTray } from './FxTray'
import { pickerFamilyForSheet } from '../fx/fxEditorModel'
import { HeadsSection } from './HeadsSection'
import { ScopeLine } from './ScopeLine'
import { SheetHeader, type SheetView } from './SheetHeader'
import { buildSheetRows } from './sheetRows'
import { FixtureSheetContext, type FixtureSheetContextValue, type SheetHost } from './sheetContext'
import { useCueLabel, useEffectDetail } from './effectLabels'
import { heldLine, useHeldOnFixture, useRelease } from './useRelease'
import type { ActiveEffect } from '@/store/fixtureFx'

const EMPTY_EFFECTS: readonly ActiveEffect[] = []

/**
 * The fixture sheet (fixture-fx-sheets plan D1–D10): one body for a fixture in every host — the
 * list's pop-up, the Stage panel, the cards page — and, from session 4, a group.
 *
 * It is a **programmer surface**, like a busk tab: every value lands in Local, and every effect it
 * starts is a programmer effect. It says so — a scope line under the header, a source mark on every
 * row naming who drives it (D4) and opening the property's stack (D5), an × that clears one value
 * (D6), *Release n* for the lot. It is live whenever the desk is connected: no Edit / Done (D2).
 *
 * The column is the shape the effects need (issue 6): header and scope line fixed, the properties
 * the only scroller, and the FX tray pinned **outside** it at the foot, so a long fixture never
 * pushes its effects off the bottom. The sheet is a size container (`sheet`), so its width ladder
 * answers the sheet's own width and a pop-up and a panel of one width draw alike (§4).
 */
export function FixtureSheet({
  fixture,
  host,
  titleComponent,
  focus,
  aim,
}: {
  fixture: Fixture
  host: SheetHost
  /** `SheetTitle` inside a Radix sheet, for the dialog's name; a plain heading elsewhere. */
  titleComponent?: ElementType<{ className?: string; children?: ReactNode; title?: string }>
  /** The Stage view's Focus tab body. Absent everywhere else: only the Stage has a scene to focus on. */
  focus?: ReactNode
  /** The Stage view's *Aim at point* body, opened from the Position row's *Aim…*. */
  aim?: ReactNode
}) {
  const [view, setView] = useState<SheetView>('values')
  const [openRowId, setOpenRowId] = useState<string | null>(null)
  const connected = useIsDeskConnected()
  const blind = useProgrammerBlind()
  const { data: effects } = useActiveEffectsQuery()
  const cueLabel = useCueLabel()
  const effectDetail = useEffectDetail()
  const groups = useMemo(
    () =>
      buildSheetRows(fixture.properties, {
        dimmerElsewhere: (fixture.elementGroupProperties ?? []).some((p) => p.category === 'dimmer'),
      }),
    [fixture.properties, fixture.elementGroupProperties],
  )

  const context = useMemo<FixtureSheetContextValue>(
    () => ({
      fixture,
      host,
      connected,
      blind,
      effects: effects ?? EMPTY_EFFECTS,
      cueLabel,
      effectDetail,
      openRowId,
      setOpenRowId,
      aim,
    }),
    [fixture, host, connected, blind, effects, cueLabel, effectDetail, openRowId, aim],
  )
  const trayTarget = useMemo(() => ({ type: 'fixture' as const, fixture }), [fixture])
  // The picker opens on the family of the row open on the sheet, and starts its effect on that
  // row's property where the effect can take it (Fx board, "Adding"). An open row is keyed by its
  // head and its id (`PropertyRow`'s `openKey`); a head's row of the same id is the same family.
  const openRow = useMemo(() => {
    const rowId = openRowId?.split('\u0000')[1]
    return rowId == null ? null : (groups.flatMap((g) => g.rows).find((r) => r.id === rowId) ?? null)
  }, [groups, openRowId])

  const card = host === 'card'
  const shownView: SheetView = view === 'focus' && focus == null ? 'values' : view

  return (
    <FixtureSheetContext.Provider value={context}>
      <div
        data-fixture-sheet
        data-host={host}
        className={cn('@container/sheet flex min-h-0 flex-col', card ? 'h-auto' : 'h-full')}
      >
        {!card && (
          <SheetChrome
            fixture={fixture}
            view={shownView}
            onView={setView}
            hasFocus={focus != null}
            titleComponent={titleComponent}
            connected={connected}
            blind={blind}
            effects={effects}
          />
        )}
        <div data-sheet-body className={cn('min-h-0 flex-1', !card && 'overflow-y-auto pb-2')}>
          {shownView === 'values' && <ValuesView fixture={fixture} groups={groups} connected={connected} />}
          {shownView === 'channels' && (
            <div className="px-3 py-2">
              <ChannelsView fixture={fixture} span={1} isEditing={connected} />
            </div>
          )}
          {shownView === 'focus' && <div className="px-3 py-2">{focus}</div>}
        </div>
        <FxTray
          target={trayTarget}
          initialFamily={pickerFamilyForSheet(openRow?.family)}
          preferredProperty={openRow?.keys[0] ?? null}
        />
      </div>
    </FixtureSheetContext.Provider>
  )
}

/**
 * The header and the scope line, which are what read the held count — a child of its own so the
 * card host, which draws neither, does not re-count the programmer on every event for every card.
 */
function SheetChrome({
  fixture,
  view,
  onView,
  hasFocus,
  titleComponent,
  connected,
  blind,
  effects,
}: {
  fixture: Fixture
  view: SheetView
  onView: (view: SheetView) => void
  hasFocus: boolean
  titleComponent?: ElementType<{ className?: string; children?: ReactNode; title?: string }>
  connected: boolean
  blind: boolean
  effects: readonly ActiveEffect[] | undefined
}) {
  const held = useHeldOnFixture(fixture, effects)
  const release = useRelease(fixture)
  return (
    <>
      <SheetHeader
        fixture={fixture}
        view={view}
        onView={onView}
        hasFocus={hasFocus}
        titleComponent={titleComponent}
        held={held.values + held.effects}
        connected={connected}
        onRelease={() => void release()}
      />
      {view === 'values' && <ScopeLine blind={blind} connected={connected} held={heldLine(held)} />}
    </>
  )
}

function ValuesView({
  fixture,
  groups,
  connected,
}: {
  fixture: Fixture
  groups: ReturnType<typeof buildSheetRows>
  connected: boolean
}) {
  // A confetti cannon's tubes and a head's resets are not values: the cannon's panel and the
  // Commands menu, above the rows as before.
  const triggers = useMemo(() => triggersOf(fixture.properties), [fixture.properties])
  const commands = useMemo(() => commandsOf(fixture.properties), [fixture.properties])
  // A conventional with no colour of its own shows its gel, dimmed by its dimmer.
  const { data: fixtureTypes } = useFixtureTypeListQuery()
  const gels = useGelIndex()
  const type = fixtureTypes?.find((t) => t.typeKey === fixture.typeKey)
  const hasColour = findColourSource(fixture.properties) != null || (fixture.elementGroupProperties ?? []).some((p) => p.type === 'colour')
  const gel = !hasColour && type?.gelCompactDisplay && fixture.gelCode ? findGel(gels, fixture.gelCode) : null
  const empty = groups.length === 0 && (fixture.elements?.length ?? 0) === 0 && triggers.length === 0 && commands.length === 0

  return (
    <div className="flex flex-col">
      <div className="flex flex-col gap-2 px-3 empty:hidden [&>*:first-child]:mt-2">
        <FixtureBoundControlsRow fixtureKey={fixture.key} />
        {triggers.length > 0 && <CannonPanel fixtureKey={fixture.key} triggers={triggers} canFire={connected} />}
        {commands.length > 0 && (
          <FixtureCommandsMenu fixtureKey={fixture.key} fixtureName={fixture.name} commands={commands} canRun={connected} />
        )}
        {gel && <GelSwatch gelHex={gel.color} dimmerProp={findDimmerProperty(fixture.properties)} className="h-6 w-full" />}
      </div>
      <FamilyGroups groups={groups} headKey={fixture.key} />
      <HeadsSection fixture={fixture} />
      {empty && <p className="px-3 py-3 text-sm text-muted-foreground">No properties available</p>}
    </div>
  )
}
