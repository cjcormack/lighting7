package uk.me.cormack.lighting7.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [LegacyStaticEffectMigration.convertRow] — the pure conversion from a legacy
 * `cue_ad_hoc_effects` row to a `cue_property_assignments` row.
 *
 * The DB-touching [LegacyStaticEffectMigration.run] is covered end-to-end at application
 * startup: it's idempotent, runs inside the same transaction that already handles schema
 * setup, and any regressions surface the moment the backend boots.
 */
class LegacyStaticEffectMigrationTest {

    private fun row(
        effectType: String,
        category: String = "dimmer",
        propertyName: String? = "dimmer",
        parameters: Map<String, String> = mapOf("value" to "180"),
        targetType: String = "fixture",
        targetKey: String = "hex-1",
        sortOrder: Int = 3,
    ) = LegacyStaticEffectMigration.LegacyRow(
        id = 1,
        cueId = 42,
        targetType = targetType,
        targetKey = targetKey,
        effectType = effectType,
        category = category,
        propertyName = propertyName,
        parameters = parameters,
        sortOrder = sortOrder,
    )

    @Test
    fun `StaticValue with property name and value converts to assignment`() {
        val result = LegacyStaticEffectMigration.convertRow(
            row("StaticValue", propertyName = "dimmer", parameters = mapOf("value" to "180"))
        )
        val converted = assertIs<LegacyStaticEffectMigration.ConversionResult.Converted>(result)
        assertEquals(42, converted.row.cueId)
        assertEquals("fixture", converted.row.targetType)
        assertEquals("hex-1", converted.row.targetKey)
        assertEquals("dimmer", converted.row.propertyName)
        assertEquals("180", converted.row.value)
        assertEquals(3, converted.row.sortOrder)
    }

    @Test
    fun `StaticSetting uses the level parameter instead of value`() {
        val result = LegacyStaticEffectMigration.convertRow(
            row("StaticSetting", propertyName = "mode", parameters = mapOf("level" to "64"))
        )
        val converted = assertIs<LegacyStaticEffectMigration.ConversionResult.Converted>(result)
        assertEquals("mode", converted.row.propertyName)
        assertEquals("64", converted.row.value)
    }

    @Test
    fun `group target is preserved`() {
        val result = LegacyStaticEffectMigration.convertRow(
            row("StaticValue", targetType = "group", targetKey = "front-wash")
        )
        val converted = assertIs<LegacyStaticEffectMigration.ConversionResult.Converted>(result)
        assertEquals("group", converted.row.targetType)
        assertEquals("front-wash", converted.row.targetKey)
    }

    @Test
    fun `null property name with dimmer category falls back to dimmer`() {
        val result = LegacyStaticEffectMigration.convertRow(
            row("StaticSetting", category = "dimmer", propertyName = null, parameters = mapOf("level" to "50"))
        )
        val converted = assertIs<LegacyStaticEffectMigration.ConversionResult.Converted>(result)
        assertEquals("dimmer", converted.row.propertyName)
        assertEquals("50", converted.row.value)
    }

    @Test
    fun `null property name with colour category falls back to rgbColour`() {
        val result = LegacyStaticEffectMigration.convertRow(
            row("StaticValue", category = "colour", propertyName = null)
        )
        val converted = assertIs<LegacyStaticEffectMigration.ConversionResult.Converted>(result)
        assertEquals("rgbColour", converted.row.propertyName)
    }

    @Test
    fun `null property name with position category falls back to position`() {
        val result = LegacyStaticEffectMigration.convertRow(
            row("StaticValue", category = "position", propertyName = null)
        )
        val converted = assertIs<LegacyStaticEffectMigration.ConversionResult.Converted>(result)
        assertEquals("position", converted.row.propertyName)
    }

    @Test
    fun `null property name with setting category is still skipped`() {
        val result = LegacyStaticEffectMigration.convertRow(
            row("StaticSetting", category = "setting", propertyName = null, parameters = mapOf("level" to "50"))
        )
        val skipped = assertIs<LegacyStaticEffectMigration.ConversionResult.Skipped>(result)
        assertTrue("property_name" in skipped.reason)
        assertTrue("setting" in skipped.reason)
    }

    @Test
    fun `explicit property name wins over category fallback`() {
        val result = LegacyStaticEffectMigration.convertRow(
            row("StaticValue", category = "dimmer", propertyName = "uv")
        )
        val converted = assertIs<LegacyStaticEffectMigration.ConversionResult.Converted>(result)
        assertEquals("uv", converted.row.propertyName)
    }

    @Test
    fun `missing value parameter is skipped`() {
        val result = LegacyStaticEffectMigration.convertRow(
            row("StaticValue", parameters = emptyMap())
        )
        val skipped = assertIs<LegacyStaticEffectMigration.ConversionResult.Skipped>(result)
        assertTrue("value" in skipped.reason)
    }

    @Test
    fun `missing level parameter for StaticSetting is skipped`() {
        val result = LegacyStaticEffectMigration.convertRow(
            row("StaticSetting", parameters = emptyMap())
        )
        val skipped = assertIs<LegacyStaticEffectMigration.ConversionResult.Skipped>(result)
        assertTrue("level" in skipped.reason)
    }

    @Test
    fun `non-numeric value is skipped`() {
        val result = LegacyStaticEffectMigration.convertRow(
            row("StaticValue", parameters = mapOf("value" to "banana"))
        )
        val skipped = assertIs<LegacyStaticEffectMigration.ConversionResult.Skipped>(result)
        assertTrue("UByte" in skipped.reason)
    }

    @Test
    fun `out-of-range value is skipped`() {
        val result = LegacyStaticEffectMigration.convertRow(
            row("StaticValue", parameters = mapOf("value" to "300"))
        )
        assertIs<LegacyStaticEffectMigration.ConversionResult.Skipped>(result)
    }

    @Test
    fun `negative value is skipped`() {
        val result = LegacyStaticEffectMigration.convertRow(
            row("StaticValue", parameters = mapOf("value" to "-5"))
        )
        assertIs<LegacyStaticEffectMigration.ConversionResult.Skipped>(result)
    }

    @Test
    fun `unknown effect type is skipped`() {
        val result = LegacyStaticEffectMigration.convertRow(
            row("SineWave")
        )
        val skipped = assertIs<LegacyStaticEffectMigration.ConversionResult.Skipped>(result)
        assertTrue("effect_type" in skipped.reason)
    }

    @Test
    fun `value with surrounding whitespace parses`() {
        val result = LegacyStaticEffectMigration.convertRow(
            row("StaticValue", parameters = mapOf("value" to "  200  "))
        )
        val converted = assertIs<LegacyStaticEffectMigration.ConversionResult.Converted>(result)
        assertEquals("200", converted.row.value)
    }
}
