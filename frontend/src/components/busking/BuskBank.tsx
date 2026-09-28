import { useDraggable, useDroppable } from '@dnd-kit/core'
import { GripVertical, MoreHorizontal } from 'lucide-react'
import { cn } from '@/lib/utils'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { BUSK_FLOWS, BUSK_FLOW_LABELS, BUSK_WIDTHS, BUSK_WIDTH_LABELS, type BuskBank as BuskBankModel } from '@/api/buskApi'
import { HandPlaceStrip } from '@/components/hand/HandTarget'
import {
  buskBankBodyId,
  buskBankId,
  buskBankUnderId,
  duplicateBank,
  removeBank,
  removePad,
  setBank,
  setColumnWidth,
  type BankAddress,
} from '@/lib/buskLayout'
import { useBuskEdit } from './BuskEditProvider'
import { BuskDropSlot, BuskPadButton } from './BuskPad'
import { NameField } from './NameField'
import { DROP_DEPTH, type BuskBankDragData, type BuskDropData } from './buskDnd'
import type { PadBehaviour } from './padBehaviour'

/**
 * One bank: a named cluster of pads that either stacks or is exclusive.
 *
 * **Solo never decides a bank's shape.** The tag in the header is the only thing it shows here —
 * the exclusivity itself is the server's, resolved by the press route from the bank the pad sits
 * in, so nothing in this component knows what a press releases.
 */

function soloSwitchClass(on: boolean) {
  return cn(
    'relative h-4 w-7 shrink-0 rounded-full transition-colors',
    on ? 'bg-primary' : 'bg-muted',
  )
}

function BankHeader({
  bank,
  at,
  dragHandle,
}: {
  bank: BuskBankModel
  at: BankAddress
  dragHandle: React.ReactNode
}) {
  const { editing, commit } = useBuskEdit()

  if (!editing) {
    return (
      <div className="flex min-h-5 items-center gap-2">
        <span className="flex-1 truncate text-[11px] font-semibold">{bank.name}</span>
        {bank.solo && (
          <span className="inline-flex h-4 shrink-0 items-center rounded-full border border-violet-500/60 bg-violet-500/20 px-1.5 text-[9px] font-bold tracking-[0.06em] text-violet-300 uppercase">
            solo
          </span>
        )}
      </div>
    )
  }

  // The grip and the name take the first line; the controls are one group that stays on that line
  // where there is room and wraps to a second, right-aligned, where there is not. A quarter-width
  // column beside the palette has room for a name or for the controls, not both.
  return (
    <div className="flex min-h-5 flex-wrap items-center gap-x-2 gap-y-1.5">
      {dragHandle}
      <NameField
        value={bank.name}
        label="Bank name"
        placeholder="Bank"
        onSave={(name) => commit((page) => setBank(page, at, { name }))}
        // A real minimum, not `min-w-0`: in a quarter-width column the field would otherwise
        // shrink to nothing and the controls beside it would carry on past the bank's border. With
        // a floor the header wraps instead (see above), which is the better failure — and the
        // column itself has a floor in edit mode (`BuskPage`), so the field's 6rem plus the grip
        // always fits inside the bank's padding rather than running past its border.
        className="min-w-[6rem] flex-1"
      />
      <div className="ml-auto flex shrink-0 items-center gap-2">
        <span className="text-[10px] text-muted-foreground">Solo</span>
        <button
          type="button"
          role="switch"
          aria-checked={bank.solo}
          aria-label={`Solo ${bank.name || 'bank'}`}
          onClick={() => commit((page) => setBank(page, at, { solo: !bank.solo }))}
          className={soloSwitchClass(bank.solo)}
        >
          <span
            className={cn(
              'absolute top-0.5 size-3 rounded-full transition-all',
              bank.solo ? 'left-3.5 bg-card' : 'left-0.5 bg-muted-foreground',
            )}
          />
        </button>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              type="button"
              aria-label={`Options for ${bank.name || 'bank'}`}
              className="shrink-0 text-muted-foreground hover:text-foreground"
            >
              <MoreHorizontal className="size-4" />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            {/* Width belongs to the **column**, not the bank — a column can stack several banks and
                they all share its share of the row. The control is here because this is where the
                operator is looking; it reaches past the bank on purpose. */}
            <DropdownMenuLabel className="text-[11px] font-normal text-muted-foreground">
              Column width
            </DropdownMenuLabel>
            <div className="flex gap-0.5 px-1 pb-1">
              {BUSK_WIDTHS.map((width) => (
                <button
                  key={width}
                  type="button"
                  onClick={() => commit((page) => setColumnWidth(page, at.row, at.column, width))}
                  className="rounded px-2 py-1 text-xs hover:bg-accent"
                >
                  {BUSK_WIDTH_LABELS[width]}
                </button>
              ))}
            </div>
            <DropdownMenuSeparator />
            <DropdownMenuRadioGroup
              value={bank.flow}
              onValueChange={(flow) =>
                commit((page) => setBank(page, at, { flow: flow as BuskBankModel['flow'] }))
              }
            >
              {BUSK_FLOWS.map((flow) => (
                <DropdownMenuRadioItem key={flow} value={flow}>
                  Flow: {BUSK_FLOW_LABELS[flow]}
                </DropdownMenuRadioItem>
              ))}
            </DropdownMenuRadioGroup>
            <DropdownMenuSeparator />
            <DropdownMenuItem onSelect={() => commit((page) => duplicateBank(page, at))}>
              Duplicate
            </DropdownMenuItem>
            <DropdownMenuItem
              variant="destructive"
              onSelect={() => commit((page) => removeBank(page, at))}
            >
              Delete
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </div>
  )
}

export function BuskBankCluster({
  bank,
  at,
  behaviour,
}: {
  bank: BuskBankModel
  at: BankAddress
  behaviour: PadBehaviour
}) {
  const { editing, source, target, foreign, commit } = useBuskEdit()

  const { attributes, listeners, setNodeRef: setBankRef, isDragging } = useDraggable({
    id: buskBankId(at),
    data: {
      type: 'busk-bank',
      at,
      name: bank.name,
      padCount: bank.pads.length,
    } satisfies BuskBankDragData,
    disabled: !editing,
  })

  const draggingBank = source?.type === 'busk-bank'

  // A bank cannot land in a bank, so the body is no droppable while one is lifted. Disabling it
  // (rather than only ignoring it in `resolveDropTarget`) is what keeps dnd-kit's `over` — and so
  // the highlight — off a place the drop would refuse. The pad droppables make the same call.
  const { setNodeRef: setBodyRef, isOver } = useDroppable({
    id: buskBankBodyId(at),
    data: {
      type: 'busk-drop',
      target: { kind: 'pad', at: { ...at, pad: bank.pads.length } },
      depth: DROP_DEPTH.bankBody,
    } satisfies BuskDropData,
    disabled: !editing || draggingBank || foreign,
  })
  const { setNodeRef: setUnderRef, isOver: isOverUnder } = useDroppable({
    id: buskBankUnderId(at),
    data: {
      type: 'busk-drop',
      target: { kind: 'bank-under', at },
      depth: DROP_DEPTH.bankUnder,
    } satisfies BuskDropData,
    disabled: !editing || !draggingBank,
  })

  // Only a pad or a palette row opens a slot; a bank lands on the bank zones, never between pads.
  const slotIndex =
    !draggingBank &&
    target?.kind === 'pad' &&
    target.at.row === at.row &&
    target.at.column === at.column &&
    target.at.bank === at.bank
      ? target.at.pad
      : null

  const cells: React.ReactNode[] = []
  bank.pads.forEach((pad, index) => {
    if (slotIndex === index) cells.push(<BuskDropSlot key="drop-slot" />)
    cells.push(
      <BuskPadButton
        key={pad.uuid ?? pad.localKey ?? `pad-${index}`}
        pad={pad}
        at={{ ...at, pad: index }}
        presence={behaviour.presenceOf(pad)}
        isLive={behaviour.isLive(pad)}
        editing={editing}
        onPress={() => behaviour.onPress(pad)}
        onInspect={() => behaviour.onInspect(pad)}
        onPickUp={() => behaviour.onPickUp(pad)}
        onRemove={() => commit((page) => removePad(page, { ...at, pad: index }))}
      />,
    )
  })
  if (slotIndex === bank.pads.length) cells.push(<BuskDropSlot key="drop-slot" />)

  return (
    <>
      <div
        ref={setBankRef}
        className={cn(
          'flex min-w-0 flex-col gap-2 rounded-[10px] border bg-muted/15 p-2.5',
          isDragging && 'opacity-40',
          // Belt as well as braces: the body droppable is `disabled` while a bank is lifted, but
          // dnd-kit only learns that an effect-cycle after the render that sets it — so `over` can
          // still name this body for a frame at drag start, and the lifted bank would flash the
          // drop ring on itself.
          isOver && editing && !draggingBank && !foreign && 'bg-primary/5 ring-1 ring-inset ring-primary/40',
        )}
      >
        <BankHeader
          bank={bank}
          at={at}
          dragHandle={
            <button
              type="button"
              {...attributes}
              {...listeners}
              aria-label={`Reorder ${bank.name || 'bank'}`}
              // The listeners live on the grip alone. The app's pointer sensor activates at 8px
              // and is shared, so a bank that dragged by its whole body would swallow every
              // attempt to scroll the page on a touchscreen.
              className="shrink-0 cursor-grab touch-none text-muted-foreground"
            >
              <GripVertical className="size-3.5" />
            </button>
          }
        />
        <div
          ref={setBodyRef}
          data-bank-flow={bank.flow}
          className={cn(
            'gap-2',
            // SCROLL is the rig row's own flow, offered here too (2026-09-21): one line of pads at
            // the pad's width, scrolling sideways, where WRAP is the grid and COLUMN one per line.
            // The 7px inset with the negative margin is room for the pad's edit cross, which hangs
            // 7px off its top-left corner: `overflow-x: auto` clips both axes, and a scroller
            // clips at its padding edge, so the padding is what keeps the cross visible while the
            // margin keeps the pads where the other flows put them. The placeholder is the one
            // child that must fill rather than take a pad's width.
            bank.flow === 'SCROLL'
              ? 'flex overflow-x-auto pb-1 -mt-[7px] -ml-[7px] pt-[7px] pl-[7px] *:shrink-0 [&>*:not([data-bank-placeholder])]:w-[120px] [&>[data-bank-placeholder]]:flex-1'
              : bank.flow === 'COLUMN'
                ? 'grid grid-cols-1'
                : 'grid grid-cols-[repeat(auto-fill,minmax(110px,1fr))]',
            // An empty bank is legal and keeps its own height, so there is somewhere to drop into.
            bank.pads.length === 0 && slotIndex == null && 'min-h-[56px]',
          )}
        >
          {cells}
          {bank.pads.length === 0 && slotIndex == null && editing && (
            <div
              data-bank-placeholder
              className="flex min-h-[56px] items-center justify-center rounded-lg border border-dashed text-[11px] text-muted-foreground"
            >
              Drag a pad here
            </div>
          )}
        </div>
        {/* The hand's target (multi-screen plan §3.5), offered **in edit mode too**.
            It was withheld there at first, on the reasoning that the palette drag is the same
            gesture with a pointer and that a dashed band which is *not* a droppable, sitting among
            ones that are, is the confusion `BuskDropSlot`'s own comment is about. The second half
            is real and the first is not: the palette only makes the hand *redundant* for a record
            this window could already see, which is precisely not the case the hand exists for. So
            the band is drawn solid while editing rather than withheld — `amongDropTargets`.
            A bank this client minted a moment ago has no server id for the append to address, so it
            offers nothing until the layout PUT answers. */}
        {bank.id != null && (
          <HandPlaceStrip
            target="bank"
            where={bank.name}
            amongDropTargets={editing}
            onPlace={(held) => behaviour.onHandPlace(bank.id!, bank.name, held)}
          />
        )}
      </div>
      {/* Not under the bank being lifted: its own strip would light and then refuse — `dropBank`
          re-finds the anchor after the lift, and the anchor would be the bank that just left. With
          no strip there the pointer is inside nothing and the nearest droppable answers, which is
          at least a place the drop will go. */}
      {editing && draggingBank && !isDragging && (
        <div
          ref={setUnderRef}
          className={cn(
            'grid h-[26px] place-items-center rounded-lg border-2 border-dashed text-[10px] font-semibold transition-colors',
            isOverUnder
              ? 'border-primary bg-primary/10 text-primary'
              : 'border-border text-muted-foreground',
          )}
        >
          stack under
        </div>
      )}
    </>
  )
}
