package uk.me.cormack.lighting7.scriptSettings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class IntValue(val int: Int): ScriptSettingValue

@Serializable
@SerialName("scriptSettingInt")
class IntSetting(
    override val name: String,
    val minValue: IntValue? = null,
    val maxValue: IntValue? = null,
    override val defaultValue: IntValue? = null,
): ScriptSetting<IntValue>()
