package uk.me.cormack.lighting7.dmx

import ch.bildspur.artnet.ArtNetClient

/**
 * Transport seam for ArtNet packet I/O. Production wraps [ArtNetClient]; tests inject
 * a recording fake to assert what the controller transmits and in what order — in
 * particular, that the very first frame after construction overlays the [ParkSource].
 */
interface ArtNetTransport {
    fun start()
    fun stop()
    fun broadcastDmx(subnet: Int, universe: Int, dmxData: ByteArray)
    fun unicastDmx(address: String, subnet: Int, universe: Int, dmxData: ByteArray)
}

internal class DefaultArtNetTransport : ArtNetTransport {
    private val client = ArtNetClient()
    override fun start() { client.start() }
    override fun stop() { client.stop() }
    override fun broadcastDmx(subnet: Int, universe: Int, dmxData: ByteArray) {
        client.broadcastDmx(subnet, universe, dmxData)
    }
    override fun unicastDmx(address: String, subnet: Int, universe: Int, dmxData: ByteArray) {
        client.unicastDmx(address, subnet, universe, dmxData)
    }
}
