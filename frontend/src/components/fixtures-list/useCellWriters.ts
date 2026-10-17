import { useMemo } from 'react'
import { lightingApi } from '../../api/lightingApi'
import { useEditorContext } from '../../components/programmer/EditorContext'
import {
  parseProgrammerEntryValue,
  parseProgrammerValue,
  serializeColour,
  serializeLevel,
  serializePosition,
} from '../../lib/programmerValue'
import { lookRowKey, useLookRowStore } from '../programmer/LookRowStore'
import { useCueRowStore, type CueRowStoreValue } from '../cues/editor/CueRowStore'
import type { CueRowTargetType } from '../cues/editor/cueRowDraft'
import { getProgrammerFadeMs } from '../../lib/programmerFade'
import type { PlannedWrite } from './rowModel'
import type { ChannelRef, ColourPropertyDescriptor, WhiteMixPropertyDescriptor } from '../../store/fixtures'

export interface CellWriters {
  /** `propertyName` is the backend's own name for the property behind `ref`. */
  writeSlider(fixtureKey: string, propertyName: string, ref: ChannelRef, value: number): void
  /** A white-mix [property] takes the RGB alone; the desk lands it as the whites' nearest mix. */
  writeColour(
    fixtureKey: string,
    property: ColourPropertyDescriptor | WhiteMixPropertyDescriptor,
    r: number,
    g: number,
    b: number,
    w?: number,
    a?: number,
    uv?: number,
  ): void
  /** Omitted axes are left untouched (live) or filled from the fixture's
   *  current value (cue mode needs both for setProperty('position')). */
  writePosition(
    fixtureKey: string,
    pan: ChannelRef,
    tilt: ChannelRef,
    panValue: number | undefined,
    tiltValue: number | undefined,
    /** Property names for the pan/tilt axes when they are separate sliders. */
    axisProperties?: { pan?: string; tilt?: string },
  ): void
  writeSetting(fixtureKey: string, propertyName: string, ref: ChannelRef, level: number): void
  /**
   * Take a property out of Local — the marquee's Backspace. In a cue context it takes the cue's own
   * row away (`CueRowStore.removeValue`). A Look layer's row draft has no removal (`LookRowStore`
   * exposes `setValue` alone), so in that context this is a no-op and the container does not offer
   * the key there. A no-op rather than a throw because the writers are one object serving every
   * cell, and the gate belongs where the gesture is.
   */
  clearValue(fixtureKey: string, propertyName: string): void
}

/**
 * EditorContext-aware imperative writers. Like useUpdateChannel, these take the
 * target as an argument rather than closing over one descriptor, so a single
 * hook instance serves every cell in the table — batch apply is a loop over
 * these calls.
 *
 * Routing: live → the **programmer** (Layer 2) at property level. Look contexts are a no-op or go
 * to the row draft: Look rows are property-name-keyed, which this channel-level layer doesn't
 * carry. Both a channel ref and a property name are passed so neither branch has to
 * reverse-engineer the other. A **cue** context (the cue editor's Values grid) goes to the cue's
 * own rows in its `CueRowStore`, with no live fallback — see `cueCellWriters`.
 *
 * There was a live route until session 2b — `cueEdit.*`, which put a cue being edited on stage. The
 * backend protocol was deleted in the post-refactor sweep; the cue arm above writes the stored cue
 * and never the rig (show-cue-editing plan P2), so it is not that route back.
 */
/**
 * Dispatch one planned write to the matching writer. The single place that maps a
 * `(target, resolution, commit)` triple onto a writer call — cell edits, batch apply and the raw Spread
 * all funnel through it, so the property names the programmer is keyed by are derived once.
 *
 * Commits whose shape doesn't match the resolution are skipped (planBatchWrites already
 * filters those, but the raw Spread builds its commits directly).
 */
export function applyPlannedWrite(writers: CellWriters, planned: PlannedWrite): void {
  const { target, resolution, commit } = planned
  switch (commit.kind) {
    case 'slider':
      if (resolution.kind === 'slider') {
        writers.writeSlider(target.key, resolution.property.name, resolution.property.channel, commit.value)
      }
      break
    case 'colour':
      if (resolution.kind === 'colour' || resolution.kind === 'white-mix') {
        writers.writeColour(
          target.key,
          resolution.property,
          commit.r,
          commit.g,
          commit.b,
          commit.w,
          commit.a,
          commit.uv,
        )
      }
      break
    case 'position':
      if (resolution.kind === 'position') {
        writers.writePosition(
          target.key,
          resolution.pan,
          resolution.tilt,
          commit.pan,
          commit.tilt,
          // Present only when the position was paired from two axis sliders; a real
          // `position` descriptor writes both axes as one entry instead.
          resolution.property
            ? undefined
            : { pan: resolution.panProperty?.name, tilt: resolution.tiltProperty?.name },
        )
      }
      break
    case 'setting':
      if (resolution.kind === 'setting' || resolution.kind === 'colour-setting') {
        writers.writeSetting(
          target.key,
          resolution.property.name,
          resolution.property.channel,
          commit.level,
        )
      }
      break
  }
}

export function useCellWriters(): CellWriters {
  const ctx = useEditorContext()
  // Null outside the programmer, and null in the programmer's other two scopes. Read
  // unconditionally — a hook cannot be called behind the `ctx.kind` test below.
  const lookStore = useLookRowStore()
  const setLookValue = ctx.kind === 'lookLayer' ? lookStore?.setValue : undefined
  // The cue editor's store (show-cue-editing plan §3.3). Null outside a cue editor.
  const cueStore = useCueRowStore()
  const inCue = ctx.kind === 'cue'

  return useMemo<CellWriters>(() => {
    // **No live fallback.** A cue edit lands in the cue's own rows and nowhere else (P2): with no
    // store above it — which no host mounts — the write is dropped rather than reaching the rig.
    if (inCue) return cueStore ? cueCellWriters(cueStore, 'fixture') : NO_WRITERS

    const writeChannelValue = (
      fixtureKey: string,
      propertyName: string,
      ref: ChannelRef,
      value: number,
    ) => {
      if (setLookValue) {
        setLookValue(fixtureKey, propertyName, serializeLevel(value))
        return
      }
      lightingApi.programmer.set('fixture', fixtureKey, propertyName, serializeLevel(value))
    }

    return {
      writeSlider: writeChannelValue,
      writeSetting: writeChannelValue,

      clearValue(fixtureKey, propertyName) {
        if (setLookValue) return
        // By the programmer fade, read at press time — the same store the action bar's Clear and
        // Blind fade by. A release that snapped while the two beside it faded would make the
        // keyboard the odd one out.
        lightingApi.programmer.clearEntry('fixture', fixtureKey, propertyName, getProgrammerFadeMs())
      },

      writeColour(fixtureKey, property, r, g, b, w, a, uv) {
        if (property.type === 'whiteMix') {
          // Only the RGB lands on a white mix: a white component folds into it, as on a head
          // without a white channel (below), and amber and UV have nowhere to go.
          const white = w ?? 0
          const rgb = { r: Math.max(r, white), g: Math.max(g, white), b: Math.max(b, white) }
          if (setLookValue) setLookValue(fixtureKey, property.name, serializeColour(rgb.r, rgb.g, rgb.b))
          else lightingApi.programmer.setColour('fixture', fixtureKey, property.name, rgb)
          return
        }
        if (setLookValue) {
          // **No wire sampling here**, unlike the live branch below. A stored Look row must not
          // bake in the current stage state of a fixture the layer may not even target: an
          // undefined component is one the caller did not set, and `serializeColour` elides a
          // zero, so the row stays as narrow as the edit was.
          setLookValue(fixtureKey, property.name, serializeColour(r, g, b, w, a, uv))
          return
        }
        // A batch commit's white component was chosen against a fixture that
        // HAS a white channel (the picker's pure-white branch emits
        // 0,0,0,w=255). A target without one would otherwise get its RGB
        // zeroed and the white silently skipped — i.e. go black instead of
        // white — so fold the undeliverable white back into RGB.
        if (w !== undefined && w > 0 && !property.whiteChannel) {
          r = Math.max(r, w)
          g = Math.max(g, w)
          b = Math.max(b, w)
        }
        // Live: one entry for the whole colour, extended components included.
        //
        // A colour entry is atomic — a component the write omits is set to 0, not left alone.
        // So for any channel the fixture actually has, an undefined component is filled from
        // its current value rather than dropped. Without this, callers that legitimately
        // supply only RGB (the old client colour fan did) would black out the
        // white/amber/UV emitters on every RGBW fixture they touched.
        const current = (ref?: ChannelRef) =>
          ref ? lightingApi.channels.get(ref.universe, ref.channelNo) : undefined
        lightingApi.programmer.setColour('fixture', fixtureKey, property.name, {
          r,
          g,
          b,
          w: property.whiteChannel ? (w ?? current(property.whiteChannel)) : undefined,
          a: property.amberChannel ? (a ?? current(property.amberChannel)) : undefined,
          uv: property.uvChannel ? (uv ?? current(property.uvChannel)) : undefined,
        })
      },

      writePosition(fixtureKey, pan, tilt, panValue, tiltValue, axisProperties) {
        if (panValue === undefined && tiltValue === undefined) return
        if (setLookValue) {
          // Separate axis sliders write the axis that moved and leave the other row alone; a real
          // `position` descriptor is one atomic row, so a single-axis nudge has to supply both —
          // and takes the other from the *draft or the Look*, never from the wire, for the reason
          // `writeColour` gives above.
          if (axisProperties) {
            if (panValue !== undefined && axisProperties.pan) {
              setLookValue(fixtureKey, axisProperties.pan, serializeLevel(panValue))
            }
            if (tiltValue !== undefined && axisProperties.tilt) {
              setLookValue(fixtureKey, axisProperties.tilt, serializeLevel(tiltValue))
            }
            return
          }
          const held = lookStore?.draft.get(fixtureKey, 'position')
          const stored = held ?? lookRowValueOf(lookStore, fixtureKey, 'position')
          const parsed = stored ? parseProgrammerValue(stored) : null
          const base = parsed?.kind === 'position' ? parsed : { pan: 0, tilt: 0 }
          setLookValue(
            fixtureKey,
            'position',
            serializePosition(panValue ?? base.pan, tiltValue ?? base.tilt),
          )
          return
        }
        if (axisProperties) {
          // Separate pan/tilt sliders: write only the axis that moved. Folding them into a
          // `position` entry would freeze the other axis into the programmer — the reason
          // the backend keeps raw pan/tilt in its channel sideband.
          if (panValue !== undefined && axisProperties.pan) {
            lightingApi.programmer.set(
              'fixture',
              fixtureKey,
              axisProperties.pan,
              serializeLevel(panValue),
            )
          }
          if (tiltValue !== undefined && axisProperties.tilt) {
            lightingApi.programmer.set(
              'fixture',
              fixtureKey,
              axisProperties.tilt,
              serializeLevel(tiltValue),
            )
          }
          return
        }
        // A real `position` property is one atomic entry covering both axes, so a single-axis
        // nudge has to supply the other one. Prefer the axis the programmer already holds
        // over the live wire value: if an effect is driving the untouched axis, the wire is a
        // moving target and sampling it bakes in whatever instant the pointer happened to
        // land on. (Taking the whole position is the console-normal consequence of touching
        // one axis; what we avoid here is freezing it to an arbitrary sample.)
        const held = lightingApi.programmer.getKeyState(fixtureKey, 'position').entry
        // Through `parseProgrammerEntryValue` rather than `parseProgrammerValue(held.value)`
        // purely to keep one entry-shaped reader: the two are the same function now that `ref:`
        // is retired, and going straight to the string would quietly re-fork them.
        const heldPosition = held ? parseProgrammerEntryValue(held) : null
        const staged = heldPosition?.kind === 'position' ? heldPosition : null
        const effectivePan =
          panValue ?? staged?.pan ?? lightingApi.channels.get(pan.universe, pan.channelNo)
        const effectiveTilt =
          tiltValue ?? staged?.tilt ?? lightingApi.channels.get(tilt.universe, tilt.channelNo)
        lightingApi.programmer.setPosition(
          'fixture',
          fixtureKey,
          Math.round(effectivePan),
          Math.round(effectiveTilt),
        )
      },
    }
  }, [lookStore, setLookValue, inCue, cueStore])
}

const NO_WRITERS: CellWriters = {
  writeSlider: () => {},
  writeSetting: () => {},
  writeColour: () => {},
  writePosition: () => {},
  clearValue: () => {},
}

/**
 * Writers over one cue's **own rows**, all of whose targets are [targetType]: `fixture` for the
 * grid's fixture and cell rows (an element key is a fixture-typed target), `group` for a group row,
 * whose edit is the group's own row (show-cue-editing plan §4.1).
 *
 * The Look arm's rules, over a cue: a colour is stored as the components the write named (no wire
 * sampling — a stored row must not bake in what the rig happens to show), and a single-axis nudge on
 * an atomic `position` takes the other axis from the draft, else the **composed** cell — what GO
 * would land — never the wire. Clear takes the own row away, so the layer under it shows again.
 */
export function cueCellWriters(store: CueRowStoreValue, targetType: CueRowTargetType): CellWriters {
  const set = (key: string, propertyName: string, value: string) => store.setValue(targetType, key, propertyName, value)
  const writeLevel = (key: string, propertyName: string, _ref: ChannelRef, value: number) =>
    set(key, propertyName, serializeLevel(value))
  return {
    writeSlider: writeLevel,
    writeSetting: writeLevel,
    clearValue(key, propertyName) {
      store.removeValue(targetType, key, propertyName)
    },
    writeColour(key, property, r, g, b, w, a, uv) {
      if (property.type === 'whiteMix') {
        const white = w ?? 0
        set(key, property.name, serializeColour(Math.max(r, white), Math.max(g, white), Math.max(b, white)))
        return
      }
      // A head with no white channel takes a white component as RGB, as the live arm folds it: the
      // desk writes a stored colour verbatim per head and drops a white it has nowhere to send, so
      // the picker's pure white (0,0,0 + w 255) would land black.
      if (w !== undefined && w > 0 && !property.whiteChannel) {
        set(key, property.name, serializeColour(Math.max(r, w), Math.max(g, w), Math.max(b, w), undefined, a, uv))
        return
      }
      set(key, property.name, serializeColour(r, g, b, w, a, uv))
    },
    writePosition(key, _pan, _tilt, panValue, tiltValue, axisProperties) {
      if (panValue === undefined && tiltValue === undefined) return
      if (axisProperties) {
        if (panValue !== undefined && axisProperties.pan) set(key, axisProperties.pan, serializeLevel(panValue))
        if (tiltValue !== undefined && axisProperties.tilt) set(key, axisProperties.tilt, serializeLevel(tiltValue))
        return
      }
      const stored = store.composedValue(targetType, key, 'position')
      const parsed = stored ? parseProgrammerValue(stored) : null
      const base = parsed?.kind === 'position' ? parsed : { pan: 128, tilt: 128 }
      set(key, 'position', serializePosition(panValue ?? base.pan, tiltValue ?? base.tilt))
    },
  }
}

/** A committed Look row value, in canonical string form, or undefined. */
function lookRowValueOf(
  store: ReturnType<typeof useLookRowStore>,
  targetKey: string,
  propertyName: string,
): string | undefined {
  const staged = store?.serverRows.get(lookRowKey(targetKey, propertyName))
  if (!staged) return undefined
  return staged.kind === 'position' ? serializePosition(staged.pan, staged.tilt) : undefined
}
