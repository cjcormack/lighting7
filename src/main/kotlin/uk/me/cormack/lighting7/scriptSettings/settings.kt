package uk.me.cormack.lighting7.scriptSettings

import kotlinx.serialization.Serializable

@Serializable
sealed interface ScriptSettingValue

@JvmInline
@Serializable
value class ScriptSettingList(val list: List<ScriptSetting<*>>)

@Serializable
sealed class ScriptSetting<T: ScriptSettingValue> {
    abstract val name: String
    abstract val defaultValue: T?
}
