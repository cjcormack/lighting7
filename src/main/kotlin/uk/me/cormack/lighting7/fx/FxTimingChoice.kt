package uk.me.cormack.lighting7.fx

/**
 * An effect's clock (fx-speed plan D3, D4): it follows a speed master, or it runs on its own.
 *
 * - A **beat** effect follows a speed master, or runs on its **Own tempo**, [FxInstance.ownBpm]
 *   (20–300). `cycle` is beats either way. On Own tempo it runs on the wall-clock loop with no master,
 *   its accumulator advancing at [ownTempoRate] — `ownBpm / 60`, so `cycle` beats take
 *   `cycle × 60 ÷ ownBpm` seconds, and a tempo change moves only the rate, never the phase.
 * - A **wall-clock** effect's `cycle` is seconds. It follows a rate master, scaling its cycle by
 *   `bpm / 120`, or runs on **Natural**, a fixed 120 BPM: no rate master. It has no Own tempo.
 *
 * [applyTimingChoice] is the one writer before an instance reaches the engine, and
 * `FxEngine.updateFx`'s [FxClockMove] the one after.
 */
internal fun FxInstance.applyTimingChoice(registrationSource: TimingSource, ownBpm: Double?, natural: Boolean = false) {
    when {
        ownBpm != null && registrationSource == TimingSource.BEAT -> {
            this.ownBpm = clampOwnBpm(ownBpm)
            timingSource = TimingSource.WALL_CLOCK
            speedMasterUuid = null
            rateSpeedMasterUuid = null
        }
        else -> {
            this.ownBpm = null
            timingSource = registrationSource
            // Natural is a wall-clock effect with no rate master.
            if (natural && registrationSource == TimingSource.WALL_CLOCK) rateSpeedMasterUuid = null
        }
    }
}

/** Where an edit moves an effect's clock (fx-speed plan D3, D4). */
sealed interface FxClockMove {
    /** A beat effect onto Own tempo at [bpm], or an Own tempo's new tempo. */
    data class OntoOwnTempo(val bpm: Double) : FxClockMove

    /** A wall-clock effect onto Natural: its rate master dropped. */
    data object OntoNatural : FxClockMove

    /** Off Own tempo or Natural, onto the master the edit names (a beat effect's absent: master 1). */
    data object OntoMaster : FxClockMove
}

/** The lowest and highest Own tempo (D3), a speed master's range. */
const val MIN_OWN_BPM = MasterClock.MIN_BPM
const val MAX_OWN_BPM = MasterClock.MAX_BPM

/**
 * The tempo a beat effect on Own tempo is read at on the wall-clock loop: its accumulator advances
 * at `ownBpm / 60`, so a `cycle` of beats is a cycle of seconds at 60 BPM.
 */
internal const val OWN_TEMPO_REFERENCE_BPM = 60.0

/** The wall-clock rate an Own tempo of [ownBpm] runs its `cycle` of beats at. */
internal fun ownTempoRate(ownBpm: Double): Double = ownBpm / OWN_TEMPO_REFERENCE_BPM

/** The Own tempo D5's rewrite gives a beat effect on *Seconds*, at twice its cycle. */
const val SECONDS_OWN_BPM = 120.0

/** A stored Own tempo held to the range, for a lenient read; a non-finite one is [SECONDS_OWN_BPM]. */
internal fun clampOwnBpm(ownBpm: Double): Double =
    if (ownBpm.isFinite()) ownBpm.coerceIn(MIN_OWN_BPM, MAX_OWN_BPM) else SECONDS_OWN_BPM

/**
 * D5: a beat effect on *Seconds* of [cycleSeconds] as Own tempo — [SECONDS_OWN_BPM] at twice the
 * cycle in beats, which runs exactly as Seconds did: the rate doubles and so does the cycle.
 */
fun secondsAsOwnTempo(cycleSeconds: Double): Pair<Double, Double> = SECONDS_OWN_BPM to cycleSeconds * 2.0

/**
 * A stored row's clock as the effect of [registrationSource] runs it: a beat effect's [ownBpm]; a
 * wall-clock effect has none. A wall-clock row that says an Own tempo (a script effect D5's rewrite
 * could not tell from a beat one) runs on Natural at the seconds it meant: its [cycle] of beats at
 * that tempo. Answers the Own tempo and the cycle to spawn with.
 */
internal fun storedClock(registrationSource: TimingSource, ownBpm: Double?, cycle: Double): Pair<Double?, Double> =
    when {
        ownBpm == null -> null to cycle
        registrationSource == TimingSource.BEAT -> clampOwnBpm(ownBpm) to cycle
        else -> null to cycle / ownTempoRate(clampOwnBpm(ownBpm))
    }

/** The problem with a requested Own tempo, or null: a number from 20 to 300. */
fun ownBpmProblem(ownBpm: Double?): String? =
    if (ownBpm != null && (!ownBpm.isFinite() || ownBpm < MIN_OWN_BPM || ownBpm > MAX_OWN_BPM)) {
        "ownBpm must be between ${MIN_OWN_BPM.toInt()} and ${MAX_OWN_BPM.toInt()}, got $ownBpm"
    } else {
        null
    }

/**
 * The refusal a request asking for its own clock beside a master earns, or null: Own tempo and
 * Natural follow no master, so asking for both is two answers to one question. [seconds] is the old
 * spelling of either. The effect routes (`POST /fx/add`, `POST /groups/{name}/fx`, `POST
 * /programmer/fx`, `PUT /fx/{id}` and `updateFx`) and the MCP effect tools refuse with it. The
 * stored-effect writes (a Look's, a palette's, a cue's) do not: they take Own tempo and drop the
 * master beside it, since a row on Own tempo keeps none.
 */
fun ownTempoTimingConflict(
    ownBpm: Double?,
    seconds: Boolean?,
    natural: Boolean?,
    speedMasterUuid: String?,
    rateSpeedMasterUuid: String?,
): String? {
    val free = ownBpm != null || seconds == true || natural == true
    return when {
        ownBpm != null && natural == true ->
            "ownBpm and natural are two answers to one question — Own tempo is a beat FX's, Natural a wall-clock FX's"
        free && (speedMasterUuid != null || rateSpeedMasterUuid != null) ->
            "${if (ownBpm != null) "ownBpm" else if (natural == true) "natural" else "seconds"}: an FX on its own " +
                "tempo follows no master — send it or a master, not both"
        else -> ownBpmProblem(ownBpm)
    }
}

/**
 * A request's own clock (D3–D5) for an effect of [kind], before any master: [ownBpm] the Own tempo
 * a beat effect runs on, or null; [natural] a wall-clock effect on Natural; [cycle] the cycle to
 * run, which the old *Seconds* spelling may have moved. Null [cycle] leaves the effect's own.
 */
data class RequestedClock(val ownBpm: Double?, val natural: Boolean, val cycle: Double?)

/**
 * [ownBpm], [natural] and the old [seconds] of a request for an effect of [kind], read by D5: on a
 * beat effect `seconds: true` with a [cycle] is [SECONDS_OWN_BPM] at twice it, and without one the
 * Own tempo [runningBpm] answers (the master it would have followed) with the cycle kept; on a
 * wall-clock effect it is Natural. Throws for an Own tempo on a wall-clock effect, or Natural on a
 * beat one. Run after [ownTempoTimingConflict].
 */
fun resolveRequestedClock(
    kind: TimingSource,
    ownBpm: Double?,
    seconds: Boolean?,
    natural: Boolean?,
    cycle: Double?,
    runningBpm: () -> Double,
): RequestedClock = if (kind == TimingSource.WALL_CLOCK) {
    require(ownBpm == null) {
        "ownBpm: a wall-clock FX runs on Natural or a rate master, not its own tempo — send natural: true"
    }
    RequestedClock(null, natural == true || seconds == true, cycle)
} else {
    require(natural != true) { "natural: only a wall-clock FX runs on Natural — send ownBpm for a beat FX's own tempo" }
    when {
        ownBpm != null -> RequestedClock(ownBpm, false, cycle)
        seconds == true && cycle != null -> secondsAsOwnTempo(cycle).let { (bpm, beats) -> RequestedClock(bpm, false, beats) }
        seconds == true -> RequestedClock(clampOwnBpm(runningBpm()), false, null)
        else -> RequestedClock(null, false, cycle)
    }
}

/**
 * Venue-visit plan D16's default master for a new effect of [fxType]: the speed master whose
 * `family` is the effect's family — its registration's category on [attribute] ([fxFamilyOnAttribute]), else [fallbackCategory] (always for
 * a Steps FX, whose `steps` category names no family: Slot Steps is Beam's, Palette Steps the
 * caller's) — or null,
 * which is master 1. Null too for a wall-clock effect, whose Natural is having no rate master, since
 * "one bar on a master" means nothing to an effect timed in seconds.
 *
 * A uuid stamped once, at creation, never a rule resolved later. The one reading for every door the
 * desk stamps on: MCP's effect tools (`parseFxTiming`) and the programmer's bare effect press
 * (`POST /programmer/fx`, apply-model plan §3.4). The effect editor stamps the same choice
 * client-side.
 */
fun familyMasterUuidFor(
    registry: FxRegistry,
    speedMasters: SpeedMasterBank,
    fxType: String,
    fallbackCategory: String? = null,
    /** The attribute the new effect drives: Wave on zoom is Beam's (fx-settings plan D5). */
    attribute: String? = null,
): java.util.UUID? {
    val registration = registry.getRegistration(fxType)
    if (registration?.timingSource == TimingSource.WALL_CLOCK) return null
    val family = when {
        registration == null -> fallbackCategory
        registration.family == STEPS_CATEGORY -> stepsFxFamily(registration.id, fallbackCategory)
        else -> fxFamilyOnAttribute(registration.family, attribute)
    } ?: return null
    return speedMasters.masterStates().firstOrNull { it.family == family }?.uuid
}

/** The tempo the master [uuid] (null → master 1) is running at, held to the Own tempo range. */
fun SpeedMasterBank.runningBpmOf(uuid: java.util.UUID?): Double =
    clampOwnBpm(clockFor(slotFor(uuid)).bpm.value)

/**
 * A stored row's clock as a write keeps it (fx-speed plan D3–D5): [ownBpm] the Own tempo it stores,
 * [cycle] its cycle, [free] whether it keeps no master — Own tempo, or Natural.
 */
data class StoredWriteClock(val ownBpm: Double?, val cycle: Double, val free: Boolean)

/**
 * A stored write's [ownBpm] and old [seconds] for an effect of [kind] (null, an unknown type, is a
 * beat one), as the row keeps them: `seconds` on a beat effect is Own tempo 120 at twice [cycle] (D5),
 * on a wall-clock one Natural. A wall-clock row that says an Own tempo — one a client read back and
 * sent again, of a script effect the database step took for a beat one — is written on Natural at the
 * seconds it meant, as [storedClock] runs it.
 */
fun storedWriteClock(kind: TimingSource?, ownBpm: Double?, seconds: Boolean, cycle: Double): StoredWriteClock = when {
    kind == TimingSource.WALL_CLOCK -> storedClock(kind, ownBpm, cycle).let { (_, seconds2) ->
        StoredWriteClock(null, seconds2, seconds || ownBpm != null)
    }
    ownBpm != null -> StoredWriteClock(ownBpm, cycle, true)
    seconds -> secondsAsOwnTempo(cycle).let { (bpm, beats) -> StoredWriteClock(bpm, beats, true) }
    else -> StoredWriteClock(null, cycle, false)
}

/**
 * What a stored write's clock may not say: an Own tempo out of range, or one on a wall-clock effect
 * the database step names ([named]: a built-in or a project's FX definition), which can never have
 * been given one (D4). A wall-clock effect a script registers is the step's blind spot, so its Own
 * tempo is written on Natural ([storedWriteClock]) rather than refused.
 */
fun storedClockProblem(kind: TimingSource?, named: Boolean, ownBpm: Double?): String? = when {
    ownBpm == null -> null
    kind == TimingSource.WALL_CLOCK && named ->
        "ownBpm: a wall-clock FX runs on Natural or a rate master, not its own tempo — leave ownBpm out for Natural"
    else -> ownBpmProblem(ownBpm)
}

/**
 * The built-in wall-clock effects by id: with a project's own wall-clock definitions
 * ([wallClockFxTypes]), what tells a stored row's old `seconds` (D5) on a wall-clock effect, which
 * becomes Natural, from one on a beat effect where no registry is to hand — the database step and the
 * importer. A script effect registered by a script, which neither can see, is read as a beat one, and
 * [storedClock] runs it at the seconds it meant. `FxTimingChoiceTest` holds it to the built-in library.
 */
val BUILT_IN_WALL_CLOCK_FX: Set<String> = setOf("CandleFlicker", "FluorescentFlicker")

/**
 * The wall-clock effect types a stored `fxType` may name, normalised as the registry resolves a name
 * ([normalizeFxType]): the built-ins and [definitions], a project's FX definitions on the wall clock.
 */
fun wallClockFxTypes(definitions: Collection<String> = emptyList()): Set<String> =
    (BUILT_IN_WALL_CLOCK_FX + definitions).mapTo(HashSet(), ::normalizeFxType)

/** [fxType]'s loop by [wallClock] ([wallClockFxTypes]): `WALL_CLOCK` for one of them, else `BEAT`. */
fun timingSourceByName(fxType: String, wallClock: Set<String>): TimingSource =
    if (normalizeFxType(fxType) in wallClock) TimingSource.WALL_CLOCK else TimingSource.BEAT
