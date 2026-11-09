package uk.me.cormack.lighting7.osc

import java.net.Inet4Address
import java.net.InetAddress

/**
 * The allowlist as the receive path asks it (venue-rig-fixes plan D1, D2): which [OscSender] an
 * address belongs to. A sender's address is one IP or a range, `a.b.c.d/n` or an IPv6 `addr/n`; an
 * exact address wins, then the longest matching prefix, IPv4 and IPv6 apart, an IPv4-mapped IPv6
 * source matching as IPv4. Built once per settings load and read from the receive threads.
 */
class OscSenderMatcher(senders: List<OscSender>) {
    private val exact: Map<Key, OscSender>
    private val ranges: List<Range>

    private class Range(val network: ByteArray, val prefix: Int, val sender: OscSender)

    /** An address's bytes as a map key. */
    private class Key(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is Key && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    init {
        val exact = HashMap<Key, OscSender>()
        val ranges = ArrayList<Range>()
        for (sender in senders) {
            val entry = parse(sender.address) ?: continue
            if (entry.prefix == entry.network.size * 8) exact.putIfAbsent(Key(entry.network), sender)
            else ranges += Range(entry.network, entry.prefix, sender)
        }
        this.exact = exact
        this.ranges = ranges.sortedByDescending { it.prefix }
    }

    /** The sender [address] belongs to, or null when none admits it. */
    fun match(address: InetAddress): OscSender? {
        // `getByAddress` turns an IPv4-mapped IPv6 address into its IPv4 one, as [canonicalIp] does.
        val bytes = if (address is Inet4Address) address.address else InetAddress.getByAddress(address.address).address
        exact[Key(bytes)]?.let { return it }
        for (range in ranges) {
            if (range.network.size == bytes.size && inPrefix(bytes, range.network, range.prefix)) return range.sender
        }
        return null
    }

    /** [match] for an IP literal; anything else matches nothing, and no name is looked up. */
    fun match(literal: String): OscSender? {
        val canonical = OscService.ipLiteral(literal) ?: return null
        return match(InetAddress.getByName(canonical))
    }

    /** One entry: [network] its address with the host bits cleared, [prefix] bits long. */
    class Entry(val network: ByteArray, val prefix: Int) {
        /** The bare address for a single host, else `network/prefix`. */
        val canonical: String
            get() {
                val host = InetAddress.getByAddress(network).hostAddress
                return if (prefix == network.size * 8) host else "$host/$prefix"
            }
    }

    companion object {
        val EMPTY = OscSenderMatcher(emptyList())

        private val PREFIX = Regex("""\d{1,3}""")

        /**
         * [raw] as an allowlist entry, or null when it is neither an IP literal nor a range of one.
         * An IPv4-mapped IPv6 entry is the IPv4 one, its prefix less 96.
         */
        fun parse(raw: String): Entry? {
            val text = raw.trim()
            val slash = text.indexOf('/')
            val addressText = if (slash < 0) text else text.substring(0, slash)
            val literal = OscService.ipLiteral(addressText) ?: return null
            val parsed = InetAddress.getByName(literal)
            val bytes = parsed.address
            val full = bytes.size * 8
            var prefix = full
            if (slash >= 0) {
                val prefixText = text.substring(slash + 1)
                if (!PREFIX.matches(prefixText)) return null
                prefix = prefixText.toInt()
                // `::ffff:a.b.c.d` parses as IPv4; its prefix counts the 96 mapped bits.
                if (parsed is Inet4Address && addressText.contains(':')) {
                    if (prefix < 96) return null
                    prefix -= 96
                }
                if (prefix > full) return null
            }
            return Entry(masked(bytes, prefix), prefix)
        }

        /** [raw]'s canonical entry: `192.168.0.1/24` is `192.168.0.0/24`, `10.0.0.1/32` is `10.0.0.1`. */
        fun canonical(raw: String): String? = parse(raw)?.canonical

        private fun masked(bytes: ByteArray, prefix: Int): ByteArray = ByteArray(bytes.size) { i ->
            val bits = (prefix - i * 8).coerceIn(0, 8)
            (bytes[i].toInt() and (0xff shl (8 - bits)) and 0xff).toByte()
        }

        /** Whether [address]'s first [prefix] bits are [network]'s, whose host bits are clear. */
        private fun inPrefix(address: ByteArray, network: ByteArray, prefix: Int): Boolean {
            val whole = prefix / 8
            for (i in 0 until whole) if (address[i] != network[i]) return false
            val bits = prefix % 8
            if (bits == 0) return true
            val mask = (0xff shl (8 - bits)) and 0xff
            return (address[whole].toInt() and mask) == (network[whole].toInt() and 0xff)
        }
    }
}
