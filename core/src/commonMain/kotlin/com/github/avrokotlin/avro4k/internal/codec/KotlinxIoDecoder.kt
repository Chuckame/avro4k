package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.AvroMalformedDataException
import kotlinx.io.InternalIoApi
import kotlinx.io.Source
import kotlinx.io.UnsafeIoApi
import kotlinx.io.readByteArray
import kotlinx.io.unsafe.UnsafeBufferOperations

/**
 * Reads Avro binary from a kotlinx-io [Source], straight from the array of its head segment.
 *
 * Reading through the [Source] API updates the buffer's segment bookkeeping on every read. Instead, the head segment's
 * array is taken once and read with a plain position, as [ByteArrayDecoder] reads its input: the bytes read are only
 * consumed from the [source] when the segment has been read to its end, or by [release]. A value that does not fit in
 * what is left of the segment is read across segments, through the [Source] API or byte by byte.
 *
 * So the [source] lags behind what has been read until [release] is called: nothing else may read the [source] in
 * between, and [release] must be called once decoding ends, failed or not, so that the [source] is left right after
 * the last value read.
 *
 * Every length prefix is checked by [checkBinaryLength] before anything is allocated or skipped for it, and every
 * returned array is an exact copy, safe even when the value spans several segments of the [source].
 */
internal class KotlinxIoDecoder(
    private val source: Source,
) : AvroBinaryDecoder() {
    /** The head segment's array, or an empty one when no segment is taken. Never written. */
    private var bytes: ByteArray = EMPTY

    /** The index of the next byte to read in [bytes]. */
    private var position = 0

    /** The index after the head segment's last byte in [bytes]. */
    private var limit = 0

    /**
     * The index of the head segment's first byte when it was taken: the bytes from there to [position] are read, but not
     * yet consumed from the [source].
     */
    private var start = 0

    /** Consumes from the [source] every byte read so far, and lets go of the head segment. */
    fun release() {
        val read = position - start
        bytes = EMPTY
        position = 0
        limit = 0
        start = 0
        if (read != 0) {
            source.skip(read.toLong())
        }
    }

    override fun readNull() {
    }

    override fun readBoolean(): Boolean {
        return nextByte() == 1.toByte()
    }

    /** Apache Avro's shape, as [ByteArrayDecoder.readInt]: the whole int decoded here when 5 bytes are left in the segment. */
    override fun readInt(): Int {
        val p = position
        if (limit - p >= MAX_VARINT_INT_BYTES) {
            var len = 0
            val result = decodeVarInt { bytes[p + len++] }
            position = p + len
            return result
        }
        return readVarIntSlow()
    }

    /**
     * Apache Avro's shape, as [ByteArrayDecoder.readLong]: the first 4 bytes decoded here when 10 bytes are left in the
     * segment, the 5th to 10th out of line.
     */
    override fun readLong(): Long {
        val p = position
        if (limit - p >= MAX_VARINT_LONG_BYTES) {
            var b = bytes[p].toInt()
            if (b >= 0) {
                position = p + 1
                return decodeZigZag(b).toLong()
            }
            var n = b and 0x7F
            b = bytes[p + 1].toInt()
            n += (b and 0x7F) shl 7
            if (b >= 0) {
                position = p + 2
                return decodeZigZag(n).toLong()
            }
            b = bytes[p + 2].toInt()
            n += (b and 0x7F) shl 14
            if (b >= 0) {
                position = p + 3
                return decodeZigZag(n).toLong()
            }
            b = bytes[p + 3].toInt()
            n += (b and 0x7F) shl 21
            if (b >= 0) {
                position = p + 4
                return decodeZigZag(n).toLong()
            }
            return readVarLongHigh(n.toLong(), p + 4)
        }
        return readVarLongSlow()
    }

    override fun readFloat(): Float {
        return Float.fromBits(readIntLe())
    }

    override fun readDouble(): Double {
        val low = readIntLe().toLong() and 0xFFFFFFFFL
        val high = readIntLe().toLong()
        return Double.fromBits(low or (high shl 32))
    }

    /**
     * Decodes with the standard library's [decodeToString] rather than kotlinx-io's own UTF-8 decoder: on the JVM, it is
     * the JDK's decoder, as with Apache Avro, which skips the intermediate `CharArray` kotlinx-io fills, and keeps
     * ASCII and Latin-1 strings one byte per character. When the head segment holds the whole string, it is decoded
     * straight from the segment's array.
     */
    override fun readString(): String {
        val length = readLength()
        val p = position
        if (limit - p >= length) {
            position = p + length
            return bytes.decodeToString(p, p + length)
        }
        release()
        return source.readByteArray(length).decodeToString()
    }

    override fun readStringBytes(): ByteArray {
        return readExactly(readLength())
    }

    override fun skipString() {
        skip(readLength().toLong())
    }

    override fun readBytes(): ByteArray {
        return readExactly(readLength())
    }

    override fun skipBytes() {
        skip(readLength().toLong())
    }

    override fun readFixed(size: Int): ByteArray {
        return readExactly(size)
    }

    override fun skipFixed(size: Int) {
        skip(size.toLong())
    }

    override fun readEnum(): Int {
        return readInt()
    }

    override fun readIndex(): Int {
        return readInt()
    }

    override fun readArrayStart(): Long {
        return doReadItemCount()
    }

    override fun arrayNext(): Long {
        return doReadItemCount()
    }

    override fun skipArray(): Long {
        return doSkipItems()
    }

    override fun readMapStart(): Long {
        return doReadItemCount()
    }

    override fun mapNext(): Long {
        return doReadItemCount()
    }

    override fun skipMap(): Long {
        return doSkipItems()
    }

    /**
     * The int varints [readInt] does not read itself: those starting less than 5 bytes before the end of the segment,
     * read byte by byte across segments. Out of line, so that [readInt] stays small.
     */
    private fun readVarIntSlow(): Int {
        return decodeVarInt { nextByte() }
    }

    /** The long counterpart of [readVarIntSlow], for varints starting less than 10 bytes before the end of the segment. */
    private fun readVarLongSlow(): Long {
        val first = nextByte().toInt()
        return if (first >= 0) decodeZigZag(first).toLong() else decodeVarLongTail(first) { nextByte() }
    }

    /** The 5th to 10th bytes of a long, out of line as Apache Avro's `innerLongDecode`: they are rare. */
    private fun readVarLongHigh(
        low: Long,
        index: Int,
    ): Long {
        var p = index
        val result = decodeVarLong2(low) { bytes[p++] }
        position = p
        return result
    }

    /** Reads one byte, taking the next segment when the current one has been read to its end. */
    private fun nextByte(): Byte {
        if (position == limit) {
            takeNextSegment()
        }
        return bytes[position++]
    }

    /** Consumes the segment read so far and takes the next one, throwing an EOFException at the end of the input. */
    @OptIn(InternalIoApi::class, UnsafeIoApi::class)
    private fun takeNextSegment() {
        release()
        source.require(1L)
        UnsafeBufferOperations.readFromHead(source.buffer) { array, from, to ->
            bytes = array
            position = from
            limit = to
            start = from
            0
        }
    }

    private fun readIntLe(): Int {
        val p = position
        if (limit - p >= 4) {
            position = p + 4
            return (bytes[p].toInt() and 0xFF) or
                ((bytes[p + 1].toInt() and 0xFF) shl 8) or
                ((bytes[p + 2].toInt() and 0xFF) shl 16) or
                (bytes[p + 3].toInt() shl 24)
        }
        return (nextByte().toInt() and 0xFF) or
            ((nextByte().toInt() and 0xFF) shl 8) or
            ((nextByte().toInt() and 0xFF) shl 16) or
            (nextByte().toInt() shl 24)
    }

    private fun readLength(): Int {
        return checkBinaryLength(readLong())
    }

    private fun readExactly(length: Int): ByteArray {
        val p = position
        if (limit - p >= length) {
            position = p + length
            return bytes.copyOfRange(p, p + length)
        }
        release()
        return source.readByteArray(length)
    }

    /** Skips the next [length] bytes, throwing an EOFException if fewer are left. */
    private fun skip(length: Long) {
        if (limit - position >= length) {
            position += length.toInt()
            return
        }
        release()
        source.skip(length)
    }

    private fun doReadItemCount(): Long {
        var result = readLong()
        if (result < 0L) {
            readLong()
            result = -result
        }
        return result
    }

    private fun doSkipItems(): Long {
        var result = readLong()
        while (result < 0L) {
            val blockBytes = readLong()
            if (blockBytes < 0L) {
                throw AvroMalformedDataException("Malformed data: negative byte size $blockBytes for an array or map block")
            }
            skip(blockBytes)
            result = readLong()
        }
        return result
    }

    private inline fun decodeVarInt(readByte: () -> Byte): Int {
        val first = readByte().toInt()
        if (first >= 0) {
            return decodeZigZag(first)
        }
        return decodeVarIntTail(first, readByte)
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}