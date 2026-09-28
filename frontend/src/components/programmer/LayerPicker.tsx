import { useMemo, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { AudioWaveform, ChevronLeft, ChevronRight, Layers, Palette } from 'lucide-react'
import { useLookListQuery } from '@/store/looks'
import { useTemplateListQuery } from '@/store/templates'
import { useGroupListQuery } from '@/store/groups'
import { useFixtureListQuery } from '@/store/fixtures'
import { CueTargetPicker } from '@/components/cues/CueTargetPicker'
import { TimingFields } from '@/components/cues/TimingEditor'
import { SpeedMasterSelect } from '@/components/fx/SpeedMasterSelect'
import { useSpeedMasterLiveQuery } from '@/store/speedMasters'
import { FAMILY_LABELS, type AttributeFamily } from '@/lib/attributeFamily'
import { describeTemplateRows } from '@/lib/templateIntent'
import type { CueLayer, CueTarget } from '@/api/cuesApi'
import type { LookSummary } from '@/api/looksApi'
import type { TemplateSummary } from '@/api/templatesApi'

interface TimingValues {
  delayMs?: number | null
  intervalMs?: number | null
  randomWindowMs?: number | null
}

interface LayerPickerProps {
  projectId: number
  onConfirm: (layer: CueLayer) => void
  onCancel: () => void
  /** Pre-selected target — the layer skips the target step and lands on this one. */
  preselectedTarget?: CueTarget | null
  /**
   * Whether the host can honour a layer's timing.
   *
   * False for the programmer, which has no playback to delay against: a programmer layer fires now,
   * by definition, and `ProgrammerLookStack` drops `delayMs` / `intervalMs` / `randomWindowMs` on
   * the way out. Offering the fields there would take an operator's "in 3 seconds" and silently
   * ignore it, so they are not offered.
   */
  allowTiming?: boolean
  /**
   * Open on one library rather than both. The programmer rail's doors — `+ Look` and
   * `+ Template`, in its footer and behind the strip's `+` menu — are each named for a kind, and
   * a door that then offered the other kind too would make the name a lie.
   *
   * Unset, the first step offers both libraries. No production caller leaves it unset today:
   * `ProgrammerAddLayerSheet` is the only host and always names a kind, and the cue editor has
   * had no add-layer gesture since session 2a made a cue read-only. The general form is kept
   * because it is the shape a future host without kind-named doors would want, not because
   * anything reaches it.
   */
  kind?: 'look' | 'template'
}

type Step = 'look' | 'targets' | 'timing'

/**
 * Build one cue layer: which Look, over which targets, with what timing.
 *
 * **Source first, then targets**, which is the reverse of the preset picker this replaces, and the
 * reason is the targeting mode. A *template* needs targets — it names none — so the target step can
 * usefully disable the heads it cannot drive. A *Look* already names its own, so the target set
 * becomes a filter rather than a requirement, and "all of them" has to be offerable. Asking for
 * targets first cannot express either.
 *
 * Both libraries are offered here, because both are things a layer can apply. The sections are the
 * two entities rather than the old bound/deferred split of one — which is the same split, now that
 * the two halves have their own tables.
 */
export function LayerPicker({
  projectId,
  onConfirm,
  onCancel,
  preselectedTarget,
  allowTiming = true,
  kind,
}: LayerPickerProps) {
  const { data: looks } = useLookListQuery({ projectId })
  const { data: templates } = useTemplateListQuery({ projectId })
  const { data: groups } = useGroupListQuery()
  const { data: fixtures } = useFixtureListQuery()
  const { data: liveMasters } = useSpeedMasterLiveQuery()

  const [step, setStep] = useState<Step>('look')
  /**
   * What this layer will apply — a Look or a template, never both.
   *
   * One state rather than two, so "which did they pick?" has one answer and the steps after it do not
   * have to agree about precedence.
   */
  const [source, setSource] = useState<
    { kind: 'look'; look: LookSummary } | { kind: 'template'; template: TemplateSummary } | null
  >(null)
  const [targets, setTargets] = useState<CueTarget[]>(
    preselectedTarget ? [preselectedTarget] : [],
  )
  const [timingValues, setTimingValues] = useState<TimingValues>({
    delayMs: null,
    intervalMs: null,
    randomWindowMs: null,
  })
  // Per-layer overrides. Null means "each of the Look's effects follows its own master" —
  // deliberately distinct from picking M1, which pins every effect in this layer to the global
  // master. When the operator opens the override, the state is seeded with a CONCRETE uuid
  // (master 1's) immediately: the select would otherwise *display* M1 without any onChange
  // firing, and confirming would silently save no override at all.
  const [speedMasterUuid, setSpeedMasterUuid] = useState<string | null>(null)
  // Independent of the speed override above, and seeded the same way for the same reason: a
  // wall-clock effect's rate master is a separate axis, so pinning one must not imply anything
  // about the other.
  const [rateSpeedMasterUuid, setRateSpeedMasterUuid] = useState<string | null>(null)

  /**
   * Targets the chosen source cannot drive.
   *
   * Two different questions, answered two different ways, and that is the point:
   *
   *  - a **Look** is filtered on `compatibleLookIds`, which is now **capability-only** (D6): "does
   *    this head have colour at all", never "was this authored against that model". It only matters
   *    for a Look whose *effects* are deferred, since those are what the layer's targets supply; a
   *    Look with no deferred effects names its own heads and the question does not apply.
   *  - a **template** is filtered from the target's own `capabilities`, with no server list at all.
   *    A template's family is derived and exact, so the client can answer this itself — which is why
   *    there is no `compatibleTemplateIds` to keep in step.
   */
  const disabledKeys = useMemo(() => {
    const disabled = new Map<string, string>()
    if (source == null) return disabled

    if (source.kind === 'look') {
      if (!source.look.hasDeferredEffects) return disabled
      for (const group of groups ?? []) {
        if (!group.compatibleLookIds.includes(source.look.id)) {
          disabled.set(`group:${group.name}`, 'not compatible')
        }
      }
      for (const fixture of fixtures ?? []) {
        if (!fixture.compatibleLookIds.includes(source.look.id)) {
          disabled.set(`fixture:${fixture.key}`, 'not compatible')
        }
      }
      return disabled
    }

    const required = capabilityForFamily(source.template.family)
    if (required == null) return disabled
    for (const group of groups ?? []) {
      if (!group.capabilities.includes(required)) {
        disabled.set(`group:${group.name}`, `no ${required}`)
      }
    }
    for (const fixture of fixtures ?? []) {
      if (!fixture.capabilities.includes(required)) {
        disabled.set(`fixture:${fixture.key}`, `no ${required}`)
      }
    }
    return disabled
  }, [source, groups, fixtures])

  const handleSelectLook = (next: LookSummary) => {
    setSource({ kind: 'look', look: next })
    // A preselected target means the operator came from that target's card and has already said
    // where this goes.
    setStep(preselectedTarget ? 'timing' : 'targets')
  }

  const handleSelectTemplate = (next: TemplateSummary) => {
    setSource({ kind: 'template', template: next })
    setStep(preselectedTarget ? 'timing' : 'targets')
  }

  const handleSelectTarget = (target: CueTarget) => {
    setTargets([target])
    setStep('timing')
  }

  const useLooksOwnTargets = () => {
    setTargets([])
    setStep('timing')
  }

  const handleConfirm = () => {
    if (source == null) return
    onConfirm({
      // Exactly one, which is the DTO's write contract.
      lookId: source.kind === 'look' ? source.look.id : undefined,
      templateId: source.kind === 'template' ? source.template.id : undefined,
      // A template layer is masked to its own family, so it asserts nothing outside it. Stated here
      // rather than left to the server: the layer row shows the mask, and an unmasked template layer
      // would read as "this could touch anything".
      propertyMask: source.kind === 'template' ? (source.template.family ?? undefined) : undefined,
      targets,
      delayMs: timingValues.delayMs,
      intervalMs: timingValues.intervalMs,
      randomWindowMs: timingValues.randomWindowMs,
      speedMasterUuid,
      rateSpeedMasterUuid,
    })
  }

  return (
    <div className="flex flex-col h-full">
      {step === 'look' && (
        <>
          <div className="flex items-center gap-2 px-4 pt-4 pb-2">
            <button onClick={onCancel} className="hover:bg-accent rounded p-0.5 -ml-1">
              <ChevronLeft className="size-5" />
            </button>
            <div>
              <h3 className="font-medium text-sm">
                {kind === 'template' ? 'Choose a template' : 'Choose a look'}
              </h3>
              <p className="text-xs text-muted-foreground">
                Layers compose in order — a later one wins over an earlier one.
              </p>
            </div>
          </div>

          <div className="flex-1 overflow-y-auto">
            <div className="flex flex-col gap-1 p-4 pt-0">
              {(kind === 'template' || (looks?.length ?? 0) === 0) &&
                (kind === 'look' || (templates?.length ?? 0) === 0) && (
                  <div className="py-8 text-center text-sm text-muted-foreground">
                    {kind === 'look'
                      ? 'No looks yet. Record one from the programmer.'
                      : kind === 'template'
                        ? 'No templates yet. Create one in the template library.'
                        : 'Nothing to layer yet. Record a look from the programmer, or create a template.'}
                  </div>
                )}
              {kind !== 'template' && (
                <LookGroup
                  title="Looks"
                  hint="These name their own fixtures. Targets narrow them."
                  looks={looks ?? []}
                  onSelect={handleSelectLook}
                />
              )}
              {kind !== 'look' && (
                <TemplateGroup
                  title="Templates"
                  hint="One value or effect each. These take their fixtures from the layer."
                  templates={templates ?? []}
                  onSelect={handleSelectTemplate}
                />
              )}
            </div>
          </div>
        </>
      )}

      {step === 'targets' && source && (
        <>
          <div className="flex items-center gap-2 px-4 pt-4 pb-2">
            <button
              onClick={() => {
                setSource(null)
                setStep('look')
              }}
              className="hover:bg-accent rounded p-0.5 -ml-1"
            >
              <ChevronLeft className="size-5" />
            </button>
            <div>
              <h3 className="font-medium text-sm">{sourceName(source)}</h3>
              <p className="text-xs text-muted-foreground">
                {source.kind === 'template'
                  ? 'Choose the fixture or group this template should land on.'
                  : 'Narrow this look to one fixture or group, or use the ones it already names.'}
              </p>
            </div>
          </div>

          {source.kind === 'look' && (
            <div className="px-4 pb-2">
              <Button variant="outline" size="sm" className="w-full" onClick={useLooksOwnTargets}>
                Use the look&rsquo;s own {source.look.targetCount} fixture
                {source.look.targetCount === 1 ? '' : 's'}
              </Button>
            </div>
          )}

          <div className="flex-1 overflow-y-auto">
            <CueTargetPicker onSelect={handleSelectTarget} disabledKeys={disabledKeys} />
          </div>
        </>
      )}

      {step === 'timing' && source && (
        <>
          <div className="flex items-center gap-2 px-4 pt-4 pb-2">
            <button
              onClick={() => setStep(preselectedTarget ? 'look' : 'targets')}
              className="hover:bg-accent rounded p-0.5 -ml-1"
            >
              <ChevronLeft className="size-5" />
            </button>
            <div>
              <h3 className="font-medium text-sm">{sourceName(source)}</h3>
              <p className="text-xs text-muted-foreground">
                {targets.length === 0
                  ? 'On the fixtures the look itself names.'
                  : `On ${targets.map((t) => t.key).join(', ')}.`}
              </p>
            </div>
          </div>

          <div className="flex-1 overflow-y-auto px-4 pb-4 space-y-4">
            {allowTiming && <TimingFields values={timingValues} onChange={setTimingValues} />}

            {/* Per-layer speed master. Opt-in: unset means every effect in the look follows its
                own master, and most layers want exactly that. Opening seeds master 1's concrete
                uuid so what the select shows is what gets saved — confirm-without-touching must
                not silently drop the pin. */}
            {speedMasterUuid == null ? (
              <button
                type="button"
                disabled={!liveMasters?.length}
                onClick={() => {
                  const master1 = liveMasters?.find((m) => m.index === 1)
                  if (master1?.uuid) setSpeedMasterUuid(master1.uuid)
                }}
                className="text-xs text-muted-foreground hover:text-foreground transition-colors disabled:opacity-50"
              >
                + Override speed master for this layer
              </button>
            ) : (
              <div className="space-y-1.5">
                <SpeedMasterSelect value={speedMasterUuid} onChange={setSpeedMasterUuid} />
                <button
                  type="button"
                  onClick={() => setSpeedMasterUuid(null)}
                  className="text-xs text-muted-foreground hover:text-foreground transition-colors"
                >
                  Remove override — each effect follows its own master
                </button>
              </div>
            )}

            {/* Wall-clock rate master, same opt-in shape. Separate control because the two apply
                to different effects: a look can hold both beat and wall-clock effects, and pinning
                one axis says nothing about the other. */}
            {rateSpeedMasterUuid == null ? (
              <button
                type="button"
                disabled={!liveMasters?.length}
                onClick={() => {
                  const master1 = liveMasters?.find((m) => m.index === 1)
                  if (master1?.uuid) setRateSpeedMasterUuid(master1.uuid)
                }}
                className="text-xs text-muted-foreground hover:text-foreground transition-colors disabled:opacity-50"
              >
                + Override rate master for this layer
              </button>
            ) : (
              <div className="space-y-1.5">
                <SpeedMasterSelect
                  value={rateSpeedMasterUuid}
                  onChange={setRateSpeedMasterUuid}
                  label="Rate master"
                  description="Only wall-clock effects in this look are affected."
                />
                <button
                  type="button"
                  onClick={() => setRateSpeedMasterUuid(null)}
                  className="text-xs text-muted-foreground hover:text-foreground transition-colors"
                >
                  Remove override — each effect follows its own rate master
                </button>
              </div>
            )}
          </div>

          <div className="border-t p-4 flex items-center gap-2">
            <div className="flex-1" />
            <Button variant="outline" onClick={onCancel}>
              Cancel
            </Button>
            <Button onClick={handleConfirm}>Add layer</Button>
          </div>
        </>
      )}
    </div>
  )
}

function LookGroup({
  title,
  hint,
  looks,
  onSelect,
}: {
  title: string
  hint: string
  looks: LookSummary[]
  onSelect: (look: LookSummary) => void
}) {
  if (looks.length === 0) return null
  return (
    <div className="space-y-1">
      <div className="pt-2">
        <p className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
          {title}
        </p>
        <p className="text-[11px] text-muted-foreground">{hint}</p>
      </div>
      {looks.map((look) => (
        <button
          key={look.id}
          onClick={() => onSelect(look)}
          className="flex items-center gap-2 p-3 rounded-md border text-left hover:bg-accent/50 transition-colors"
        >
          <Layers className="size-4 text-muted-foreground shrink-0" />
          <div className="flex-1 min-w-0">
            <div className="text-sm font-medium truncate">{look.name}</div>
            <div className="mt-0.5 flex flex-wrap items-center gap-1">
              {look.families.map((family) => (
                <Badge key={family} variant="outline" className="px-1.5 py-0 text-[10px]">
                  {FAMILY_LABELS[family].singular}
                </Badge>
              ))}
              <span className="text-[10px] text-muted-foreground">
                {look.rowCount} row{look.rowCount === 1 ? '' : 's'}
                {look.effectCount > 0 &&
                  ` · ${look.effectCount} effect${look.effectCount === 1 ? '' : 's'}`}
              </span>
            </div>
          </div>
          <ChevronRight className="size-4 text-muted-foreground shrink-0" />
        </button>
      ))}
    </div>
  )
}

/**
 * The template half of the source list.
 *
 * A separate component rather than a generic one over both, because the two rows show different
 * things: a Look shows its families (several, derived) and its counts, a template shows its one
 * family, its actual value and whether it is generic or per fixture. A shared row would have to
 * render the union and mean less in both places.
 */
function TemplateGroup({
  title,
  hint,
  templates,
  onSelect,
}: {
  title: string
  hint: string
  templates: TemplateSummary[]
  onSelect: (template: TemplateSummary) => void
}) {
  if (templates.length === 0) return null
  return (
    <div className="space-y-1">
      <div className="pt-2">
        <p className="text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
          {title}
        </p>
        <p className="text-[11px] text-muted-foreground">{hint}</p>
      </div>
      {templates.map((template) => (
        <button
          key={template.id}
          onClick={() => onSelect(template)}
          className="flex items-center gap-2 p-3 rounded-md border text-left hover:bg-accent/50 transition-colors"
        >
          <Palette className="size-4 text-muted-foreground shrink-0" />
          {/* Beside the palette rather than instead of it, as `LookNameBadge` draws it: an effect
              template is a template that holds an effect, not a third kind of row. */}
          {template.kind === 'effect' && (
            <AudioWaveform className="size-4 text-muted-foreground shrink-0 -ml-1" />
          )}
          <div className="flex-1 min-w-0">
            <div className="text-sm font-medium truncate">{template.name}</div>
            <div className="mt-0.5 flex flex-wrap items-center gap-1">
              {template.family != null && (
                <Badge variant="outline" className="text-[10px] px-1.5 py-0">
                  {FAMILY_LABELS[template.family].singular}
                </Badge>
              )}
              <span className="text-[11px] text-muted-foreground">
                {/* An effect template is always generic (D3), so "Generic" would be true and
                    uninformative — what it holds is the thing worth saying here. */}
                {template.kind === 'effect'
                  ? (template.effect?.effectType ?? 'Effect')
                  : template.isGeneric
                    ? 'Generic'
                    : `Per fixture · ${template.rows?.length ?? 0}`}
              </span>
              {(template.rows?.length ?? 0) > 0 && (
                <span className="font-mono text-[10px] tabular-nums text-muted-foreground">
                  {/* The whole row set, not `rows[0]`: a colour template may hold a hex plus
                      explicit white / amber / uv rows, and row order is authoring order — so
                      naming the first row would describe a template by whichever part of it
                      happened to be saved first. */}
                  {describeTemplateRows(template.rows)}
                </span>
              )}
            </div>
          </div>
          <ChevronRight className="size-4 text-muted-foreground shrink-0" />
        </button>
      ))}
    </div>
  )
}

/** The chosen source's operator-facing name, whichever kind it is. */
function sourceName(
  source: { kind: 'look'; look: LookSummary } | { kind: 'template'; template: TemplateSummary },
): string {
  return source.kind === 'look' ? source.look.name : source.template.name
}

/**
 * The one capability a family needs, or null when there is nothing to check.
 *
 * BEAM answers null deliberately: a gobo wheel and a zoom are separate channels with no single
 * capability flag between them, and the backend skips a property a fixture lacks. Disabling a head
 * for "beam" would refuse heads that can do the specific role the template holds.
 */
function capabilityForFamily(family: AttributeFamily | null): string | null {
  switch (family) {
    case 'INTENSITY':
      return 'dimmer'
    case 'COLOUR':
      return 'colour'
    case 'POSITION':
      return 'position'
    default:
      return null
  }
}
