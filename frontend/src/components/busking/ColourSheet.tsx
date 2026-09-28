import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { toast } from 'sonner'
import { lightingApi } from '@/api/lightingApi'
import { ColourEditor, emitterHeadCounts, type ColourChannels, type ColourRecentSource } from '@/components/editor/ColourEditor'
import type { WriteTarget } from '@/components/fixtures-list/rowModel'
import { NewTemplateFromSelectionSheet } from '@/components/programmer/NewTemplateFromSelectionSheet'
import { useEditorCramped } from '@/components/editor/EditorSurface'
import { useLivePush } from '@/components/editor/useLivePush'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import type { AttributeFamily } from '@/lib/attributeFamily'
import { effectiveRig } from '@/lib/buskRig'
import { computeCombinedCss } from '@/lib/colourMath'
import { rigHeadOrder } from '@/lib/liveAppearance'
import { getProgrammerFadeMs } from '@/lib/programmerFade'
import { useBuskRigQuery } from '@/store/busk'
import { useGroupListQuery } from '@/store/groups'
import type { Fixture, ColourPropertyDescriptor } from '@/store/fixtures'
import { lookLayerTarget, type BuskingTarget } from './buskingTypes'

/**
 * The side sheet's **Colour** tab (busk-further plan D8; `Sheets.dc.html` §Colour): the docked
 * host of `ColourEditor` (editor-kit plan D10), writing to the selection as it is dragged.
 *
 * What is the host's: **the selection → write-targets planning** (`planColourWrites`, which is
 * about the busk selection and not the editor — a group as a group write, a cell by its element
 * key, a whole fixture as one write or one per cell for a pixel bar whose colour lives on its
 * cells), the **rig order** Pick reads in, the **Spread hand-over**, the live push and its release,
 * and the save sheet. Everything else — the picker, the fields, the emitter rows, the read-out,
 * Pick and its hidden leaves, Recent, the footer — is the editor's, docked.
 *
 * **It writes literals to Local, and only that.** Every drag is `programmer.setColour` per selected
 * target, which is what a template pad's click does and what the programmer's colour cell does.
 * There is **no layer arm and no ⌥ arm**: a picked colour has no library referent for a layer to
 * follow, so *Save as template…* is the route to something trackable, through the same
 * `POST /templates/from-programmer` the strip uses. The writes go through {@link useLivePush}, the
 * tempo fader's discipline over six bytes.
 *
 * **The family mask is not consulted.** The desk's mask gates *presses*, and a colour drag is a
 * value write like a cell edit, so the sheet does not refuse under a Position marquee. A Recent
 * chip is a *press*, and goes through `useTemplatePress` under the mask like any other.
 *
 * **The buffer is seeded from the rig** — the editor's Pick on mount and on every change of heads,
 * retried until they have reported (`pickOnTargets`) — so a single-channel edit means what it
 * means in the cell editor: the other RGB bytes are left *where the rig has them*, not at a
 * neutral the sheet made up. The three emitters start at 0 whatever the rig holds, because the
 * appearance store exposes one folded colour and nothing per emitter. And **the release is read
 * from the window**, as the tempo fader's is: react-colorful binds its own release to the
 * document, so a drag let go outside the sheet still ends the gesture here rather than leaving a
 * stale flag for the next pointer to flush.
 *
 * The editor's *Spread…* hands the current channels to `onSpread`, and the Spread tab's *From* is
 * their **RGB** — a colour intent has no emitter component, so a white or amber this tab was
 * driving does not travel (`SpreadSeed` in `SpreadSheet.tsx` says the same). The side sheet's two
 * hosts wire it (`SideSheet.tsx`'s `useSpreadSeed`), and a host with no Spread tab to open leaves
 * it out, which draws the button inert.
 */

export interface ColourSheetProps {
  projectId: number
  selectedTargets: Map<string, BuskingTarget>
  /** The selection's attribute mask, for a Recent press. Null is every attribute. */
  families: AttributeFamily[] | null
  /** Open the Spread tab with *From* set to the current colour. Absent, the button is inert. */
  onSpread?: (from: ColourChannels) => void
  /** Force the two-column layout; the cramped height query answers it otherwise. */
  compact?: boolean
}

/** One `programmer.setColour` the sheet sends for one selected target. */
export interface ColourWrite {
  targetType: 'fixture' | 'group'
  targetKey: string
  propertyName: string
  colour: { r: number; g: number; b: number; w?: number; a?: number; uv?: number }
  /** Names the group a per-member write came from — a fan-out that could not be a group write. */
  sourceGroup?: string
}

const EMPTY_SELECTION_TOAST = 'colour-sheet-empty-selection'
const NEUTRAL: ColourChannels = { r: 255, g: 255, b: 255, w: 0, a: 0, uv: 0 }
/**
 * The knob's seed — a constant, deliberately. The editor re-seeds its knob from `combinedCss`
 * whenever it changes while open, and here it is always open; routing every drag and typed byte
 * back into that prop put `react-colorful` into a ping-pong that never settled (see the editor's
 * `combinedCss` note — found on the dev desk, at 20 writes a second). The knob moves only when the
 * editor means it to: on Pick, which is the editor's own, and the seed from the rig, which is Pick.
 */
const NEUTRAL_CSS = computeCombinedCss(NEUTRAL.r, NEUTRAL.g, NEUTRAL.b, 0, 0, 0)

function colourDescriptorOf(properties: readonly { type: string }[]): ColourPropertyDescriptor | null {
  return (properties.find((p) => p.type === 'colour') as ColourPropertyDescriptor | undefined) ?? null
}

/** Every colour descriptor a head writes through: its own, or its cells'. */
function colourDescriptorsOf(fixture: Fixture): ColourPropertyDescriptor[] {
  const own = colourDescriptorOf(fixture.properties)
  if (own != null) return [own]
  return (fixture.elements ?? []).map((element) => colourDescriptorOf(element.properties)).filter((d): d is ColourPropertyDescriptor => d != null)
}

/** Which emitters a descriptor can take, as a comparable key. */
function emitterShape(descriptor: ColourPropertyDescriptor): string {
  return `${descriptor.whiteChannel ? 'w' : '-'}${descriptor.amberChannel ? 'a' : '-'}${descriptor.uvChannel ? 'u' : '-'}`
}

/** The colour a head can take: RGB always, an emitter only where its descriptor has the channel. */
function colourFor(channels: ColourChannels, descriptor: ColourPropertyDescriptor): ColourWrite['colour'] {
  let { r, g, b } = channels
  // The picker's pure-white branch emits `0,0,0,w=255` for a selection whose union has white; a
  // head without one would go black instead of white, so the undeliverable white is folded back
  // into RGB — `useCellWriters.writeColour`'s rule.
  if (channels.w > 0 && descriptor.whiteChannel == null) {
    r = Math.max(r, channels.w)
    g = Math.max(g, channels.w)
    b = Math.max(b, channels.w)
  }
  return {
    r,
    g,
    b,
    w: descriptor.whiteChannel ? channels.w : undefined,
    a: descriptor.amberChannel ? channels.a : undefined,
    uv: descriptor.uvChannel ? channels.uv : undefined,
  }
}

/** A whole fixture's writes: one on its own colour, or one per cell for a bar whose colour lives on its cells. */
function fixtureWrites(fixture: Fixture, channels: ColourChannels, sourceGroup?: string): ColourWrite[] {
  const own = colourDescriptorOf(fixture.properties)
  if (own != null) {
    return [{ targetType: 'fixture', targetKey: fixture.key, propertyName: own.name, colour: colourFor(channels, own), sourceGroup }]
  }
  const writes: ColourWrite[] = []
  for (const element of fixture.elements ?? []) {
    const descriptor = colourDescriptorOf(element.properties)
    if (descriptor != null) {
      writes.push({ targetType: 'fixture', targetKey: element.key, propertyName: descriptor.name, colour: colourFor(channels, descriptor), sourceGroup })
    }
  }
  return writes
}

/**
 * The writes one colour becomes over a selection: a fixture as one write (or one per cell, for a
 * pixel bar whose colour lives on its cells), a cell as one write by its element key, and a group
 * as **one group write where its members agree on emitters** — otherwise one write per member,
 * each carrying `sourceGroup`. A head with no colour at all contributes nothing.
 *
 * The group rule is forced by the desk: `writeGroupProperty` fans one colour to every member and
 * `resolveColour` writes R/G/B verbatim, adding white only on a head that has one — it can fold an
 * undeliverable white into RGB for none of them. So pure white (`0,0,0,w255`, which the picker emits
 * because the emitter rows are a union) sent as a group write to a group holding an RGBW and an
 * RGB head would set the RGB head to black. Per-member writes put the fold where it can be made,
 * and `sourceGroup` keeps the group's name on each, which is what that field exists for.
 */
export function planColourWrites(
  selected: readonly BuskingTarget[],
  channels: ColourChannels,
  fixtures: readonly Fixture[] | undefined,
): ColourWrite[] {
  const writes: ColourWrite[] = []
  for (const target of selected) {
    if (target.type === 'group') {
      const members = (fixtures ?? []).filter((fixture) => fixture.groups.includes(target.name))
      const descriptors = members.flatMap(colourDescriptorsOf)
      if (descriptors.length === 0) continue
      const shapes = new Set(descriptors.map(emitterShape))
      if (shapes.size === 1) {
        writes.push({
          targetType: 'group',
          targetKey: target.name,
          propertyName: descriptors[0].name,
          colour: colourFor(channels, descriptors[0]),
        })
      } else {
        for (const member of members) writes.push(...fixtureWrites(member, channels, target.name))
      }
      continue
    }
    if (target.element != null) {
      const descriptor = colourDescriptorOf(target.element.properties)
      if (descriptor != null) {
        writes.push({ targetType: 'fixture', targetKey: target.element.key, propertyName: descriptor.name, colour: colourFor(channels, descriptor) })
      }
      continue
    }
    writes.push(...fixtureWrites(target.fixture, channels))
  }
  return writes
}

/**
 * The selection as the editor reads it — a group as its members, a cell as itself with its parent
 * named (`fixtureKey` / `cellIndex`, the shape `rowWriteTargets` stamps on the programmer's element
 * rows, so the editor derives its heads one way for both hosts). Exported for the Spread tab, whose
 * family segment asks the same `targetFamilies` question of the same expansion, so the two tabs
 * cannot count a selection's heads two ways.
 */
export function writeTargetsOf(selected: readonly BuskingTarget[], fixtures: readonly Fixture[] | undefined): WriteTarget[] {
  const out: WriteTarget[] = []
  for (const target of selected) {
    if (target.type === 'group') {
      for (const fixture of fixtures ?? []) {
        if (fixture.groups.includes(target.name)) out.push({ key: fixture.key, properties: fixture.properties, elements: fixture.elements })
      }
    } else if (target.element != null) {
      const elementKey = target.element.key
      const cellIndex = (target.fixture.elements ?? []).findIndex((element) => element.key === elementKey)
      out.push({
        key: elementKey,
        properties: target.element.properties,
        fixtureKey: target.fixture.key,
        cellIndex: cellIndex < 0 ? undefined : cellIndex,
      })
    } else {
      out.push({ key: target.fixture.key, properties: target.fixture.properties, elements: target.fixture.elements })
    }
  }
  return out
}

const sameChannels = (a: ColourChannels, b: ColourChannels) =>
  a.r === b.r && a.g === b.g && a.b === b.b && a.w === b.w && a.a === b.a && a.uv === b.uv

export function ColourSheet({ projectId, selectedTargets, families, onSpread, compact }: ColourSheetProps) {
  const cramped = useEditorCramped()
  const isCompact = compact ?? cramped
  const selected = useMemo(() => [...selectedTargets.values()], [selectedTargets])
  const { fixtures } = useFixtureLookup()
  const { data: groups } = useGroupListQuery()
  const { data: rig } = useBuskRigQuery(projectId)

  const writeTargets = useMemo(() => writeTargetsOf(selected, fixtures), [selected, fixtures])
  // The rows and the count line come off one probe: the emitters a `setColour` can drive.
  const emitterCounts = useMemo(() => emitterHeadCounts(writeTargets), [writeTargets])
  // The order Pick reads in — the built rig's, or the show-all fallback's, which is the desk's own
  // order for an empty rig.
  const headOrder = useMemo(() => rigHeadOrder(effectiveRig(rig, groups, fixtures).rows, fixtures), [rig, groups, fixtures])
  const layerTargets = useMemo(() => selected.map(lookLayerTarget), [selected])

  const [channels, setChannels] = useState<ColourChannels>(NEUTRAL)
  // Written by the handlers that move `channels`, never at render time: a fast drag can dispatch
  // its last move and its release inside one task with no render between, and a ref assigned
  // during render would hand the release the colour from the move before last — the speed rail's
  // `slideBpmRef` rule, for its reason.
  const channelsRef = useRef(channels)
  const gestureRef = useRef(false)

  const { push, flush, reset } = useLivePush<ColourChannels>(
    (value) => {
      const fadeMs = getProgrammerFadeMs()
      for (const write of planColourWrites(selected, value, fixtures)) {
        lightingApi.programmer.setColour(write.targetType, write.targetKey, write.propertyName, write.colour, fadeMs, write.sourceGroup)
      }
    },
    { equals: sameChannels },
  )

  // A fresh selection is a fresh gesture — nothing the last one sent says where these heads are,
  // so the dedupe is reset and any gesture in flight is over. The buffer itself is re-seeded by the
  // editor's Pick on the new heads.
  const selectionKey = useMemo(() => [...selectedTargets.keys()].join('|'), [selectedTargets])
  useEffect(() => {
    gestureRef.current = false
    reset()
  }, [selectionKey, reset])

  const onColourChange = useCallback(
    (r: number, g: number, b: number, w?: number, a?: number, uv?: number) => {
      const next: ColourChannels = { r, g, b, w: w ?? 0, a: a ?? 0, uv: uv ?? 0 }
      channelsRef.current = next
      setChannels(next)
      if (selected.length === 0) {
        // The strip's own sentence, keyed so a drag over nothing says it once rather than per frame.
        toast.error('Select the fixtures this should land on first', { id: EMPTY_SELECTION_TOAST })
        return
      }
      gestureRef.current = true
      push(next)
    },
    [selected.length, push],
  )

  // What Pick read is the buffer now — the editor has moved its own fields and knob; this keeps the
  // sheet's copy (the release flush, the Spread hand-over) in step, and writes nothing.
  const onPick = useCallback((next: ColourChannels) => {
    channelsRef.current = next
    setChannels(next)
  }, [])

  // The release: whatever the finger let go on lands, past the floor — and only after a gesture
  // wrote something, so a pointer lifted after Pick does not turn the read into a write. Listened
  // for on the **window**, as the tempo fader's is: the picker binds its own release to the
  // document, so a drag let go outside the sheet never reaches a handler on it, and a flag left
  // standing would be flushed by the next pointer to land anywhere in the sheet.
  useEffect(() => {
    const onRelease = () => {
      if (!gestureRef.current) return
      gestureRef.current = false
      flush(channelsRef.current)
    }
    window.addEventListener('pointerup', onRelease)
    window.addEventListener('pointercancel', onRelease)
    return () => {
      window.removeEventListener('pointerup', onRelease)
      window.removeEventListener('pointercancel', onRelease)
    }
  }, [flush])

  const recent = useMemo<ColourRecentSource>(
    () => ({ projectId, targets: layerTargets, localFamilies: families }),
    [projectId, layerTargets, families],
  )
  const [saving, setSaving] = useState(false)
  const onSave = useCallback(() => setSaving(true), [])

  return (
    <div data-colour-sheet={isCompact ? 'compact' : 'full'} className="flex min-h-0 flex-1 flex-col border-l">
      <ColourEditor
        r={channels.r}
        g={channels.g}
        b={channels.b}
        w={emitterCounts.white > 0 ? channels.w : undefined}
        a={emitterCounts.amber > 0 ? channels.a : undefined}
        uv={emitterCounts.uv > 0 ? channels.uv : undefined}
        combinedCss={NEUTRAL_CSS}
        hasWhiteChannel={emitterCounts.white > 0}
        hasAmberChannel={emitterCounts.amber > 0}
        hasUvChannel={emitterCounts.uv > 0}
        onColourChange={onColourChange}
        channelFields
        compact={isCompact}
        open
        docked
        targets={writeTargets}
        headOrder={headOrder}
        projectId={projectId}
        pickOnTargets
        recent={recent}
        onPick={onPick}
        onSave={onSave}
        onSpread={onSpread}
      />

      <NewTemplateFromSelectionSheet
        open={saving}
        onOpenChange={setSaving}
        projectId={projectId}
        families={['COLOUR']}
        targets={layerTargets}
      />
    </div>
  )
}
