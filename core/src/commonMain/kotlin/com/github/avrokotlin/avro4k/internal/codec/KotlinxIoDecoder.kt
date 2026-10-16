package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.AvroMalformedDataException
import kotlinx.io.InternalIoApi
import kotlinx.io.Source
import kotlinx.io.UnsafeIoApi
import kotlinx.io.readByteArray
import kotlinx.io.readDoubleLe
import kotlinx.io.readFloatLe
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

    /**
     * Decodes with the standard library's [decodeToString] rather than kotlinx-io's own UTF-8 decoder: on the JVM, it is
     * the JDK's decoder, as with Apache Avro, which skips the intermediate `CharArray` kotlinx-io fills, and keeps
     * ASCII and Latin-1 strings one byte per character. When the head segment holds the whole string, it is decoded
     * straight from the segment's array.
     */
    @OptIn(InternalIoApi::class, UnsafeIoApi::class)
    override fun readString(): String {
        val length = readLength()
        source.require(length.toLong())
        // We do not really iterate, we just want to peek the head segment, if any
        UnsafeBufferOperations.forEachSegment(source.buffer) { ctx, segment ->
            if (segment.size >= length) {
                ctx.withData(segment) { bytes, offset, _ ->
                    val result = bytes.decodeToString(offset, offset + length)
                    source.skip(length.toLong())
                    return result
                }
            }
            return source.readByteArray(length).decodeToString()
        }
        // Only an empty string leaves no segment to peek
        return ""
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
 * When the head segment holds every byte the varint may need, they are decoded straight from its array, then skipped
 * at once. Otherwise the varint may be split across segments, and is read byte by byte.
 *
 * The one-byte case is not split off into a smaller function: reading that first byte with [Source.readByte] updates
 * the buffer once, and a longer varint then updates it a second time when its remaining bytes are skipped, which made
 * reads of multi-byte values much slower than decoding the whole varint here and updating the buffer once.
 */
@OptIn(InternalIoApi::class, UnsafeIoApi::class)
internal fun Source.readVarInt(): Int {
    // This ensures having at least one byte available to read
    // There are 3 outcomes:
    // 1. There are already all the bytes of the varint available in the buffer: fast mode
    // 2. There are no bytes available in the buffer, so it triggers a read of a full segment, retrieving much more than 1 byte if more available, finally reading the varint in fast mode
    // 3. There is only one byte available in the buffer, while the varint has 2 bytes across segments, so the read will be done in slow mode
    require(1L)
    // We do not really iterate, we just want to peek the head segment
    UnsafeBufferOperations.forEachSegment(buffer) { ctx, segment ->
        // TODO: start in fast mode, finish in slow mode if the varint is split across segments
        if (segment.size < MAX_VARINT_INT_BYTES) {
            // Slow mode: the int may be split across segments
            return decodeVarInt { readByte() }
        }
        ctx.withData(segment) { bytes, offset, _ ->
            // Fast mode: we have all the bytes in the current segment
            var len = 0
            val result = decodeVarInt { bytes[offset + len++] }
            skip(len.toLong())
            return result
        }
    }
    error("Unreachable")
}

/** The long counterpart of [readVarInt], the whole varint decoded in one function for the same reason. */
@OptIn(InternalIoApi::class, UnsafeIoApi::class)
internal fun Source.readVarLong(): Long {
    require(1L)
    // We do not really iterate, we just want to peek the head segment
    UnsafeBufferOperations.forEachSegment(buffer) { ctx, segment ->
        if (segment.size < MAX_VARINT_LONG_BYTES) {
            // Slow mode: the long may be split across segments
            return decodeVarLong { readByte() }
        }
        ctx.withData(segment) { bytes, offset, _ ->
            var len = 0
            val result = decodeVarLong { bytes[offset + len++] }
            skip(len.toLong())
            return result
        }
    }
    error("Unreachable")
}

private inline fun decodeVarInt(readByte: () -> Byte): Int {
    val first = readByte().toInt()
    if (first >= 0) {
        return decodeZigZag(first)
    }
    return decodeVarIntTail(first, readByte)
}

private inline fun decodeVarLong(readByte: () -> Byte): Long {
    val first = readByte().toInt()
    if (first >= 0) {
        return decodeZigZag(first).toLong()
    }
    return decodeVarLongTail(first, readByte)
}