package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.*
import uk.me.cormack.lighting7.fixture.group.*
import uk.me.cormack.lighting7.fixture.trait.*
import uk.me.cormack.lighting7.routes.PropertyDescriptor
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor

/**
 * Registry of all known fixture type classes.
 *
 * For single-mode fixtures, list the concrete class directly.
 * For multi-mode fixture families (sealed class hierarchies), list the
 * sealed base class — the registry will automatically discover all mode subclasses.
 *
 * Each fixture class is instantiated with dummy parameters for introspection,
 * reusing the same capability detection and property descriptor generation
 * that the live fixture system uses.
 */
object FixtureTypeRegistry {

    /**
     * All top-level fixture classes. For multi-mode families, list the sealed parent.
     * Mode subclasses are discovered automatically via [KClass.sealedSubclasses].
     */
    private val fixtureClasses: List<KClass<out Fixture>> = listOf(
        AdjFogFuryJettFixture::class,
        China2CellLedBlinderFixture::class,
        EquinoxTwinShotMkIIFixture::class,
        Fusion100SpotMkIIFixture::class,
        Gear4MusicOrbit70Fixture::class,
        Gear4MusicSolParty12BFixture::class,
        GenericDimmerFixture::class,
        HazerFixture::class,
        HexFixture::class,
        ImgStageLineWash42LedFixture::class,
        KamLiteobar252Fixture::class,
        LaserworldCS1000RGBMk3Fixture::class,
        LedLightbar12PixelFixture::class,
        LightstripFixture::class,
        MartinMac250Fixture::class,
        RobeColorSpot575Fixture::class,
        Scantastic4Fixture::class,
        ShehdsLed19RgbwFixture::class,
        SlenderBeamBarQuadFixture::class,
        Source4RevolutionFixture::class,
        StarClusterFixture::class,
        UVFixture::class,
        VarytecEasymoveXl60SpotFixture::class,
        WhexFixture::class,
    )

    data class FixtureTypeInfo(
        val typeKey: String,
        val manufacturer: String,
        val model: String,
        val modeName: String?,
        val channelCount: Int?,
        val capabilities: List<String>,
        val properties: List<PropertyDescriptor>,
        val elementGroupProperties: List<GroupPropertyDescriptor>?,
        val acceptsBeamAngle: Boolean,
        val acceptsGel: Boolean,
        val gelCompactDisplay: CompactDisplayRole,
        val kind: FixtureKind,
    )

    /**
     * All known fixture types, including individual modes of multi-mode families.
     * Lazily computed on first access.
     */
    val allTypes: List<FixtureTypeInfo> by lazy {
        fixtureClasses.flatMap { klass -> discoverTypes(klass) }
    }

    private val typeKeyToInfo: Map<String, FixtureTypeInfo> by lazy {
        allTypes.associateBy { it.typeKey }
    }

    /** O(1) lookup of a [FixtureTypeInfo] by its declared typeKey. */
    fun typeInfoForKey(typeKey: String): FixtureTypeInfo? = typeKeyToInfo[typeKey]

    /**
     * Map from typeKey to concrete KClass, lazily computed.
     * Used for instantiation by typeKey (DB-based mode).
     */
    private val typeKeyToClass: Map<String, KClass<out Fixture>> by lazy {
        fixtureClasses.flatMap { klass -> collectConcreteClasses(klass) }
            .mapNotNull { klass ->
                val annotation = klass.findAnnotation<FixtureType>() ?: return@mapNotNull null
                annotation.typeKey to klass
            }
            .toMap()
    }

    /**
     * Recursively collect concrete fixture classes, expanding sealed class hierarchies.
     */
    private fun collectConcreteClasses(klass: KClass<out Fixture>): List<KClass<out Fixture>> {
        val subclasses = klass.sealedSubclasses
        return if (subclasses.isNotEmpty()) {
            subclasses.flatMap { collectConcreteClasses(it) }
        } else {
            listOf(klass)
        }
    }

    /**
     * Instantiate a fixture by its typeKey with real parameters.
     * Used by DbFixtureLoader to create fixtures from DB records.
     *
     * @throws IllegalArgumentException if the typeKey is unknown or instantiation fails
     */
    fun instantiateByTypeKey(
        typeKey: String,
        universe: Universe,
        key: String,
        fixtureName: String,
        firstChannel: Int,
    ): DmxFixture {
        val klass = typeKeyToClass[typeKey]
            ?: throw IllegalArgumentException("Unknown fixture type key: $typeKey")

        val constructor = klass.primaryConstructor
            ?: throw IllegalArgumentException("No primary constructor for fixture type: $typeKey")

        return try {
            constructor.callBy(
                constructor.parameters
                    .filter { !it.isOptional }
                    .associateWith { param ->
                        when (param.type.classifier) {
                            Universe::class -> universe
                            String::class -> when (param.name) {
                                "key" -> key
                                "fixtureName" -> fixtureName
                                else -> key // fallback
                            }
                            Int::class -> when (param.name) {
                                "firstChannel" -> firstChannel
                                else -> firstChannel // fallback
                            }
                            else -> throw IllegalArgumentException(
                                "Unexpected parameter type ${param.type} for fixture type: $typeKey"
                            )
                        }
                    }
            ) as? DmxFixture ?: throw IllegalArgumentException("Fixture type $typeKey is not a DmxFixture")
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to instantiate fixture type $typeKey: ${e.message}", e)
        }
    }

    /**
     * Get the channel count for a fixture type by its typeKey.
     */
    fun channelCountForTypeKey(typeKey: String): Int? {
        return allTypes.find { it.typeKey == typeKey }?.channelCount
    }

    /**
     * Reverse-lookup the typeKey for a fixture class.
     */
    fun typeKeyForClass(klass: KClass<*>): String? {
        return klass.findAnnotation<FixtureType>()?.typeKey
    }

    /**
     * Get the simple class name for a given typeKey.
     */
    fun classNameForTypeKey(typeKey: String): String? {
        return typeKeyToClass[typeKey]?.simpleName
    }

    /** Dummy universe used for introspection — no real DMX connection needed. */
    private val dummyUniverse = Universe(0, 0)

    /**
     * Instantiate a fixture class with dummy parameters for read-only introspection.
     * All fixture constructors follow the pattern (universe, key, fixtureName, firstChannel, ...)
     * with any extra parameters (maxDimmerLevel, transaction) having defaults.
     */
    private fun instantiateForIntrospection(klass: KClass<out Fixture>): DmxFixture? {
        val constructor = klass.primaryConstructor ?: return null
        return try {
            constructor.callBy(
                constructor.parameters
                    .filter { !it.isOptional }
                    .associateWith { param ->
                        when (param.type.classifier) {
                            Universe::class -> dummyUniverse
                            String::class -> "introspection"
                            Int::class -> 1
                            else -> return null
                        }
                    }
            ) as? DmxFixture
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Detect capabilities from a fixture instance, using the same logic as the
     * live fixture details endpoint.
     */
    private fun detectCapabilities(fixture: DmxFixture): List<String> {
        val caps = mutableListOf<String>()
        if (fixture is WithDimmer) caps.add("dimmer")
        if (fixture is WithColour) caps.add("colour")
        if (fixture is WithPosition) caps.add("position")
        if (fixture is WithUv) caps.add("uv")
        if (fixture is WithStrobe) caps.add("strobe")
        if (fixture is MultiElementFixture<*>) {
            caps.add("multi-element")
            val egp = fixture.generateElementGroupPropertyDescriptors()
            if (egp != null) {
                if ("dimmer" !in caps && egp.any { it is GroupSliderPropertyDescriptor && it.category == "dimmer" }) caps.add("dimmer")
                if ("colour" !in caps && egp.any { it is GroupColourPropertyDescriptor }) caps.add("colour")
                if ("position" !in caps && egp.any { it is GroupPositionPropertyDescriptor }) caps.add("position")
            }
        }
        return caps
    }

    private fun discoverTypes(klass: KClass<out Fixture>): List<FixtureTypeInfo> {
        val subclasses = klass.sealedSubclasses
        if (subclasses.isNotEmpty()) {
            // Multi-mode sealed family — recurse into subclasses
            return subclasses.flatMap { discoverTypes(it) }
        }

        // Concrete class — extract @FixtureType annotation
        val annotation = klass.findAnnotation<FixtureType>() ?: return emptyList()

        // Try to extract mode info from the Mode enum
        val modeInfo = extractModeInfo(klass)

        // Instantiate for introspection to get capabilities and properties
        val instance = instantiateForIntrospection(klass)
        val capabilities = instance?.let { detectCapabilities(it) } ?: emptyList()
        val properties = instance?.generatePropertyDescriptors() ?: emptyList()
        val elementGroupProperties = instance?.generateElementGroupPropertyDescriptors()

        // Channel count: prefer mode info (multi-mode fixtures), fall back to instance
        val channelCount = modeInfo?.channelCount ?: instance?.channelCount

        return listOf(
            FixtureTypeInfo(
                typeKey = annotation.typeKey,
                manufacturer = annotation.manufacturer,
                model = annotation.model,
                modeName = modeInfo?.modeName,
                channelCount = channelCount,
                capabilities = capabilities,
                properties = properties,
                elementGroupProperties = elementGroupProperties,
                acceptsBeamAngle = annotation.acceptsBeamAngle,
                acceptsGel = annotation.acceptsGel,
                gelCompactDisplay = annotation.gelCompactDisplay,
                kind = annotation.kind,
            )
        )
    }

    private data class ModeInfoResult(val modeName: String, val channelCount: Int)

    private fun extractModeInfo(klass: KClass<out Fixture>): ModeInfoResult? {
        // For multi-mode fixture subclasses, try to find the DmxChannelMode enum value
        // by checking if the class implements MultiModeFixtureFamily
        val ifaces = klass.supertypes.mapNotNull { it.classifier as? KClass<*> }
        val isMultiMode = ifaces.any { it == MultiModeFixtureFamily::class } ||
            klass.supertypes.any { supertype ->
                (supertype.classifier as? KClass<*>)?.supertypes?.any { st ->
                    (st.classifier as? KClass<*>) == MultiModeFixtureFamily::class
                } == true
            }

        if (!isMultiMode) return null

        // Find the Mode enum from the enclosing sealed class
        val enclosingClass = klass.java.enclosingClass?.kotlin ?: return null
        val modeEnumClass = enclosingClass.nestedClasses.find { nested ->
            nested.supertypes.any { (it.classifier as? KClass<*>) == DmxChannelMode::class }
        } ?: return null

        // Match enum constant name to class name by normalising both:
        // e.g. MODE_48CH -> "mode48ch", Mode48Ch -> "mode48ch"
        val className = klass.simpleName?.lowercase() ?: return null
        val enumConstants = modeEnumClass.java.enumConstants ?: return null
        for (constant in enumConstants) {
            if (constant is DmxChannelMode) {
                val enumName = (constant as Enum<*>).name.lowercase().replace("_", "")
                if (className == enumName) {
                    return ModeInfoResult(constant.modeName, constant.channelCount)
                }
            }
        }

        return null
    }
}
