import { useEffect, useRef, useState } from 'react'
import { Loader2 } from 'lucide-react'
import {
  Sheet,
  SheetBody,
  SheetContent,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import type { SpeedMaster } from '../../api/speedMastersApi'
import { MASTER_1_DELETE_REFUSAL, useSpeedMasterDelete } from './useSpeedMasterDelete'
import {
  useSaveSpeedMasterMutation,
  useSpeedMasterListQuery,
  useSpeedMasterLiveQuery,
} from '../../store/speedMasters'
import {
  DEFAULT_FOLLOW_RATIO,
  FOLLOW_RATIOS,
  SPEED_MASTER_USAGES,
  derivedBpm,
  eligibleFollowTargets,
  followRatioOf,
  followTargetOf,
  formatFollowRatio,
  leaderNameOf,
  usageOptionLabel,
  type FollowRatio,
} from '@/lib/speedMasterModel'
import { formatBpm } from '../../hooks/useBpmDraft'

/**
 * Radix `Select` reserves the empty string for "no value", so "routes nothing" needs a sentinel
 * of its own rather than `''`. It never reaches the wire — {@link usageToSend} maps it back to
 * the null the PUT wants.
 */
const USAGE_NONE = '__none__'

/**
 * Rename a master, note what it is for, and set the tempo it boots at.
 *
 * The *starting* BPM lives here, not the live one. They are different values with different
 * lifetimes: the row's `bpm` is what the master comes up at after a restart or an import,
 * while the live tempo is whatever it has been tapped to since. Editing them in the same
 * control would make one silently overwrite the other, so the live tempo stays on the row
 * (and the strip) where tap is, and this sheet owns the stored default.
 *
 * It is also the only place the two routing facts are set — a master's **usage** (which
 * category of busked effect defaults to it) and whether it **follows** another master at a
 * ratio. Both are deliberately here rather than on the performance surfaces: they are decisions
 * about how the show is wired, not knobs to reach for mid-cue, and the follow switch in
 * particular takes a master's own tempo away from it — the leader *drives its clock*, so the
 * two beat together rather than merely running at proportional speeds.
 */
export function SpeedMasterDetailSheet({
  open,
  onOpenChange,
  projectId,
  master,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: number
  master: SpeedMaster | null
}) {
  const [saveMaster, { isLoading: isSaving, error: saveError }] = useSaveSpeedMasterMutation()
  // The delete is the sheet's batch delete over one master (library-sheets plan D13): one dialog,
  // naming what uses it — its followers included — instead of an inline alert of its own.
  const {
    run: runDelete,
    busy: isDeleting,
    dialog: deleteDialog,
  } = useSpeedMasterDelete({ projectId, onDeleted: () => onOpenChange(false) })

  const [name, setName] = useState('')
  const [notes, setNotes] = useState('')
  const [startingBpm, setStartingBpm] = useState('')
  const [usage, setUsage] = useState<string>(USAGE_NONE)
  // null = manual. Held as the ratio itself rather than a mode flag plus a ratio, so there is
  // exactly one source of truth for "is this following" and the two cannot disagree.
  const [follow, setFollow] = useState<FollowRatio | null>(null)
  // Which master is followed, as a uuid; null means master 1, the spelling a row written before
  // follow targets existed carries. Kept beside the ratio rather than inside it because the two
  // are edited by different controls and only the pair together is a link.
  const [followTarget, setFollowTarget] = useState<string | null>(null)

  // The bank, for the leader picker: which masters this one may point at, and what they are
  // called. Already fetched by every surface that opens this sheet, so this is a cache read —
  // skipped on a non-numeric route param for the same reason `BuskSpeedRail` skips its own copy,
  // since this sheet's hooks run even while it is closed.
  const { data: bank } = useSpeedMasterListQuery(
    { projectId },
    { skip: !Number.isFinite(projectId) },
  )

  // The leader's *live* tempo, for the follow preview — the preview answers "what will this
  // master run at", which is a question about the running bank, not about stored defaults.
  const leaderUuid = followTarget ?? bank?.find((m) => m.masterIndex === 1)?.uuid ?? null
  const { leaderBpm } = useSpeedMasterLiveQuery(undefined, {
    selectFromResult: ({ data }) => ({
      leaderBpm:
        (leaderUuid == null
          ? data?.find((m) => m.index === 1)
          : data?.find((m) => m.uuid === leaderUuid)
        )?.bpm ?? null,
    }),
  })
  // Through the shared helper rather than an inline find: it is the same lookup every read-only
  // surface uses, and it falls back to "Master N" on a blank name instead of rendering nothing.
  const leaderName = leaderNameOf(bank, followTarget)

  // Seed once per master, tracked by id in a ref rather than by dependencies — the list
  // refetches on every `speedMasters.listChanged`, and depending on the fields would let a
  // rename in another tab replace whatever is being typed here. Same rationale as
  // LookDetailSheet; closing and reopening re-seeds from the server.
  const seededIdRef = useRef<number | null>(null)
  useEffect(() => {
    if (master == null) {
      seededIdRef.current = null
      return
    }
    if (seededIdRef.current === master.id) return
    seededIdRef.current = master.id
    setName(master.name)
    setNotes(master.notes ?? '')
    setStartingBpm(String(master.bpm))
    setUsage(master.usage ?? USAGE_NONE)
    setFollow(followRatioOf(master))
    setFollowTarget(followTargetOf(master))
  }, [master])

  if (!master) return null

  // Master 1 is the global tempo every legacy surface and every unassigned effect resolves
  // to. The server refuses to delete it (409 SPEED_MASTER_PROTECTED), and refuses to give it a
  // follow ratio (400 SPEED_MASTER_CANNOT_FOLLOW) — it is what followers derive *from*. Saying
  // both up front beats round-tripping a request that cannot succeed.
  const isProtected = master.masterIndex === 1
  const parsedBpm = Number(startingBpm)
  const bpmValid = Number.isFinite(parsedBpm) && parsedBpm >= 20 && parsedBpm <= 300
  const storedFollow = followRatioOf(master)
  const storedTarget = followTargetOf(master)
  // Master 1 has two spellings — its uuid, and the null that means it — so the comparison
  // normalises both sides. Without that, opening the sheet on a follower of master 1 and
  // saving a rename would send a redundant re-link (and a picker that shows the uuid it
  // resolved to would read as a pending edit the operator never made).
  const master1Uuid = bank?.find((m) => m.masterIndex === 1)?.uuid ?? null
  const canonicalTarget = (uuid: string | null) => (uuid === master1Uuid ? null : uuid)
  const followChanged =
    follow?.num !== storedFollow?.num ||
    follow?.den !== storedFollow?.den ||
    (follow != null && canonicalTarget(followTarget) !== canonicalTarget(storedTarget))

  // Itself and its own descendants are excluded: a loop is the one shape with no tempo, and the
  // server refuses it (400 SPEED_MASTER_FOLLOW_CYCLE). Offering a choice that can only fail is
  // worse than not offering it.
  const followTargetOptions = eligibleFollowTargets(bank, master)
  const usageToSend = usage === USAGE_NONE ? null : usage
  const usageChanged = usageToSend !== (master.usage ?? null)
  // A follower's tempo is derived, so its stored default is meaningless while linked and the
  // BPM field is not rendered at all — which also means it can never be dirty.
  const bpmChanged = follow == null && bpmValid && parsedBpm !== master.bpm
  const dirty =
    name.trim() !== master.name ||
    (notes.trim() || null) !== (master.notes ?? null) ||
    usageChanged ||
    followChanged ||
    bpmChanged
  // Only the manual arm can be invalid: the BPM field is the only one with a range.
  const canSave = name.trim() !== '' && (follow != null || bpmValid)

  const save = async () => {
    if (!canSave) return
    await saveMaster({
      projectId,
      masterId: master.id,
      name: name.trim(),
      // Only send the tempo when it was actually edited. The field is seeded once per
      // master and the PUT *retunes the running clock*, so resending the seed on a
      // name-only save would snap a master that has been tapped since back to its stored
      // value — silently undoing a live tempo change mid-show. And never send it at all
      // alongside a follow ratio: the server 400s the pair (SPEED_MASTER_FOLLOWER), because
      // a follower's tempo comes from master 1 rather than from a stored default.
      ...(bpmChanged ? { bpm: parsedBpm } : {}),
      // The PUT is a patch, so an untouched key is left alone; usage present-with-null clears.
      ...(usageChanged ? { usage: usageToSend } : {}),
      // Both halves or neither — a half-patch is a 400. Unlinking is both explicitly null, and
      // the target rides with the pair (the server carries the stored leader forward when a
      // ratio-only patch omits it, but this sheet always knows which master it means).
      ...(followChanged
        ? {
            followNum: follow?.num ?? null,
            followDen: follow?.den ?? null,
            followTargetUuid: follow == null ? null : canonicalTarget(followTarget),
          }
        : {}),
      notes: notes.trim() === '' ? null : notes.trim(),
    })
      .unwrap()
      .then(() => onOpenChange(false))
      .catch(() => {
        // Rendered inline below — a duplicate name, or a usage another master already claims,
        // is a 409 and nothing moved.
      })
  }

  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent className="flex flex-col sm:max-w-md">
        <SheetHeader>
          <SheetTitle>
            M{master.masterIndex} · {master.name}
          </SheetTitle>
        </SheetHeader>

        <SheetBody>
          <div className="space-y-1.5">
            <Label htmlFor="speed-master-name">Name</Label>
            <Input
              id="speed-master-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder={`Master ${master.masterIndex}`}
            />
          </div>

          <div className="space-y-1.5">
            <Label htmlFor="speed-master-usage">Default usage</Label>
            <Select value={usage} onValueChange={setUsage}>
              <SelectTrigger id="speed-master-usage">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={USAGE_NONE}>Not routed</SelectItem>
                {SPEED_MASTER_USAGES.map((u) => (
                  <SelectItem key={u} value={u}>
                    {usageOptionLabel(u)}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">
              A busked effect with no explicit speed master follows the master whose usage
              matches its family. One master per usage — unmatched effects stay on Master 1.
            </p>
          </div>

          <div className="border-t" />

          {/* Master 1 is the root every chain ends at, so it is offered no tempo mode at all —
              only the stored default below. Hiding the control rather than disabling it: a
              segmented switch with both halves dead reads as breakage. */}
          {!isProtected && (
            <div className="space-y-1.5">
              <Label>Tempo</Label>
              <ToggleGroup
                type="single"
                value={follow == null ? 'manual' : 'follow'}
                onValueChange={(v) => {
                  if (!v) return
                  setFollow(v === 'follow' ? (storedFollow ?? DEFAULT_FOLLOW_RATIO) : null)
                  // A fresh link starts on the stored leader, or master 1 — the same default
                  // the ratio chips take, and the master every bank has.
                  if (v === 'follow') setFollowTarget(storedTarget ?? master1Uuid)
                }}
                className="w-full gap-1"
              >
                <ToggleGroupItem value="manual" className="flex-1">
                  Manual
                </ToggleGroupItem>
                <ToggleGroupItem value="follow" className="flex-1">
                  Follow
                </ToggleGroupItem>
              </ToggleGroup>
            </div>
          )}

          {follow != null ? (
            <div className="space-y-3">
              <div className="space-y-1.5">
                <Label htmlFor="speed-master-follow-target" className="text-xs">
                  Follows
                </Label>
                <Select
                  value={followTarget ?? master1Uuid ?? ''}
                  onValueChange={(v) => setFollowTarget(v)}
                >
                  <SelectTrigger id="speed-master-follow-target">
                    <SelectValue placeholder="Master 1" />
                  </SelectTrigger>
                  <SelectContent>
                    {followTargetOptions.map((option) => (
                      <SelectItem key={option.uuid} value={option.uuid}>
                        M{option.masterIndex} · {option.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <p className="text-[11px] text-muted-foreground">
                  Any master except this one and anything already following it — a follow chain
                  may not loop.
                </p>
              </div>

              <div className="space-y-1.5">
                <Label className="text-xs">Time signature</Label>
                <ToggleGroup
                  type="single"
                  value={`${follow.num}/${follow.den}`}
                  onValueChange={(v) => {
                    if (!v) return
                    const [num, den] = v.split('/').map(Number)
                    setFollow({ num, den })
                  }}
                  className="w-full gap-1"
                >
                  {FOLLOW_RATIOS.map((r) => (
                    <ToggleGroupItem
                      key={r.label}
                      value={`${r.num}/${r.den}`}
                      className="flex-1 font-bold"
                      aria-label={`Follow ${leaderName} at ${r.num}/${r.den}`}
                    >
                      {r.label}
                    </ToggleGroupItem>
                  ))}
                </ToggleGroup>
              </div>

              {/* What the ratio actually resolves to, off the leader's *live* tempo — the
                  arithmetic is the server's, this only shows its answer. */}
              {leaderBpm != null && (
                <div className="flex items-center gap-3 rounded-md bg-muted/40 p-3">
                  <div className="flex flex-col gap-0.5">
                    <span className="truncate text-[9px] font-bold uppercase tracking-[0.08em] text-muted-foreground">
                      {leaderName}
                    </span>
                    <span className="font-mono text-lg font-bold tabular-nums">
                      {formatBpm(leaderBpm)}
                    </span>
                  </div>
                  <span className="text-sm text-muted-foreground">
                    → {formatFollowRatio(follow.num, follow.den)} →
                  </span>
                  <div className="flex flex-col gap-0.5">
                    <span className="text-[9px] font-bold uppercase tracking-[0.08em] text-muted-foreground">
                      This master
                    </span>
                    <span className="font-mono text-lg font-bold tabular-nums text-primary">
                      {formatBpm(derivedBpm(leaderBpm, follow.num, follow.den))}
                    </span>
                  </div>
                </div>
              )}

              <p className="text-xs text-muted-foreground">
                {leaderName} drives this master&apos;s clock, so their beats land together — a ½
                follower beats on every second beat of its leader. TAP and typed tempos are
                disabled here while linked; retune {leaderName} and this master moves with it,
                everywhere it is shown.
              </p>
            </div>
          ) : (
            <div className="space-y-1.5">
              <Label htmlFor="speed-master-bpm">Starting BPM</Label>
              <Input
                id="speed-master-bpm"
                inputMode="decimal"
                value={startingBpm}
                onChange={(e) => setStartingBpm(e.target.value)}
              />
              <p className="text-xs text-muted-foreground">
                What this master comes up at after a restart or an import. Tapping or typing a
                tempo on the row changes it live and writes back here shortly after.
              </p>
              {startingBpm !== '' && !bpmValid && (
                <p className="text-xs text-destructive">Must be between 20 and 300.</p>
              )}
            </div>
          )}

          <div className="space-y-1.5">
            <Label htmlFor="speed-master-notes">Notes</Label>
            <Textarea
              id="speed-master-notes"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="What this master is for"
              rows={3}
            />
          </div>

          {master.referenceCount > 0 && (
            <p className="text-xs text-muted-foreground">
              {master.referenceCount} saved{' '}
              {master.referenceCount === 1 ? 'reference' : 'references'} — look effects, cue
              effects and per-layer overrides that follow this master.
            </p>
          )}

          {saveError != null && (
            <Alert variant="destructive">
              <AlertDescription>
                {(saveError as { data?: { error?: string } })?.data?.error ??
                  'Could not save this master.'}
              </AlertDescription>
            </Alert>
          )}

        </SheetBody>

        <SheetFooter className="flex-row justify-between">
          <Button
            variant="destructive"
            onClick={() => void runDelete([master])}
            disabled={isDeleting || isProtected}
            title={isProtected ? MASTER_1_DELETE_REFUSAL : undefined}
          >
            {isDeleting && <Loader2 className="size-4 animate-spin" />}
            Delete
          </Button>
          <div className="flex gap-2">
            <Button variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button onClick={save} disabled={!dirty || isSaving || !canSave}>
              {isSaving && <Loader2 className="size-4 animate-spin" />}
              Save
            </Button>
          </div>
        </SheetFooter>
      </SheetContent>
      {deleteDialog}
    </Sheet>
  )
}
