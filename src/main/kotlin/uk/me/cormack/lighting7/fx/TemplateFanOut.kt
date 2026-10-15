package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fixture.media.FittedMedia

/**
 * **Where one template row lands on one target**: the target itself when it carries the row's
 * property, or — for a multi-head fixture whose property lives only on its cells — the cells that
 * carry it (venue-visit plan S1).
 *
 * The Shehds in 24-channel mode is the case that found it: its colour lives on three `Zone`
 * elements and the parent has none, so a colour template pressed on the parent, or on a group of
 * them, asked [TemplateResolver] about a head with no colour and wrote nothing. The rule is the
 * one the FX engine already applies to an effect on a parent (`docs/groups-engineering.md`
 * §"Automatic FX Expansion to Elements"), stated for values:
 *
 *  - **A target that carries the property keeps it**, whatever its cells hold. A parent with its
 *    own dimmer takes a dimmer row as it always did; only the rows its cells alone can serve fan.
 *  - **The decision is per row**, so one template can land partly on a parent and partly on its
 *    cells — a beam template's zoom on the Shehds' parent, its colour on the zones.
 *  - **Presence, not resolution, decides** ([TemplateResolver.hasProperty]): a parent that has the
 *    property but cannot take this value — a pan with no annotated degree range — keeps the row
 *    and reports why, rather than handing it to cells that can.
 *  - **Colour is decided as a family**: a colour-family row (the hex, white, amber, UV) fans when
 *    the target has no colour at all, the question [TemplateResolver.unmetColourRequirement] asks.
 *    A parent that has colour but lacks an emitter the template names stays on the parent and is
 *    refused there as a whole — fanning would put a different colour on the cells under the
 *    template's name, which is what the whole-template rule exists to prevent.
 *  - **Nothing carries it → the target itself**, so the caller reports the target's own skip, as
 *    before, rather than a skip per cell.
 *
 * Every consumer that resolves a template row onto a selection asks this — the click
 * (`applyTemplateToProgrammer`), the cook (`CueComposer.applyLayer`, so a ⌥click layer and a busk
 * pad), Spread over heads and the editor's resolves-to panel — for the reason [TemplateResolver]
 * is a singleton: four answers to "where does this land" would drift.
 */
object TemplateFanOut {
    /**
     * The heads [target] stands for, for [propertyName]. [fittedMediaFor] is
     * `Fixtures.fittedMediaFor`, answered per head through [mediaOf].
     *
     * Cells come back in element order, each the parent's own element object — the same instance
     * the parent's transaction writes through. A cell is chosen for **having** the property
     * ([TemplateResolver.hasProperty]), not for resolving one value of it; whether the value lands
     * is each head's own answer, reported by the caller.
     */
    fun headsFor(
        target: GroupableFixture,
        propertyName: String,
        fittedMediaFor: (String) -> FittedMedia?,
    ): List<GroupableFixture> {
        val multi = target as? MultiElementFixture<*> ?: return listOf(target)
        if (TemplateResolver.hasProperty(target, propertyName, mediaOf(target, fittedMediaFor))) return listOf(target)
        val cells = multi.elements.filter {
            TemplateResolver.hasProperty(it, propertyName, mediaOf(it, fittedMediaFor))
        }
        return cells.ifEmpty { listOf(target) }
    }

    /**
     * A head's fitted media: a cell's is its **parent's**. Media is loaded per patch and a cell is
     * part of one, so `Fixtures.fittedMediaFor` answers nothing for an element key; every arm that
     * resolves a fanned cell asks through this, as `set_programmer`'s cell fan-out
     * (`ai/LookValues.kt`) already did.
     */
    fun mediaOf(head: GroupableFixture, fittedMediaFor: (String) -> FittedMedia?): FittedMedia? =
        fittedMediaFor((head as? FixtureElement<*>)?.parentFixture?.key ?: head.targetKey)
}
