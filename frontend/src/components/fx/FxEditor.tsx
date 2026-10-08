import { useCallback, useEffect, useMemo, useRef, useState, type MutableRefObject } from 'react'
import { toast } from 'sonner'
import { Pause, Play, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { lightingApi } from '@/api/lightingApi'
import { groupMemberFixtures } from '@/lib/fxTargetProperties'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import {
  useEffectLibraryQuery,
  usePauseFxMutation,
  useResetFxToTemplateMutation,
  useResumeFxMutation,
  type ActiveEffect,
} from '@/store/fixtureFx'
import { useSaveTemplateMutation } from '@/store/templates'
import type { TemplateSummary } from '@/api/templatesApi'
import { usePauseGroupFxMutation, useResumeGroupFxMutation } from '@/store/groups'
import { useMaster1Uuid } from '@/store/speedMasters'
import { useIsDeskConnected } from '@/store/status'
import type { Fixture, SettingPropertyDescriptor } from '@/store/fixtures'
import { EditorReadout } from '../editor/EditorReadout'
import { EditorFooter } from '../editor/EditorFooter'
import { useLivePush } from '../editor/useLivePush'
import { findEffectEntry } from '../busking/buskingTypes'
import { detectExtendedChannels } from './colourUtils'
import { FxEffectFields } from './FxEffectFields'
import { useSpawningTemplate } from './useSpawningTemplate'
import {
  defaultParameters,
  differsFromTemplate,
  draftOf,
  positionAxesOf,
  withTemplateDraft,
  OPTIONAL_FIELDS,
  sameRequest,
  updateRequestOf,
  type FxDraft,
  type FxScope,
  type OptionalField,
} from './fxEditorModel'

/**
 * The live effect editor (fixture-fx-sheets plan D11, D12, D12a, D15; §3.3) — the old
 * `EffectParameterForm`'s parameter inputs rebuilt on the editor kit, and the one editor every
 * running effect is edited in: the FX tray's rows (inline), the programmer rail's *Edit…* and
 * `FxSheet`'s chips (both through `EditorSurface`), the add sheet after the picker, and the busk
 * view's Effects tab. Its fields (`FxEffectFields`) are `TemplateEditor`'s too, over a draft.
 *
 * **There is no Apply.** Every control writes as it moves through `useLivePush` — a 50 ms floor, a
 * dedupe on the whole write, the release always landing — into `updateFx`, which swaps the
 * parameters and keeps the effect's id and phase. A typed field commits on Enter. **Revert** sends
 * the settings the editor opened with, in one write; **Done** goes back to the host's list and
 * changes nothing; **×** stops the effect.
 *
 * The draft is the editor's own, seeded when it opens: the operator is the only writer while it is
 * open, and a refetch of the effect list (every write answers one) must not pull a slider back
 * under a finger. A host keys the editor on the effect's id *and* type, so the picker's type swap
 * opens a fresh one.
 *
 * Two questions replace the blend for the effects they are about: a movement effect's *Centre*
 * (D12 — Around is Additive with the centre pinned at 128 and hidden, Absolute is Override with the
 * centre shown) and a level effect's *Over the level underneath* (D12a — Replace is Override, Within
 * is Multiply). The blend itself stays under Advanced for every effect, so nothing it could reach
 * before is out of reach now.
 */
export function FxEditor({
  effect,
  onDone,
  onStop,
  flushRef,
  className,
}: {
  effect: ActiveEffect
  /** Back to the host's list. */
  onDone: () => void
  /**
   * × — the host stops the effect (and asks first where that is its rule). Absent, no × is drawn:
   * a pad's instance is stopped by pressing its pad off (the busk Effects tab), since stopping the
   * instance alone would leave the layer to respawn it on its next recook.
   */
  onStop?: () => void
  /**
   * Filled with "land whatever move the floor still holds", for a host that acts on the effect as
   * the editor goes — the add sheet absorbing it into a Look on close must write the last move first,
   * or the absorb takes the instance away and the unmount's flush reaches nothing.
   */
  flushRef?: MutableRefObject<(() => void) | null>
  className?: string
}) {
  const { data: library } = useEffectLibraryQuery()
  const entry = useMemo(() => findEffectEntry(library, effect.effectType), [library, effect.effectType])
  const { fixtures, fixtureByKey } = useFixtureLookup()
  const connected = useIsDeskConnected()
  const master1Uuid = useMaster1Uuid()

  const targetFixtures = useMemo((): Fixture[] => {
    if (effect.isGroupTarget) return groupMemberFixtures(fixtures, effect.targetKey)
    const fixture = fixtureByKey.get(effect.targetKey)
    return fixture ? [fixture] : []
  }, [effect.isGroupTarget, effect.targetKey, fixtures, fixtureByKey])

  const scope = useMemo((): FxScope => {
    const multiElement = targetFixtures.some((f) => (f.elements?.length ?? 0) > 1)
    if (effect.isGroupTarget) return { heads: true, elementMode: multiElement, elementFilter: multiElement }
    const heads = targetFixtures.some((f) => (f.elementGroupProperties?.length ?? 0) > 0)
    return { heads, elementMode: false, elementFilter: heads }
  }, [effect.isGroupTarget, targetFixtures])

  const axes = useMemo(() => (entry?.category === 'position' ? positionAxesOf(targetFixtures) : null), [entry, targetFixtures])
  const extendedChannels = useMemo(
    () => (entry?.category === 'colour' ? detectExtendedChannels(targetFixtures.map((f) => f.properties ?? [])) : undefined),
    [entry, targetFixtures],
  )
  const settingProperty = useMemo(() => {
    for (const fixture of targetFixtures) {
      const p = fixture.properties?.find((d) => d.name === effect.propertyName && d.type === 'setting')
      if (p) return p as SettingPropertyDescriptor
    }
    return undefined
  }, [targetFixtures, effect.propertyName])

  const [snapshot] = useState(() => draftOf(effect))
  const [draft, setDraft] = useState<FxDraft>(snapshot)
  const touched = useRef(false)
  /** The optional fields the operator has set — `updateRequestOf` sends only those. */
  const setFields = useRef(new Set<OptionalField>())

  // A write that never left the browser (the socket closed: `updateFx` toasts and answers false) is
  // forgotten as sent, so the next push or release of the same value goes out rather than being
  // deduplicated against a frame the desk never saw.
  const resetRef = useRef<() => void>(() => {})
  const send = useCallback((request: ReturnType<typeof updateRequestOf>) => {
    if (!lightingApi.fx.updateFx(effect.id, request)) resetRef.current()
  }, [effect.id])
  const live = useLivePush(send, { equals: sameRequest })
  resetRef.current = live.reset
  const latest = useRef<ReturnType<typeof updateRequestOf> | null>(null)
  const flushHeld = useCallback(() => {
    if (touched.current && latest.current) live.flush(latest.current)
  }, [live])
  if (flushRef) flushRef.current = flushHeld

  /** A move from a running gesture (`push`) or a release (`flush`). */
  const write = useCallback(
    (next: FxDraft, release: boolean, fields: readonly OptionalField[] = []) => {
      setDraft(next)
      touched.current = true
      for (const field of fields) setFields.current.add(field)
      const request = updateRequestOf(next, setFields.current)
      latest.current = request
      if (release) live.flush(request)
      else live.push(request)
    },
    [live],
  )

  // A move still held by the floor when the editor goes — a Done mid-drag, the host unmounting it —
  // lands rather than being dropped with `useLivePush`'s timer. Declared after the hook, so its
  // cleanup runs after the hook's has cleared the timer, and `flush` dedupes against what was sent.
  const liveRef = useRef(live)
  liveRef.current = live
  useEffect(
    () => () => {
      if (touched.current && latest.current) liveRef.current.flush(latest.current)
    },
    [],
  )

  const revert = () => {
    const request = updateRequestOf(snapshot, setFields.current, master1Uuid)
    // A master the frame could not put back stays drawn as the desk holds it: a rate master has no
    // spelling for *unscaled*, and a never-assigned speed master needs master 1's uuid, which may not
    // have arrived. Drawing the snapshot there would show a state the rig is not in.
    setDraft({
      ...snapshot,
      speedMasterUuid:
        setFields.current.has('speedMasterUuid') && request.speedMasterUuid == null ? draft.speedMasterUuid : snapshot.speedMasterUuid,
      rateSpeedMasterUuid:
        setFields.current.has('rateSpeedMasterUuid') && request.rateSpeedMasterUuid == null
          ? draft.rateSpeedMasterUuid
          : snapshot.rateSpeedMasterUuid,
    })
    latest.current = request
    live.flush(request)
  }

  const [pauseFx] = usePauseFxMutation()
  const [resumeFx] = useResumeFxMutation()
  const [pauseGroupFx] = usePauseGroupFxMutation()
  const [resumeGroupFx] = useResumeGroupFxMutation()
  const togglePause = () => {
    const request = effect.isGroupTarget
      ? (effect.isRunning ? pauseGroupFx : resumeGroupFx)({ id: effect.id, groupName: effect.targetKey })
      : (effect.isRunning ? pauseFx : resumeFx)({ id: effect.id, fixtureKey: effect.targetKey })
    request.unwrap().catch(() => {
      // Reported by errorToastMiddleware.
    })
  }

  const set = (patch: Partial<FxDraft>, release = true) =>
    write(
      { ...draft, ...patch },
      release,
      OPTIONAL_FIELDS.filter((field) => field in patch),
    )
  const setParam = (name: string, value: string, release: boolean) =>
    write({ ...draft, parameters: { ...draft.parameters, [name]: value } }, release)

  const readOnly = !connected
  const name = entry?.name ?? effect.effectType
  const dirty = JSON.stringify(draft) !== JSON.stringify(snapshot)
  // A pad's effect (§3.3, D20): edited against its template rather than against the open, and its
  // footer is the template's two verbs in place of Revert.
  const spawning = useSpawningTemplate(effect)
  const defaults = useMemo(() => (entry ? defaultParameters(entry) : {}), [entry])
  // The instance says its distribution only where it has one (a group, or heads); the operator
  // setting one here says it too.
  const hasDistribution = effect.distributionStrategy != null || setFields.current.has('distributionStrategy')
  const edited =
    spawning?.template.effect != null &&
    differsFromTemplate(draft, spawning.template.effect, master1Uuid, defaults, { distribution: hasDistribution })
  /** The desk put the instance back (or re-keyed it): the draft is what it now runs. */
  const reseed = useCallback(
    (next: ActiveEffect) => {
      live.reset()
      latest.current = null
      touched.current = false
      setDraft(draftOf(next))
    },
    [live],
  )

  return (
    <div data-fx-editor={effect.id} className={cn('flex flex-col gap-3', className)}>
      <div className="flex items-center gap-1.5">
        <span className="min-w-0 truncate text-[13px] font-medium">{name}</span>
        <span className="shrink-0 font-mono text-[11px] text-muted-foreground">→ {effect.propertyName}</span>
        {edited && <EditedMark template={spawning?.template.name} />}
        <span className="flex-1" />
        {/* Pause and resume, which every host's editor carries — the rail's and `FxSheet`'s had them
            on `ActiveEffectSheet`, and their rows have no other. */}
        <button
          type="button"
          aria-label={effect.isRunning ? `Pause ${name}` : `Resume ${name}`}
          disabled={readOnly}
          onClick={togglePause}
          className="grid size-6 shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground disabled:opacity-50"
        >
          {effect.isRunning ? <Pause className="size-3.5" /> : <Play className="size-3.5" />}
        </button>
        {onStop != null && (
          <button
            type="button"
            aria-label={`Stop ${name}`}
            disabled={readOnly}
            onClick={() => {
              // A move the floor still holds goes before the stop, never after it — sent from the
              // unmount it would reach a stopped effect and come back as `FX_NOT_FOUND`.
              flushHeld()
              onStop()
            }}
            className="grid size-6 shrink-0 place-items-center rounded-md text-muted-foreground hover:bg-accent hover:text-destructive disabled:opacity-50"
          >
            <X className="size-3.5" />
          </button>
        )}
      </div>

      {entry == null ? (
        <EditorReadout>
          {library == null
            ? 'Reading the effect library…'
            : `“${effect.effectType}” is not in this desk's effect library, so its settings cannot be drawn.`}
        </EditorReadout>
      ) : (
        <FxEffectFields
          entry={entry}
          timingSource={effect.timingSource}
          draft={draft}
          scope={scope}
          axes={axes}
          extendedChannels={extendedChannels}
          settingProperty={settingProperty}
          readOnly={readOnly}
          set={set}
          setParam={setParam}
        />
      )}

      <EditorFooter
        note={spawning != null ? 'Live — this running instance only' : 'Live — each change lands as you make it'}
        // The template's two verbs need more than a 320px sheet has beside the note: they wrap
        // under it, together, rather than running past the editor's edge.
        className={spawning != null ? 'flex-wrap' : undefined}
      >
        {spawning != null ? (
          <TemplateArm
            effect={effect}
            template={spawning.template}
            projectId={spawning.projectId}
            draft={draft}
            hasDistribution={hasDistribution}
            edited={edited}
            readOnly={readOnly}
            flushHeld={flushHeld}
            dropHeld={live.reset}
            reseed={reseed}
          />
        ) : (
          <Button variant="outline" size="sm" className="h-7" disabled={readOnly || !dirty} onClick={revert}>
            Revert
          </Button>
        )}
        <Button
          size="sm"
          className="h-7"
          onClick={() => {
            flushHeld()
            onDone()
          }}
        >
          Done
        </Button>
      </EditorFooter>
    </div>
  )
}

/**
 * *edited* — a pad's running instance differs from its template (D20). Amber, the dot's colour: a
 * press of the pad off and on would put the template's settings back, which is the thing to know.
 */
export function EditedMark({ template }: { template?: string }) {
  return (
    <span
      data-edited
      title={template ? `Differs from “${template}” — this running instance only` : 'Differs from its template'}
      className="inline-flex h-4 shrink-0 items-center rounded border border-amber-500/55 px-1 text-[9.5px] font-medium text-amber-700 dark:text-amber-400"
    >
      edited
    </span>
  )
}

/**
 * The footer for a pad's effect (fixture-fx-sheets plan §3.3, D20), in place of Revert. The edit
 * is **this running instance's only** — the template is untouched until **Update template** writes
 * these settings back to it (`PUT` the template's effect), and **Reset to template** puts the
 * template's settings back on the instance, phase kept (W5).
 *
 * Update template is the PUT **then** W5 on this instance: the PUT alone reaches an applied
 * instance at the stack's next recook by respawning it, phase restarted (session 1's amendment), and
 * the reset re-keys this one to the template's new entry so the recook keeps it. Every *other*
 * running instance of the template still holds the old settings, and reads *edited* until it is
 * reset by hand (`FU-TMPL-FX-EDIT-NO-RETIME`).
 */
function TemplateArm({
  effect,
  template,
  projectId,
  draft,
  hasDistribution,
  edited,
  readOnly,
  flushHeld,
  dropHeld,
  reseed,
}: {
  effect: ActiveEffect
  template: TemplateSummary
  projectId: number
  draft: FxDraft
  hasDistribution: boolean
  edited: boolean
  readOnly: boolean
  flushHeld: () => void
  dropHeld: () => void
  reseed: (next: ActiveEffect) => void
}) {
  const [saveTemplate, { isLoading: saving }] = useSaveTemplateMutation()
  const [resetFx, { isLoading: resetting }] = useResetFxToTemplateMutation()
  const busy = saving || resetting

  const update = async () => {
    if (template.effect == null) return
    // The move the floor still holds lands first, so the template takes what the rig shows.
    flushHeld()
    try {
      await saveTemplate({ projectId, templateId: template.id, effect: withTemplateDraft(template.effect, draft, { distribution: hasDistribution }) }).unwrap()
      reseed(await resetFx({ id: effect.id }).unwrap())
      toast.success(`Updated “${template.name}” from this ${effect.effectType}`)
    } catch {
      // Reported by errorToastMiddleware.
    }
  }
  const reset = async () => {
    // A held move would land after the reset and edit the instance again.
    dropHeld()
    try {
      reseed(await resetFx({ id: effect.id }).unwrap())
    } catch {
      // Reported by errorToastMiddleware.
    }
  }

  return (
    // Wraps within itself as well as under the note: at the busk sheet's 320px floor the two worded
    // buttons are wider than the editor card's row, so the second takes a line of its own rather
    // than running into the card's padding.
    <div data-template-arm className="ml-auto flex min-w-0 flex-wrap justify-end gap-1.5">
      <Button
        variant="outline"
        size="sm"
        className="h-7"
        disabled={readOnly || busy || !edited}
        title={`Write these settings to “${template.name}” — every pad and cue layering it follows`}
        onClick={() => void update()}
      >
        Update template
      </Button>
      <Button
        variant="outline"
        size="sm"
        className="h-7"
        disabled={readOnly || busy || !edited}
        title={`Put “${template.name}”'s settings back on this instance, phase kept`}
        onClick={() => void reset()}
      >
        Reset to template
      </Button>
    </div>
  )
}
