import { useCallback } from 'react'
import { AddLayerSheet } from './AddLayerSheet'
import { programmerAddLayer } from '@/store/programmer'
import type { CueLayer } from '@/api/cuesApi'

export type ProgrammerAddLayerKind = 'look' | 'template'

/**
 * The programmer's `AddLayerSheet`: the shared picker, opened on one library, and the one place
 * a picked layer is turned into a `programmer.addLayer` frame.
 *
 * It was `ProgrammerLookStack`'s, back when that component drew the stack's section chrome and
 * its Add button. Session 3 of the space plan gave the rail one footer — `+ Look · + Template ·
 * + Effect` — and one `+` on the collapsed strip that opens the same three doors, and the stack
 * became rows only. The sheet therefore has to outlive the stack (the strip is on screen while
 * the rail body is not), which is why it is mounted by `ProgrammerRail` rather than inside it.
 *
 * `kind` is the door that was pressed and doubles as `open`: null closes it, and a change of
 * kind while open is not a gesture anything offers.
 */
export function ProgrammerAddLayerSheet({
  projectId,
  kind,
  onClose,
}: {
  projectId: number
  kind: ProgrammerAddLayerKind | null
  onClose: () => void
}) {
  const handleAdd = useCallback(
    (layer: CueLayer) => {
      // The picker's timing fields — and *only* those — are dropped rather than sent: a programmer
      // layer fires now, and "in 3 seconds" is a property of a cue's playback, which the
      // programmer has none of. The picker is told so via `allowTiming={false}`, so they are not
      // offered either — a field the operator can fill in and this call then ignores is worse
      // than no field.
      //
      // Every other field the picker sets is forwarded. `propertyMask` in particular: the picker
      // masks a template layer to the template's own family so the row cannot read as "this
      // could touch anything", and dropping it here made the identical picker produce a masked
      // layer in a cue and an unmasked one in the programmer.
      programmerAddLayer({
        lookId: layer.lookId ?? undefined,
        templateId: layer.templateId ?? undefined,
        targets: layer.targets,
        propertyMask: layer.propertyMask ?? undefined,
        speedMasterUuid: layer.speedMasterUuid ?? undefined,
        rateSpeedMasterUuid: layer.rateSpeedMasterUuid ?? undefined,
      })
      onClose()
    },
    [onClose],
  )

  return (
    <AddLayerSheet
      open={kind != null}
      onOpenChange={(next) => !next && onClose()}
      projectId={projectId}
      defaultTarget={null}
      allowTiming={false}
      kind={kind ?? undefined}
      onAdd={handleAdd}
    />
  )
}
