package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.AvroMalformedDataException
import kotlinx.io.EOFException

/**
 * Reads Avro binary straight from a [ByteArray], with nothing between the decoder and the bytes but a position.
 *
 * Reading through a kotlinx-io [kotlinx.io.Buffer] updates the buffer's segment bookkeeping on every read, even when the
 * whole input is one array. Here a read is an array access and a position update, as in Apache Avro's `BinaryDecoder`,
 * which is what lets the one-byte case of a varint, by far the most common (lengths, counts, indexes, small values), be
 * kept small enough for a JIT to inline at every call site, while the longer encodings are decoded out of line.
 *
 * Every read checks the bytes left before reading them: the end of the input is a [kotlinx.io.EOFException], as from a
 * [kotlinx.io.Source]. Every length prefix is checked by [checkBinaryLength] before anything is allocated or skipped for
 * it, and every returned array is a copy.
 */
internal class ByteArrayDecoder(
    private val bytes: ByteArray,
) : AvroBinaryDecoder() {
    private var position = 0

    /** Whether every byte of the input has been read. */
    val isExhausted: Boolean
        get() = position == bytes.size

    override fun readNull() {
    }

    override fun readBoolean(): Boolean {
        return byteAt(position++) == 1.toByte()
    }

    override fun readInt(): Int {
        val position = position
        if (position < bytes.size) {
            val first = bytes[position].toInt()
            if (first >= 0) {
                this.position = position + 1
                return decodeZigZag(first)
            }
        }
        return readVarIntSlow()
    }

    override fun readLong(): Long {
        val position = position
        if (position < bytes.size) {
            val first = bytes[position].toInt()
            if (first >= 0) {
                this.position = position + 1
                return decodeZigZag(first).toLong()
            }
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

    override fun readString(): String {
        val length = readLength()
        val start = take(length)
        return bytes.decodeToString(start, start + length)
    }

    override fun readStringBytes(): ByteArray {
        return readExactly(readLength())
    }

    override fun skipString() {
        take(readLength())
    }

    override fun readBytes(): ByteArray {
        return readExactly(readLength())
    }

    override fun skipBytes() {
        take(readLength())
    }

    override fun readFixed(size: Int): ByteArray {
        return readExactly(size)
    }

    override fun skipFixed(size: Int) {
        take(size)
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
     * The int varints [readInt] does not read itself: those of more than one byte, and the end of the input. Out of line,
     * so that [readInt] stays small.
     */
    private fun readVarIntSlow(): Int {
        var p = position
        val result =
            if (bytes.size - p >= MAX_VARINT_INT_BYTES) {
                decodeVarIntTail(bytes[p++].toInt()) { bytes[p++] }
            } else {
                // Near the end of the input, every byte is checked
                decodeVarIntTail(byteAt(p++).toInt()) { byteAt(p++) }
            }
        position = p
        return result
    }

    /** The long counterpart of [readVarIntSlow]. */
    private fun readVarLongSlow(): Long {
        var p = position
        val result =
            if (bytes.size - p >= MAX_VARINT_LONG_BYTES) {
                decodeVarLongTail(bytes[p++].toInt()) { bytes[p++] }
            } else {
                decodeVarLongTail(byteAt(p++).toInt()) { byteAt(p++) }
            }
        position = p
        return result
    }

    private fun byteAt(index: Int): Byte {
        if (index >= bytes.size) {
            throw EOFException("The input ended in the middle of a value")
        }
        return bytes[index]
    }

    private fun readIntLe(): Int {
        val start = take(4)
        return (bytes[start].toInt() and 0xFF) or
            ((bytes[start + 1].toInt() and 0xFF) shl 8) or
            ((bytes[start + 2].toInt() and 0xFF) shl 16) or
            (bytes[start + 3].toInt() shl 24)
    }

    private fun readLength(): Int {
        return checkBinaryLength(readLong())
    }

    private fun readExactly(length: Int): ByteArray {
        val start = take(length)
        return bytes.copyOfRange(start, start + length)
    }

    /** Consumes the next [length] bytes and returns the index of the first, or throws if fewer are left. */
    private fun take(length: Int): Int {
        val start = position
        if (length > bytes.size - start) {
            throw EOFException("$length bytes expected, only ${bytes.size - start} remaining")
        }
        position = start + length
        return start
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
            if (blockBytes > bytes.size - position) {
                throw EOFException("$blockBytes bytes expected, only ${bytes.size - position} remaining")
            }
            position += blockBytes.toInt()
            result = readLong()
        }
        return result
    }
}