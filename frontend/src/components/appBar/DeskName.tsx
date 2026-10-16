import { useEffect } from 'react'
import { useInstallQuery } from '@/store/installs'
import { useProjectListQuery } from '@/store/projects'
import { deskNameOf, documentTitleOf } from '@/lib/projectSwitch'

/** The desk's friendly name (navigation plan D5), `Lighting desk` until the install row has one. */
export function useDeskName(): string {
  const { data: install } = useInstallQuery()
  return deskNameOf(install)
}

/**
 * The desk's name, small and muted before the switcher, then a divider (D5). Hidden below 640px of
 * header: the switcher names the show, and on a phone the header has no room for both.
 */
export function DeskName() {
  const name = useDeskName()
  return (
    <span data-desk-name="" className="hidden shrink-0 items-center gap-2 @[640px]:flex">
      <span className="max-w-40 truncate text-xs font-medium text-primary-foreground/70">{name}</span>
      <span aria-hidden className="h-4 w-px bg-primary-foreground/30" />
    </span>
  )
}

/** `document.title` as *Show · Desk name* — the show on the rig, whatever this window is viewing (§4.1). */
export function useDocumentTitle() {
  const deskName = useDeskName()
  const { data: projects } = useProjectListQuery()
  const current = projects?.find((p) => p.isCurrent) ?? null
  const title = documentTitleOf(current, deskName)
  useEffect(() => {
    document.title = title
  }, [title])
}
