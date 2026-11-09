package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.fixture.FixtureTriggers
import uk.me.cormack.lighting7.fx.PersistedFixtureReferenceValidator
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID

/**
 * Pure evaluator that maps a [Binding] onto an [AssignmentHealth] given a snapshot of the current
 * project state: its subject's health, then its function's own parameters. Stateless — callers
 * assemble a [Context] per evaluation batch and throw it away. A fixture's or group's attribute is
 * judged per head [Heads.of] reaches, through [PersistedFixtureReferenceValidator], so cue-authoring's
 * rules (property canonicalisation, the `"position"` compound) stay the one source of truth.
 */
object BindingHealthEvaluator {

    /**
     * Bundle of snapshots needed to evaluate every target variant. Assembled once by
     * [ControlSurfaceBindingService] per rebuild / query so we don't re-read the DB or
     * re-scan fixtures per binding.
     *
     * @param fixtures current patch; delegated to `PersistedFixtureReferenceValidator`
     * @param deviceTypes device profiles — used for a [Does.Layer]'s layer
     * @param validSpeedMasterUuids uuids currently in the bank; master 1 is addressed by no
     *   subject and so never needs to appear here
     * @param validStackUuids the project's cue stacks
     * @param validCueUuids the project's cues
     * @param selectionAttributes every property name some patched fixture declares that a
     *   continuous control can write (sliders and colour) — the vocabulary of a level on the
     *   selection and of an encoder page; see [selectionAttributesOf]
     * @param colourAttributes the subset of [selectionAttributes] some patched fixture declares as
     *   a **colour** — what a [ColourAxis] on a selection or encoder-bank target is judged against
     *   at the write boundary (`refuseAxisOnNonColour`); see [colourAttributesOf]. Health itself
     *   does not read it: an axis is refused when it is bound, never reported afterwards, and a
     *   head that stops being a colour under a re-patch simply drops the move.
     * @param validLookUuids Looks that exist in the project
     * @param validPaletteUuids templates that exist in the project
     * @param validPadUuids busk pads that exist — a pad dragged off a page, or swept by its
     *   record's delete, is gone
     * @param validBankUuids busk banks that exist — what a [Does.Place] places onto. A bank, not a
     *   pad: the place appends, so what must exist is the container
     * @param validPageUuids busk pages that exist
     * @param connectedWindowNames the registry names of every window signed in **now** — what a
     *   [Does.WindowFocus] / [Does.WindowSheet] is judged against. The one
     *   input here that is not a database fact, which is why the publisher re-evaluates on every
     *   registry change rather than only on the fixture hooks
     */
    data class Context(
        val fixtures: Fixtures,
        val deviceTypes: List<ControlSurfaceRegistry.DeviceTypeInfo>,
        val validSpeedMasterUuids: Set<UUID> = emptySet(),
        val validStackUuids: Set<UUID> = emptySet(),
        val validCueUuids: Set<UUID> = emptySet(),
        val selectionAttributes: Set<String> = emptySet(),
        val colourAttributes: Set<String> = emptySet(),
        val validLookUuids: Set<UUID> = emptySet(),
        val validPaletteUuids: Set<UUID> = emptySet(),
        val validPadUuids: Set<UUID> = emptySet(),
        val validBankUuids: Set<UUID> = emptySet(),
        val validPageUuids: Set<UUID> = emptySet(),
        val connectedWindowNames: Set<String> = emptySet(),
        /** Every cue entry's uuid in the project, to its stack's uuid. */
        val validEntryUuids: Map<UUID, UUID> = emptyMap(),
    )

    /**
     * The property names a level on the selection may carry: every name that at least
     * one patched fixture declares and that [AttributeChannelResolver] would accept on a fader —
     * sliders and colour, never a setting. Computed once per context, not per binding.
     */
    fun selectionAttributesOf(fixtures: Fixtures): Set<String> =
        fixtures.fixtures.flatMapTo(HashSet()) { fixture ->
            fixture.fixtureAttributes.map { it.name }
                .filter { AttributeChannelResolver.describeFixtureAttribute(fixture, it).isNotEmpty() }
        }

    /**
     * The property names some patched fixture declares as a **colour** — the subset of
     * [selectionAttributesOf] a [ColourAxis] may be bound against on a target that names no head.
     */
    fun colourAttributesOf(fixtures: Fixtures): Set<String> =
        fixtures.fixtures.flatMapTo(HashSet()) { fixture ->
            fixture.fixtureAttributes.map { it.name }.filter { AttributeChannelResolver.isColour(fixture, it) == true }
        }

    /** [binding]'s health on the device [deviceTypeKey] it is bound on: its subject's, then its function's parameters'. */
    fun evaluate(binding: Binding, context: Context, deviceTypeKey: String? = null): AssignmentHealth {
        val does = binding.does
        if (does is Does.Unknown) return AssignmentHealth.UnknownTarget(does.storedType)
        val subject = subjectHealth(binding, context)
        if (subject != AssignmentHealth.Ok) return subject
        return parameterHealth(binding, context, deviceTypeKey)
    }

    /**
     * Does the subject resolve: a fixture or group (with the attribute a level or flash writes on
     * some head it reaches), a fixture's trigger, a record by uuid. None, the selection and a slot
     * always do — an empty selection is a fact about this second, not about the binding.
     */
    private fun subjectHealth(binding: Binding, context: Context): AssignmentHealth {
        val named = binding.does.named
        return when (val on = binding.on) {
            Subject.None, Subject.Selection, Subject.Slot -> AssignmentHealth.Ok
            is Subject.Fixture ->
                if (runCatching { context.fixtures.untypedGroupableFixture(on.key) }.isFailure) AssignmentHealth.MissingFixture(on.key)
                else when (named) {
                    is Named.Trigger -> checkTrigger(on.key, named.trigger, context)
                    is Named.Attribute -> headsHealth(on, on.key, named.attribute, context)
                    null -> AssignmentHealth.Ok
                }
            is Subject.Group ->
                if (runCatching { context.fixtures.untypedGroup(on.key) }.isFailure) AssignmentHealth.MissingGroup(on.key)
                else when (named) {
                    is Named.Attribute -> headsHealth(on, on.key, named.attribute, context)
                    is Named.Trigger, null -> AssignmentHealth.Ok
                }
            is Subject.Record -> recordHealth(on, context)
        }
    }

    /**
     * A fixture's or group's [attribute] judged over the heads the write reaches ([Heads.of]) — an
     * element, a group's cells, the cells a parent's attribute lives on — each as cue authoring
     * judges a cell. Healthy when any head has it, as the write skips the heads that do not.
     */
    private fun headsHealth(on: Subject, key: String, attribute: String, context: Context): AssignmentHealth {
        val heads = Heads.of(context.fixtures, on, attribute)
        if (heads.isEmpty()) return if (on is Subject.Group) AssignmentHealth.MissingGroup(key) else AssignmentHealth.MissingFixture(key)
        val reached = heads.any { head ->
            PersistedFixtureReferenceValidator.validateTargetedReference(
                context.fixtures, TargetRef.Fixture(head.fixture.targetKey), attribute, allowElements = true,
            ) == AssignmentHealth.Ok
        }
        return if (reached) AssignmentHealth.Ok else AssignmentHealth.MissingAttribute(key, attribute)
    }

    private fun recordHealth(on: Subject.Record, context: Context): AssignmentHealth {
        val uuid = on.uuid
        val raw = uuid.toString()
        fun check(valid: Boolean, missing: () -> AssignmentHealth) = if (valid) AssignmentHealth.Ok else missing()
        return when (on.kind) {
            RecordKind.STACK -> check(uuid in context.validStackUuids) { AssignmentHealth.MissingStack(stackUuid = raw) }
            // An entry gone reads as a missing cue: the health vocabulary has no word for an entry.
            RecordKind.ENTRY -> check(uuid in context.validEntryUuids) { AssignmentHealth.MissingCue(cueUuid = raw) }
            RecordKind.CUE -> check(uuid in context.validCueUuids) { AssignmentHealth.MissingCue(cueUuid = raw) }
            // A Look lands whole (absolute-looks plan D1), so the one failure is a Look that is gone.
            RecordKind.LOOK -> check(uuid in context.validLookUuids) { AssignmentHealth.MissingLook(raw) }
            RecordKind.PALETTE -> check(uuid in context.validPaletteUuids) { AssignmentHealth.MissingPalette(raw) }
            // The pad, not its record: a pad whose record was deleted is itself swept in the same
            // transaction, so "the pad exists" already answers "its record does".
            RecordKind.PAD -> check(uuid in context.validPadUuids) { AssignmentHealth.MissingPad(raw) }
            RecordKind.BANK -> check(uuid in context.validBankUuids) { AssignmentHealth.MissingBank(raw) }
            RecordKind.PAGE -> check(uuid in context.validPageUuids) { AssignmentHealth.MissingPage(raw) }
            RecordKind.MASTER -> check(uuid in context.validSpeedMasterUuids) { AssignmentHealth.MissingSpeedMaster(raw) }
        }
    }

    /**
     * What a function's own parameters need: a level on the selection, or an encoder page, an
     * attribute some patched fixture declares; a palette that exists; a layer the device has; a
     * window that is connected. A palette's *emptiness*, a hand's contents, a selection with no Local
     * FX — each is a fact about this second, never a dead binding.
     */
    private fun parameterHealth(binding: Binding, context: Context, deviceTypeKey: String?): AssignmentHealth =
        when (val does = binding.does) {
            is Does.Level ->
                if (binding.on != Subject.Selection || does.attribute in context.selectionAttributes) AssignmentHealth.Ok
                else AssignmentHealth.UnknownAttribute(does.attribute)
            is Does.EncoderPage ->
                if (does.attribute in context.selectionAttributes) AssignmentHealth.Ok
                else AssignmentHealth.UnknownAttribute(does.attribute)
            is Does.Palette ->
                if (does.palette in context.validPaletteUuids) AssignmentHealth.Ok
                else AssignmentHealth.MissingPalette(does.palette.toString())
            is Does.Layer -> {
                val profile = deviceTypeKey?.let { key -> context.deviceTypes.firstOrNull { it.typeKey == key } }
                if (profile == null || profile.banks.none { it.id == does.layer }) {
                    AssignmentHealth.UnknownBank(deviceTypeKey ?: "", does.layer)
                } else {
                    AssignmentHealth.Ok
                }
            }
            // Window-addressed: dead while no connected window carries the name, alive the moment one
            // announces. Transient, unlike every other check — see `AssignmentHealth.MissingWindow`.
            is Does.WindowFocus -> windowHealth(does.window, context)
            is Does.WindowSheet -> windowHealth(does.window, context)
            is Does.Flash, Does.Select, Does.Locate, is Does.Fan, Does.FxSpeed, Does.Look, Does.Cue, Does.Pad,
            Does.PickUp, Does.Place, Does.Drop, Does.Go, Does.Back, Does.Pause, Does.Stop, Does.GoTo,
            Does.Standby, Does.FxAmount, is Does.Fire, Does.Blackout, is Does.GrandMaster, is Does.Tempo,
            Does.Tap, is Does.TempoScale, is Does.Undo, is Does.BuskPage, is Does.SelectionStep, Does.Strip,
            is Does.Unknown,
                -> AssignmentHealth.Ok
        }

    /** A fire's fixture must be patched and carry that trigger (by name or label). */
    private fun checkTrigger(fixtureKey: String, trigger: String, context: Context): AssignmentHealth {
        val fixture = runCatching { context.fixtures.untypedFixture(fixtureKey) }.getOrNull()
            ?: return AssignmentHealth.MissingFixture(fixtureKey)
        val known = FixtureTriggers.of(fixture).any { it.name.equals(trigger, ignoreCase = true) || it.spec.label.equals(trigger, ignoreCase = true) }
        return if (known) AssignmentHealth.Ok else AssignmentHealth.MissingAttribute(fixtureKey, trigger)
    }

    private fun windowHealth(windowName: String, context: Context): AssignmentHealth =
        if (windowName in context.connectedWindowNames) AssignmentHealth.Ok
        else AssignmentHealth.MissingWindow(windowName)
}
