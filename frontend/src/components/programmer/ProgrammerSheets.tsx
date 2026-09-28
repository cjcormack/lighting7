import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { IncludeSheet } from './IncludeSheet'
import { MakeLayerSheet } from './MakeLayerSheet'
import { RecordLookSheet } from './RecordLookSheet'
import { RecordSheet } from './RecordSheet'
import { UpdateDialog } from './UpdateDialog'
import { useProgrammerSummaryQuery } from '@/store/programmer'

/** Which cue (if any) a Record opens preselected. */
export interface RecordPreset {
  /** Merge into this existing cue. */
  targetCueId?: number
  targetCueName?: string
  /** Create a new cue in this stack. */
  defaultCueStackId?: number
}

interface ProgrammerSheetsValue {
  openRecord: (preset?: RecordPreset) => void
  openRecordLook: () => void
  openInclude: () => void
  openUpdate: () => void
  /** Promote a Local selection into a shared Look, applied here as a layer. */
  openMakeLayer: () => void
}

const Ctx = createContext<ProgrammerSheetsValue | null>(null)

/**
 * The programmer's modal surfaces, mounted **once** for the whole page.
 *
 * They used to live inside the toolbar, which was fine while the toolbar was the only thing that
 * opened them. It is not: the source strip owns Update and offers Record, the action bar owns
 * Record and Include, and Session 2's stack rail will want Include too. Without one owner, either
 * the strip prop-drills four setters through the page or a second `RecordSheet` gets mounted — and
 * two of those would each hold their own draft of the same write.
 */
export function ProgrammerSheetsProvider({
  projectId,
  children,
}: {
  projectId: number
  children: ReactNode
}) {
  const [record, setRecord] = useState<RecordPreset | null>(null)
  const [recordLook, setRecordLook] = useState(false)
  const [include, setInclude] = useState(false)
  const [update, setUpdate] = useState(false)
  const [makeLayer, setMakeLayer] = useState(false)
  const { data: summary } = useProgrammerSummaryQuery()

  const openRecord = useCallback((preset?: RecordPreset) => setRecord(preset ?? {}), [])
  const value = useMemo<ProgrammerSheetsValue>(
    () => ({
      openRecord,
      openRecordLook: () => setRecordLook(true),
      openInclude: () => setInclude(true),
      openUpdate: () => setUpdate(true),
      openMakeLayer: () => setMakeLayer(true),
    }),
    [openRecord],
  )

  return (
    <Ctx.Provider value={value}>
      {children}
      {/* Keyed on the preset so reopening Record with a different destination starts a fresh
          draft rather than reusing the last one's. The key names WHICH id it is, not just the
          number: a cue id and a stack id come from different tables and collide freely, so a bare
          `targetCueId ?? defaultCueStackId` makes "update cue 3" and "new cue in stack 3" the same
          key — and the second one silently reuses the first's draft. */}
      {record != null && (
        <RecordSheet
          key={
            record.targetCueId != null
              ? `cue:${record.targetCueId}`
              : record.defaultCueStackId != null
                ? `stack:${record.defaultCueStackId}`
                : 'new'
          }
          open
          onOpenChange={(o) => !o && setRecord(null)}
          projectId={projectId}
          targetCueId={record.targetCueId}
          targetCueName={record.targetCueName}
          defaultCueStackId={record.defaultCueStackId}
        />
      )}
      <RecordLookSheet open={recordLook} onOpenChange={setRecordLook} projectId={projectId} />
      <MakeLayerSheet open={makeLayer} onOpenChange={setMakeLayer} projectId={projectId} />
      <IncludeSheet open={include} onOpenChange={setInclude} projectId={projectId} />
      <UpdateDialog
        open={update}
        onOpenChange={setUpdate}
        projectId={projectId}
        includeTarget={summary?.lastIncluded ?? null}
      />
    </Ctx.Provider>
  )
}

/** Open one of the programmer's sheets. Throws outside the provider — there is no sensible no-op. */
export function useProgrammerSheets(): ProgrammerSheetsValue {
  const value = useContext(Ctx)
  if (value == null) {
    throw new Error('useProgrammerSheets must be used inside <ProgrammerSheetsProvider>')
  }
  return value
}
