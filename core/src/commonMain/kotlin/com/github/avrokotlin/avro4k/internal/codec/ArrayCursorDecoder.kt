package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.AvroMalformedDataException

/**
 * Reads Avro binary from an array with a plain position, whatever the input: [ByteArrayDecoder] reads a whole
 * [ByteArray], [KotlinxIoDecoder] the array of a stream's head segment, then the next one.
 *
 * Reading through a kotlinx-io [kotlinx.io.Source] updates the buffer's segment bookkeeping on every read, even when the
 * whole input is one array. Here a read is an array access and a position update, as in Apache Avro's `BinaryDecoder`,
 * whose varint shape is kept too: away from the end of the array, an int is decoded in one go without checking each
 * byte against the end, and so are the first four bytes of a long, its rare 5th to 10th bytes being decoded out of line.
 *
 * Every value is read here; a subclass only says what follows the array, for a value that does not fit in what is left
 * of it: the next array ([nextArray]), or the bytes read or skipped across arrays ([readAcross], [skipAcross]). The end
 * of the input is a [kotlinx.io.EOFException], as from a [kotlinx.io.Source]. Every length prefix is checked by
 * [checkBinaryLength] before anything is allocated or skipped for it, and every returned array is a copy.
 */
internal abstract class ArrayCursorDecoder(
    /** The array being read. Never written. */
    protected var bytes: ByteArray,
    /** The index of the next byte to read in [bytes]. */
    protected var position: Int,
    /** The index after the last byte of the input in [bytes]. */
    protected var limit: Int,
) : AvroBinaryDecoder() {
    /**
     * Makes [bytes] the next array of the input, [position] its first byte and [limit] the index after its last, every
     * byte of the current array having been read, or throws an EOFException at the end of the input.
     */
    protected abstract fun nextArray()

    /** Reads the next [length] bytes, more than what is left of [bytes], or throws an EOFException if fewer are left. */
    protected abstract fun readAcross(length: Int): ByteArray

    /** Skips the next [length] bytes, more than what is left of [bytes], or throws an EOFException if fewer are left. */
    protected abstract fun skipAcross(length: Long)

    final override fun readNull() {
    }

    final override fun readBoolean(): Boolean {
        return nextByte() == 1.toByte()
    }

    /** Apache Avro's shape: the whole int decoded here when 5 bytes are left, as `BinaryDecoder.readInt`. */
    final override fun readInt(): Int {
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
     * Apache Avro's shape: the first 4 bytes decoded here when 10 bytes are left, the 5th to 10th out of line, as
     * `BinaryDecoder.readLong` and its `innerLongDecode`.
     */
    final override fun readLong(): Long {
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

    final override fun readFloat(): Float {
        return Float.fromBits(readIntLe())
    }

    final override fun readDouble(): Double {
        val low = readIntLe().toLong() and 0xFFFFFFFFL
        val high = readIntLe().toLong()
        return Double.fromBits(low or (high shl 32))
    }

    /** Decodes straight from [bytes] when they hold the whole string, from a copy otherwise. */
    final override fun readString(): String {
        val length = readLength()
        val p = position
        if (limit - p >= length) {
            position = p + length
            return bytes.decodeUtf8(p, p + length)
        }
        return readAcross(length).decodeUtf8()
    }

    final override fun readStringBytes(): ByteArray {
        return readExactly(readLength())
    }

    final override fun skipString() {
        skip(readLength().toLong())
    }

    final override fun readBytes(): ByteArray {
        return readExactly(readLength())
    }

    final override fun skipBytes() {
        skip(readLength().toLong())
    }

    final override fun readFixed(size: Int): ByteArray {
        return readExactly(size)
    }

    final override fun skipFixed(size: Int) {
        skip(size.toLong())
    }

    final override fun readEnum(): Int {
        return readInt()
    }

    final override fun readIndex(): Int {
        return readInt()
    }

    final override fun readArrayStart(): Long {
        return doReadItemCount()
    }

    final override fun arrayNext(): Long {
        return doReadItemCount()
    }

    final override fun skipArray(): Long {
        return doSkipItems()
    }

    final override fun readMapStart(): Long {
        return doReadItemCount()
    }

    final override fun mapNext(): Long {
        return doReadItemCount()
    }

    final override fun skipMap(): Long {
        return doSkipItems()
    }

    /**
     * The int varints [readInt] does not read itself: those starting less than 5 bytes before the end of the array,
     * read byte by byte. Out of line, so that [readInt] stays small.
     */
    private fun readVarIntSlow(): Int {
        return decodeVarInt { nextByte() }
    }

    /** The long counterpart of [readVarIntSlow], for varints starting less than 10 bytes before the end of the array. */
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

    private inline fun decodeVarInt(readByte: () -> Byte): Int {
        val first = readByte().toInt()
        if (first >= 0) {
            return decodeZigZag(first)
        }
        return decodeVarIntTail(first, readByte)
    }

    /** Reads one byte, taking the next array when the current one has been read to its end. */
    private fun nextByte(): Byte {
        if (position == limit) {
            nextArray()
        }
        return bytes[position++]
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
        return readAcross(length)
    }

    private fun skip(length: Long) {
        if (limit - position >= length) {
            position += length.toInt()
            return
        }
        skipAcross(length)
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
}