import type { TemplateSummary } from '@/api/templatesApi'
import { familyForEffectCategory } from '@/lib/attributeFamily'
import { stepEffectPaletteShape } from '@/lib/stepEffects'
import type { ProgrammerLayer } from '@/store/programmer'
import type { ActiveEffect, EffectLibraryEntry } from '@/store/fixtureFx'
import { findEffectEntry } from '../busking/buskingTypes'

/**
 * What the FX table says of an effect beyond its own fields: where it lives, and whether it can
 * become a palette. Pure, so the table's rows and its verbs read one answer.
 */

/** Each layer's name and its place in the stack, which an effect's home names it by. */
export function layerHomesOf(
  layers: readonly ProgrammerLayer[],
): ReadonlyMap<number, { name: string; position: number }> {
  return new Map(layers.map((l, i) => [l.layerId, { name: l.source.name, position: i + 1 }]))
}

/**
 * Whether this effect can become a template, and why not when it cannot.
 *
 * Two independent gates, and the order matters for what the operator is told:
 *
 *  - **The programmer must own it.** A copy of an effect that already belongs to a Look or a
 *    template is not a new named thing — the named thing exists, and *Edit template* is the gesture.
 *    An effect on a cue is that cue's, and a base effect belongs to the show.
 *  - **Its category must map to a template family.** `controls` has no tempo, so names none.
 *
 * Returns a reason rather than false, because the item is **shown disabled** in both cases. A menu
 * that silently loses an entry teaches nobody why.
 */
export function saveAsTemplateOffer(
  effect: ActiveEffect,
  library: EffectLibraryEntry[] | undefined,
  templates: TemplateSummary[] | undefined,
): { enabled: boolean; reason?: string } {
  if (!effect.programmerOwned || effect.lookId != null || effect.templateId != null || effect.programmerLayerId != null) {
    return {
      enabled: false,
      reason:
        effect.sourceName != null
          ? `Already named — this is “${effect.sourceName}” running. Edit that instead.`
          : 'Only an FX the programmer owns can become a palette. This one belongs to a cue or the show.',
    }
  }
  // Two different absences, and telling them apart is the point: a library that has not arrived is
  // a wait, while a miss against a loaded one is an effect type this desk's registry does not know
  // — an import from a desk with script-registered effects. Reporting the second as the first
  // leaves the item disabled forever under a message that promises it is about to work.
  if (library == null) return { enabled: false, reason: 'Reading the FX library…' }
  const entry = findEffectEntry(library, effect.effectType)
  if (entry == null) {
    return {
      enabled: false,
      reason: `“${effect.effectType}” is not in this desk's FX library, so its category — and so the palette's family — cannot be read.`,
    }
  }
  // A step effect is banked by its palettes' family or as Beam on its setting, never by its library
  // category — Palette Steps' `steps` names no family at all.
  const step = stepEffectPaletteShape(effect, templates)
  if (step != null) return step.reason != null ? { enabled: false, reason: step.reason } : { enabled: true }
  const family = familyForEffectCategory(entry.category)
  if (family == null) {
    return {
      enabled: false,
      reason: `A “${entry.category}” FX is not banked by family, so it cannot be a palette. It can live in a recorded look.`,
    }
  }
  return { enabled: true }
}

/**
 * Where an effect lives, in the words the design asks for.
 *
 * The answer to "why can't I delete this?" and to "what will editing it break?". An effect is
 * never *on* a layer — it is **in a Look** (and travels with it, so every other layer applying
 * that Look runs it too) or **on the cue** (this once, belonging to nothing else) or loose in the
 * programmer band, which is what a busked effect is until something records it.
 */
export function homeOf(
  effect: ActiveEffect,
  layerHomes: ReadonlyMap<number, { name: string; position: number }>,
): { label: string; detail?: string } {
  if (effect.programmerLayerId != null) {
    const home = layerHomes.get(effect.programmerLayerId)
    // The layer broadcast first, because it is the same list the Stack table draws and it can also
    // say *which* layer. `sourceName` is the fallback and is right for both kinds since
    // fx-templates D7 — an effect can come from a template as well as a Look, and the old literal
    // "in a look" named the wrong entity for half of them.
    const name = home?.name ?? effect.sourceName
    return {
      label: name != null ? `in ${name}` : 'in a layer',
      detail: home ? `layer ${home.position}` : undefined,
    }
  }
  if (effect.cueId != null) return { label: 'on this cue', detail: 'ad-hoc' }
  if (effect.programmerOwned) return { label: 'programmer band', detail: 'yours until recorded' }
  return { label: 'base' }
}
