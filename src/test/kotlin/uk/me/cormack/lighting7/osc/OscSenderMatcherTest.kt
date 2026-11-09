package uk.me.cormack.lighting7.osc

import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OscSenderMatcherTest {
    private fun ip(literal: String): InetAddress = InetAddress.getByName(literal)

    @Test
    fun `a range is stored as its network, and a full-length prefix as the bare address`() {
        assertEquals("192.168.0.0/24", OscSenderMatcher.canonical("192.168.0.1/24"))
        assertEquals("192.168.0.0/24", OscSenderMatcher.canonical(" 192.168.0.0/24 "))
        assertEquals("10.0.0.0/8", OscSenderMatcher.canonical("10.200.3.4/8"))
        assertEquals("0.0.0.0/0", OscSenderMatcher.canonical("172.16.5.5/0"))
        assertEquals("192.168.1.20", OscSenderMatcher.canonical("192.168.1.20"))
        assertEquals("192.168.1.20", OscSenderMatcher.canonical("192.168.1.20/32"))
        assertEquals("fd00:0:0:0:0:0:0:0/64", OscSenderMatcher.canonical("fd00::1234/64"))
        assertEquals("0:0:0:0:0:0:0:1", OscSenderMatcher.canonical("::1/128"))
        assertEquals(OscSenderMatcher.canonical("::1"), OscSenderMatcher.canonical("::1/128"))
    }

    @Test
    fun `an IPv4-mapped entry is the IPv4 one`() {
        assertEquals("192.168.1.0/24", OscSenderMatcher.canonical("::ffff:192.168.1.7/120"))
        assertNull(OscSenderMatcher.canonical("::ffff:192.168.1.7/64"))
    }

    @Test
    fun `a bad prefix or text is refused`() {
        for (bad in listOf(
            "192.168.0.0/33", "fd00::/129", "192.168.0.0/", "192.168.0.0/-1", "192.168.0.0/2a",
            "192.168.0.0/24/8", "/24", "desk.local/24", "desk.local", "", "300.1.1.1/8", "192.168.0.0/0024",
        )) {
            assertNull(OscSenderMatcher.canonical(bad), bad)
        }
    }

    @Test
    fun `an exact address wins over a range, and a longer prefix over a shorter`() {
        val wide = OscSender(1, "Wide", "10.0.0.0/8")
        val narrow = OscSender(2, "Narrow", "10.1.0.0/16")
        val qlab = OscSender(3, "QLab", "10.1.2.3")
        val matcher = OscSenderMatcher(listOf(wide, narrow, qlab))

        assertEquals(qlab, matcher.match(ip("10.1.2.3")))
        assertEquals(narrow, matcher.match(ip("10.1.2.4")))
        assertEquals(wide, matcher.match(ip("10.2.0.1")))
        assertNull(matcher.match(ip("11.0.0.1")))
        assertEquals(narrow, matcher.match("10.1.9.9"))
        assertNull(matcher.match("desk.local"))
    }

    @Test
    fun `IPv4 and IPv6 never match each other, and a mapped source matches as IPv4`() {
        val all4 = OscSender(1, "Every IPv4", "0.0.0.0/0")
        val all6 = OscSender(2, "Every IPv6", "::/0")
        val matcher = OscSenderMatcher(listOf(all4, all6))
        assertEquals(all4, matcher.match(ip("192.168.1.1")))
        assertEquals(all6, matcher.match(ip("fd00::1")))

        val mappedBytes = ByteArray(16).also {
            it[10] = 0xff.toByte(); it[11] = 0xff.toByte()
            it[12] = 192.toByte(); it[13] = 168.toByte(); it[14] = 1; it[15] = 7
        }
        val mapped = Inet6Address.getByAddress(null, mappedBytes, -1)
        val lan = OscSender(3, "LAN", "192.168.1.0/24")
        assertEquals(lan, OscSenderMatcher(listOf(lan)).match(mapped))
        val host = OscSender(4, "Host", "192.168.1.7")
        assertEquals(host, OscSenderMatcher(listOf(host)).match(mapped))
        assertEquals("192.168.1.7", canonicalIp(mapped))
    }

    @Test
    fun `a prefix that ends inside a byte compares only its own bits`() {
        val block = OscSender(1, "Block", "172.16.0.0/12")
        val matcher = OscSenderMatcher(listOf(block))
        assertEquals(block, matcher.match(ip("172.31.255.255")))
        assertNull(matcher.match(ip("172.32.0.0")))
        assertNull(matcher.match(ip("172.15.255.255")))
        val v6 = OscSender(2, "ULA", "fc00::/7")
        assertEquals(v6, OscSenderMatcher(listOf(v6)).match(ip("fdff::1")))
        assertNull(OscSenderMatcher(listOf(v6)).match(ip("fe00::1")))
    }

    @Test
    fun `an entry the matcher cannot read is skipped`() {
        val good = OscSender(1, "Good", "127.0.0.1")
        val matcher = OscSenderMatcher(listOf(OscSender(2, "Bad", "nonsense"), good))
        assertEquals(good, matcher.match(ip("127.0.0.1")))
    }
}
