package uk.me.cormack.lighting7.fx

/**
 * An effect's timing (venue-visit plan D7): it follows a speed master, or it runs on *Seconds* — its
 * own cycle, with no master.
 *
 * One choice, two spellings, because the two kinds of effect already read their division
 * differently:
 *
 * - A **beat** effect's division is beats of its speed master. On Seconds the same field is read as
 *   seconds and the effect runs on the wall-clock loop with no master — [FxInstance.seconds] true,
 *   [FxInstance.timingSource] `WALL_CLOCK`, both masters null. That is exactly an unscaled wall-clock
 *   effect, so neither loop learned anything new.
 * - A **wall-clock** effect's division is seconds already. Its Seconds is having no rate master; a
 *   master is its rate master, which scales the cycle by `bpm / 120` as it always has. The
 *   [FxInstance.seconds] flag stays false for it, so [FxInstance.registrationTimingSource] can tell
 *   the two kinds apart on the wall-clock loop. A *stored* row may say `seconds` of a wall-clock
 *   effect (a client's or the AI's Seconds), which spawns it with no rate master; a stored
 *   wall-clock row with no rate master and no flag — every row before S15 — is on Seconds too.
 *
 * [FxInstance.onSeconds] is the reading for either kind; [applyTimingChoice] is the one writer
 * before an instance reaches the engine, and `FxEngine.updateFx`'s `newSeconds` the one after.
 */
internal fun FxInstance.applyTimingChoice(registrationSource: TimingSource, seconds: Boolean) {
    when {
        seconds && registrationSource == TimingSource.BEAT -> {
            this.seconds = true
            timingSource = TimingSource.WALL_CLOCK
            speedMasterUuid = null
            rateSpeedMasterUuid = null
        }
        seconds -> {
            this.seconds = false
            timingSource = registrationSource
            rateSpeedMasterUuid = null
        }
        else -> {
            this.seconds = false
            timingSource = registrationSource
        }
    }
}

/**
 * The refusal a request carrying [seconds] beside a master earns, or null: Seconds is free of any
 * master, so asking for both is two answers to one question. The effect routes (`POST /fx/add`,
 * `POST /groups/{name}/fx`, `PUT /fx/{id}` and `updateFx`) and the MCP effect tools refuse with it,
 * in the same words. The stored-effect writes (a Look's, a palette's, a cue's) do not: they take
 * Seconds and drop the master beside it, since a row on Seconds keeps none.
 */
fun secondsTimingConflict(seconds: Boolean?, speedMasterUuid: String?, rateSpeedMasterUuid: String?): String? =
    if (seconds == true && (speedMasterUuid != null || rateSpeedMasterUuid != null)) {
        "seconds: an FX on Seconds follows no master — send seconds or a master, not both"
    } else {
        null
    }

/**
 * Venue-visit plan D16's default master for a new effect of [fxType]: the speed master whose
 * `family` is the effect's family — its registration's category on [attribute] ([fxFamilyOnAttribute]), else [fallbackCategory] (always for
 * a Steps FX, whose `steps` category names no family: Slot Steps is Beam's, Palette Steps the
 * caller's) — or null,
 * which is master 1. Null too for a wall-clock effect, whose Seconds is having no rate master, since
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
