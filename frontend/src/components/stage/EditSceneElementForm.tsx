import { forwardRef, useImperativeHandle, useMemo, useState } from 'react'
import { toast } from 'sonner'
import { Plus, Trash2, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { SheetHeader, SheetBody, SheetFooter } from '@/components/ui/sheet'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { EditorLabel } from '@/components/editor/EditorLabel'
import { MovesWithList } from '@/components/scenery/MovesWithList'
import type { MovesWithEntry } from '@/lib/scenery'
import { useDeleteStageElementMutation, useUpdateStageElementMutation } from '@/store/stageElements'
import { useStageRegionListQuery } from '@/store/stageRegions'
import type { StageElementDto } from '@/api/stageElementApi'
import { formatError } from '@/lib/formatError'
import { parseNullableNumber } from '@/lib/utils'
import { elementKindLabel } from '../stage3d/edit/sceneryKinds'
import { elementProblems, fileProblems, type ElementProblem } from './elementProblems'
import {
  draftOf,
  elementUpdate,
  emptyNumbers,
  nextAisleSeat,
  statesOf,
  withKindParam,
  withParam,
  withState,
  type Draft,
  type Params,
} from './elementDraft'
import { OwnerEditor } from './OwnerEditor'
import { PaintField } from './PaintField'
import { useSceneImageListQuery, useSetElementDisplayDetailMutation } from '@/store/sceneImages'
import { paintOf, withPaintSide, type PaintSide, type SceneImageInfo } from '@/api/sceneImageApi'

/**
 * A scene element's form (stage-view plan session 5, `Edit.dc.html` §3): the body the docked
 * `StageEditorPanel` shows for a selected piece of scenery or venue, beside the region's and the
 * rigging's. The kind decides the fields — every kind's `params` as the desk's `ElementParams`
 * spells them — and a save is one partial `PUT` of what changed, `params` whole.
 *
 * **The desk checks it, not this form.** A write goes through `validateStageElement`, the check
 * `set_scene` makes, and a refusal comes back as a 400 listing every problem at once; each is drawn
 * beside the field it names (`elementProblems.ts`), and one naming no field at the top. The form
 * checks nothing itself beyond a name, so the two surfaces cannot disagree about what is allowed.
 *
 * *Moves with* lists what moves the piece (scenery-programmer plan D11, session 4): every cue that
 * changes it, every stack whose set holds it, every Look that shows it — from `GET
 * stage-elements/{id}/scenery` — and each entry opens its owner's own editor in place (Cue
 * properties, Stack settings, the Look sheet, `OwnerEditor`), since a scenery change is edited on
 * its owner and never here. Beside the base states, a piece that travels — a DRAW or FLY drape, a
 * flown object — takes its **travel time** (`travelS`, D6): how long a full travel takes when no
 * cue's own clock moves it. `withKindParam` drops it with the travel.
 *
 * *Fabric and paint* (scrim plan §4, session 1): a drape's **Fabric** — velour unless it says
 * otherwise (D1) — and on a drape or a flat the two painted faces (`PaintField`, D4), each an image
 * in the desk's store named by its hash in `params.paint`. **Full detail** is this machine's switch
 * for a hero cloth's 4096 px copy (D12): it is not part of the element, so it writes at once through
 * `PUT …/display-detail` rather than waiting for Save. The Stage view draws both (session 2): only
 * velour pleats, so a drape's **Depth** says it folds velour only ([DRAPE_DEPTH_HINT]).
 */

interface EditSceneElementFormProps {
  element: StageElementDto
  projectId: number
  onClose: () => void
}

export interface EditSceneElementFormHandle {
  /** A drag on a section moved the element: show where it is now without saving anything. */
  setPosition: (next: { positionX: number; positionY: number; positionZ: number }) => void
}

const SIDES = ['DOWNSTAGE', 'UPSTAGE', 'STAGE_LEFT', 'STAGE_RIGHT', 'FLOOR', 'CEILING'] as const
const EDGES = ['DOWNSTAGE', 'UPSTAGE', 'STAGE_LEFT', 'STAGE_RIGHT'] as const
const PATTERNS = ['PLAIN', 'PANELS', 'TILES', 'BOARDS'] as const
const DRAPE_ROLES = ['LEG', 'BORDER', 'TABS', 'CYC', 'BACKCLOTH'] as const
const DRAPE_OPERATIONS = ['DEAD', 'DRAW', 'FLY'] as const
const OPENING_KINDS = ['DOOR', 'WINDOW', 'FRENCH_WINDOW', 'ARCH'] as const
const OBJECT_SHAPES = ['BOX', 'CYLINDER', 'SHADE', 'DISC'] as const
const CHAIR_STYLES = ['THEATRE', 'BANQUET'] as const

/** What a drape's Depth means (scrim plan §4): the pleats', and only velour has them (D2). */
export const DRAPE_DEPTH_HINT = 'The depth of the pleats. Only velour folds; every other fabric hangs flat.'

/** A drape's fabric (scrim plan D1): absent is velour, the only one that pleats. */
const DRAPE_FABRICS = ['CANVAS', 'MUSLIN', 'SHARKSTOOTH', 'BOBBINET'] as const
const FABRIC_LABELS: Record<string, string> = {
  CANVAS: 'Canvas',
  MUSLIN: 'Muslin (translucent)',
  SHARKSTOOTH: 'Sharkstooth scrim',
  BOBBINET: 'Bobbinet scrim',
}

/** `STAGE_LEFT` → `Stage left`. */
function words(value: string): string {
  const lower = value.toLowerCase().replace(/_/g, ' ')
  return lower.charAt(0).toUpperCase() + lower.slice(1)
}

function num(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function str(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

/** The desk's `STAGE_ELEMENT_IN_USE`: seat views still look from a seating this write reshapes. */
function inUseMessage(err: unknown): string | null {
  const e = err as { status?: unknown; data?: { code?: unknown; error?: unknown } } | null
  if (e?.status !== 409 || e.data?.code !== 'STAGE_ELEMENT_IN_USE') return null
  return typeof e.data.error === 'string' ? e.data.error : 'Seat views still look from this seating.'
}

export const EditSceneElementForm = forwardRef<EditSceneElementFormHandle, EditSceneElementFormProps>(
  function EditSceneElementForm({ element, projectId, onClose }, ref) {
    // Callers re-key on the element's uuid, so the form mounts fresh per element.
    const [draft, setDraft] = useState<Draft>(() => draftOf(element))
    const [problems, setProblems] = useState<ElementProblem[]>([])
    const [inUse, setInUse] = useState<{ message: string; action: 'save' | 'delete' } | null>(null)
    // The *Moves with* entry whose owner's editor is open over the form.
    const [owner, setOwner] = useState<Pick<MovesWithEntry, 'kind' | 'id'> | null>(null)
    const [updateElement, { isLoading: isUpdating }] = useUpdateStageElementMutation()
    const [deleteElement, { isLoading: isDeleting }] = useDeleteStageElementMutation()
    const { data: regions } = useStageRegionListQuery(projectId, { skip: element.kind !== 'PLATFORM' })
    const paintable = element.kind === 'DRAPE' || element.kind === 'FLAT'
    const { data: imageList, isSuccess: imagesLoaded } = useSceneImageListQuery(projectId, { skip: !paintable })
    const images = useMemo(() => new Map<string, SceneImageInfo>((imageList ?? []).map((i) => [i.hash, i])), [imageList])
    const [setDisplayDetail, { isLoading: isSettingDetail }] = useSetElementDisplayDetailMutation()
    // The switch writes at once, so it keeps its own state rather than the draft's; a refusal puts it back.
    const [fullDetail, setFullDetail] = useState(element.fullDetail === true)

    useImperativeHandle(ref, () => ({ setPosition: (next) => setDraft((prev) => ({ ...prev, ...next })) }), [])

    const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft((prev) => ({ ...prev, [key]: value }))
    const setParam = (key: string, value: unknown) =>
      setDraft((prev) => ({ ...prev, params: withKindParam(element.kind, prev.params, key, value) }))
    const setStateValue = (key: string, value: unknown) =>
      setDraft((prev) => ({ ...prev, params: withState(prev.params, key, value) }))

    const kind = element.kind
    const params = draft.params
    const states = statesOf(params)
    const operation = str(params.operation).toUpperCase()
    const drawn = kind === 'DRAPE' && operation === 'DRAW'
    const flies = (kind === 'OBJECT' && params.flies === true) || (kind === 'DRAPE' && operation === 'FLY')

    // Every path a field below draws its own problems beside; the rest are the form's.
    const shown = useMemo(() => {
      const paths = ['name', 'layer', 'positionX', 'positionY', 'positionZ', 'yawDeg', 'finishColour', 'finishPattern']
      if (kind !== 'SEATING') paths.push('widthM', 'depthM', 'heightM')
      const p = (keys: string[]) => keys.forEach((k) => paths.push(`params.${k}`))
      p(['states.visible'])
      if (drawn) p(['states.open'])
      if (flies) p(['states.trimM'])
      if (drawn || flies) p(['travelS'])
      switch (kind) {
        case 'ROOM':
          p(['omit', 'floor.colour', 'floor.pattern', 'ceiling.colour', 'ceiling.pattern'])
          break
        case 'PROSCENIUM':
          p(['openingWidthM', 'openingHeightM', 'openingSillM', 'surroundM'])
          break
        case 'FLAT': {
          const openings = Array.isArray(params.openings) ? params.openings : []
          openings.forEach((_, i) =>
            p([`openings[${i}]`, ...['kind', 'fromM', 'widthM', 'heightM', 'sillM'].map((k) => `openings[${i}].${k}`)]),
          )
          p(['paint', 'paint.front', 'paint.back'])
          break
        }
        case 'DRAPE':
          p(['role', 'operation', 'fabric', 'paint', 'paint.front', 'paint.back'])
          break
        case 'PLATFORM':
          p(['railHeightM', 'railEdge', 'regionUuid'])
          break
        case 'SEATING': {
          p(['rows', 'seatsPerRow', 'rowPitchM', 'seatPitchM', 'firstRow', 'rakeM', 'chair', 'frameColour', 'aisles'])
          const aisles = Array.isArray(params.aisles) ? params.aisles : []
          aisles.forEach((_, i) => p([`aisles[${i}]`, `aisles[${i}].afterSeat`, `aisles[${i}].widthM`]))
          break
        }
        case 'OBJECT':
          p(['shape', 'flies'])
          break
      }
      return new Set(paths)
    }, [kind, params.openings, params.aisles, drawn, flies])
    const filed = fileProblems(problems, shown)
    const isBusy = isUpdating || isDeleting
    const isValid = draft.name.trim().length > 0

    const handleSave = async (force = false) => {
      if (!isValid) return
      // Every pose and size field is required; an empty one is said beside it, and nothing is sent.
      const empty = emptyNumbers(element.kind, draft)
      if (empty.length > 0) {
        setProblems(empty.map((path) => ({ path, message: 'Enter a number' })))
        return
      }
      setProblems([])
      const body = elementUpdate(element, draft)
      if (Object.keys(body).length === 0) {
        onClose()
        return
      }
      try {
        await updateElement({ projectId, elementId: element.id, force, ...body }).unwrap()
        toast.success(`${draft.name.trim()} saved`)
        onClose()
      } catch (err) {
        const status = (err as { status?: unknown } | null)?.status
        const conflict = inUseMessage(err)
        if (conflict) setInUse({ message: conflict, action: 'save' })
        else if (status === 400 || status === 409) setProblems(elementProblems(formatError(err)))
        else toast.error(`Failed to save ${element.name}: ${formatError(err)}`)
      }
    }

    const handleDelete = async (force = false) => {
      try {
        await deleteElement({ projectId, elementId: element.id, force }).unwrap()
        toast.success(`${element.name} deleted`)
        onClose()
      } catch (err) {
        const conflict = inUseMessage(err)
        if (conflict) setInUse({ message: conflict, action: 'delete' })
        else toast.error(`Failed to delete ${element.name}: ${formatError(err)}`)
      }
    }

    const numberField = (id: string, label: string, value: number | null, onChange: (v: number | null) => void, path: string) => (
      <Field key={id} id={id} label={label} errors={filed.at(path)}>
        <Input
          id={id}
          type="number"
          value={value ?? ''}
          onChange={(e) => onChange(parseNullableNumber(e.target.value))}
          onFocus={(e) => e.target.select()}
          aria-invalid={filed.at(path).length > 0 || undefined}
        />
      </Field>
    )
    const paramNumber = (key: string, label: string) =>
      numberField(`element-${key}`, label, num(params[key]), (v) => setParam(key, v), `params.${key}`)
    const paramSelect = (key: string, label: string, options: readonly string[], none?: string) => (
      <Field id={`element-${key}`} label={label} errors={filed.at(`params.${key}`)}>
        <NativeSelect
          id={`element-${key}`}
          value={str(params[key]).toUpperCase()}
          onChange={(v) => setParam(key, v || null)}
          options={options}
          none={none}
        />
      </Field>
    )

    const paint = paintOf(params)
    const setPaint = (side: PaintSide, hash: string | null) => setParam('paint', withPaintSide(paint, side, hash))
    const toggleFullDetail = async (full: boolean) => {
      setFullDetail(full)
      try {
        await setDisplayDetail({ projectId, elementId: element.id, full }).unwrap()
      } catch {
        // The middleware has said why; put the switch back where the desk has it.
        setFullDetail(!full)
      }
    }

    const openings = (Array.isArray(params.openings) ? params.openings : []) as Params[]
    const aisles = (Array.isArray(params.aisles) ? params.aisles : []) as Params[]
    const nextAisle = nextAisleSeat(num(params.seatsPerRow), aisles)
    const setAisle = (i: number, key: string, value: unknown) =>
      setParam(
        'aisles',
        aisles.map((a, j) => (j === i ? withParam(a, key, value) : a)),
      )
    const setOpening = (i: number, key: string, value: unknown) =>
      setParam(
        'openings',
        openings.map((o, j) => (j === i ? withParam(o, key, value) : o)),
      )

    return (
      <div className="flex flex-1 min-h-0 flex-col">
        <SheetHeader>
          <h2 className="text-foreground font-semibold">{element.name}</h2>
          <p className="text-xs text-muted-foreground">
            {elementKindLabel({ kind, params })} · metres, FOH-relative, Z up
            {kind === 'PLATFORM' ? ' (Z is the deck’s top)' : ''}
          </p>
        </SheetHeader>

        <SheetBody>
          {filed.general.length > 0 && (
            <Alert variant="destructive" role="alert">
              <AlertDescription>
                <ul className="list-disc space-y-0.5 pl-4">
                  {filed.general.map((m) => (
                    <li key={m}>{m}</li>
                  ))}
                </ul>
              </AlertDescription>
            </Alert>
          )}

          <Field id="element-name" label="Name" errors={filed.at('name')}>
            <Input id="element-name" value={draft.name} onChange={(e) => set('name', e.target.value)} />
          </Field>

          <Field id="element-layer" label="Layer" errors={filed.at('layer')}>
            <ToggleGroup
              id="element-layer"
              type="single"
              size="sm"
              value={draft.layer}
              onValueChange={(v) => {
                if (v === 'VENUE' || v === 'SET') set('layer', v)
              }}
              aria-label="Layer"
            >
              <ToggleGroupItem value="VENUE">Venue</ToggleGroupItem>
              <ToggleGroupItem value="SET">Set</ToggleGroupItem>
            </ToggleGroup>
          </Field>

          <Section title="Where">
            <div className="grid grid-cols-3 gap-2">
              {numberField('element-x', 'X', draft.positionX, (v) => set('positionX', v), 'positionX')}
              {numberField('element-y', 'Y', draft.positionY, (v) => set('positionY', v), 'positionY')}
              {numberField('element-z', kind === 'PLATFORM' ? 'Z (top)' : 'Z', draft.positionZ, (v) => set('positionZ', v), 'positionZ')}
            </div>
            {numberField('element-yaw', 'Yaw (deg)', draft.yawDeg, (v) => set('yawDeg', v), 'yawDeg')}
            {kind !== 'SEATING' && (
              <div className="grid grid-cols-3 gap-2">
                {numberField('element-w', 'Width', draft.widthM, (v) => set('widthM', v), 'widthM')}
                {numberField('element-d', 'Depth', draft.depthM, (v) => set('depthM', v), 'depthM')}
                {numberField('element-h', 'Height', draft.heightM, (v) => set('heightM', v), 'heightM')}
              </div>
            )}
            {kind === 'DRAPE' && <p className="text-[11px] leading-snug text-muted-foreground">{DRAPE_DEPTH_HINT}</p>}
          </Section>

          <Section title={kindLabelFor(kind)}>
            {kind === 'ROOM' && (
              <>
                <Field id="element-omit" label="Sides left out" errors={filed.at('params.omit')}>
                  <div className="flex flex-wrap gap-1" id="element-omit">
                    {SIDES.map((side) => {
                      const omit = (Array.isArray(params.omit) ? params.omit : []) as string[]
                      const on = omit.includes(side)
                      return (
                        <Button
                          key={side}
                          type="button"
                          size="sm"
                          variant={on ? 'default' : 'outline'}
                          aria-pressed={on}
                          className="h-7 px-2 text-xs"
                          onClick={() =>
                            setParam('omit', on ? omit.filter((s) => s !== side) : [...omit, side])
                          }
                        >
                          {words(side)}
                        </Button>
                      )
                    })}
                  </div>
                </Field>
                {(['floor', 'ceiling'] as const).map((surface) => {
                  const finish = (params[surface] ?? {}) as Params
                  const setFinish = (key: string, value: unknown) => {
                    const next = withParam(finish, key, value)
                    setParam(surface, Object.keys(next).length > 0 ? next : null)
                  }
                  return (
                    <div key={surface} className="grid grid-cols-2 gap-2">
                      <Field id={`element-${surface}-colour`} label={`${words(surface)} colour`} errors={filed.at(`params.${surface}.colour`)}>
                        <ColourInput id={`element-${surface}-colour`} value={str(finish.colour)} onChange={(v) => setFinish('colour', v)} />
                      </Field>
                      <Field id={`element-${surface}-pattern`} label={`${words(surface)} pattern`} errors={filed.at(`params.${surface}.pattern`)}>
                        <NativeSelect
                          id={`element-${surface}-pattern`}
                          value={str(finish.pattern).toUpperCase()}
                          onChange={(v) => setFinish('pattern', v || null)}
                          options={PATTERNS}
                          none="As the room"
                        />
                      </Field>
                    </div>
                  )
                })}
              </>
            )}
            {kind === 'PROSCENIUM' && (
              <div className="grid grid-cols-2 gap-2">
                {paramNumber('openingWidthM', 'Opening width')}
                {paramNumber('openingHeightM', 'Opening height')}
                {paramNumber('openingSillM', 'Sill')}
                {paramNumber('surroundM', 'Surround')}
              </div>
            )}
            {kind === 'FLAT' && (
              <div className="space-y-2">
                {openings.length === 0 && <p className="text-xs text-muted-foreground">No doors or windows.</p>}
                {openings.map((o, i) => (
                  <div key={i} className="space-y-1.5 rounded-md border p-2" data-opening={i}>
                    <div className="flex items-center gap-2">
                      <NativeSelect
                        id={`element-opening-${i}-kind`}
                        value={str(o.kind).toUpperCase()}
                        onChange={(v) => setOpening(i, 'kind', v || null)}
                        options={OPENING_KINDS}
                        none="Kind…"
                        aria-label={`Opening ${i + 1} kind`}
                      />
                      <Button
                        type="button"
                        size="icon"
                        variant="ghost"
                        className="size-7 shrink-0"
                        aria-label={`Remove opening ${i + 1}`}
                        onClick={() => setParam('openings', openings.filter((_, j) => j !== i))}
                      >
                        <X className="size-3.5" />
                      </Button>
                    </div>
                    <FieldErrors errors={[...filed.at(`params.openings[${i}]`), ...filed.at(`params.openings[${i}].kind`)]} />
                    <div className="grid grid-cols-4 gap-1.5">
                      {(['fromM', 'widthM', 'heightM', 'sillM'] as const).map((key) =>
                        numberField(
                          `element-opening-${i}-${key}`,
                          { fromM: 'From', widthM: 'Width', heightM: 'Height', sillM: 'Sill' }[key],
                          num(o[key]),
                          (v) => setOpening(i, key, v),
                          `params.openings[${i}].${key}`,
                        ),
                      )}
                    </div>
                  </div>
                ))}
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  onClick={() =>
                    setParam('openings', [...openings, { kind: 'DOOR', fromM: 0.2, widthM: 0.9, heightM: 2.1 }])
                  }
                >
                  <Plus className="mr-1 size-3.5" />
                  Opening
                </Button>
              </div>
            )}
            {kind === 'DRAPE' && (
              <div className="grid grid-cols-2 gap-2">
                {paramSelect('role', 'Role', DRAPE_ROLES)}
                {paramSelect('operation', 'Moves by', DRAPE_OPERATIONS, 'Dead (default)')}
                <div className="col-span-2">
                  <Field id="element-fabric" label="Fabric" errors={filed.at('params.fabric')}>
                    <NativeSelect
                      id="element-fabric"
                      value={str(params.fabric).toUpperCase()}
                      onChange={(v) => setParam('fabric', v || null)}
                      options={DRAPE_FABRICS}
                      labels={FABRIC_LABELS}
                      none="Velour (default)"
                    />
                  </Field>
                </div>
              </div>
            )}
            {kind === 'PLATFORM' && (
              <>
                <div className="grid grid-cols-2 gap-2">
                  {paramNumber('railHeightM', 'Rail height')}
                  {paramSelect('railEdge', 'Rail on', EDGES, 'No rail')}
                </div>
                <Field id="element-regionUuid" label="Deck of region" errors={filed.at('params.regionUuid')}>
                  <select
                    id="element-regionUuid"
                    className={SELECT_CLASS}
                    value={str(params.regionUuid)}
                    onChange={(e) => setParam('regionUuid', e.target.value || null)}
                  >
                    <option value="">None</option>
                    {(regions ?? []).map((r) => (
                      <option key={r.uuid} value={r.uuid}>
                        {r.name}
                      </option>
                    ))}
                  </select>
                </Field>
              </>
            )}
            {kind === 'SEATING' && (
              <div className="grid grid-cols-2 gap-2">
                {paramNumber('rows', 'Rows')}
                {paramNumber('seatsPerRow', 'Seats per row')}
                {paramNumber('rowPitchM', 'Row pitch')}
                {paramNumber('seatPitchM', 'Seat pitch')}
                <Field id="element-firstRow" label="First row" errors={filed.at('params.firstRow')}>
                  <Input
                    id="element-firstRow"
                    value={str(params.firstRow)}
                    maxLength={1}
                    placeholder="A"
                    onChange={(e) => setParam('firstRow', e.target.value.toUpperCase() || null)}
                  />
                </Field>
                {paramNumber('rakeM', 'Rake per row')}
                <Field id="element-chair" label="Chair" errors={filed.at('params.chair')}>
                  <NativeSelect
                    id="element-chair"
                    value={str(params.chair).toUpperCase() || 'THEATRE'}
                    onChange={(v) => setParam('chair', v === 'THEATRE' ? null : v)}
                    options={CHAIR_STYLES}
                  />
                </Field>
                <Field id="element-frameColour" label="Frame colour" errors={filed.at('params.frameColour')}>
                  <ColourInput
                    id="element-frameColour"
                    value={str(params.frameColour)}
                    placeholder="Chair's own"
                    onChange={(v) => setParam('frameColour', v || null)}
                  />
                </Field>
                <div className="col-span-2 space-y-2">
                  <FieldErrors errors={filed.at('params.aisles')} />
                  {aisles.map((a, i) => (
                    <div key={i} className="space-y-1" data-aisle={i}>
                      <div className="flex items-end gap-1.5">
                        <div className="grid flex-1 grid-cols-2 gap-1.5">
                          {numberField(`element-aisle-${i}-afterSeat`, 'Aisle after seat', num(a.afterSeat), (v) => setAisle(i, 'afterSeat', v), `params.aisles[${i}].afterSeat`)}
                          {numberField(`element-aisle-${i}-widthM`, 'Aisle width', num(a.widthM), (v) => setAisle(i, 'widthM', v), `params.aisles[${i}].widthM`)}
                        </div>
                        <Button
                          type="button"
                          size="icon"
                          variant="ghost"
                          className="mb-0.5 size-7 shrink-0"
                          aria-label={`Remove aisle ${i + 1}`}
                          onClick={() => setParam('aisles', aisles.length > 1 ? aisles.filter((_, j) => j !== i) : null)}
                        >
                          <X className="size-3.5" />
                        </Button>
                      </div>
                      <FieldErrors errors={filed.at(`params.aisles[${i}]`)} />
                    </div>
                  ))}
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    disabled={nextAisle == null}
                    title={nextAisle == null ? 'Every gap between two seats has an aisle' : undefined}
                    onClick={() => nextAisle != null && setParam('aisles', [...aisles, { afterSeat: nextAisle, widthM: 1 }])}
                  >
                    <Plus className="mr-1 size-3.5" />
                    Aisle
                  </Button>
                </div>
              </div>
            )}
            {kind === 'OBJECT' && (
              <div className="grid grid-cols-2 items-end gap-2">
                {paramSelect('shape', 'Shape', OBJECT_SHAPES)}
                <Check id="element-flies" label="Flies" checked={params.flies === true} onChange={(v) => setParam('flies', v || null)} errors={filed.at('params.flies')} />
              </div>
            )}
          </Section>

          {paintable && (
            <Section title="Paint">
              {(['front', 'back'] as const).map((side) => (
                <div key={side} className="space-y-1">
                  <PaintField
                    projectId={projectId}
                    side={side}
                    hash={paint[side] ?? null}
                    images={images}
                    imagesLoaded={imagesLoaded}
                    widthM={draft.widthM}
                    heightM={draft.heightM}
                    onChange={(hash) => setPaint(side, hash)}
                    onMatchHeight={(h) => set('heightM', h)}
                  />
                  <FieldErrors errors={filed.at(`params.paint.${side}`)} />
                </div>
              ))}
              <FieldErrors errors={filed.at('params.paint')} />
              <Check
                id="element-full-detail"
                label="Full detail (4096 px, this machine)"
                checked={fullDetail}
                disabled={isSettingDetail}
                onChange={(v) => void toggleFullDetail(v)}
              />
              <p className="text-[11px] leading-snug text-muted-foreground">
                A hero cloth&apos;s sharper copy, on this desk only. It changes at once, without Save.
              </p>
            </Section>
          )}

          <Section title="Finish">
            <div className="grid grid-cols-2 gap-2">
              <Field id="element-colour" label="Colour" errors={filed.at('finishColour')}>
                <ColourInput id="element-colour" value={draft.finishColour} onChange={(v) => set('finishColour', v)} />
              </Field>
              <Field id="element-pattern" label="Pattern" errors={filed.at('finishPattern')}>
                <NativeSelect
                  id="element-pattern"
                  value={draft.finishPattern.toUpperCase()}
                  onChange={(v) => set('finishPattern', v)}
                  options={PATTERNS}
                  none="Plain (default)"
                />
              </Field>
            </div>
            <Check id="element-emissive" label="Glows (emissive)" checked={draft.emissive} onChange={(v) => set('emissive', v)} />
          </Section>

          <Section title="State when nothing moves it">
            <Check
              id="element-visible"
              label="Visible"
              checked={states.visible !== false}
              onChange={(v) => setStateValue('visible', v ? null : false)}
              errors={filed.at('params.states.visible')}
            />
            {drawn && numberField('element-open', 'Open (0 closed – 1 drawn)', num(states.open), (v) => setStateValue('open', v), 'params.states.open')}
            {flies && numberField('element-trim', 'Trim (m)', num(states.trimM), (v) => setStateValue('trimM', v), 'params.states.trimM')}
            {(drawn || flies) && (
              <>
                {paramNumber('travelS', 'Travel time (s, a full travel)')}
                <p className="text-[11px] leading-snug text-muted-foreground">
                  How long the piece takes to travel all the way when no cue&apos;s own clock moves it — a pressed Look, a
                  stack&apos;s set, the programmer. A shorter move takes its share. Blank snaps.
                </p>
              </>
            )}
          </Section>

          <Section title="Moves with">
            <MovesWithList projectId={projectId} element={element} onOpen={(entry) => setOwner(entry)} className="-mx-1" />
            <p className="text-[11px] leading-snug text-muted-foreground">
              Scenery changes are edited on the cue, the stack or the Look, never here.
            </p>
          </Section>

          <Check id="element-hidden" label="Hidden in the Stage view" checked={draft.hidden} onChange={(v) => set('hidden', v)} />
        </SheetBody>

        <SheetFooter className="flex-row justify-between">
          <Button variant="destructive" size="sm" onClick={() => void handleDelete()} disabled={isBusy}>
            <Trash2 className="mr-1.5 size-3.5" />
            {isDeleting ? 'Deleting...' : 'Delete'}
          </Button>
          <div className="flex gap-2">
            <Button variant="outline" onClick={onClose} disabled={isBusy}>
              Cancel
            </Button>
            <Button onClick={() => void handleSave()} disabled={!isValid || isBusy}>
              {isUpdating ? 'Saving...' : 'Save'}
            </Button>
          </div>
        </SheetFooter>

        <OwnerEditor projectId={projectId} entry={owner} onClose={() => setOwner(null)} />

        <AlertDialog open={inUse != null} onOpenChange={(open) => !open && setInUse(null)}>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>{inUse?.action === 'delete' ? `Delete ${element.name}?` : `Save ${element.name}?`}</AlertDialogTitle>
              <AlertDialogDescription>{inUse?.message}</AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>Keep it</AlertDialogCancel>
              <AlertDialogAction
                onClick={() => {
                  const action = inUse?.action
                  setInUse(null)
                  if (action === 'delete') void handleDelete(true)
                  else void handleSave(true)
                }}
              >
                {inUse?.action === 'delete' ? 'Delete anyway' : 'Save anyway'}
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    )
  },
)

function kindLabelFor(kind: StageElementDto['kind']): string {
  switch (kind) {
    case 'ROOM':
      return 'Room'
    case 'PROSCENIUM':
      return 'Opening'
    case 'FLAT':
      return 'Doors and windows'
    case 'DRAPE':
      return 'Drape'
    case 'PLATFORM':
      return 'Platform'
    case 'SEATING':
      return 'Seats'
    case 'OBJECT':
      return 'Object'
  }
}

const SELECT_CLASS =
  'h-9 w-full rounded-md border border-input bg-transparent px-2 text-sm shadow-xs outline-none focus-visible:ring-[3px] focus-visible:ring-ring/50'

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="space-y-2 border-t pt-3">
      <EditorLabel>{title}</EditorLabel>
      {children}
    </section>
  )
}

function Field({ id, label, errors, children }: { id: string; label: string; errors?: string[]; children: React.ReactNode }) {
  return (
    <div className="min-w-0 space-y-1">
      <Label htmlFor={id} className="text-xs font-normal text-muted-foreground">
        {label}
      </Label>
      {children}
      <FieldErrors errors={errors} />
    </div>
  )
}

function FieldErrors({ errors }: { errors?: string[] }) {
  if (!errors || errors.length === 0) return null
  return (
    <div className="space-y-0.5" role="alert">
      {errors.map((m) => (
        <p key={m} className="text-[11px] leading-snug text-destructive">
          {m}
        </p>
      ))}
    </div>
  )
}

function Check({
  id,
  label,
  checked,
  onChange,
  errors,
  disabled,
}: {
  id: string
  label: string
  checked: boolean
  onChange: (v: boolean) => void
  errors?: string[]
  disabled?: boolean
}) {
  return (
    <div className="space-y-1">
      <label htmlFor={id} className="flex items-center gap-2 text-sm">
        <input
          id={id}
          type="checkbox"
          className="size-4 accent-primary"
          checked={checked}
          disabled={disabled}
          onChange={(e) => onChange(e.target.checked)}
        />
        {label}
      </label>
      <FieldErrors errors={errors} />
    </div>
  )
}

/**
 * A plain `<select>`: the options are a closed vocabulary of a handful of names, and a native list
 * keeps the form usable by keyboard and by a test without a portal.
 */
function NativeSelect({
  id,
  value,
  onChange,
  options,
  none,
  labels,
  ...rest
}: {
  id: string
  value: string
  onChange: (v: string) => void
  options: readonly string[]
  none?: string
  /** Each option's words, where they are not the name's own. */
  labels?: Record<string, string>
  'aria-label'?: string
}) {
  return (
    <select id={id} className={SELECT_CLASS} value={value} onChange={(e) => onChange(e.target.value)} {...rest}>
      {none != null && <option value="">{none}</option>}
      {none == null && !options.includes(value) && <option value="">—</option>}
      {options.map((o) => (
        <option key={o} value={o}>
          {labels?.[o] ?? words(o)}
        </option>
      ))}
    </select>
  )
}

/** A `#rrggbb` text field with a swatch that opens the browser's picker. */
function ColourInput({
  id,
  value,
  onChange,
  placeholder = "Kind's own",
}: {
  id: string
  value: string
  onChange: (v: string) => void
  placeholder?: string
}) {
  const valid = /^#[0-9a-fA-F]{6}$/.test(value)
  return (
    <div className="flex items-center gap-1.5">
      <input
        type="color"
        aria-label="Pick a colour"
        className="h-9 w-9 shrink-0 cursor-pointer rounded-md border bg-transparent p-0.5"
        value={valid ? value.toLowerCase() : '#808080'}
        onChange={(e) => onChange(e.target.value)}
      />
      <Input id={id} value={value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />
    </div>
  )
}
