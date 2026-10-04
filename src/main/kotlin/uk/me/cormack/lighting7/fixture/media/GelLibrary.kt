package uk.me.cormack.lighting7.fixture.media

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * One colour filter of the **gel library** (fixture optics plan D7): a maker's swatch, by the code
 * a lighting plot writes it (`L201`, `R26`, `L-HT115`). [color] is an approximate swatch for the
 * UI and the Stage view, never a measured transmission.
 */
@Serializable
data class Gel(
    val code: String,
    val name: String,
    /** `#rrggbb`. */
    val color: String,
    /** One of [GelLibrary.BRANDS]. A string rather than an enum so a bad one is a problem the
     *  loader names beside the others, not the first exception the decoder throws. */
    val brand: String,
    /** Estimate (plan D15): the swatch is the plan's approximation, not the maker's swatch book. */
    val estimate: Boolean = false,
)

/**
 * The **gel library**: the desk's, shipped as a resource (`src/main/resources/gels.json`), read once
 * and served whole at `GET /gels`, the way the lantern library is. It moved here from the
 * frontend (`data/gels.ts`) so the backend can turn a fitted gel into a colour — for the template
 * resolver's wheel snap and `describe_rig` (D7).
 *
 * A patch's `gelCode` is not checked against it: a gel the plot names and the library lacks is
 * still the plot's, drawn as the type's default colour. Fitted media is checked
 * ([FittedMedia.typeProblems]), because a fitted gel is what a beam is drawn and a wheel snapped by.
 */
object GelLibrary {
    private val logger = LoggerFactory.getLogger(GelLibrary::class.java)
    private const val RESOURCE = "gels.json"

    /** The brands a gel may name. */
    val BRANDS = listOf("Lee", "Rosco")

    /** Every gel, in the resource's order (by brand, then code). */
    val all: List<Gel> by lazy { load() }

    private val byCode: Map<String, Gel> by lazy { all.associateBy { it.code } }

    fun byCode(code: String?): Gel? = code?.let { byCode[it] }

    /** Parse and check [text]; answers the gels or throws naming every problem. */
    internal fun parse(text: String): List<Gel> {
        val gels = Json.decodeFromString(ListSerializer(Gel.serializer()), text)
        val problems = gels.flatMap { problemsOf(it) }.toMutableList()
        gels.groupBy { it.code }.filterValues { it.size > 1 }.keys.forEach { problems += "code '$it' appears twice" }
        require(problems.isEmpty()) { "Invalid gel library: ${problems.joinToString("; ")}" }
        return gels
    }

    private fun problemsOf(g: Gel): List<String> = buildList {
        val at = "gel '${g.code}'"
        if (!CODE_PATTERN.matches(g.code)) add("$at: code must be a maker's letter and a number, such as L201 or L-HT115")
        if (g.name.isBlank()) add("$at: name is blank")
        if (!HEX_PATTERN.matches(g.color)) add("$at: color '${g.color}' is not #rrggbb")
        if (g.brand !in BRANDS) {
            add("$at: unknown brand '${g.brand}' (the library's are ${BRANDS.joinToString()})")
        } else if (!g.code.startsWith(g.brand.first())) {
            add("$at: a ${g.brand} code starts with ${g.brand.first()}")
        }
    }

    private fun load(): List<Gel> {
        val text = GelLibrary::class.java.classLoader.getResource(RESOURCE)?.readText()
        if (text == null) {
            logger.error("{} not found — the gel library is empty and every fitted gel is refused", RESOURCE)
            return emptyList()
        }
        return parse(text)
    }

    private val CODE_PATTERN = Regex("[A-Z](-[A-Z]+)?[0-9]{1,4}")
    private val HEX_PATTERN = Regex("#[0-9a-fA-F]{6}")
}
