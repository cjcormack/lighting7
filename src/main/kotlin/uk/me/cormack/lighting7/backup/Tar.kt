package uk.me.cormack.lighting7.backup

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The POSIX tar a backup archive is (cloud-p3 plan D5): regular files only, each a ustar header and its
 * bytes padded to 512, a path too long for ustar carried in a PAX `path` record before it, and two
 * zero blocks at the end. Written as a stream, so nothing is held but one block.
 */
class TarWriter(private val out: OutputStream) {
    private var closed = false

    /** One file of [size] bytes; [copy] must write exactly that many to the stream it is given. */
    fun file(path: String, size: Long, modifiedS: Long, copy: (OutputStream) -> Unit) {
        check(!closed) { "The archive is closed" }
        require(size >= 0) { "A size is not negative" }
        val split = splitUstar(path)
        if (split == null) {
            val record = paxRecord("path", path)
            writeHeader(PAX_NAME, record.size.toLong(), modifiedS, TYPE_PAX, prefix = "")
            out.write(record)
            pad(record.size.toLong())
            writeHeader(utf8Tail(path, 99).trimStart('/').ifEmpty { "file" }, size, modifiedS, TYPE_FILE, prefix = "")
        } else {
            writeHeader(split.second, size, modifiedS, TYPE_FILE, prefix = split.first)
        }
        val counting = CountingOutput(out)
        copy(counting)
        if (counting.count != size) throw IOException("$path wrote ${counting.count} bytes, not $size")
        pad(size)
    }

    /** The end-of-archive blocks; leaves the underlying stream open. */
    fun finish() {
        if (closed) return
        closed = true
        out.write(ByteArray(BLOCK * 2))
        out.flush()
    }

    private fun pad(size: Long) {
        val rest = ((BLOCK - size % BLOCK) % BLOCK).toInt()
        if (rest > 0) out.write(ByteArray(rest))
    }

    private fun writeHeader(name: String, size: Long, modifiedS: Long, type: Char, prefix: String) {
        val header = ByteArray(BLOCK)
        put(header, 0, 100, name.toByteArray(Charsets.UTF_8))
        put(header, 100, 8, octal(0b110_100_100, 8))
        put(header, 108, 8, octal(0, 8))
        put(header, 116, 8, octal(0, 8))
        put(header, 124, 12, octal(size, 12))
        put(header, 136, 12, octal(modifiedS.coerceAtLeast(0), 12))
        header.fill(' '.code.toByte(), 148, 156)
        header[156] = type.code.toByte()
        put(header, 257, 6, "ustar\u0000".toByteArray(Charsets.US_ASCII))
        put(header, 263, 2, "00".toByteArray(Charsets.US_ASCII))
        put(header, 345, 155, prefix.toByteArray(Charsets.UTF_8))
        val sum = header.sumOf { it.toInt() and 0xff }
        put(header, 148, 8, "%06o\u0000 ".format(sum).toByteArray(Charsets.US_ASCII))
        out.write(header)
    }

    private class CountingOutput(private val out: OutputStream) : OutputStream() {
        var count = 0L

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }
    }

    companion object {
        const val BLOCK = 512
        private const val TYPE_FILE = '0'
        private const val TYPE_PAX = 'x'
        private const val PAX_NAME = "PaxHeader"

        /** A path as ustar's prefix and name, or null when it needs a PAX record. */
        internal fun splitUstar(path: String): Pair<String, String>? {
            val bytes = path.toByteArray(Charsets.UTF_8)
            if (bytes.size <= 100) return "" to path
            var cut = path.lastIndexOf('/')
            while (cut > 0) {
                val prefix = path.substring(0, cut)
                val name = path.substring(cut + 1)
                if (prefix.toByteArray(Charsets.UTF_8).size <= 155 && name.toByteArray(Charsets.UTF_8).size <= 100 && name.isNotEmpty()) {
                    return prefix to name
                }
                cut = path.lastIndexOf('/', cut - 1)
            }
            return null
        }

        /** The end of [text] that fits in [maxBytes] of UTF-8, never splitting a character. */
        internal fun utf8Tail(text: String, maxBytes: Int): String {
            var bytes = 0
            var start = text.length
            while (start > 0) {
                val cp = text.codePointBefore(start)
                val width = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                if (bytes + width > maxBytes) break
                bytes += width
                start -= Character.charCount(cp)
            }
            return text.substring(start)
        }

        private fun paxRecord(key: String, value: String): ByteArray {
            val body = " $key=$value\n".toByteArray(Charsets.UTF_8)
            var length = body.size + 1
            while (length.toString().length + body.size != length) length = length.toString().length + body.size
            return length.toString().toByteArray(Charsets.US_ASCII) + body
        }

        private fun octal(value: Long, width: Int): ByteArray {
            val digits = value.toString(8)
            require(digits.length < width) { "$value does not fit a tar field of $width" }
            return (digits.padStart(width - 1, '0') + "\u0000").toByteArray(Charsets.US_ASCII)
        }

        private fun octal(value: Int, width: Int) = octal(value.toLong(), width)

        private fun put(header: ByteArray, at: Int, width: Int, bytes: ByteArray) {
            require(bytes.size <= width) { "A tar field of $width bytes cannot hold ${bytes.size}" }
            System.arraycopy(bytes, 0, header, at, bytes.size)
        }
    }
}

/** Reads what [TarWriter] writes: each file's path and a stream of exactly its bytes. */
class TarReader(private val input: InputStream) {
    class Entry(val path: String, val size: Long, val content: InputStream)

    private var current: BoundedInput? = null
    private var pendingPad = 0L

    /** The next file, or null at the end of the archive. The previous entry's stream is skipped to its end. */
    fun next(): Entry? {
        current?.let { skipFully(it.remaining()) }
        current = null
        skipFully(pendingPad)
        pendingPad = 0
        var paxPath: String? = null
        while (true) {
            val header = readBlock() ?: return null
            if (header.all { it.toInt() == 0 }) return null
            val type = header[156].toInt().toChar()
            val size = parseOctal(header, 124, 12)
            if (type == 'x') {
                val record = readExactly(size.toInt())
                skipFully(padOf(size))
                paxPath = parsePaxPath(record.toString(Charsets.UTF_8)) ?: paxPath
                continue
            }
            val name = text(header, 0, 100)
            val prefix = text(header, 345, 155)
            val path = paxPath ?: if (prefix.isEmpty()) name else "$prefix/$name"
            if (type != '0' && type != '\u0000') {
                skipFully(size + padOf(size))
                paxPath = null
                continue
            }
            val entry = BoundedInput(input, size)
            current = entry
            pendingPad = padOf(size)
            return Entry(path, size, entry)
        }
    }

    private fun readBlock(): ByteArray? {
        val block = ByteArray(TarWriter.BLOCK)
        var read = 0
        while (read < block.size) {
            val n = input.read(block, read, block.size - read)
            if (n == -1) return if (read == 0) null else throw EOFException("The archive ends inside a header")
            read += n
        }
        return block
    }

    private fun readExactly(n: Int): ByteArray {
        val bytes = ByteArray(n)
        var read = 0
        while (read < n) {
            val got = input.read(bytes, read, n - read)
            if (got == -1) throw EOFException("The archive ends inside an entry")
            read += got
        }
        return bytes
    }

    private fun skipFully(n: Long) {
        var left = n
        val buffer = ByteArray(8192)
        while (left > 0) {
            val got = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (got == -1) throw EOFException("The archive ends inside an entry")
            left -= got
        }
    }

    private class BoundedInput(private val input: InputStream, private var left: Long) : InputStream() {
        fun remaining() = left

        override fun read(): Int {
            if (left <= 0) return -1
            val b = input.read()
            if (b == -1) throw EOFException("The archive ends inside an entry")
            left--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n == -1) throw EOFException("The archive ends inside an entry")
            left -= n
            return n
        }
    }

    private companion object {
        fun padOf(size: Long) = (TarWriter.BLOCK - size % TarWriter.BLOCK) % TarWriter.BLOCK

        fun text(header: ByteArray, at: Int, width: Int): String {
            var end = at
            while (end < at + width && header[end].toInt() != 0) end++
            return String(header, at, end - at, Charsets.UTF_8)
        }

        fun parseOctal(header: ByteArray, at: Int, width: Int): Long =
            text(header, at, width).trim().ifEmpty { "0" }.toLong(8)

        fun parsePaxPath(records: String): String? = records.split('\n').firstNotNullOfOrNull { line ->
            line.substringAfter(' ', "").takeIf { it.startsWith("path=") }?.removePrefix("path=")
        }
    }
}
