import { useCallback, useEffect, useRef, useState } from 'react'
import { ChevronLeft } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Sheet, SheetBody, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { useActiveEffectsQuery, useRemoveFxMutation } from '@/store/fixtureFx'
import { useRemoveGroupFxMutation } from '@/store/groups'
import { FxEditor } from './FxEditor'
import { FxPicker, type AuditionedEffect, type FxTarget } from './FxPicker'

/**
 * `FxPicker` + `FxEditor` in a sheet (fixture-fx-sheets plan D17): ⌘K's *Apply FX* and the
 * programmer rail's `+ Effect`, which have no tray to open in. It replaced `AddEditFxSheet`'s
 * category → effect → configure → Apply steps: a tap on an effect starts it — a programmer effect
 * (D10) — another tap swaps it, and *Edit <name>* goes on to the live editor, whose **Done** closes
 * the sheet with the effect running. Closing it any other way leaves the effect running too: it was
 * started on the tap, and stopping it is the editor's ×.
 *
 * [onFinished] is the programmer's layer scope: the effect lands in the focused Look once the
 * operator has finished with it — on close, with the effect it ends on, so an audition of five
 * effects absorbs the one kept and the Look takes the settings it was left at, not the defaults it
 * started with. It is called **once per session however the sheet goes** — closed, or unmounted by
 * its host when the selection it was opened for goes — with the auditioned effect's id (the create
 * answered it, so a close before the effect list refetches still absorbs it), and only after the
 * editor has landed any move its floor still held. Not called when nothing was started, or the
 * effect was stopped from the editor.
 */
export function FxAddSheet({
  target,
  open,
  onClose,
  onFinished,
}: {
  target: FxTarget | null
  open: boolean
  onClose: () => void
  onFinished?: (effectId: number) => void
}) {
  const { data: active } = useActiveEffectsQuery()
  const [current, setCurrent] = useState<AuditionedEffect | null>(null)
  const [editing, setEditing] = useState(false)
  const [removeFx] = useRemoveFxMutation()
  const [removeGroupFx] = useRemoveGroupFxMutation()

  const live = current != null ? (active ?? []).find((e) => e.id === current.effectId) ?? null : null

  /** The open editor's "land what the floor holds" — written before the effect is handed on. */
  const flushEditor = useRef<(() => void) | null>(null)
  const currentRef = useRef(current)
  currentRef.current = current
  const onFinishedRef = useRef(onFinished)
  onFinishedRef.current = onFinished
  const finished = useRef(false)
  const finish = useCallback(() => {
    if (finished.current) return
    finished.current = true
    const effect = currentRef.current
    if (effect == null) return
    flushEditor.current?.()
    onFinishedRef.current?.(effect.effectId)
  }, [])

  // A fresh session per open; the one before it is finished first, should the host have kept the
  // sheet mounted across a close.
  useEffect(() => {
    if (!open) return
    finished.current = false
    setCurrent(null)
    setEditing(false)
  }, [open])
  // A host that stops rendering the sheet (the programmer's, when the selection goes) finishes it.
  useEffect(() => finish, [finish])

  const close = () => {
    finish()
    onClose()
  }

  const stop = () => {
    if (live == null) return
    const request = live.isGroupTarget
      ? removeGroupFx({ id: live.id, groupName: live.targetKey })
      : removeFx({ id: live.id, fixtureKey: live.targetKey })
    request.unwrap().catch(() => {
      // Reported by errorToastMiddleware.
    })
    setCurrent(null)
    setEditing(false)
  }

  const targetName = target == null ? '' : target.type === 'fixture' ? target.fixture.name : target.group.name

  return (
    <Sheet open={open && target != null} onOpenChange={(next) => !next && close()}>
      <SheetContent side="right" className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>{editing && live ? `Edit ${live.effectType}` : 'Add an effect'}</SheetTitle>
          <SheetDescription>{targetName} · a programmer effect, live as you change it</SheetDescription>
        </SheetHeader>
        <SheetBody>
          {target != null &&
            (editing && live != null ? (
              <div className="flex flex-col gap-2">
                <Button variant="ghost" size="sm" className="h-7 self-start gap-1 px-1.5 text-xs" onClick={() => setEditing(false)}>
                  <ChevronLeft className="size-3.5" />
                  Effects
                </Button>
                <FxEditor
                  key={`${live.id}:${live.effectType}`}
                  effect={live}
                  onDone={close}
                  onStop={stop}
                  flushRef={flushEditor}
                />
              </div>
            ) : (
              <FxPicker target={target} current={current} onCurrent={setCurrent} onEdit={() => setEditing(true)} />
            ))}
        </SheetBody>
      </SheetContent>
    </Sheet>
  )
}
