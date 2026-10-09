package uk.me.cormack.lighting7.testsupport

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import javax.imageio.ImageIO

/**
 * Scene images made in code (scrim plan session 1) — no binary is committed. Each is a gradient, so
 * a resample that dropped a channel or flipped an axis would change its bytes, and a [holes] PNG
 * carries a transparent square in its top-left quarter: the "cut cloth" case.
 */
fun scenePng(width: Int, height: Int, alpha: Boolean = false, holes: Boolean = alpha, seed: Int = 0): ByteArray {
    val image = BufferedImage(width, height, if (alpha) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val r = (x * 255 / maxOf(1, width - 1) + seed) and 0xFF
            val g = (y * 255 / maxOf(1, height - 1)) and 0xFF
            val b = (seed * 37) and 0xFF
            val a = if (holes && x < width / 4 && y < height / 4) 0 else 255
            image.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
        }
    }
    return encode(image, "png")
}

fun sceneJpeg(width: Int, height: Int, seed: Int = 0): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val r = (x * 255 / maxOf(1, width - 1) + seed) and 0xFF
            val g = (y * 255 / maxOf(1, height - 1)) and 0xFF
            image.setRGB(x, y, (r shl 16) or (g shl 8) or 0x40)
        }
    }
    return encode(image, "jpeg")
}

/**
 * A PNG that is only a signature, a header claiming [width] × [height] and an end — no pixel data.
 * A store that decoded before reading the header would answer "does not decode"; one that reads the
 * header first answers the size.
 */
fun pngHeaderOnly(width: Int, height: Int): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    val ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height)
        .put(8).put(6).put(0).put(0).put(0).array()
    chunk(out, "IHDR", ihdr)
    chunk(out, "IEND", ByteArray(0))
    return out.toByteArray()
}

private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
    out.write(ByteBuffer.allocate(4).putInt(data.size).array())
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    out.write(typeBytes)
    out.write(data)
    val crc = CRC32().apply { update(typeBytes); update(data) }
    out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
}

private fun encode(image: BufferedImage, format: String): ByteArray =
    ByteArrayOutputStream().also { check(ImageIO.write(image, format, it)) { "no $format writer" } }.toByteArray()
