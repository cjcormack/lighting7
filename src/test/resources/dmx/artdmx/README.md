# ArtDMX captures

What artnet4j 0.6.2 put on the wire, captured once on 2026-11-02 before it left the build, and what
`ArtDmxGoldenTest` holds `DefaultArtNetTransport` to.

- `cases.json` lists each call in the order it was made, on one fresh client. Its data is `length`
  bytes, byte `i` being `(seed + 37 * i) and 0xFF`. A unicast went to `127.0.0.1`.
- `<name>.hex` is the datagram that call sent, 16 bytes to a line. Byte 12, the Sequence, is the
  call's place in the list.
- `sequence.txt` is the Sequence of each of 300 full-frame broadcasts from a second fresh client.

The capture ran in the desk's test source set while artnet4j was still a dependency. The receiver was
a `DatagramSocket` on the loopback address and an ephemeral port, and the client was made to send to
it, everything else as production builds it:

```kotlin
val client = ArtNetClient(ArtNetBuffer(), ownPort, receiver.localPort)
client.start()
client.artNetServer.setBroadcastAddress("127.0.0.1")
// for each case: client.broadcastDmx(subnet, universe, data)
//            or client.unicastDmx("127.0.0.1", subnet, universe, data)
// and write the datagram the receiver gets to <name>.hex
```

Never regenerate these from `DefaultArtNetTransport`: they are the evidence that it sends what
artnet4j sent.
