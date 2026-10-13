import { useMemo, useState } from 'react'
import { AlertTriangle, RotateCcw } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { EditorLabel } from '@/components/editor/EditorLabel'
import type {
  AddressFacetValue,
  FacetValue,
  FixturePatch,
  FocusFacetValue,
  MediaFacetValue,
  PatchFacet,
  PatchPlacementInput,
  PositionFacetValue,
  UniverseConfig,
} from '@/api/patchApi'
import { useKeepPatchFacetMutation, useSetPatchFacetMutation } from '@/store/patches'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { resolveFixtureKind, type FixtureTypeInfo } from '@/store/fixtures'
import { focusFields, LANTERN_FAMILY_KIND, type LanternIndex } from '@/lib/lanterns'
import type { GelIndex } from '@/lib/gels'
import { loadableSettings } from '@/lib/fittedMedia'
import { acceptsLength as typeAcceptsLength } from '@/lib/fixtureLength'
import { describeFacet, driftLine, FACET_LABEL, facetState, facetValueOf, type FacetNames } from '@/lib/patchFacets'
import { cn } from '@/lib/utils'
import { PatchPlacementFields } from './PatchPlacementFields'
import { FixtureLengthField, fixtureLengthValid } from './FixtureLengthField'
import { LanternBox } from './LanternBox'
import { MediaBox } from './MediaBox'
import { BeamAngleField } from './BeamAngleField'
import { GelPickerField } from './GelPickerField'

export interface FacetCardContext {
  projectId: number
  fixtureType: FixtureTypeInfo | undefined
  universes: readonly UniverseConfig[]
  lanterns: LanternIndex
  gels: GelIndex
  names: FacetNames
}

/**
 * One facet of a venue patch in a show — Address, Position, Focus or Media — with its **Venue | This
 * show** switch (venue plan session 5, `Show.dc.html`'s Edit Fixture sheet). *Venue* reads the
 * venue's value; *This show* opens the facet's fields, editable, and saves the facet whole
 * (`PUT …/facets/{facet}`); *Revert to venue* takes the venue's back. A drifted facet draws the drift
 * note — the venue changed it under the override — with *Keep mine* and *Take venue's*.
 *
 * Nothing writes until Save (or a revert): the draft is this card's. Refusals are the desk's, named
 * (`errorToastMiddleware` reports them with its words); an overlap an Address lands on is allowed and
 * said in a warning toast, as the Patch list rings it.
 */
export function VenueFacetCard({ facet, patch, ctx }: { facet: PatchFacet; patch: FixturePatch; ctx: FacetCardContext }) {
  const state = facetState(patch, facet)
  const overridden = state != null
  // The draft is seeded from the patch as the rig shows it — the show's value when overridden, the
  // venue's otherwise — and re-seeded when the stored value changes under it.
  const seed = useMemo(() => facetValueOf(patch, facet), [patch, facet])
  const [draft, setDraft] = useState<FacetValue>(seed)
  const [seedKey, setSeedKey] = useState(() => JSON.stringify(seed))
  const currentKey = JSON.stringify(seed)
  if (currentKey !== seedKey) {
    setSeedKey(currentKey)
    setDraft(seed)
  }
  const [opened, setOpened] = useState(false)
  const showing = overridden || opened
  const [setFacet, { isLoading: saving }] = useSetPatchFacetMutation()
  const [keepFacet, { isLoading: keeping }] = useKeepPatchFacetMutation()
  const busy = saving || keeping
  const dirty = !overridden || JSON.stringify(draft) !== currentKey
  const editable = facetEditable(facet, ctx.fixtureType)
  const valid = facetValid(facet, draft, ctx.fixtureType)

  const save = () => {
    setFacet({ projectId: ctx.projectId, patchId: patch.id, facet, value: draft })
      .unwrap()
      .then((answer) => {
        setOpened(false)
        for (const warning of answer.warnings ?? []) toast.warning('Address overlap', { description: warning })
      })
      .catch(ignoreReportedError)
  }
  const revert = () => {
    setFacet({ projectId: ctx.projectId, patchId: patch.id, facet, value: null })
      .unwrap()
      .then(() => setOpened(false))
      .catch(ignoreReportedError)
  }
  const keepMine = () => {
    keepFacet({ projectId: ctx.projectId, patchId: patch.id, facet }).unwrap().catch(ignoreReportedError)
  }

  const label = FACET_LABEL[facet]
  const venueValue = state?.venue ?? seed
  const drift = state ? driftLine(state, ctx.names) : null

  return (
    <section
      className={cn('space-y-2 rounded-md border p-2.5', overridden ? 'border-primary/50' : 'border-border')}
      aria-label={label}
      data-facet={facet}
      data-overridden={overridden || undefined}
      data-drifted={state?.drifted || undefined}
    >
      <div className="flex items-center gap-2">
        <EditorLabel className="flex-1">{label}</EditorLabel>
        <ToggleGroup
          type="single"
          size="sm"
          value={showing ? 'show' : 'venue'}
          aria-label={`${label}: whose`}
          onValueChange={(next) => {
            if (next === 'show' && !overridden) setOpened(true)
            else if (next === 'venue') {
              if (overridden) revert()
              else setOpened(false)
            }
          }}
          disabled={busy}
        >
          <ToggleGroupItem value="venue" className="text-xs">Venue</ToggleGroupItem>
          <ToggleGroupItem value="show" className="text-xs" disabled={!editable.ok} title={editable.ok ? undefined : editable.reason}>
            This show
          </ToggleGroupItem>
        </ToggleGroup>
      </div>

      {!showing && <p className="text-xs text-muted-foreground" data-facet-venue>{describeFacet(facet, venueValue, ctx.names)}</p>}

      {showing && (
        <>
          {overridden && (
            <p className="text-xs text-muted-foreground" data-facet-venue>
              Venue: {describeFacet(facet, venueValue, ctx.names)}
            </p>
          )}
          {state?.dormant && (
            <p className="text-xs text-amber-600 dark:text-amber-400">
              This override names a universe or rigging this show no longer has, so the venue&apos;s {label.toLowerCase()} stands.
            </p>
          )}
          {drift && (
            <div className="space-y-2 rounded-md border border-amber-500/50 bg-amber-500/10 p-2" role="status" data-drift-note>
              <p className="flex items-start gap-1.5 text-xs">
                <AlertTriangle className="mt-0.5 size-3.5 shrink-0 text-amber-600 dark:text-amber-400" aria-hidden />
                <span>
                  <b>The venue changed this under you.</b> {drift}. Yours is kept.
                </span>
              </p>
              <div className="flex gap-1.5">
                <Button variant="outline" size="sm" className="h-7" onClick={keepMine} disabled={busy}>
                  Keep mine
                </Button>
                <Button variant="outline" size="sm" className="h-7" onClick={revert} disabled={busy}>
                  <RotateCcw className="size-3.5" aria-hidden />
                  Take venue&apos;s
                </Button>
              </div>
            </div>
          )}
          <FacetFields facet={facet} patch={patch} draft={draft} onChange={setDraft} ctx={ctx} />
          <div className="flex items-center justify-end gap-2">
            {overridden ? (
              <Button variant="ghost" size="sm" className="h-7" onClick={revert} disabled={busy}>
                <RotateCcw className="size-3.5" aria-hidden />
                Revert to venue
              </Button>
            ) : (
              <Button variant="ghost" size="sm" className="h-7" onClick={() => { setOpened(false); setDraft(seed) }} disabled={busy}>
                Cancel
              </Button>
            )}
            <Button size="sm" className="h-7" onClick={save} disabled={busy || !dirty || !valid}>
              {overridden ? 'Save' : `Make ${label.toLowerCase()} this show's`}
            </Button>
          </div>
        </>
      )}
    </section>
  )
}

/** Whether this type has anything in [facet] to override, and why not when it has not. */
export function facetEditable(facet: PatchFacet, type: FixtureTypeInfo | undefined): { ok: true } | { ok: false; reason: string } {
  if (facet === 'FOCUS' && type && !type.acceptsLantern && !type.acceptsBeamAngle) {
    return { ok: false, reason: 'This type carries its own optics — there is no focus to change' }
  }
  if (facet === 'MEDIA' && type && !type.acceptsGel && loadableSettings(type.properties).length === 0) {
    return { ok: false, reason: 'This type takes no gel and has no loadable settings' }
  }
  return { ok: true }
}

function facetValid(facet: PatchFacet, draft: FacetValue, type: FixtureTypeInfo | undefined): boolean {
  if (facet === 'ADDRESS') {
    const v = draft as AddressFacetValue
    return Number.isInteger(v.startChannel) && v.startChannel >= 1
  }
  if (facet === 'POSITION' && typeAcceptsLength(type)) {
    const v = draft as PositionFacetValue
    return fixtureLengthValid(v.lengthM ?? null) && (v.placements ?? []).every((p) => fixtureLengthValid(p.lengthM ?? null))
  }
  return true
}

/** The facet's fields, the patch editor's own controls over the draft. */
function FacetFields({
  facet,
  patch,
  draft,
  onChange,
  ctx,
}: {
  facet: PatchFacet
  patch: FixturePatch
  draft: FacetValue
  onChange: (next: FacetValue) => void
  ctx: FacetCardContext
}) {
  const id = `facet-${patch.id}-${facet.toLowerCase()}`
  const type = ctx.fixtureType
  switch (facet) {
    case 'ADDRESS': {
      const v = draft as AddressFacetValue
      const channels = patch.channelCount ?? 1
      const last = v.startChannel + channels - 1
      return (
        <div className="grid grid-cols-2 gap-2">
          <div className="space-y-1">
            <Label htmlFor={`${id}-universe`} className="text-xs">Universe</Label>
            <Select
              value={String(v.universe ?? patch.universe)}
              onValueChange={(next) => {
                const u = ctx.universes.find((x) => String(x.universe) === next)
                onChange({ ...v, universe: Number(next), subnet: u?.subnet ?? 0 })
              }}
            >
              <SelectTrigger id={`${id}-universe`} className="h-8"><SelectValue /></SelectTrigger>
              <SelectContent>
                {ctx.universes.map((u) => (
                  <SelectItem key={u.id} value={String(u.universe)}>
                    {u.universe}{u.owner === 'VENUE' ? ' · venue' : ' · this show'}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="space-y-1">
            <Label htmlFor={`${id}-start`} className="text-xs">Start channel</Label>
            <Input
              id={`${id}-start`}
              type="number"
              min={1}
              max={512}
              className="h-8 font-mono"
              value={v.startChannel}
              onChange={(e) => onChange({ ...v, startChannel: Math.max(1, Number(e.target.value) || 1) })}
            />
          </div>
          <p className={cn('col-span-2 text-xs', last > 512 ? 'text-destructive' : 'text-muted-foreground')}>
            {last > 512 ? `Runs to channel ${last} — max start ${512 - channels + 1}` : `Channels ${v.startChannel}–${last}`}
          </p>
        </div>
      )
    }
    case 'POSITION': {
      const v = draft as PositionFacetValue
      return (
        <div className="space-y-2">
          <PatchPlacementFields
            projectId={ctx.projectId}
            idPrefix={id}
            value={{
              riggingUuid: v.riggingUuid ?? null,
              stageX: v.stageX ?? null,
              stageY: v.stageY ?? null,
              stageZ: v.stageZ ?? null,
              baseYawDeg: v.baseYawDeg ?? null,
              basePitchDeg: v.basePitchDeg ?? null,
              baseRollDeg: v.baseRollDeg ?? null,
            }}
            onChange={(next) => onChange({ ...v, ...next })}
          />
          {typeAcceptsLength(type) && (
            <FixtureLengthField
              id={`${id}-length`}
              value={v.lengthM ?? null}
              onChange={(lengthM) => onChange({ ...v, lengthM })}
              fallbackM={type?.lengthM ?? null}
              fallbackLabel="default"
            />
          )}
          {(v.placements?.length ?? 0) > 0 && (
            <p className="text-xs text-muted-foreground">
              Its other placements keep where they are in this override; the venue names them.
            </p>
          )}
        </div>
      )
    }
    case 'FOCUS': {
      const v = draft as FocusFacetValue
      if (!type?.acceptsLantern) {
        return <BeamAngleField id={`${id}-beam`} value={v.beamAngleDeg ?? null} onChange={(beamAngleDeg) => onChange({ ...v, beamAngleDeg })} />
      }
      const placements = mergePlacements(patch, v.placements, (p) => focusFields(p))
      const named = v.lanternType ? ctx.lanterns.byId.get(v.lanternType) ?? null : null
      const kind = named ? LANTERN_FAMILY_KIND[named.family] : resolveFixtureKind(patch.kindOverride, type?.kind)
      return (
        <LanternBox
          idPrefix={id}
          lanterns={ctx.lanterns}
          kind={kind}
          focus={v}
          onFocusChange={(next) => onChange({ ...v, ...focusFields(next) })}
          gelCode={null}
          beamAngleDeg={v.beamAngleDeg ?? null}
          onBeamAngleChange={(beamAngleDeg) => onChange({ ...v, beamAngleDeg })}
          lampWatts={v.lampWatts ?? null}
          lampKelvin={v.lampKelvin ?? null}
          onLampChange={(lamp) => onChange({ ...v, ...lamp })}
          placements={placements}
          onPlacementsChange={(next) =>
            onChange({ ...v, placements: next.map((p) => ({ placementUuid: p.uuid!, ...focusFields(p) })) })
          }
        />
      )
    }
    case 'MEDIA': {
      const v = draft as MediaFacetValue
      const settings = loadableSettings(type?.properties)
      const placements = mergePlacements(patch, v.placements, (p) => ({ media: p.media ?? null }))
      return (
        <div className="space-y-2">
          {type?.acceptsGel && (
            <GelPickerField id={`${id}-gel`} value={v.gelCode ?? null} onChange={(gelCode) => onChange({ ...v, gelCode })} />
          )}
          {settings.length > 0 && (
            <MediaBox
              settings={settings}
              gels={ctx.gels}
              media={v.media ?? null}
              onMediaChange={(media) => onChange({ ...v, media })}
              placements={placements}
              onPlacementsChange={(next) =>
                onChange({ ...v, placements: next.map((p) => ({ placementUuid: p.uuid!, media: p.media ?? null })) })
              }
            />
          )}
        </div>
      )
    }
  }
}

/**
 * The patch's placements as the editors take them, each carrying the draft's fields of the facet for
 * it — the patch's own placements otherwise. A placement is the venue's: its uuid and label never
 * change here.
 */
function mergePlacements<T extends { placementUuid: string }>(
  patch: FixturePatch,
  drafted: readonly T[] | undefined,
  fields: (p: T) => Partial<PatchPlacementInput>,
): PatchPlacementInput[] {
  return (patch.extraPlacements ?? []).map((p) => {
    const d = drafted?.find((x) => x.placementUuid === p.uuid)
    return { ...p, ...(d ? fields(d) : {}) }
  })
}
