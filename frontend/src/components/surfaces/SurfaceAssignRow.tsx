import type { AssignSubject } from './assignOffer'
import { AssignToSurfaceButton } from './AssignToSurface'
import { BoundOnChip } from './BoundOnChip'

/**
 * The fixture sheet's and the group sheet's surface line: *Assign to surface…* and what the target is
 * bound on (surface-setup plan §4, D). [also] are keys whose bindings count as the target's for the
 * chip — a fixture's heads — but are not offered to the assign, which binds the target itself.
 */
export function SurfaceAssignRow({
  name,
  subject,
  also = [],
  assignable,
}: {
  name: string
  subject: AssignSubject
  also?: readonly AssignSubject[]
  assignable: boolean
}) {
  return (
    <div className="flex flex-wrap items-center gap-1.5 empty:hidden">
      {assignable && <AssignToSurfaceButton className="h-7" target={{ name, subjects: [subject] }} />}
      <BoundOnChip subjects={[subject, ...also]} />
    </div>
  )
}
