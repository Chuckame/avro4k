package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.AvroMalformedDataException
import kotlinx.io.InternalIoApi
import kotlinx.io.Source
import kotlinx.io.UnsafeIoApi
import kotlinx.io.readByteArray
import kotlinx.io.readDoubleLe
import kotlinx.io.readFloatLe
import kotlinx.io.readString
import kotlinx.io.unsafe.UnsafeBufferOperations
import kotlinx.io.unsafe.withData

/**
 * Reads Avro binary from a kotlinx-io [Source].
 *
 * Every length prefix is checked by [checkBinaryLength] before anything is allocated or skipped for it, and every
 * returned array is an exact copy, safe even when the value spans several segments of the [source].
 */
internal class KotlinxIoDecoder(
    private val source: Source,
) : AvroBinaryDecoder() {
    override fun readNull() {
    }

    override fun readBoolean(): Boolean {
        return source.readByte() == 1.toByte()
    }

    override fun readInt(): Int {
        return source.readVarInt()
    }

    override fun readLong(): Long {
        return source.readVarLong()
    }

    override fun readFloat(): Float {
        return source.readFloatLe()
    }

    override fun readDouble(): Double {
        return source.readDoubleLe()
    }

    override fun readString(): String {
        return source.readString(readLength().toLong())
    }

    override fun readStringBytes(): ByteArray {
        return source.readByteArray(readLength())
    }

    override fun skipString() {
        source.skip(readLength().toLong())
    }

    override fun readBytes(): ByteArray {
        return source.readByteArray(readLength())
    }

    override fun skipBytes() {
        source.skip(readLength().toLong())
    }

    override fun readFixed(size: Int): ByteArray {
        return source.readByteArray(size)
    }

    override fun skipFixed(size: Int) {
        source.skip(size.toLong())
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

    private fun readLength(): Int {
        return checkBinaryLength(readLong())
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
            source.skip(blockBytes)
            result = readLong()
        }
        return result
    }
}

/**
 * Reads a zig-zag varint int, throwing an EOFException at the end of the input.
 *
 * Most ints, lengths, indexes and counts take a single byte, and that case is all this function handles itself: it
 * stays small enough to be inlined into every caller by a JIT or an optimizing compiler, while the longer encodings,
 * whose unrolled decoding is large, are read out of line by [readVarIntTail].
 */
internal fun Source.readVarInt(): Int {
    val first = readByte().toInt()
    if (first >= 0) {
        return decodeZigZag(first)
    }
    return readVarIntTail(first)
}

/** Reads a zig-zag varint long, throwing an EOFException at the end of the input. Kept small as [readVarInt]. */
internal fun Source.readVarLong(): Long {
    val first = readByte().toInt()
    if (first >= 0) {
        return decodeZigZag(first).toLong()
    }
    return readVarLongTail(first)
}

/**
 * Reads the bytes of a varint int following its [first] byte, already read.
 *
 * When the head segment holds every byte the varint may still need, they are decoded straight from its array, then
 * skipped. Otherwise the varint may be split across segments, or not yet loaded from the underlying source, and is
 * read byte by byte.
 */
@OptIn(InternalIoApi::class, UnsafeIoApi::class)
private fun Source.readVarIntTail(first: Int): Int {
    // We do not really iterate, we just want to peek the head segment, if any
    UnsafeBufferOperations.forEachSegment(buffer) { ctx, segment ->
        // TODO: start in fast mode, finish in slow mode if the varint is split across segments
        if (segment.size >= MAX_VARINT_INT_BYTES - 1) {
            ctx.withData(segment) { bytes, offset, _ ->
                var len = 0
                val result = decodeVarIntTail(first) { bytes[offset + len++] }
                skip(len.toLong())
                return result
            }
        }
        return readVarIntTailByteByByte(first)
    }
    return readVarIntTailByteByByte(first)
}

/** The long counterpart of [readVarIntTail]. */
@OptIn(InternalIoApi::class, UnsafeIoApi::class)
private fun Source.readVarLongTail(first: Int): Long {
    // We do not really iterate, we just want to peek the head segment, if any
    UnsafeBufferOperations.forEachSegment(buffer) { ctx, segment ->
        if (segment.size >= MAX_VARINT_LONG_BYTES - 1) {
            ctx.withData(segment) { bytes, offset, _ ->
                var len = 0
                val result = decodeVarLongTail(first) { bytes[offset + len++] }
                skip(len.toLong())
                return result
            }
        }
        return readVarLongTailByteByByte(first)
    }
    return readVarLongTailByteByByte(first)
}

private fun Source.readVarIntTailByteByByte(first: Int): Int = decodeVarIntTail(first) { readByte() }

private fun Source.readVarLongTailByteByByte(first: Int): Long = decodeVarLongTail(first) { readByte() }

/** Decodes the bytes of a varint int following its [first] byte, whose continuation bit is set. */
private inline fun decodeVarIntTail(
    first: Int,
    readByte: () -> Byte,
): Int {
    var b = readByte().toInt()
    var varint = (first and 0x7F) + ((b and 0x7F) shl 7)
    if (b < 0) {
        b = readByte().toInt()
        varint += ((b and 0x7F) shl 14)
        if (b < 0) {
            b = readByte().toInt()
            varint += ((b and 0x7F) shl 21)
            if (b < 0) {
                b = readByte().toInt()
                if (b < 0) {
                    throw AvroMalformedDataException("Invalid varint encoding: more bytes than expected")
                }
                varint += (b shl 28)
            }
        }
    }
    return decodeZigZag(varint)
}

/** Decodes the bytes of a varint long following its [first] byte, whose continuation bit is set. */
private inline fun decodeVarLongTail(
    first: Int,
    readByte: () -> Byte,
): Long {
    var b: Int = readByte().toInt()
    var i = (first and 0x7F) + ((b and 0x7F) shl 7)
    if (b < 0) {
        b = readByte().toInt()
        i += ((b and 0x7F) shl 14)
        if (b < 0) {
            b = readByte().toInt()
            i += ((b and 0x7F) shl 21)
            if (b < 0) {
                return decodeVarLong2(i.toLong(), readByte)
            }
        }
    }
    return decodeZigZag(i).toLong()
}

private inline fun decodeVarLong2(
    lo: Long,
    readByte: () -> Byte,
): Long {
    // then next 28 bits (altogether 8 bytes)
    var lo = lo
    var b: Int = readByte().toInt()
    var i = b and 0x7F
    if (b < 0) {
        i = i and 0x7F
        b = readByte().toInt()
        i += ((b and 0x7F) shl 7)
        if (b < 0) {
            b = readByte().toInt()
            i += ((b and 0x7F) shl 14)
            if (b < 0) {
                b = readByte().toInt()
                i += ((b and 0x7F) shl 21)
                if (b < 0) {
                    // Ok 56-bits gone... still going strong!
                    lo = lo or ((i.toLong()) shl 28)
                    b = readByte().toInt()
                    i = b and 0x7F
                    if (b < 0) {
                        b = readByte().toInt()
                        if (b < 0) {
                            throw AvroMalformedDataException("Invalid varlong encoding: more bytes than expected")
                        }
                        i = i or (b shl 7)
                    }
                    lo = lo or ((i.toLong()) shl 56)
                    return decodeZigZag(lo)
                }
            }
        }
    }
    lo = lo or ((i.toLong()) shl 28)
    return decodeZigZag(lo)
}

private fun decodeZigZag(varint: Int): Int = (varint ushr 1) xor (-(varint and 1))

private fun decodeZigZag(varint: Long): Long = (varint ushr 1) xor (-(varint and 1))