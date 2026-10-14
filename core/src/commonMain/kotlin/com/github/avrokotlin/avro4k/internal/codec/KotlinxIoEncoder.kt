package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.internal.encodeToUtf8
import kotlinx.io.DelicateIoApi
import kotlinx.io.Sink
import kotlinx.io.UnsafeIoApi
import kotlinx.io.unsafe.UnsafeBufferOperations
import kotlinx.io.writeDoubleLe
import kotlinx.io.writeFloatLe
import kotlinx.io.writeToInternalBuffer

/**
 * Writes Avro binary into a kotlinx-io [Sink], byte for byte as Apache Avro's `BinaryEncoder` does.
 *
 * Nothing is flushed: the caller owns the [sink].
 */
internal class KotlinxIoEncoder(
    private val sink: Sink,
) : AvroBinaryEncoder() {
    override fun writeNull() {
    }

    override fun writeBoolean(value: Boolean) {
        sink.writeByte(if (value) 1 else 0)
    }

    /**
     * Most ints, lengths and indexes take a single byte, and that case is all this function writes itself: it stays
     * small enough to be inlined into every caller, while the longer encodings are written out of line.
     */
    override fun writeInt(value: Int) {
        val zigZag = (value shl 1) xor (value shr 31)
        if (zigZag and 0x7F.inv() == 0) {
            sink.writeByte(zigZag.toByte())
        } else {
            writeVarInt(value)
        }
    }

    /** Kept small as [writeInt]. */
    override fun writeLong(value: Long) {
        val zigZag = (value shl 1) xor (value shr 63)
        if (zigZag and 0x7FL.inv() == 0L) {
            sink.writeByte(zigZag.toByte())
        } else {
            writeVarLong(value)
        }
    }

    override fun writeFloat(value: Float) {
        sink.writeFloatLe(value)
    }

    override fun writeDouble(value: Double) {
        sink.writeDoubleLe(value)
    }

    override fun writeString(value: String) {
        if (value.isEmpty()) {
            writeZero()
            return
        }
        // TODO: encode straight into the sink instead of allocating the intermediate array
        writeBytes(value.encodeToUtf8())
    }

    override fun writeString(utf8: ByteArray) {
        writeBytes(utf8)
    }

    override fun writeBytes(value: ByteArray) {
        if (value.isEmpty()) {
            writeZero()
            return
        }
        writeInt(value.size)
        sink.write(value)
    }

    override fun writeFixed(value: ByteArray) {
        sink.write(value)
    }

    override fun writeEnum(index: Int) {
        writeInt(index)
    }

    override fun writeIndex(index: Int) {
        writeInt(index)
    }

    override fun writeArrayStart(count: Long) {
        writeItemCount(count)
    }

    override fun writeMapStart(count: Long) {
        writeItemCount(count)
    }

    override fun startItem() {
    }

    override fun writeArrayEnd() {
        writeZero()
    }

    override fun writeMapEnd() {
        writeZero()
    }

    /**
     * A collection is written as a single block: its count, its items, then the terminating empty block written by
     * [writeArrayEnd] / [writeMapEnd]. An empty collection is only that terminating block.
     */
    private fun writeItemCount(count: Long) {
        if (count > 0L) {
            writeLong(count)
        }
    }

    private fun writeZero() {
        sink.writeByte(0)
    }

    @OptIn(DelicateIoApi::class, UnsafeIoApi::class)
    private fun writeVarInt(value: Int) {
        sink.writeToInternalBuffer { buffer ->
            UnsafeBufferOperations.writeToTail(buffer, MAX_VARINT_INT_BYTES) { bytes, offset, _ ->
                encodeZigZagInt(value, bytes, offset)
            }
        }
    }

    @OptIn(DelicateIoApi::class, UnsafeIoApi::class)
    private fun writeVarLong(value: Long) {
        sink.writeToInternalBuffer { buffer ->
            UnsafeBufferOperations.writeToTail(buffer, MAX_VARINT_LONG_BYTES) { bytes, offset, _ ->
                encodeZigZagLong(value, bytes, offset)
            }
        }
    }
}