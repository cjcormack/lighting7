import { useCallback, useMemo, useState, type ElementType, type ReactNode } from 'react'
import { Loader2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import { commandsOf, findColourSource, findDimmerProperty, triggersOf, useFixtureTypeListQuery, type Fixture } from '@/store/fixtures'
import { useActiveEffectsQuery } from '@/store/fixtureFx'
import { useGroupQuery } from '@/store/groups'
import { useIsDeskConnected } from '@/store/status'
import { useProgrammerBlind } from '@/hooks/useProgrammerBlind'
import { useGelIndex } from '@/hooks/useGelIndex'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { fixtureChannelRefs } from '@/hooks/useFixturePark'
import { getPropertyChannels } from '@/hooks/usePropertyParkStatus'
import { findGel } from '@/lib/gels'
import type { GroupSummary } from '@/api/groupsApi'
import type { ActiveEffect } from '@/store/fixtureFx'
import type { ChannelRef } from '@/store/fixtures'
import { CannonPanel } from '../effects/CannonPanel'
import { FixtureCommandsMenu } from '../fixtures/FixtureCommandsMenu'
import { FixtureParkButton, ParkButton } from '../fixtures/FixtureParkButton'
import { LocateTargetsButton } from '../fixtures/LocateButton'
import { GelSwatch } from '../fixtures/GelSwatch'
import { FixtureBoundControlsRow, GroupBoundControlsRow } from '../surfaces/FixtureBoundControlsRow'
import { GroupMembersSection } from '../groups/GroupMembersSection'
import { useVisibleGroupMembers } from '../groups/useVisibleGroupMembers'
import { EditorLabel } from '../editor/EditorLabel'
import { pickerFamilyForSheet } from '../fx/fxEditorModel'
import { ChannelsView } from './ChannelsView'
import { FamilyGroups, PickFamilyGroups } from './FamilyGroups'
import { FxTray, type FxTrayTarget, type TrayPick } from './FxTray'
import { HeadStrip } from './HeadStrip'
import { ScopeLine } from './ScopeLine'
import { FIXTURE_VIEWS, FOCUS_VIEW, GROUP_VIEWS, SheetHeader, groupModelLine, modelLine, type SheetView } from './SheetHeader'
import { buildSheetRows, type SheetRow, type SheetRowGroup } from './sheetRows'
import {
  elementFilterFor,
  groupSheetMembers,
  headAsFixture,
  headsOfFixture,
  pickCountLabel,
  pickedHeads,
  pickWrite,
  rowsOverHeads,
  type GroupSheetMember,
  type HeadPick,
  type PickHead,
  type PickRowGroup,
} from './sheetPick'
import { FixtureSheetContext, type FixtureSheetContextValue, type SheetHost, type SheetTarget } from './sheetContext'
import { useCueLabel, useEffectDetail } from './effectLabels'
import { fixtureHeadKeys, heldLine, useHeldOnTarget, useRelease } from './useRelease'

const EMPTY_EFFECTS: readonly ActiveEffect[] = []

type TitleComponent = ElementType<{ className?: string; children?: ReactNode; title?: string }>

interface SheetProps {
  host: SheetHost
  /** `SheetTitle` inside a Radix sheet, for the dialog's name; a plain heading elsewhere. */
  titleComponent?: TitleComponent
  /** The Stage view's Focus tab body. Absent everywhere else: only the Stage has a scene to focus on. */
  focus?: ReactNode
  /** The Stage view's *Aim at point* body, opened from the Position row's *Aim…*. */
  aim?: ReactNode
  /** A group's Members view: open a member's own sheet (where a group effect reads *via <group>*). */
  onOpenMember?: (fixtureKey: string) => void
}

/**
 * The fixture sheet (fixture-fx-sheets plan D1–D13): one body for a fixture **or a group** in every
 * host — the list's pop-up, the Stage panel, a phone's bottom sheet (`PhoneSheet`), the cards page,
 * the group sheet and `GroupCard`.
 *
 * It is a **programmer surface**, like a busk tab: every value lands in Local, and every effect it
 * starts is a programmer effect. It says so — a scope line under the header, a source mark on every
 * row naming who drives it (D4) and opening the property's stack (D5), an × that clears one value
 * (D6), *Release n* for the lot. It is live whenever the desk is connected: no Edit / Done (D2).
 *
 * A multi-head fixture or a group draws **the head strip** (D13): *All*, then a pip per head or
 * member. The pick is the sheet's own (call 4) and resets when the sheet opens on another target;
 * the rows under it, the tray and Locate follow it.
 *
 * The column is the shape the effects need (issue 6): header and scope line fixed, the properties
 * the only scroller, and the FX tray pinned **outside** it at the foot, so a long fixture never
 * pushes its effects off the bottom. The sheet is a size container (`sheet`), so its width ladder
 * answers the sheet's own width and a pop-up and a panel of one width draw alike (§4).
 */
export function FixtureSheet(props: ({ fixture: Fixture; group?: undefined } | { group: GroupSummary; fixture?: undefined }) & SheetProps) {
  return props.group != null ? <GroupSheet {...props} group={props.group} /> : <FixtureTargetSheet {...props} fixture={props.fixture!} />
}

/**
 * The strip's pick, **reset when the sheet moves to another target** (call 4): it is remembered
 * against the target it was made on, and a different target reads it as *All* — so a host that keeps
 * one sheet mounted across fixtures (the Stage panel) starts each on *All* without an effect.
 */
function useSheetPick(targetKey: string): [HeadPick, (pick: HeadPick) => void] {
  const [state, setState] = useState<{ on: string; pick: HeadPick }>({ on: targetKey, pick: null })
  const pick = state.on === targetKey ? state.pick : null
  const setPick = useCallback((next: HeadPick) => setState({ on: targetKey, pick: next }), [targetKey])
  return [pick, setPick]
}

/** What every sheet shares: the context the rows read, the open row, the picker's starting family. */
function useSheetContext(
  target: SheetTarget,
  host: SheetHost,
  reachOf: FixtureSheetContextValue['reachOf'],
  aim: ReactNode,
  rowGroups: readonly { rows: readonly SheetRow[] }[],
) {
  const [openRowId, setOpenRowId] = useState<string | null>(null)
  const connected = useIsDeskConnected()
  const blind = useProgrammerBlind()
  const { data: effects } = useActiveEffectsQuery()
  const cueLabel = useCueLabel()
  const effectDetail = useEffectDetail()
  const context = useMemo<FixtureSheetContextValue>(
    () => ({
      target,
      host,
      connected,
      blind,
      effects: effects ?? EMPTY_EFFECTS,
      reachOf,
      cueLabel,
      effectDetail,
      openRowId,
      setOpenRowId,
      aim,
    }),
    [target, host, connected, blind, effects, reachOf, cueLabel, effectDetail, openRowId, aim],
  )
  // The picker opens on the family of the row open on the sheet, and starts its effect on that
  // row's property where the effect can take it (Fx board, "Adding"). An open row is keyed by its
  // head and its id (`PropertyRow`'s `openKey`); a head's row of the same id is the same family.
  const openRow = useMemo(() => {
    const rowId = openRowId?.split('\u0000')[1]
    return rowId == null ? null : (rowGroups.flatMap((g) => g.rows).find((r) => r.id === rowId) ?? null)
  }, [rowGroups, openRowId])
  return {
    context,
    connected,
    blind,
    effects,
    initialFamily: pickerFamilyForSheet(openRow?.family),
    preferredProperty: openRow?.keys[0] ?? null,
  }
}

/** The column: chrome, the fixed strip, the only scroller, and the tray outside it at the foot. */
function SheetColumn({
  host,
  chrome,
  fixed,
  body,
  tray,
}: {
  host: SheetHost
  chrome: ReactNode
  /** Under the chrome and outside the scroller — a group's head strip (HeadsGroups board). */
  fixed?: ReactNode
  body: ReactNode
  tray: ReactNode
}) {
  const card = host === 'card'
  return (
    <div data-fixture-sheet data-host={host} className={cn('@container/sheet flex min-h-0 flex-col', card ? 'h-auto' : 'h-full')}>
      {!card && chrome}
      {fixed}
      <div data-sheet-body className={cn('min-h-0 flex-1', !card && 'overflow-y-auto pb-2')}>
        {body}
      </div>
      {tray}
    </div>
  )
}

// ─── A fixture ─────────────────────────────────────────────────────────────

function FixtureTargetSheet({ fixture, host, titleComponent, focus, aim }: SheetProps & { fixture: Fixture }) {
  const [view, setView] = useState<SheetView>('values')
  const heads = useMemo(() => headsOfFixture(fixture), [fixture])
  const [pick, setPick] = useSheetPick(fixture.key)
  const groups = useMemo(
    () =>
      buildSheetRows(fixture.properties, {
        dimmerElsewhere: (fixture.elementGroupProperties ?? []).some((p) => p.category === 'dimmer'),
      }),
    [fixture.properties, fixture.elementGroupProperties],
  )
  const fixtureDimmer = useMemo(() => findDimmerProperty(fixture.properties), [fixture.properties])
  const picked = useMemo(() => pickedHeads(heads, pick), [heads, pick])
  const headGroups = useMemo(() => rowsOverHeads(picked, () => ({ fallbackDimmer: fixtureDimmer })), [picked, fixtureDimmer])
  const write = useMemo(() => pickWrite(heads, pick), [heads, pick])
  const target = useMemo<SheetTarget>(() => ({ type: 'fixture', fixture }), [fixture])
  const reachOf = useCallback(
    (head: string) => ({ keys: head === fixture.key ? [fixture.key] : [head, fixture.key], groups: fixture.groups }),
    [fixture.key, fixture.groups],
  )
  const rowGroups = useMemo(() => [...groups, ...headGroups.map((g) => ({ rows: g.rows.map((r) => r.row) }))], [groups, headGroups])
  const { context, connected, blind, effects, initialFamily, preferredProperty } = useSheetContext(target, host, reachOf, aim, rowGroups)

  const trayTarget = useMemo<FxTrayTarget>(() => ({ type: 'fixture', fixture }), [fixture])
  const trayPick = useMemo((): TrayPick | undefined => {
    if (pick == null) return undefined
    const one = picked.length === 1 ? picked[0] : null
    const filter = elementFilterFor(heads, pick)
    return {
      reach: { keys: [...picked.map((h) => h.key), fixture.key], groups: fixture.groups },
      start:
        one != null
          ? { target: { type: 'fixture', fixture: headAsFixture(fixture, one) } }
          : filter != null
            ? { target: { type: 'fixture', fixture }, elementFilter: filter }
            : {
                reason:
                  'An effect starts on all the heads, one head, or the odd, even or a half of them — pick one of those, or a head at a time',
              },
      nameOf: (key) => heads.find((h) => h.key === key)?.name,
      noun: one != null ? one.name : `these ${picked.length} heads`,
    }
  }, [pick, picked, heads, fixture])

  const locateTargets = useMemo(
    () => (pick == null ? [{ type: 'fixture' as const, key: fixture.key }] : picked.map((h) => ({ type: 'fixture' as const, key: h.key }))),
    [pick, picked, fixture.key],
  )
  const shownView: SheetView = view === 'focus' && focus == null ? 'values' : view === 'members' ? 'values' : view

  return (
    <FixtureSheetContext.Provider value={context}>
      <SheetColumn
        host={host}
        chrome={
          <SheetChrome
            target={target}
            name={fixture.name}
            model={modelLine(fixture)}
            views={focus != null ? [...FIXTURE_VIEWS, FOCUS_VIEW] : FIXTURE_VIEWS}
            view={shownView}
            onView={setView}
            titleComponent={titleComponent}
            connected={connected}
            blind={blind}
            effects={effects}
            locate={<LocateTargetsButton targets={locateTargets} name={pick == null ? fixture.name : `${picked.length === 1 ? picked[0].name : `${picked.length} heads`} of ${fixture.name}`} />}
            park={<FixtureParkButton fixture={fixture} isEditing={connected} iconOnly />}
          />
        }
        body={
          <>
            {shownView === 'values' && (
              <FixtureValues
                fixture={fixture}
                groups={groups}
                heads={heads}
                pick={pick}
                onPick={setPick}
                headGroups={headGroups}
                write={write}
                connected={connected}
              />
            )}
            {shownView === 'channels' && (
              <div className="px-3 py-2">
                <ChannelsView fixture={fixture} span={1} isEditing={connected} />
              </div>
            )}
            {shownView === 'focus' && <div className="px-3 py-2">{focus}</div>}
          </>
        }
        tray={<FxTray target={trayTarget} pick={trayPick} initialFamily={initialFamily} preferredProperty={preferredProperty} />}
      />
    </FixtureSheetContext.Provider>
  )
}

function FixtureValues({
  fixture,
  groups,
  heads,
  pick,
  onPick,
  headGroups,
  write,
  connected,
}: {
  fixture: Fixture
  groups: readonly SheetRowGroup[]
  heads: readonly PickHead[]
  pick: HeadPick
  onPick: (pick: HeadPick) => void
  headGroups: readonly PickRowGroup[]
  write: ReturnType<typeof pickWrite>
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
  const empty = groups.length === 0 && heads.length === 0 && triggers.length === 0 && commands.length === 0

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
      {heads.length > 0 && (
        // The fixture's own properties stay above the strip; the heads' are below it and follow the
        // pick (HeadsGroups board, the bar).
        <section data-heads className="mt-2 flex flex-col">
          <div className="flex items-center gap-2 px-3 pt-3 pb-1.5">
            <EditorLabel>Heads</EditorLabel>
            <span className="text-[11px] text-muted-foreground">
              <b className="font-medium text-foreground">{pickCountLabel(heads, pick)}</b>
              <span className="@max-[400px]/sheet:hidden"> · drag across to pick a run</span>
            </span>
            <span className="h-px flex-1 bg-border" />
          </div>
          <div className="border-y bg-muted/30 px-3 py-2">
            <HeadStrip heads={heads} pick={pick} onPick={onPick} kind="heads" fixture={fixture} allLabel="All" />
          </div>
          <PickFamilyGroups groups={headGroups} write={write} />
        </section>
      )}
      {empty && <p className="px-3 py-3 text-sm text-muted-foreground">No properties available</p>}
    </div>
  )
}

// ─── A group ───────────────────────────────────────────────────────────────

function GroupSheet({ group, host, titleComponent, onOpenMember }: SheetProps & { group: GroupSummary }) {
  const [view, setView] = useState<SheetView>('values')
  const { data: detail, isLoading } = useGroupQuery(group.name)
  const visible = useVisibleGroupMembers(detail?.members)
  const { fixtures } = useFixtureLookup()
  const members = useMemo(() => groupSheetMembers(visible ?? [], fixtures ?? []), [visible, fixtures])
  const [pick, setPick] = useSheetPick(group.name)
  const picked = useMemo(() => pickedHeads(members, pick), [members, pick])
  const rowGroups = useMemo(() => rowsOverHeads(picked), [picked])
  const write = useMemo(() => pickWrite(members, pick, group.name), [members, pick, group.name])
  const target = useMemo<SheetTarget>(() => ({ type: 'group', group, members }), [group, members])
  const reachOf = useCallback(
    (key: string) => {
      const member = members.find((m) => m.key === key)
      if (member == null) return { keys: [key], groups: [group.name] }
      return { keys: member.elementIndex == null ? [key] : [key, member.fixture.key], groups: member.fixture.groups }
    },
    [members, group.name],
  )
  const contextRows = useMemo(() => rowGroups.map((g) => ({ rows: g.rows.map((r) => r.row) })), [rowGroups])
  const { context, connected, blind, effects, initialFamily, preferredProperty } = useSheetContext(target, host, reachOf, undefined, contextRows)

  const trayTarget = useMemo<FxTrayTarget>(() => ({ type: 'group', group }), [group])
  const trayPick = useMemo((): TrayPick => {
    // A whole member brings its heads; one head brings its fixture, whose effects the desk paints on it.
    const keys = picked.flatMap((m) => (m.elementIndex == null ? [...fixtureHeadKeys(m.fixture)] : [m.key, m.fixture.key]))
    const groups = [...new Set([group.name, ...picked.flatMap((m) => m.fixture.groups)])]
    const one = picked.length === 1 ? picked[0] : null
    return {
      reach: { keys, groups },
      start:
        pick == null
          ? { target: { type: 'group', group } }
          : one != null
            ? { target: { type: 'fixture', fixture: one.elementIndex == null ? one.fixture : headAsFixture(one.fixture, one) } }
            : { reason: `An effect starts on the whole group, with its distribution, or on one member — pick All or one of them` },
      nameOf: (key) => members.find((m) => m.key === key)?.name ?? members.find((m) => fixtureHeadKeys(m.fixture).has(key))?.fixture.name,
      noun: pick == null ? group.name : one != null ? one.name : `these ${picked.length} members`,
    }
  }, [pick, picked, members, group])

  const locateTargets = useMemo(
    () => (pick == null ? [{ type: 'group' as const, key: group.name }] : picked.map((m) => ({ type: 'fixture' as const, key: m.key }))),
    [pick, picked, group.name],
  )
  const parkChannels = useMemo(() => memberChannels(members), [members])
  const shownView: SheetView = view === 'members' ? 'members' : 'values'
  const card = host === 'card'

  const strip =
    members.length > 0 ? (
      <div className={cn('flex-none bg-muted/30 px-3 pt-2 pb-2', !card && 'border-b')}>
        <HeadStrip heads={members} pick={pick} onPick={setPick} kind="members" members={members} allLabel={`All ${members.length}`} />
      </div>
    ) : null

  const values = (
    <div className="flex flex-col">
      <div className="flex flex-col gap-2 px-3 empty:hidden [&>*:first-child]:mt-2">
        <GroupBoundControlsRow groupName={group.name} />
      </div>
      {isLoading && members.length === 0 ? (
        <div className="flex justify-center py-4">
          <Loader2 className="size-5 animate-spin text-muted-foreground" />
        </div>
      ) : members.length === 0 ? (
        <p className="px-3 py-3 text-sm text-muted-foreground">No members</p>
      ) : rowGroups.length === 0 ? (
        <p className="px-3 py-3 text-sm text-muted-foreground">The {pick == null ? 'members' : 'picked members'} share no property</p>
      ) : (
        <PickFamilyGroups groups={rowGroups} write={write} />
      )}
    </div>
  )
  const membersView = (
    <div className="px-3 py-2">
      <GroupMembersSection members={detail?.members} isLoading={isLoading} onFixtureClick={onOpenMember ?? (() => {})} />
    </div>
  )

  return (
    <FixtureSheetContext.Provider value={context}>
      <SheetColumn
        host={host}
        chrome={
          <SheetChrome
            target={target}
            name={group.name}
            model={groupModelLine(members.map((m) => m.fixture))}
            views={GROUP_VIEWS}
            view={shownView}
            onView={setView}
            titleComponent={titleComponent}
            connected={connected}
            blind={blind}
            effects={effects}
            scopeWhat={pick == null ? 'the group' : `${picked.length} member${picked.length === 1 ? '' : 's'}`}
            locate={<LocateTargetsButton targets={locateTargets} name={pick == null ? group.name : `${picked.length} of ${group.name}`} />}
            park={<ParkButton channels={parkChannels} name={group.name} isEditing={connected} iconOnly />}
          />
        }
        // The strip is the group's whole subject, so it stays put above the scroller (HeadsGroups
        // board, the group); a card, which has no chrome, carries it at its top.
        fixed={shownView === 'values' ? strip : null}
        body={
          card ? (
            <>
              {values}
              {membersView}
            </>
          ) : shownView === 'members' ? (
            membersView
          ) : (
            values
          )
        }
        tray={<FxTray target={trayTarget} pick={trayPick} initialFamily={initialFamily} preferredProperty={preferredProperty} />}
      />
    </FixtureSheetContext.Provider>
  )
}

/** Every channel a group's members are patched on: a whole member's, or one head's own. */
function memberChannels(members: readonly GroupSheetMember[]): ChannelRef[] {
  const seen = new Set<string>()
  const out: ChannelRef[] = []
  for (const m of members) {
    const refs = m.elementIndex == null ? fixtureChannelRefs(m.fixture) : m.properties.flatMap(getPropertyChannels)
    for (const ref of refs) {
      const key = `${ref.universe}:${ref.channelNo}`
      if (seen.has(key)) continue
      seen.add(key)
      out.push(ref)
    }
  }
  return out
}

// ─── Chrome ────────────────────────────────────────────────────────────────

/**
 * The header and the scope line, which are what read the held count — a child of its own so the
 * card host, which draws neither, does not re-count the programmer on every event for every card.
 */
function SheetChrome({
  target,
  name,
  model,
  views,
  view,
  onView,
  titleComponent,
  connected,
  blind,
  effects,
  scopeWhat,
  locate,
  park,
}: {
  target: SheetTarget
  name: string
  model: string
  views: readonly { id: SheetView; label: string }[]
  view: SheetView
  onView: (view: SheetView) => void
  titleComponent?: TitleComponent
  connected: boolean
  blind: boolean
  effects: readonly ActiveEffect[] | undefined
  scopeWhat?: string
  locate: ReactNode
  park: ReactNode
}) {
  const held = useHeldOnTarget(target, effects)
  const release = useRelease(target)
  return (
    <>
      <SheetHeader
        name={name}
        model={model}
        views={views}
        view={view}
        onView={onView}
        titleComponent={titleComponent}
        held={held.values + held.effects}
        connected={connected}
        onRelease={() => void release()}
        locate={locate}
        park={park}
      />
      {view === 'values' && <ScopeLine blind={blind} connected={connected} held={heldLine(held)} what={scopeWhat} />}
    </>
  )
}
