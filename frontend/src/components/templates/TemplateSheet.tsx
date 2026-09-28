import { useCallback, useMemo, useState } from 'react'
import { toast } from 'sonner'
import { ArrowRight, AudioWaveform, CopyPlus, Hand, LayoutGrid, Layers, Trash2, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { CellSelectionActions } from '@/components/sheet/CellSelectionActions'
import { CopyToProjectSheet } from '@/components/sheet/CopyToProjectSheet'
import { CountReadOut, ReadOnlyNote } from '@/components/sheet/LibraryReadOuts'
import { LibraryVerb, oneRecordReason } from '@/components/sheet/LibraryVerb'
import { libraryNameColumn } from '@/components/sheet/LibraryNameColumn'
import { libraryPermission } from '@/components/sheet/libraryScope'
import { reportSheetWriteFailure } from '@/components/sheet/reportSheetWriteFailure'
import { SelectionBar } from '@/components/sheet/SelectionBar'
import { SheetTable } from '@/components/sheet/SheetTable'
import { NumberCell } from '@/components/sheet/cells/NumberCell'
import { OptionCell, type SheetOption } from '@/components/sheet/cells/OptionCell'
import { TextCell } from '@/components/sheet/cells/TextCell'
import { listNames, type SheetColumn, type SheetRow } from '@/components/sheet/sheetModel'
import { PHONE_FOLDED_CLASS } from '@/components/sheet/toolbarFolds'
import { useSheet } from '@/components/sheet/useSheet'
import { useDuplicateBatch } from '@/components/sheet/useDuplicateBatch'
import { SpreadPanel, type SpreadPlan } from '@/components/editor/SpreadPanel'
import { effectSpeedLabel } from '@/components/fx/fxConstants'
import { describeTemplateRows, templateRowsSwatch } from '@/lib/templateIntent'
import { handPickUp } from '@/store/hand'
import { useCopyTemplateMutation, useSaveTemplateMutation } from '@/store/templates'
import type { TemplateEffect, TemplateInput, TemplateSummary } from '@/api/templatesApi'
import type { SpeedMaster } from '@/api/speedMastersApi'
import { useTemplateDelete } from './useTemplateDelete'
import { TemplateValueCell, type TemplateValueDraft, type TemplateValueLanding } from './TemplateValueCell'
import { applyValueChanges, seedValues, templateRowsFromValues, templateRowsKey } from './familyControls/templateRows'
import { formatFamilyList, type AttributeFamily } from '@/lib/attributeFamily'

export type TemplateColumnKey = 'holds' | 'value' | 'fade' | 'master' | 'notes' | 'layers' | 'pages' | 'pressed'

export interface TemplateSheetRow extends SheetRow {
  /** Absent on a family divider. */
  template?: TemplateSummary
}

/** A member row — every row but a divider carries its template. */
type MemberRow = TemplateSheetRow & { template: TemplateSummary }

export function templateRowId(templateId: number): string {
  return `tmpl:${templateId}`
}

/** The Master column's choice for a wall-clock effect's null rate master. Never on the wire. */
export const UNSCALED = 'unscaled'

/**
 * Master 1 before the bank has loaded — a beat effect's null master, which is master 1 whether or
 * not its uuid is known yet. Without it that cell read `undefined` until the masters query landed,
 * so a marquee over it skipped a row that does take a master. Never on the wire: it canonicalises
 * to null, the spelling every writer uses for master 1.
 */
const MASTER_1 = 'master-1'

export interface TemplateSheetProps {
  projectId: number
  /** The rows to draw, dividers included — the route groups them under *All* (`groupRows`). */
  rows: readonly TemplateSheetRow[]
  /** Every template in the library, for Duplicate's `(Copy n)` names. */
  library: readonly TemplateSummary[]
  /** This project's speed masters, for the Master column. */
  masters: readonly SpeedMaster[]
  isCurrentProject: boolean
  projectName: string
  onOpenTemplate: (template: TemplateSummary) => void
}

/**
 * **The template library as a sheet** (library-sheets plan §3.2, session 2), replacing
 * `TemplateListRow` and its `…` menu. The route draws the family chips and groups the rows under
 * family dividers; this draws the columns and the bar.
 *
 * - **Holds** is a read-out. **Value** reads the template's own grammar in one line — the swatch
 *   and `#FF9D4A · Extract white`, `50%`, `270° / 135°` for a generic value; *4 heads · per
 *   fixture*; the effect and its speed — read from `rows ?? []`, since an effect template's empty
 *   list is omitted on the wire. On a **generic value** template it is `TemplateValueCell` (session
 *   3, D6): the editor's own family controls, `PUT {rows}` through the lifted rows builder, the
 *   origin's family only (see the column), Clear refused, no Spread. Per-fixture and effect
 *   templates read out and are skipped by name.
 * - **Fade** is a `NumberCell` in seconds on **value** templates: `PUT {fadeDurationMs,
 *   fadeDurationMsPresent: true}`. Clear writes null — *default (none)*, the caller's default, which
 *   a press applies at 0 — and Spread is the kit's `duration` plan. An effect template has no
 *   arrival to time, so its Fade is `undefined`: blank, and skipped by name.
 * - **Master** is an `OptionCell` on **effect** templates — `PUT {effect}` with **one field
 *   changed**: `speedMasterUuid` for a `BEAT` effect, `rateSpeedMasterUuid` for a `WALL_CLOCK` one
 *   (§Speed Masters' two references; a beat effect never reads the rate master, and a wall-clock
 *   one never reads the beat master). The PUT deletes and recreates the effect row and republishes,
 *   so **a running instance restarts** — the editor says so. A null beat master is M1; a null rate
 *   master is **unscaled**, which is not M1. An effect whose type no longer resolves in the library
 *   has no `timingSource`, so which field to write is unknown: its Master is blank and skipped.
 * - **Notes** writes `{notes, notesPresent: true}`; the name `PUT {name}`. Both metadata only.
 * - **Layers**, **Pages** and **Pressed** are read-outs; Pressed is `lastPressedAt`, which
 *   `templatePressed` patches into the list live.
 *
 * The verbs, the read-only scope and the refusals are `LookSheet`'s rules (D11, D12, D14), with
 * **Spread** added and **Include** dropped: a template is applied, never included.
 */
export function TemplateSheet({
  projectId,
  rows,
  library,
  masters,
  isCurrentProject,
  projectName,
  onOpenTemplate,
}: TemplateSheetProps) {
  const [saveTemplate] = useSaveTemplateMutation()
  const [copyTemplate] = useCopyTemplateMutation()
  const scope = useMemo(() => libraryPermission(isCurrentProject, projectName), [isCurrentProject, projectName])

  const master1 = useMemo(() => masters.find((m) => m.masterIndex === 1) ?? null, [masters])
  const masterByUuid = useMemo(() => new Map(masters.map((m) => [m.uuid, m])), [masters])
  const beatOptions = useMemo<SheetOption[]>(
    () => masters.map((m) => ({ value: m.uuid, label: `M${m.masterIndex} · ${m.name}` })),
    [masters],
  )
  const rateOptions = useMemo<SheetOption[]>(
    () => [{ value: UNSCALED, label: 'Unscaled' }, ...beatOptions],
    [beatOptions],
  )

  /** One PUT, its refusal toasted by code under the column's key (D14). */
  const put = useCallback(
    (template: TemplateSummary, column: string, body: TemplateInput) => {
      saveTemplate({ projectId, templateId: template.id, ...body })
        .unwrap()
        .catch((err: unknown) => reportSheetWriteFailure(err, { key: `templates:${column}` }))
    },
    [projectId, saveTemplate],
  )

  /**
   * The Master column's value for one effect template — the stored uuid, **canonical**: a beat
   * effect's null is master 1, so it reads as M1's uuid and an M1 commit writes null back; a
   * wall-clock effect's null is `unscaled`. Undefined where the effect type is unknown.
   */
  const masterValue = useCallback(
    (effect: TemplateEffect): string | undefined => {
      if (effect.timingSource === 'BEAT') return effect.speedMasterUuid ?? master1?.uuid ?? MASTER_1
      if (effect.timingSource === 'WALL_CLOCK') return effect.rateSpeedMasterUuid ?? UNSCALED
      return undefined
    },
    [master1],
  )

  /**
   * The effect with its master changed — the one field, and only if it moves. Null where the
   * choice cannot apply to this effect: `unscaled` on a beat effect (a beat effect always runs on a
   * master), or a master this project does not have.
   */
  const effectWithMaster = useCallback(
    (effect: TemplateEffect, choice: string): TemplateEffect | null | 'same' => {
      if (choice !== UNSCALED && choice !== MASTER_1 && !masterByUuid.has(choice)) return null
      if (effect.timingSource === 'BEAT') {
        if (choice === UNSCALED) return null
        // Master 1 has two spellings — its uuid and the null that means it — so both sides are
        // compared canonically, and M1 is written as null, the spelling every other writer uses.
        const current = canonicalBeat(effect.speedMasterUuid, master1)
        const next = canonicalBeat(choice, master1)
        return current === next ? 'same' : { ...effect, speedMasterUuid: next }
      }
      if (effect.timingSource === 'WALL_CLOCK') {
        const next = choice === UNSCALED ? null : choice
        if ((effect.rateSpeedMasterUuid ?? null) === next) return 'same'
        return { ...effect, rateSpeedMasterUuid: next }
      }
      return null
    },
    [master1, masterByUuid],
  )

  const columns = useMemo<SheetColumn<TemplateSheetRow, TemplateColumnKey>[]>(
    () => [
      {
        key: 'holds',
        label: 'Holds',
        width: '64px',
        value: () => undefined,
        display: (row) =>
          row.template ? (
            <span className={row.template.kind === 'effect' ? 'mx-1.5 text-xs text-violet-400' : 'mx-1.5 text-xs text-muted-foreground'}>
              {row.template.kind === 'effect' ? 'Effect' : 'Value'}
            </span>
          ) : null,
      },
      {
        key: 'value',
        label: 'Value',
        width: 'minmax(150px, 200px)',
        // Editable on a generic value template only (D6). A per-fixture template's values were
        // recorded per head and an effect template holds no value, so both read out — `undefined`
        // here — and open the editor from the pencil; a marquee over them skips them by name.
        // **No `kind`**: the column takes commits from its own editor alone, and the family rule
        // lives in `write`, since one column cannot carry a per-row `value:<family>` kind.
        value: (row) => (row.template != null ? templateValueDraft(row.template) : undefined),
        display: (row) => (row.template ? <TemplateValue template={row.template} /> : null),
        cell: (row, props) => {
          const cellProps = props as React.ComponentProps<typeof TemplateValueCell>
          return (
            <TemplateValueCell
              {...cellProps}
              face={row.template ? <TemplateValue template={row.template} /> : null}
              landing={() => valueLanding(cellProps.value.family, cellProps.batchRows() as TemplateSheetRow[])}
            />
          )
        },
        write: (batch, value) => {
          if (!isTemplateValueDraft(value) || value.changes == null) return false
          const { family, changes } = value
          const emptied: string[] = []
          for (const row of batch as MemberRow[]) {
            // The origin's family only: a colour draft on an intensity template would be refused
            // as a change of family, and is named as skipped on the editor's read-out instead.
            if (row.template.family !== family) continue
            // **What changed, over this template's own values** — never the origin's whole draft.
            // The PUT replaces a template's rows, so the draft would delete a sibling's strobe,
            // its white, another beam role, on an Enter that changed nothing.
            const own = seedValues(row.template)
            const next = templateRowsFromValues(family, applyValueChanges(own, changes))
            // The write boundary refuses a value template with no rows: a removal that would leave
            // one empty is skipped and named. (The editor refuses an empty origin before this.)
            if (next.length === 0) {
              emptied.push(row.template.name)
              continue
            }
            // Compared through the builder on both sides, so a stored row that parses to the same
            // intent — a lower-case hex, an older row order — is no change.
            if (templateRowsKey(next) !== templateRowsKey(templateRowsFromValues(family, own))) {
              put(row.template, 'value', { rows: next })
            }
          }
          if (emptied.length > 0) {
            toast.info(`${listNames(emptied, 'template')} would hold no value — skipped`, {
              id: 'sheet-write:templates:value-skip',
            })
          }
          return true
        },
        clearRefusal: 'A template holds a value — set another one instead',
        skipNote: (skipped) => {
          const one = skipped.length === 1
          const effect = (row: TemplateSheetRow) => row.template?.kind === 'effect'
          const perHead = (row: TemplateSheetRow) => row.template?.kind === 'value' && !row.template.isGeneric
          const reason = skipped.every(effect)
            ? one ? 'runs an effect' : 'run effects'
            : skipped.every(perHead)
              ? one ? 'holds a value per head' : 'hold values per head'
              : skipped.every((row) => effect(row) || perHead(row))
                ? 'hold effects or per-head values'
                : 'cannot be set here'
          return `${listNames(skipped.map(templateRowName), 'template')} ${reason} — open to change · skipped`
        },
      },
      {
        key: 'fade',
        label: 'Fade',
        kind: 'fade',
        width: '72px',
        // Seconds, the editor's unit. A null fade is the caller's default — *(none)* — and a press
        // applies it at 0, so the editor opens at 0 for one; an effect template has no fade at all.
        value: (row) =>
          row.template?.kind === 'value' ? (row.template.fadeDurationMs ?? 0) / 1000 : undefined,
        cell: (row, props) => (
          <NumberCell
            {...(props as React.ComponentProps<typeof NumberCell>)}
            unit="s"
            min={0}
            step={0.1}
            format={formatSeconds}
            note="How long a press takes to arrive — empty is the default (none), which lands at once"
            face={
              row.template?.fadeDurationMs != null ? (
                <span className="mx-1.5 font-mono text-xs tabular-nums">{formatSeconds(row.template.fadeDurationMs / 1000)}s</span>
              ) : (
                <span className="mx-1.5 text-xs text-muted-foreground/60">—</span>
              )
            }
          />
        ),
        write: (batch, value) => {
          if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return false
          const ms = Math.round(value * 1000)
          for (const row of batch as MemberRow[]) {
            // Compared as the press lands it: a null fade applies at 0, so 0 over a null fade
            // changes nothing — and the editor opens a null fade at 0, so Enter on it untouched
            // must not turn *default (none)* into an explicit 0s.
            if ((row.template.fadeDurationMs ?? 0) !== ms) {
              put(row.template, 'fade', { fadeDurationMs: ms, fadeDurationMsPresent: true })
            }
          }
          return true
        },
        clear: (batch) => {
          for (const row of batch as MemberRow[]) {
            if (row.template.fadeDurationMs != null) put(row.template, 'fade', { fadeDurationMs: null, fadeDurationMsPresent: true })
          }
        },
        skipNote: (skipped) =>
          `${listNames(skipped.map(templateRowName), 'template')} ${skipped.length === 1 ? 'runs an effect' : 'run effects'} — no fade to time · skipped`,
        spread: (batch): SpreadPlan | null =>
          batch.length === 0
            ? null
            : {
                kind: 'duration',
                col: 'fade',
                label: 'Fade',
                count: batch.length,
                names: batch.map(templateRowName),
                apply: (ms) => {
                  ;(batch as MemberRow[]).forEach((row, i) => {
                    const next = Math.max(0, Math.round(ms[i]))
                    if (next !== (row.template.fadeDurationMs ?? 0)) {
                      put(row.template, 'fade', { fadeDurationMs: next, fadeDurationMsPresent: true })
                    }
                  })
                },
              },
      },
      {
        key: 'master',
        label: 'Master',
        kind: 'master',
        width: 'minmax(96px, 120px)',
        value: (row) => (row.template?.effect != null ? masterValue(row.template.effect) : undefined),
        cell: (row, props) => {
          const effect = row.template?.effect
          const wallClock = effect?.timingSource === 'WALL_CLOCK'
          const uuid = wallClock ? effect?.rateSpeedMasterUuid : (effect?.speedMasterUuid ?? master1?.uuid)
          const master = uuid != null ? masterByUuid.get(uuid) : undefined
          return (
            <OptionCell
              {...(props as React.ComponentProps<typeof OptionCell>)}
              options={wallClock ? rateOptions : beatOptions}
              face={
                master != null ? (
                  <span className="mx-1.5 truncate text-xs">
                    <span className="font-mono font-bold">M{master.masterIndex}</span> {master.name}
                  </span>
                ) : (
                  <span className="mx-1.5 text-xs text-muted-foreground">{wallClock && uuid == null ? 'unscaled' : 'M1'}</span>
                )
              }
            />
          )
        },
        write: (batch, value) => {
          if (typeof value !== 'string') return false
          const refused: string[] = []
          for (const row of batch as MemberRow[]) {
            const effect = row.template.effect
            if (effect == null) continue
            const next = effectWithMaster(effect, value)
            if (next === 'same') continue
            if (next == null) {
              refused.push(row.template.name)
              continue
            }
            // The whole effect with the one field changed: the PUT replaces the effect half, so a
            // body carrying only the master would be refused, and every other field is the
            // template's own. It recreates the effect — a running instance restarts.
            put(row.template, 'master', { effect: next })
          }
          if (refused.length > 0) {
            toast.info(
              `${listNames(refused, 'template')} skipped — a beat effect always runs on a master, so it cannot be unscaled`,
              { id: 'sheet-write:templates:master-skip' },
            )
          }
          return true
        },
        clear: (batch) => {
          for (const row of batch as MemberRow[]) {
            const effect = row.template.effect
            if (effect == null) continue
            // The stored null on either field: M1 for a beat effect, unscaled for a wall-clock one.
            if (effect.timingSource === 'BEAT' && canonicalBeat(effect.speedMasterUuid, master1) != null) {
              put(row.template, 'master', { effect: { ...effect, speedMasterUuid: null } })
            } else if (effect.timingSource === 'WALL_CLOCK' && effect.rateSpeedMasterUuid != null) {
              put(row.template, 'master', { effect: { ...effect, rateSpeedMasterUuid: null } })
            }
          }
        },
        cellTitle: (row) =>
          row.template?.effect != null
            ? 'Changing the master recreates the effect — a running instance restarts'
            : undefined,
        skipNote: (skipped) => {
          const values = skipped.filter((row) => row.template?.kind === 'value')
          return values.length === skipped.length
            ? `${listNames(skipped.map(templateRowName), 'template')} ${skipped.length === 1 ? 'holds a value' : 'hold values'} — no master · skipped`
            : `${listNames(skipped.map(templateRowName), 'template')} · no master to set · skipped`
        },
      },
      {
        key: 'notes',
        label: 'Notes',
        kind: 'notes',
        width: 'minmax(120px, 1fr)',
        value: (row) => (row.template ? (row.template.notes ?? '') : undefined),
        cell: (row, props) => (
          <TextCell
            {...(props as React.ComponentProps<typeof TextCell>)}
            allowEmpty
            face={
              row.template?.notes ? (
                <span className="mx-1.5 truncate text-xs text-muted-foreground">{row.template.notes}</span>
              ) : (
                <span className="mx-1.5 text-xs text-muted-foreground/60">—</span>
              )
            }
          />
        ),
        write: (batch, value) => {
          if (typeof value !== 'string') return false
          const next = value.trim() === '' ? null : value.trim()
          for (const row of batch as MemberRow[]) {
            if (next !== (row.template.notes ?? null)) put(row.template, 'notes', { notes: next, notesPresent: true })
          }
          return true
        },
        clear: (batch) => {
          for (const row of batch as MemberRow[]) {
            if (row.template.notes) put(row.template, 'notes', { notes: null, notesPresent: true })
          }
        },
      },
      {
        key: 'layers',
        label: 'Layers',
        width: '64px',
        value: () => undefined,
        display: (row) =>
          row.template ? (
            <CountReadOut
              count={row.template.layerCount}
              icon={<Layers className="size-3" />}
              title={`Applied by ${row.template.layerCount} cue layer${row.template.layerCount === 1 ? '' : 's'} — these gate the delete`}
            />
          ) : null,
      },
      {
        key: 'pages',
        label: 'Pages',
        width: '64px',
        value: () => undefined,
        display: (row) =>
          row.template ? (
            <CountReadOut
              count={row.template.buskPageCount}
              icon={<LayoutGrid className="size-3" />}
              title={`On ${row.template.buskPageCount} busk page${row.template.buskPageCount === 1 ? '' : 's'} — a hint, not a use`}
            />
          ) : null,
      },
      {
        key: 'pressed',
        label: 'Pressed',
        width: '80px',
        value: () => undefined,
        display: (row) => (row.template ? <PressedAgo at={row.template.lastPressedAt} /> : null),
      },
    ],
    [beatOptions, effectWithMaster, master1, masterByUuid, masterValue, put, rateOptions],
  )

  const openRow = useCallback(
    (row: TemplateSheetRow) => {
      if (row.template) onOpenTemplate(row.template)
    },
    [onOpenTemplate],
  )
  const sheet = useSheet<TemplateSheetRow, TemplateColumnKey>({
    rows,
    columns,
    permission: scope.permission,
    copy: scope.copy,
    cellDisabled: scope.readOnly ? cellsInert : undefined,
    noun: 'template',
    rowName: templateRowName,
    onOpenRow: isCurrentProject ? openRow : undefined,
  })
  const { selectedRows, cellCount, clearByLadder, setRows } = sheet
  const selected = useMemo(
    () => selectedRows.flatMap((row) => (row.template ? [row.template] : [])),
    [selectedRows],
  )

  const { run: runDelete, busy: deleting, dialog: deleteDialog } = useTemplateDelete({
    projectId,
    onDeleted: () => clearByLadder(),
    onKept: (kept) => setRows(kept.map((t) => templateRowId(t.id))),
  })

  const [copyOpen, setCopyOpen] = useState(false)
  const [copying, setCopying] = useState<readonly TemplateSummary[]>([])

  const copyHere = useCallback(
    (template: TemplateSummary, newName: string) =>
      copyTemplate({ projectId, templateId: template.id, targetProjectId: projectId, newName }).unwrap(),
    [copyTemplate, projectId],
  )
  const { duplicate, duplicating } = useDuplicateBatch({
    library,
    name: templateName,
    copy: copyHere,
    toastKey: 'templates',
  })

  const count = selected.length
  const noun = count === 1 ? 'template' : 'templates'
  const verbs =
    count > 0 ? (
      <div className="flex shrink-0 items-center gap-1.5 sm:gap-2">
        {cellCount > 0 && (
          <CellSelectionActions
            copy={sheet.copy}
            permission={sheet.permission}
            setRef={sheet.setButtonRef}
            onSet={sheet.toggleCellEditor}
            onClear={sheet.clearSelectedCells}
            spread={
              <SpreadPanel
                host="popover"
                plans={sheet.spreadPlans}
                disabledReason={scope.reason}
                drivableHint="fade"
                className={PHONE_FOLDED_CLASS}
              />
            }
          />
        )}
        {isCurrentProject && (
          <LibraryVerb
            icon={Hand}
            label="Pick up"
            title={`Pick up ${selected[0]?.name ?? 'this template'} — place it on another screen`}
            disabledReason={oneRecordReason('Pick up', 'template', count)}
            onClick={() => handPickUp('TEMPLATE', selected[0].id)}
          />
        )}
        <LibraryVerb
          icon={CopyPlus}
          label="Duplicate"
          title={`Duplicate ${count} ${noun} in this project`}
          disabledReason={scope.reason ?? (duplicating ? 'Duplicating…' : null)}
          onClick={() => void duplicate(selected)}
        />
        <LibraryVerb
          icon={ArrowRight}
          label="Copy to…"
          title={`Copy ${count} ${noun} to another project`}
          onClick={() => {
            setCopying(selected)
            setCopyOpen(true)
          }}
        />
        <LibraryVerb
          icon={Trash2}
          label="Delete"
          destructive
          title={`Delete ${count} ${noun}`}
          disabledReason={scope.reason ?? (deleting ? 'Deleting…' : null)}
          onClick={() => void runDelete(selected)}
        />
        <Button variant="ghost" size="sm" onClick={clearByLadder} title="Deselect all">
          <X className="size-3.5" />
          <span className="hidden sm:inline">Deselect</span>
        </Button>
      </div>
    ) : null

  const firstColumn = useMemo(
    () =>
      libraryNameColumn<TemplateSheetRow>({
        label: 'Template',
        width: `${NAME_WIDTH}px`,
        noun: 'template',
        name: (row) => row.template?.name ?? '',
        rename: (row, next) => {
          if (row.template) put(row.template, 'name', { name: next })
        },
        renameDisabled: scope.readOnly ? () => true : undefined,
        onOpen: isCurrentProject ? openRow : undefined,
        openLabel: (row) => `Edit ${row.template?.name ?? 'template'}`,
      }),
    [isCurrentProject, openRow, put, scope.readOnly],
  )

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="@container">
        <SelectionBar
          rowLabel={count > 0 ? `${count} ${noun}` : null}
          cellLabel={cellCount > 0 ? `${cellCount} cell${cellCount === 1 ? '' : 's'}` : null}
          cellTitle={`${count} ${noun} · ${sheet.family ?? ''} — edit once, applies to all`}
          family={sheet.family}
          hints={{ entry: cellCount > 0 && sheet.permission.entry, clear: cellCount > 0 && sheet.permission.clear }}
          strip={scope.reason != null ? <ReadOnlyNote reason={scope.reason} /> : undefined}
          verbs={verbs}
          marqueeDragging={sheet.marqueeDragging}
          foldForStrip={false}
        />
      </div>
      <SheetTable<TemplateSheetRow, TemplateColumnKey>
        {...sheet.tableProps}
        minWidth={`${NAME_WIDTH + MIN_TRACKS}px`}
        firstColumn={firstColumn}
      />
      {deleteDialog}
      <CopyToProjectSheet<TemplateSummary>
        open={copyOpen}
        onOpenChange={setCopyOpen}
        items={copying}
        noun="template"
        name={(t) => t.name}
        sourceProjectId={projectId}
        copy={(t, targetProjectId, newName) =>
          copyTemplate({ projectId, templateId: t.id, targetProjectId, newName }).unwrap()
        }
      />
    </div>
  )
}

/**
 * The name column's track. With the other tracks' floors (64 + 150 + 72 + 96 + 120 + 64 + 64 + 80
 * = 710) the sheet needs 890px, inside the ~940 the iPad frame (1180×820) leaves it with the
 * sidebar open.
 */
const NAME_WIDTH = 180
const MIN_TRACKS = 710

/** A beat master uuid as stored: master 1 is null, whichever of its two spellings came in. */
function canonicalBeat(uuid: string | null | undefined, master1: SpeedMaster | null): string | null {
  return uuid == null || uuid === MASTER_1 || uuid === master1?.uuid ? null : uuid
}

function cellsInert(): boolean {
  return true
}

function templateName(template: TemplateSummary): string {
  return template.name
}

function templateRowName(row: TemplateSheetRow): string {
  return row.template?.name ?? row.id
}

/** `1.5`, `2`, `0.25` — seconds as the Fade column prints them. */
function formatSeconds(seconds: number): string {
  return String(Math.round(seconds * 1000) / 1000)
}

/**
 * A generic value template's Value — its family and its draft, parsed by `seedValues` — or
 * undefined for a per-fixture or effect template, which read out. Cached per template object, so a
 * row's cell sees one identity until the list refetches.
 */
function templateValueDraft(template: TemplateSummary): TemplateValueDraft | undefined {
  // A null family is a template whose rows name no known property: there is no control to mount.
  const family = template.family
  if (template.kind !== 'value' || !template.isGeneric || family == null) return undefined
  let draft = DRAFTS.get(template)
  if (draft == null) {
    draft = { family, values: seedValues(template) }
    DRAFTS.set(template, draft)
  }
  return draft
}
const DRAFTS = new WeakMap<TemplateSummary, TemplateValueDraft>()

function isTemplateValueDraft(value: unknown): value is TemplateValueDraft {
  return typeof value === 'object' && value != null && 'family' in value && 'values' in value
}

/**
 * The batch a Value commit lands on — the templates of the origin's family — and the rest named,
 * the read-out's half of `write`'s family rule: *Amber and Deep Blue are Colour · skipped*.
 */
function valueLanding(family: AttributeFamily, batch: readonly TemplateSheetRow[]): TemplateValueLanding {
  const other = batch.filter((row) => row.template != null && row.template.family !== family)
  const families = [...new Set(other.flatMap((row) => (row.template?.family != null ? [row.template.family] : [])))]
  const named = listNames(other.map(templateRowName), 'template')
  const verb = other.length === 1 ? 'is' : 'are'
  return {
    count: batch.length - other.length,
    skipped: other.length === 0 ? null : `${named} ${verb} ${formatFamilyList(families, ' and ')} · skipped`,
  }
}

/**
 * The Value read-out — the template's contents in its own grammar, never resolved against a head
 * (`templateIntent.ts` serialises and parses only). Every reader takes `rows ?? []`: an effect
 * template's empty list is omitted on the wire.
 */
function TemplateValue({ template }: { template: TemplateSummary }) {
  if (template.kind === 'effect') {
    const effect = template.effect
    const speed = effect == null ? null : effectSpeedLabel(effect.beatDivision, effect.timingSource)
    const text = effect == null ? 'Effect' : [effect.effectType, speed].filter((p) => p != null && p !== '').join(' · ')
    return (
      <span className="mx-1.5 flex min-w-0 items-center gap-1.5 text-xs text-muted-foreground" title={text}>
        <AudioWaveform className="size-3.5 shrink-0 text-violet-400" />
        <span className="truncate">{text}</span>
      </span>
    )
  }
  const rows = template.rows ?? []
  if (!template.isGeneric) {
    const heads = new Set(rows.map((r) => r.targetKey)).size
    return (
      <span className="mx-1.5 truncate text-xs text-muted-foreground">
        {heads} head{heads === 1 ? '' : 's'} · per fixture
      </span>
    )
  }
  const swatch = templateRowsSwatch(rows)
  const described = describeTemplateRows(rows)
  return (
    <span className="mx-1.5 flex min-w-0 items-center gap-1.5" title={described}>
      {swatch != null && (
        <span className="size-4 shrink-0 rounded-sm border border-border/60" style={{ background: swatch }} />
      )}
      <span className="truncate font-mono text-xs tabular-nums text-muted-foreground">{described}</span>
    </span>
  )
}

/** When a template was last pressed on this desk — `12s ago`, `3m ago`, `1h ago`, `2d ago`; `—` never. */
export function pressedAgo(at: string | null | undefined, now: number = Date.now()): string | null {
  if (at == null) return null
  // Parsed, never compared as text: `Instant.toString()` drops the fraction on an exact second.
  const then = Date.parse(at)
  if (!Number.isFinite(then)) return null
  const s = Math.max(0, Math.round((now - then) / 1000))
  if (s < 60) return `${s}s ago`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ago`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h ago`
  return `${Math.floor(h / 24)}d ago`
}

function PressedAgo({ at }: { at: string | null | undefined }) {
  const text = pressedAgo(at)
  if (text == null) return <span className="mx-2 text-xs text-muted-foreground/45">—</span>
  return (
    <span className="mx-2 whitespace-nowrap text-[11.5px] text-muted-foreground" title={at ?? undefined}>
      {text}
    </span>
  )
}
