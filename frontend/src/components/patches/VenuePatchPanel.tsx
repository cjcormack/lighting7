import { useMemo } from 'react'
import { Link } from 'react-router'
import { EyeOff, Landmark } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { SheetBody, SheetFooter, SheetHeader } from '@/components/ui/sheet'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { useProjectQuery } from '@/store/projects'
import { useSetPatchHiddenMutation, useUniverseConfigListQuery } from '@/store/patches'
import { useFixtureTypeListQuery } from '@/store/fixtures'
import { useRiggingListQuery } from '@/store/riggings'
import { useLanternIndex } from '@/hooks/useLanternIndex'
import { useGelIndex } from '@/hooks/useGelIndex'
import { ignoreReportedError } from '@/store/errorToastMiddleware'
import { PATCH_FACETS, type FixturePatch } from '@/api/patchApi'
import { VenueFacetCard, type FacetCardContext } from './VenueFacetCard'

/**
 * A **venue's** fixture opened from a show (venue plan sessions 3 and 5, `Show.dc.html`'s Edit
 * Fixture sheet): its identity — name, key, type, head — read-only, because it is the venue's, with a
 * way into the venue to change it for every show; its four **facets** — Address · Position · Focus ·
 * Media — each with a *Venue | This show* switch (`VenueFacetCard`), which makes that facet this
 * show's own; and *Hide in this show*.
 *
 * Mounted in place of `EditPatchForm` wherever a patch opens — the Patch list's sheet and the Stage
 * view's editor panel — so neither host offers a form for a row it cannot write. The Stage view's drag
 * of a venue patch is still refused (`VENUE_OWNED`): a Position override is a decision taken here,
 * whole, not a side effect of a drag.
 */
export function VenuePatchPanel({
  patch,
  projectId,
  onClose,
}: {
  patch: FixturePatch
  projectId: number
  onClose: () => void
}) {
  const { data: project } = useProjectQuery(projectId)
  const venue = project?.venue ?? null
  const { data: fixtureTypes } = useFixtureTypeListQuery()
  const { data: universes } = useUniverseConfigListQuery(projectId)
  const { data: riggings } = useRiggingListQuery(projectId)
  const lanterns = useLanternIndex()
  const gels = useGelIndex()
  const ctx = useMemo<FacetCardContext>(
    () => ({
      projectId,
      fixtureType: fixtureTypes?.find((t) => t.typeKey === patch.fixtureTypeKey),
      universes: universes ?? [],
      lanterns,
      gels,
      names: {
        universeOf: (uuid) => universes?.find((u) => u.uuid === uuid)?.universe,
        riggingName: (uuid) => riggings?.find((r) => r.uuid === uuid)?.name,
        lanternName: (id) => lanterns.byId.get(id)?.name,
      },
    }),
    [projectId, fixtureTypes, patch.fixtureTypeKey, universes, riggings, lanterns, gels],
  )
  const [setHidden, { isLoading }] = useSetPatchHiddenMutation()
  const type = [patch.manufacturer, patch.model].filter(Boolean).join(' ') || patch.fixtureTypeKey
  const hide = () => {
    // A refusal (`VENUE_HIDE_IN_USE`: a cue fires its tube) is toasted; the sheet stays open.
    setHidden({ projectId, patchId: patch.id, hidden: true })
      .unwrap()
      .then(onClose)
      .catch(ignoreReportedError)
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <SheetHeader>
        <h2 className="font-semibold text-foreground">{patch.displayName}</h2>
        <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
          <Landmark className="size-3.5" aria-hidden />
          {venue?.name ?? 'The venue'}
          {venue ? ` · v${venue.version}` : ''} · house fixture
        </p>
      </SheetHeader>
      <SheetBody>
        <section className="space-y-2" aria-label="Identity">
          <div className="flex items-center gap-2">
            <EditorLabel>Identity</EditorLabel>
            {venue?.id != null && (
              <Button asChild variant="link" size="sm" className="ml-auto h-auto p-0 text-xs">
                <Link to={`/projects/${venue.id}/patches`}>Edit in venue</Link>
              </Button>
            )}
          </div>
          <dl className="grid grid-cols-[88px_1fr] gap-x-3 gap-y-1.5 text-sm">
            <dt className="text-muted-foreground">Name</dt>
            <dd className="font-medium">{patch.displayName}</dd>
            <dt className="text-muted-foreground">Key</dt>
            <dd className="font-mono text-xs leading-5">{patch.key}</dd>
            <dt className="text-muted-foreground">Type</dt>
            <dd>
              {type}
              {patch.headNumber != null && <span className="text-muted-foreground"> · head {patch.headNumber}</span>}
            </dd>
          </dl>
          <p className="text-xs text-muted-foreground">
            The venue&rsquo;s. A show can hide this fixture, not rename or retype it. Its address, position,
            focus and media can each be this show&rsquo;s own below; to change it for every show, edit the
            venue and publish.
          </p>
        </section>
        {PATCH_FACETS.map((facet) => (
          <VenueFacetCard key={facet} facet={facet} patch={patch} ctx={ctx} />
        ))}
      </SheetBody>
      <SheetFooter className="flex-row justify-between">
        <Button variant="outline" onClick={hide} disabled={isLoading} title="Leave this fixture out of this show's rig — Hidden brings it back">
          <EyeOff className="mr-1.5 size-3.5" />
          Hide in this show
        </Button>
        <Button onClick={onClose}>Done</Button>
      </SheetFooter>
    </div>
  )
}
