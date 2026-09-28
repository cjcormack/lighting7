import { useCallback, useMemo, useState } from 'react'
import { useParams } from 'react-router'
import { toast } from 'sonner'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Plus } from 'lucide-react'
import { formatError } from '@/lib/formatError'
import { DEFERRED_TARGET_TYPE, type TemplateSummary } from '@/api/templatesApi'
import { useCreateTemplateMutation, useTemplateListQuery } from '@/store/templates'
import {
  serializeTemplateIntent,
  templateRowsSwatch,
  describeTemplateRows,
  templatePropertyFor,
  EMITTER_PROPERTIES,
} from '@/lib/templateIntent'
import { parseTemplateRefUuid, serializeTemplateRef } from './colourUtils'

/**
 * The colour templates an FX colour parameter may reference, and the row of chips that offers them.
 *
 * This is what replaced the positional palette row in both colour pickers. The difference that
 * matters to an operator is that a reference **has a name and follows its template**: retune "Warm
 * Key" and every running effect that names it moves, which is what the `P1` slots were reaching for
 * without an identity to hang it on.
 *
 * **Only generic colour templates are offered**, and that is a property of the mechanism rather than
 * a simplification: an effect's colour output is one colour applied to every head it targets, so
 * there is nothing for a fixture-agnostic output to take from a per-fixture template (eight heads
 * aimed at one spot hold eight different colours). `templateColourSource` on the backend refuses one
 * for the same reason.
 *
 * **A swatch here can lag a retune in *another* tab, and that is the documented tradeoff rather than
 * a bug.** `templatesWsApi` deliberately does not broadcast a contents change — only CRUD — so a
 * colour drag is not an invalidation storm behind an open editor. `saveTemplate` invalidates
 * `TemplateList`, so the tab doing the retune is current; the rig is current either way, since it
 * follows `TemplateRegistry.version` rather than anything on this side.
 */

/**
 * A template this picker can offer: the colour family, generic, and holding **values**.
 *
 * The third clause used to be `rows.length === 1`, because everything downstream read `rows[0]` and
 * only `rows[0]`. That is no longer true in either half. A colour template can now hold a hex *and*
 * explicit `white` / `amber` / `uv` rows, and both sides fold the whole set into one colour —
 * `templateRowsSwatch` here, `TemplateResolver.resolveColourGeneric` on the desk — so a multi-row
 * colour template means something exact to a single-colour output rather than "one of these".
 *
 * The old clause was also what excluded an **effect** template (fx-templates D12), by the accident
 * that it holds no rows at all; that file's own note said to put `kind !== 'effect'` in its place if
 * the count were ever relaxed, and this is that. The exclusion is deliberate and not a by-product:
 * an effect template is not a colour, has nothing for a fixture-agnostic colour output to take, and
 * `resolveTemplateColour` refuses one server-side for the same reason. The other direction stays
 * allowed and useful: an effect template's own colour parameter may name a **value** colour
 * template, which is what this picker offers it.
 */
/**
 * The one statement of which templates a colour *reference* may name — an FX colour parameter's
 * chips here, and the Spread tab's From/To picker (which adds a requirement of its own on top).
 */
export function isOfferableColourTemplate(template: TemplateSummary): boolean {
  return template.family === 'COLOUR' && template.isGeneric && template.kind === 'value'
}

/**
 * A template a **spread** endpoint may name: the offer rule above **and** a `rgbColour` row. The
 * desk's `spreadEndpoint` resolves a `tmpl:` through the template's colour row alone and answers
 * 400 `SPREAD_INVALID` for a template made only of emitter rows (a UV-only template is a legal
 * template but has no hex to interpolate), so one is not offered rather than refused after the
 * press. Here beside its sibling rather than in `editor/SpreadPanel.tsx`, which is store-free and
 * takes the filtered list from its host.
 */
export function isSpreadColourTemplate(template: TemplateSummary): boolean {
  return (
    isOfferableColourTemplate(template) &&
    (template.rows ?? []).some((row) => templatePropertyFor(row.propertyName)?.intent === 'colour')
  )
}
const isOfferable = isOfferableColourTemplate

export interface ColourTemplates {
  /** The offerable templates, in library order. Empty while loading, or outside a project. */
  templates: TemplateSummary[]
  /** The template a `tmpl:` value names, or null for a literal *or* a reference that no longer resolves. */
  templateFor: (value: string) => TemplateSummary | null
  /**
   * What to draw a `tmpl:` value as: its name, a wait marker while the library is still arriving, or
   * a plain marker once it has arrived without it. The three are distinct because "broken" and "not
   * loaded yet" look identical from here and only one of them is worth acting on.
   */
  labelFor: (value: string) => string
  /** The hex to draw a `tmpl:` value as, or null when it cannot be resolved. */
  swatchFor: (value: string) => string | null
}

/**
 * The offerable templates plus the three lookups a picker needs to render a reference.
 *
 * `projectId` comes from the route rather than a prop: the pickers are mounted from three different
 * sheets, none of which threads a project down, and the FX *library* page has no project at all —
 * which is what the `skip` is for. A picker there simply offers no templates and still edits
 * literals, so the form degrades rather than breaking.
 */
export function useColourTemplates(): ColourTemplates {
  const { projectId } = useParams<{ projectId: string }>()
  const projectIdNum = projectId ? Number(projectId) : NaN
  const { data, isLoading } = useTemplateListQuery(
    { projectId: projectIdNum, family: 'COLOUR' },
    { skip: !Number.isFinite(projectIdNum) },
  )

  const templates = useMemo(() => (data ?? []).filter(isOfferable), [data])
  const byUuid = useMemo(
    () => new Map(templates.map((t) => [t.uuid, t] as const)),
    [templates],
  )

  const templateFor = useCallback(
    (value: string) => {
      const uuid = parseTemplateRefUuid(value)
      return uuid ? byUuid.get(uuid) ?? null : null
    },
    [byUuid],
  )

  const labelFor = useCallback(
    // A first paint happens before the library query resolves, so an unmatched uuid is not evidence
    // of a dangling reference yet. Saying "Missing template" there reported every perfectly good
    // reference as broken for the width of one round-trip.
    (value: string) => templateFor(value)?.name ?? (isLoading ? 'Loading…' : 'Missing template'),
    [templateFor, isLoading],
  )

  const swatchFor = useCallback(
    (value: string) => {
      const template = templateFor(value)
      return template ? templateRowsSwatch(template.rows) : null
    },
    [templateFor],
  )

  return { templates, templateFor, labelFor, swatchFor }
}

/**
 * The Templates row inside a colour picker's popover: pick one, or mint one from the colour you have
 * just dialled in.
 *
 * The save affordance mirrors `TemplateStrip`'s new-from-selection chip, and for the same reason —
 * it is how the library fills up without anyone visiting `/templates`. It saves **what the picker is
 * showing**: the hex, plus a row per emitter the operator has dialled up. That means `policy=rgbonly`
 * rather than the `extract` it used to hard-code — an explicit white row and a policy that derives
 * one are refused together, and here the explicit value is the one the operator can actually see.
 * With no emitter dialled up there is nothing to conflict with, so the colour keeps `extract`, which
 * is what a wash wants and what the template editor can retune afterwards.
 */
export function FxColourTemplateRow({
  templates,
  /** The colour currently in the picker, offered as the body of a new template. */
  currentHex,
  /**
   * The emitter bytes currently in the picker, offered alongside [currentHex].
   *
   * Optional because two of the three pickers that mount this row have no emitter sliders to read —
   * the FX library page has no head to expand them off. Absent and all-zero mean the same thing
   * here: no emitter row, and the colour keeps its `extract` policy.
   */
  currentEmitters,
  onPick,
  /** Highlighted chip, when the parameter already references a template. */
  selectedUuid,
}: {
  templates: TemplateSummary[]
  currentHex: string
  currentEmitters?: Partial<Record<string, number>>
  onPick: (value: string) => void
  selectedUuid?: string | null
}) {
  const { projectId } = useParams<{ projectId: string }>()
  const projectIdNum = projectId ? Number(projectId) : NaN
  const [createTemplate, { isLoading: isCreating }] = useCreateTemplateMutation()
  const [naming, setNaming] = useState(false)
  const [name, setName] = useState('')

  const canCreate = Number.isFinite(projectIdNum)

  const handleCreate = useCallback(async () => {
    const trimmed = name.trim()
    if (!trimmed) return
    try {
      const emitters = EMITTER_PROPERTIES.filter((name) => (currentEmitters?.[name] ?? 0) > 0)
      const created = await createTemplate({
        projectId: projectIdNum,
        name: trimmed,
        rows: [
          {
            targetType: DEFERRED_TARGET_TYPE,
            targetKey: '',
            propertyName: 'rgbColour',
            value: serializeTemplateIntent({
              kind: 'colour',
              hex: currentHex,
              // An explicit white or amber row drives the emitter the policy would have derived, and
              // the write boundary refuses the pair. UV never conflicts, but sending `extract`
              // beside a UV row would still promise a white this template does not set.
              policy: emitters.length > 0 ? 'rgbonly' : 'extract',
            }),
          },
          ...emitters.map((name) => ({
            targetType: DEFERRED_TARGET_TYPE,
            targetKey: '',
            propertyName: name,
            value: serializeTemplateIntent({ kind: 'level', value: currentEmitters?.[name] ?? 0 }),
          })),
        ],
      }).unwrap()
      onPick(serializeTemplateRef(created.uuid))
      setNaming(false)
      setName('')
    } catch (err) {
      toast.error(formatError(err))
    }
  }, [createTemplate, currentEmitters, currentHex, name, onPick, projectIdNum])

  if (!canCreate && templates.length === 0) return null

  return (
    <div className="space-y-1.5 pt-2 border-t border-border">
      <p className="text-[10px] uppercase tracking-wide text-muted-foreground">Templates</p>
      {templates.length > 0 && (
        <div className="flex gap-1 flex-wrap">
          {templates.map((template) => {
            const hex = templateRowsSwatch(template.rows)
            return (
              <button
                key={template.uuid}
                type="button"
                title={`${template.name} — ${describeTemplateRows(template.rows)}`}
                onClick={() => onPick(serializeTemplateRef(template.uuid))}
                className={
                  'flex items-center gap-1 h-6 pl-1 pr-1.5 rounded border text-[11px] hover:bg-accent/50 transition-colors ' +
                  (template.uuid === selectedUuid ? 'border-primary' : 'border-border')
                }
              >
                <span
                  className="size-3.5 rounded-sm border border-border shrink-0"
                  style={{ backgroundColor: hex ?? 'transparent' }}
                />
                <span className="max-w-[9rem] truncate">{template.name}</span>
              </button>
            )
          })}
        </div>
      )}
      {canCreate && (naming ? (
        <div className="flex items-center gap-1">
          <Input
            autoFocus
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Template name"
            className="h-7 text-xs"
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault()
                handleCreate()
              } else if (e.key === 'Escape') {
                e.preventDefault()
                setNaming(false)
              }
            }}
          />
          <Button size="sm" className="h-7" disabled={!name.trim() || isCreating} onClick={handleCreate}>
            Save
          </Button>
        </div>
      ) : (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="h-6 px-1 text-[11px] text-muted-foreground"
          onClick={() => setNaming(true)}
        >
          <Plus className="size-3" />
          Save {currentHex} as template…
        </Button>
      ))}
    </div>
  )
}
