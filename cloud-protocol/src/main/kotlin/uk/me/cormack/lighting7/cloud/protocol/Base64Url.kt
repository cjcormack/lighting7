package uk.me.cormack.lighting7.cloud.protocol

import java.util.Base64

/** Base64url without padding, the one spelling every key, signature and token part takes on the wire. */
internal object Base64Url {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /**
     * The bytes [text] encodes, refusing any spelling but the one [encode] writes. The JDK's decoder
     * also takes padding and ignores stray low bits in the last character, so without this several
     * strings would name one value.
     */
    fun decode(text: String): ByteArray {
        val bytes = decoder.decode(text)
        require(encoder.encodeToString(bytes) == text) { "Not unpadded canonical base64url" }
        return bytes
    }
}
