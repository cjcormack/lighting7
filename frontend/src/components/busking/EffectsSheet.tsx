import { Fragment, useMemo, useState } from 'react'
import { Pause, Play, X } from 'lucide-react'
import { cn } from '@/lib/utils'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { EditedMark, FxEditor } from '@/components/fx/FxEditor'
import { defaultParameters, differsFromTemplate, draftOf, isTemplateLayerInstance } from '@/components/fx/fxEditorModel'
import { useEffectDetail } from '@/components/fixtureSheet/effectLabels'
import { effectsReaching } from '@/components/fixtureSheet/rowSource'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import {
  useActiveEffectsQuery,
  useEffectLibraryQuery,
  usePauseFxMutation,
  useRemoveFxMutation,
  useResumeFxMutation,
  type ActiveEffect,
} from '@/store/fixtureFx'
import { usePauseGroupFxMutation, useRemoveGroupFxMutation, useResumeGroupFxMutation } from '@/store/groups'
import { useMaster1Uuid } from '@/store/speedMasters'
import { useIsDeskConnected } from '@/store/status'
import { useTemplateListQuery } from '@/store/templates'
import type { Fixture } from '@/store/fixtures'
import { findEffectEntry, selectedHeadCount, type BuskingTarget } from './buskingTypes'

const EMPTY: readonly ActiveEffect[] = []
const EMPTY_FIXTURES: readonly Fixture[] = []

/**
 * The programmer effects reaching the busk selection (fixture-fx-sheets plan D20): on a selected
 * fixture or one of its heads, on a selected cell or its fixture, on a selected group or any of its
 * members — and on any group those fixtures are in. The busk tab is a programmer surface, so it
 * lists what the programmer runs — a pad's effect and the operator's own — and leaves a cue's to the
 * cue (the fixture sheet's tray lists it read-only). Pure, so the reach is a table a test can read.
 */
export function effectsOnSelection(
  effects: readonly ActiveEffect[] | undefined,
  selected: readonly BuskingTarget[],
  fixtures: readonly Fixture[],
): ActiveEffect[] {
  if (effects == null || selected.length === 0) return []
  const keys = new Set<string>()
  const groups = new Set<string>()
  const addFixture = (fixture: Fixture) => {
    keys.add(fixture.key)
    for (const element of fixture.elements ?? []) keys.add(element.key)
    for (const group of fixture.groups) groups.add(group)
  }
  for (const target of selected) {
    if (target.type === 'group') {
      groups.add(target.name)
      for (const fixture of fixtures) if (fixture.groups.includes(target.name)) addFixture(fixture)
    } else if (target.element != null) {
      keys.add(target.element.key)
      keys.add(target.fixture.key)
      for (const group of target.fixture.groups) groups.add(group)
    } else {
      addFixture(target.fixture)
    }
  }
  return effectsReaching(effects, [...keys], [...groups]).filter((e) => e.programmerOwned)
}

/**
 * The busk view's **Effects** tab — the side sheet's fifth, between Spread and Show
 * (fixture-fx-sheets plan D20, `BuskProgrammer.dc.html`). It lists what is running on the
 * selection — pad effects and the operator's own — with the tray's two-line rows, and a row's name
 * opens the live editor (`FxEditor`) under it, as the fixture sheet's tray does.
 *
 * **A pad's effect is edited as the running instance only.** The editor writes the instance
 * (`updateFx`), never the template; the row and the editor say ***edited*** while the instance
 * differs from the template's effect — the template list's summary DTO, which this tab reads once
 * for every row (`differsFromTemplate`) — and the editor's footer is **Update template** and **Reset
 * to template** in place of Revert. Pressing the pad off and on spawns the template's settings again.
 * A pad's effect has no stop here: the pad is what releases it, and stopping the instance alone would
 * leave the layer to respawn it at its next recook. The operator's own effect keeps pause and stop.
 */
export function EffectsSheet({ projectId, selectedTargets }: { projectId: number; selectedTargets: Map<string, BuskingTarget> }) {
  const { data: all } = useActiveEffectsQuery()
  const { fixtures: maybeFixtures, fixtureByKey } = useFixtureLookup()
  const fixtures = maybeFixtures ?? EMPTY_FIXTURES
  const selected = useMemo(() => [...selectedTargets.values()], [selectedTargets])
  const effects = useMemo(() => effectsOnSelection(all ?? EMPTY, selected, fixtures), [all, selected, fixtures])
  const heads = selectedHeadCount(selected)

  const { data: templates } = useTemplateListQuery({ projectId })
  const { data: library } = useEffectLibraryQuery()
  const master1Uuid = useMaster1Uuid()
  const templateById = useMemo(() => new Map((templates ?? []).map((t) => [t.id, t])), [templates])
  const edited = (e: ActiveEffect): boolean => {
    if (!isTemplateLayerInstance(e)) return false
    const effect = templateById.get(e.templateId!)?.effect
    if (effect == null) return false
    const entry = findEffectEntry(library, e.effectType)
    return differsFromTemplate(draftOf(e), effect, master1Uuid, entry ? defaultParameters(entry) : {}, {
      distribution: e.distributionStrategy != null,
    })
  }

  const connected = useIsDeskConnected()
  const effectDetail = useEffectDetail()
  const [editingId, setEditingId] = useState<number | null>(null)
  const [pauseFx] = usePauseFxMutation()
  const [resumeFx] = useResumeFxMutation()
  const [removeFx] = useRemoveFxMutation()
  const [pauseGroupFx] = usePauseGroupFxMutation()
  const [resumeGroupFx] = useResumeGroupFxMutation()
  const [removeGroupFx] = useRemoveGroupFxMutation()

  const togglePause = (e: ActiveEffect) => {
    const request = e.isGroupTarget
      ? (e.isRunning ? pauseGroupFx : resumeGroupFx)({ id: e.id, groupName: e.targetKey })
      : (e.isRunning ? pauseFx : resumeFx)({ id: e.id, fixtureKey: e.targetKey })
    request.unwrap().catch(() => {
      // Reported by errorToastMiddleware.
    })
  }
  /** A group's effect listed because a member is selected, not the group: its stop reaches further. */
  const viaGroup = (e: ActiveEffect) =>
    e.isGroupTarget && !selected.some((t) => t.type === 'group' && t.name === e.targetKey) ? e.targetKey : null
  const stop = (e: ActiveEffect) => {
    // It stops the effect on every member of the group, not only the selected heads — the fixture
    // sheet's tray asks first for the same case (Chris's call, session 2 review), and so does this.
    const group = viaGroup(e)
    if (group != null && !confirm(`Stop ${e.effectType} on every fixture in ${group}?`)) return
    const request = e.isGroupTarget ? removeGroupFx({ id: e.id, groupName: e.targetKey }) : removeFx({ id: e.id, fixtureKey: e.targetKey })
    request.unwrap().catch(() => {
      // Reported by errorToastMiddleware.
    })
    if (editingId === e.id) setEditingId(null)
  }
  /** Where an effect runs, by name — a fixture, a head or a group. */
  const onWhat = (e: ActiveEffect): string => {
    if (e.isGroupTarget) return e.targetKey
    const fixture = fixtureByKey.get(e.targetKey)
    if (fixture) return fixture.name
    for (const f of fixtures) {
      const element = f.elements?.find((el) => el.key === e.targetKey)
      if (element) return `${f.name} ${element.displayName}`
    }
    return e.targetKey
  }

  return (
    <div data-effects-sheet className="flex min-h-0 flex-col border-l bg-card/40">
      <div className="flex h-9 shrink-0 items-center gap-1.5 border-b px-3">
        <EditorLabel>Effects</EditorLabel>
        <span className="truncate text-[11px] text-muted-foreground">
          {selected.length === 0
            ? 'nothing selected'
            : `Running on ${heads} ${heads === 1 ? 'head' : 'heads'} · ${effects.length} ${effects.length === 1 ? 'effect' : 'effects'}`}
        </span>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto p-1.5" data-effects-list>
        {selected.length === 0 ? (
          <p className="px-2 py-3 text-xs text-muted-foreground">Select heads on the rig to see the effects running on them.</p>
        ) : effects.length === 0 ? (
          <p className="px-2 py-3 text-xs text-muted-foreground">
            Nothing runs on the selection — press an effect pad, or start one from a fixture sheet.
          </p>
        ) : null}
        {effects.map((e) => {
          const pad = e.programmerLayerId != null
          const isEditing = editingId === e.id
          const isEdited = edited(e)
          return (
            <Fragment key={e.id}>
              <div
                data-effect-row={e.id}
                className={cn(
                  'flex flex-col gap-0.5 rounded-lg border border-transparent px-2.5 py-1.5',
                  isEditing && 'border-violet-500/55 bg-violet-500/[0.08]',
                )}
              >
                <div className="flex items-center gap-1.5 text-[12.5px] font-medium">
                  <span className={cn('size-[7px] shrink-0 rounded-full', e.isRunning ? 'bg-violet-500' : 'bg-muted-foreground')} />
                  <button
                    type="button"
                    className="min-w-0 truncate text-left hover:underline"
                    aria-expanded={isEditing}
                    onClick={() => setEditingId(isEditing ? null : e.id)}
                  >
                    {e.effectType}
                  </button>
                  <span className="inline-flex h-4 shrink-0 items-center rounded border px-1 text-[9.5px] text-muted-foreground">
                    {effectDetail(e)}
                  </span>
                  {isEdited && <EditedMark template={e.sourceName ?? undefined} />}
                  <span className="flex-1" />
                  <button
                    type="button"
                    aria-label={e.isRunning ? `Pause ${e.effectType}` : `Resume ${e.effectType}`}
                    disabled={!connected}
                    onClick={() => togglePause(e)}
                    className="grid size-6 shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50"
                  >
                    {e.isRunning ? <Pause className="size-3" /> : <Play className="size-3" />}
                  </button>
                  {!pad && !isEditing && (
                    <button
                      type="button"
                      aria-label={`Stop ${e.effectType}`}
                      disabled={!connected}
                      onClick={() => stop(e)}
                      className="grid size-6 shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-destructive disabled:opacity-50"
                    >
                      <X className="size-3" />
                    </button>
                  )}
                </div>
                <div className="truncate pl-[13px] text-[11px] text-muted-foreground">
                  → {e.propertyName} · {viaGroup(e) != null ? `via ${e.targetKey}` : onWhat(e)} ·{' '}
                  {pad ? `pad${e.sourceName ? ` · ${e.sourceName}` : ''}` : 'programmer'}
                </div>
              </div>
              {isEditing && (
                <div data-effects-editor className="mx-1 mt-1 mb-2 rounded-lg border bg-background p-2.5">
                  <FxEditor
                    key={`${e.id}:${e.effectType}`}
                    effect={e}
                    onDone={() => setEditingId(null)}
                    onStop={pad ? undefined : () => stop(e)}
                  />
                </div>
              )}
            </Fragment>
          )
        })}
      </div>
    </div>
  )
}
