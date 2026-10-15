package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.AvroMalformedDataException
import kotlinx.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads Avro binary straight from a [ByteBuffer], heap or direct, read-only or not, starting at its position.
 *
 * Every read advances the [buffer]'s position, so once a value is decoded, the position sits right after its last
 * byte: nothing past it is read nor copied, whatever the number of bytes remaining after the value.
 *
 * It exists because kotlinx-io has no [kotlinx.io.Source] backed by a [ByteBuffer] yet
 * (https://github.com/Kotlin/kotlinx-io/issues/239): reading one through a [KotlinxIoDecoder] requires copying all its
 * remaining bytes into a [kotlinx.io.Buffer] first (a direct or read-only buffer has no array to wrap), even when the
 * value only takes a few of them. Once kotlinx-io provides such a source, this class can give way to it.
 *
 * A single-byte read past the limit throws the [ByteBuffer]'s [java.nio.BufferUnderflowException], left to the caller
 * to convert once per decoded value rather than checked on every byte; a length-prefixed read checks the remaining
 * bytes itself, before allocating anything. The [buffer]'s byte order is left as is: floats and doubles are read
 * little-endian whatever it is.
 */
internal class ByteBufferDecoder(
    private val buffer: ByteBuffer,
) : AvroBinaryDecoder() {
    private val swapBytes = buffer.order() == ByteOrder.BIG_ENDIAN

    override fun readNull() {
    }

    override fun readBoolean(): Boolean {
        return buffer.get() == 1.toByte()
    }

    override fun readInt(): Int {
        val first = buffer.get().toInt()
        if (first >= 0) {
            return decodeZigZag(first)
        }
        return readVarIntTail(first)
    }

    override fun readLong(): Long {
        val first = buffer.get().toInt()
        if (first >= 0) {
            return decodeZigZag(first).toLong()
        }
        return readVarLongTail(first)
    }

    override fun readFloat(): Float {
        val bits = buffer.getInt()
        return Float.fromBits(if (swapBytes) Integer.reverseBytes(bits) else bits)
    }

    override fun readDouble(): Double {
        val bits = buffer.getLong()
        return Double.fromBits(if (swapBytes) java.lang.Long.reverseBytes(bits) else bits)
    }

    override fun readString(): String {
        val length = readLength()
        if (buffer.hasArray()) {
            // The array may hold more bytes after the limit, which must not be decoded as part of the string
            checkRemaining(length.toLong())
            val position = buffer.position()
            val string = String(buffer.array(), buffer.arrayOffset() + position, length, Charsets.UTF_8)
            buffer.position(position + length)
            return string
        }
        return String(readExactly(length), Charsets.UTF_8)
    }

    override fun readStringBytes(): ByteArray {
        return readExactly(readLength())
    }

    override fun skipString() {
        skipExactly(readLength().toLong())
    }

    override fun readBytes(): ByteArray {
        return readExactly(readLength())
    }

    override fun skipBytes() {
        skipExactly(readLength().toLong())
    }

    override fun readFixed(size: Int): ByteArray {
        return readExactly(size)
    }

    override fun skipFixed(size: Int) {
        skipExactly(size.toLong())
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

    /** Out of line, as the multi-byte decoding of [KotlinxIoDecoder], so that [readInt] stays small enough to inline. */
    private fun readVarIntTail(first: Int): Int {
        return decodeVarIntTail(first) { buffer.get() }
    }

    /** The long counterpart of [readVarIntTail]. */
    private fun readVarLongTail(first: Int): Long {
        return decodeVarLongTail(first) { buffer.get() }
    }

    private fun readLength(): Int {
        return checkBinaryLength(readLong())
    }

    private fun readExactly(length: Int): ByteArray {
        checkRemaining(length.toLong())
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return bytes
    }

    private fun skipExactly(length: Long) {
        checkRemaining(length)
        buffer.position(buffer.position() + length.toInt())
    }

    private fun checkRemaining(length: Long) {
        if (length > buffer.remaining()) {
            throw EOFException("$length bytes expected, only ${buffer.remaining()} remaining")
        }
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
            skipExactly(blockBytes)
            result = readLong()
        }
        return result
    }
}