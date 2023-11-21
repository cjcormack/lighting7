package uk.me.cormack.lighting7.fixture.hue

import java.awt.Color

class HueController {
    fun setHueLightLevel(lightId: Int, level: UByte, fadeMs: Long = 0) {
        setHueLevel("light", lightId, level, fadeMs)
    }

    fun setHueGroupLevel(groupId: Int, level: UByte, fadeMs: Long = 0) {
        setHueLevel("group", groupId, level, fadeMs)
    }

    fun setHueLevel(type: String, id: Int, level: UByte, fadeMs: Long = 0) {
//        val ipAddress = ipAddress
//        val username = username
//
//        if (ipAddress == null || username == null) {
//            throw Exception("Missing Hue configuration")
//        }
//
//        context.sink<Int>("active:hue-${type}Action") {
//            argumentByValue("hueIp", ipAddress)
//            argumentByValue("username", username)
//            argumentByValue("${type}Id", id)
//            argumentByValue("on", level > 0u)
//            argumentByValue("bri", level.toInt())
//            argumentByValue("transitiontime", fadeMs / 100)
//        }
    }

    fun setHueLightColor(lightId: Int, color: Color, maxBrightness: Int, fadeMs: Long = 0) {
        setHueColor("light", lightId, color, maxBrightness, fadeMs)
    }

    fun setHueGroupColor(groupId: Int, color: Color, maxBrightness: Int, fadeMs: Long = 0) {
        setHueColor("group", groupId, color, maxBrightness, fadeMs)
    }

    private fun setHueColor(type: String, id: Int, color: Color, maxBrightness: Int, fadeMs: Long = 0) {
//        val ipAddress = ipAddress
//        val username = username
//
//        if (ipAddress == null || username == null) {
//            throw Exception("Missing Hue configuration")
//        }
//
//        val hsbColors = Color.RGBtoHSB(color.red, color.green, color.blue, null)
//
//        val hue: Int = (hsbColors[0] * 65280).roundToInt()
//        val saturation: Int = (hsbColors[1] * 254).roundToInt()
//        val brightness: Int = min((hsbColors[2] * 254).roundToInt(), maxBrightness)
//
//        context.sink<Int>("active:hue-${type}Action") {
//            argumentByValue("hueIp", ipAddress)
//            argumentByValue("username", username)
//            argumentByValue("${type}Id", id)
//            argumentByValue("on", brightness > 0)
//            argumentByValue("bri", brightness)
//            argumentByValue("hue", hue)
//            argumentByValue("sat", saturation)
//            argumentByValue("transitiontime", fadeMs / 100)
//        }
    }
}
