import { useEffect, useRef, useState, type RefObject } from 'react'
import { AudioWaveform } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useLitHold } from '@/lib/litHold'
import { traceFxPickerClose } from '@/lib/fxPickerCloseTrace'
import { ProgrammerAddFxSheet, useProgrammerAddFx } from './ProgrammerAddFx'

/**
 * **FX** on the programmer's row C (programmer-toolbar plan D8–D10): one Local FX on the selection,
 * through the one picker's FX arm under this button — a popover on a desk, a bottom sheet on an
 * upright phone. It opens on the mask's family when the mask names one, asks *Replace · Stack* where
 * that family already runs a Local FX on a selected head, and once a tap has started the FX it is
 * the live editor, with *Open in FX editor* ⤢ and Done.
 *
 * Where the scope sends it is `useProgrammerAddFx`'s: Local, into a focused Look (absorbed, never
 * asked Replace · Stack), or nowhere in Output, where the button is disabled with the reason.
 *
 * While open it holds *Lit* still (`lib/litHold.ts`), so a selected head going dark does not take
 * row C, and the picker with it, away. [closeTrace] is what row C's host says about its selection,
 * logged by the close trace (`lib/fxPickerCloseTrace.ts`) if row C unmounts under an open picker.
 */
export function ProgrammerAddFxButton({
  projectId,
  wordClass,
  closeTrace,
}: {
  projectId: number
  wordClass: string
  closeTrace?: RefObject<Record<string, unknown>>
}) {
  const offer = useProgrammerAddFx()
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLButtonElement>(null)
  useLitHold(open)
  // A selection that leaves while the picker is open takes the picker with it, rather than leaving
  // it to come back unasked once there is a selection again.
  useEffect(() => {
    if (offer.disabled) setOpen(false)
  }, [offer.disabled])
  const openRef = useRef(open)
  openRef.current = open
  // A disabled offer's close is the sheet's to trace; row C going with it in the next commit is not a
  // second close.
  const disabledRef = useRef(offer.disabled)
  disabledRef.current = offer.disabled
  const closeTraceRef = useRef(closeTrace)
  closeTraceRef.current = closeTrace
  useEffect(
    () => () => {
      if (openRef.current && !disabledRef.current) {
        traceFxPickerClose('row C actions unmounted', closeTraceRef.current?.current ?? {})
      }
    },
    [],
  )
  return (
    <>
      {/* The reason on a wrapper: a disabled button takes no hover. */}
      <span className="flex shrink-0" title={offer.reason}>
        <Button
          ref={ref}
          variant="outline"
          size="sm"
          disabled={offer.disabled}
          aria-label="Add FX on the selection"
          aria-expanded={open}
          data-row-c-fx
          onClick={() => {
            if (open) traceFxPickerClose('FX button pressed')
            setOpen(!open)
          }}
        >
          <AudioWaveform className="size-3.5" />
          <span className={wordClass}>FX</span>
        </Button>
      </span>
      <ProgrammerAddFxSheet
        projectId={projectId}
        open={open}
        offer={offer}
        onClose={() => setOpen(false)}
        anchorRef={ref}
        editor
      />
    </>
  )
}
