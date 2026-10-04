import type { FixturePatch } from '@/api/patchApi'
import type { SettingPropertyDescriptor } from '@/store/fixtures'
import { useGelIndex } from '@/hooks/useGelIndex'
import { findGel, type GelIndex } from '@/lib/gels'
import { hasFittedMedia, mediaOver, type FittedMedia } from '@/lib/fittedMedia'

interface FittedMediaListProps {
  patch: FixturePatch
  /** The type's loadable settings (`loadableSettings`). */
  settings: readonly SettingPropertyDescriptor[]
}

/**
 * What each unit of a fixture has loaded (fixture optics plan session 3, §4): the Stage view's
 * Focus tab draws it for a type with loadable settings, beside the note on what its channels drive.
 * Read-only — the patch sheet's Media box is where it is changed — and it names only what a unit
 * has fitted: a slot not listed holds the type's stock. A placement is listed with its own laid over
 * the patch's, as the view draws it.
 */
export function FittedMediaList({ patch, settings }: FittedMediaListProps) {
  const gels = useGelIndex()
  const placements = patch.extraPlacements ?? []
  const units = [
    { key: 'fixture', label: placements.length > 0 ? 'Unit 1' : null, media: patch.media ?? null },
    ...placements.map((pl, i) => ({
      key: pl.uuid,
      label: `Unit ${i + 2}${pl.label ? ` · ${pl.label}` : ''}`,
      media: mediaOver(pl.media, patch.media),
    })),
  ]
  return (
    <section className="space-y-2" aria-label="Fitted media" data-fitted-media>
      <p className="text-xs font-medium text-muted-foreground">Fitted media</p>
      {units.map((unit) => (
        <div key={unit.key} className="space-y-0.5" data-media-unit={unit.key}>
          {unit.label && <p className="text-xs font-medium">{unit.label}</p>}
          <UnitMedia media={unit.media} settings={settings} gels={gels} />
        </div>
      ))}
      <p className="text-xs text-muted-foreground">Set in the patch sheet&apos;s Media box. A slot not listed holds the stock.</p>
    </section>
  )
}

function UnitMedia({
  media,
  settings,
  gels,
}: {
  media: FittedMedia | null
  settings: readonly SettingPropertyDescriptor[]
  gels: GelIndex
}) {
  if (!hasFittedMedia(media)) return <p className="text-xs text-muted-foreground">Stock in every slot.</p>
  const rows = settings.flatMap((setting) => {
    const fitted = media.slots?.[setting.name] ?? {}
    return setting.options
      .map((option, i) => ({ option, index: i, slot: fitted[option.name] }))
      .filter((r) => r.slot)
      .map(({ option, index, slot }) => {
        const gel = slot!.gel ? findGel(gels, slot!.gel) : null
        const where = setting.media === 'GEL' && setting.options.length > 2 ? `frame ${index}` : option.displayName
        const content = slot!.gel
          ? gel ? `${gel.code} ${gel.name}` : slot!.gel
          : slot!.gobo
            ? `gobo ${slot!.gobo.replace(/_/g, ' ')}`
            : 'empty'
        return { key: `${setting.name}.${option.name}`, setting: setting.displayName, where, content, colour: gel?.color }
      })
  })
  return (
    <ul className="space-y-0.5">
      {rows.map((r) => (
        <li key={r.key} className="flex items-center gap-2 text-xs" data-media-row={r.key}>
          <span
            aria-hidden
            className="block size-3 shrink-0 rounded-sm border"
            style={{ background: r.colour ?? 'transparent', borderStyle: r.colour ? 'solid' : 'dashed' }}
          />
          <span className="text-muted-foreground">
            {r.setting} · {r.where}
          </span>
          <span className="min-w-0 truncate">{r.content}</span>
        </li>
      ))}
    </ul>
  )
}
