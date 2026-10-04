package uk.me.cormack.lighting7.fixture.dmx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.FixtureSettingValue

interface DmxFixtureSettingValue: FixtureSettingValue {
    val level: UByte

    /**
     * On a loadable setting (`@FixtureProperty(media =)`), whether this option is a slot something
     * can be loaded into. An open hole in a wheel, or a media frame's out position, is not: nothing
     * fitted there would ever reach the beam. Unread on every other setting.
     */
    val loadable: Boolean get() = true
}

/**
 * Setting value with an associated colour preview for display in the UI.
 * Use this for colour preset enums (e.g., RED, GREEN, BLUE presets).
 */
interface DmxFixtureColourSettingValue : DmxFixtureSettingValue {
    /**
     * The colour this position puts in the beam, as `#RRGGBB` — or null where it has none of its own
     * ([noColour]), or where a loadable setting's slot ships empty. On a COLOUR setting every option
     * is exactly one of the two, which `ColourPreviewTest` enforces (fixture-optics plan D8).
     */
    val colourPreview: String?

    /**
     * True for a band with **no single colour** — a scroll, a random or rainbow program, an auto
     * colour change, a wheel's rotation stop, or a band that hands the colour to other channels (an
     * RGB head's "direct" preset). Declared on the option rather than guessed from its name, because
     * the names are every manufacturer's own (SCROLL_CW, RAINBOW_EFFECT, AUTOMATIC_COLOUR_CHANGE,
     * ALL_COL, ORIGINAL) and a guess that misses draws black. Where the setting is the beam's colour
     * the Stage view animates such a band through the wheel's own previews; nothing draws it black
     * for want of a colour.
     */
    val noColour: Boolean get() = false
}

/**
 * A position of a **stepped zoom** (fixture-optics plan D2): a ZOOM setting rather than a slider,
 * whose bands are fixed angles — the Robe ColorSpot 575's 15°, 18° and 22°, each with and without
 * focus correction. A continuous zoom is a ZOOM slider declaring `degMin`/`degMax` instead;
 * `ZoomAnglesTest` enforces one or the other.
 */
interface DmxFixtureZoomSettingValue : DmxFixtureSettingValue {
    /** The full beam angle in degrees while the channel is in this band. */
    val zoomDeg: Double
}

/**
 * Shared vocabulary of gobo patterns the stage view can draw.
 *
 * The names are the contract with the frontend's pattern atlas (serialised
 * lowercase via [serialized]); which texture layer a name maps to — and
 * whether it's procedurally generated or an image — is a frontend detail that
 * never touches fixture definitions. A name unknown to an older frontend
 * degrades to an open beam rather than a wrong pattern, so the vocabulary can
 * grow here first.
 */
enum class GoboPattern {
    DOTS, BREAKUP, SPOKES, TRIPLE, RINGS, STARBURST, BARS,
    CONE, FAN, BEAM_SPLIT, FIBROID, HOLES, CIRCLES, STARS, SWIRL, CLOUDS;

    fun serialized(): String = name.lowercase()
}

/**
 * Setting value that puts a gobo in the beam.
 *
 * Declared per wheel *position* rather than per property because that's where
 * the fact lives: one channel mixes open, pattern, shake and scroll bands.
 */
interface DmxFixtureGoboSettingValue : DmxFixtureSettingValue {
    /**
     * Pattern at this wheel position, or null for open / scroll / rainbow /
     * no-op positions. Null on a wheel that declares patterns elsewhere means
     * "deliberately open", not "unannotated" — the stage view renders it open
     * instead of falling back to a guessed pattern.
     */
    val gobo: GoboPattern?
}

/**
 * Setting value that engages (or removes) a prism.
 *
 * Per-option for the same reason as [DmxFixtureGoboSettingValue]: every real
 * prism wheel mixes "prism out" bands with engaged bands on one channel, and
 * a wheel could carry prisms with different facet counts. If lobe separation
 * ever becomes fixture-driven, a nullable `prismDeviationDeg` belongs here.
 */
interface DmxFixturePrismSettingValue : DmxFixtureSettingValue {
    /** Facet count of the prism at this position, or null when the prism is out. */
    val prismFacets: Int?
}

/**
 * Maps an enum of setting values to a single DMX channel.
 *
 * Unlike [DmxSlider] and [DmxColour], this class retains the "Fixture" prefix because
 * settings are inherently fixture-specific (each fixture defines its own enum values).
 * There's no generic `Setting` interface since the type parameter varies per fixture.
 *
 * @param T The fixture-specific enum type implementing [DmxFixtureSettingValue]
 * @param transaction The controller transaction context
 * @param universe The DMX universe containing the channel
 * @param channelNo The DMX channel number
 * @param settingValues Array of all possible setting values
 */
class DmxFixtureSetting<T : DmxFixtureSettingValue>(
    val transaction: ControllerTransaction?,
    val universe: Universe,
    val channelNo: Int,
    settingValues: Array<T>,
) {
    private val nonNullTransaction get() = checkNotNull(transaction) {
        "Attempted to use fixture outside of a transaction"
    }

    var setting: T
        get() = valueForLevel(nonNullTransaction.getValue(universe, channelNo))
        set(value) = nonNullTransaction.setValue(universe, channelNo, value.level)

    val sortedValues: List<T>
    private val valuesByName: Map<String, T>

    init {
        check(settingValues.isNotEmpty())

        sortedValues = settingValues.sortedBy { it.level }
        valuesByName = settingValues.associateBy { it.name }

        check(valuesByName.size == settingValues.size)
    }

    fun valueForLevel(level: UByte): T {
        return sortedValues.firstOrNull { it.level >= level } ?: sortedValues.first()
    }

    fun valueForName(name: String): T {
        return valuesByName[name] ?: throw Exception("No such value '$name'")
    }
}

