package com.github.avrokotlin.avro4k.internal.codec

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

    @OptIn(DelicateIoApi::class, UnsafeIoApi::class)
    override fun writeInt(value: Int) {
        sink.writeToInternalBuffer { buffer ->
            UnsafeBufferOperations.writeToTail(buffer, MAX_VARINT_INT_BYTES) { bytes, offset, _ ->
                encodeZigZagInt(value, bytes, offset)
            }
        }
    }

    @OptIn(DelicateIoApi::class, UnsafeIoApi::class)
    override fun writeLong(value: Long) {
        sink.writeToInternalBuffer { buffer ->
            UnsafeBufferOperations.writeToTail(buffer, MAX_VARINT_LONG_BYTES) { bytes, offset, _ ->
                encodeZigZagLong(value, bytes, offset)
            }
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
        // TODO M5/C5: encode straight into the sink instead of allocating the intermediate array
        writeBytes(value.encodeToByteArray())
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
}