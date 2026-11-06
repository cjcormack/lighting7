package uk.me.cormack.lighting7.cloud

import uk.me.cormack.lighting7.cloud.protocol.Message
import uk.me.cormack.lighting7.cloud.protocol.publicKeysOf
import java.security.PublicKey

/**
 * The keys the cloud signs desk access tokens with (cloud-p1 plan D3): the last `jwks` heard, kept
 * through any reconnect until a newer one arrives, and held in memory only. A restart ends every
 * remote session anyway, and every way in needs the cloud to begin.
 */
class JwksCache {
    @Volatile var keys: Map<String, PublicKey> = emptyMap()
        private set

    fun update(jwks: Message.Jwks) {
        keys = publicKeysOf(jwks.keys)
    }

    fun clear() {
        keys = emptyMap()
    }
}
