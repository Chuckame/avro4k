package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.AvroMalformedDataException
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import org.apache.avro.AvroRuntimeException
import org.apache.avro.InvalidNumberEncodingException
import org.apache.avro.util.Utf8
import java.nio.ByteBuffer

// The codec ABI over Apache Avro's `Encoder`/`Decoder`: an Apache codec handed in from outside (Confluent,
// `DataFileWriter`/`DataFileReader`) receives exactly the calls avro4k made on it before it had its own codec: one ABI
// call is one Apache call, except a collection start, which is `writeArrayStart()` (resp. `writeMapStart()`) followed
// by `setItemCount(count)`, as before. With `validateSerialization`, the adapter is wrapped in avro4k's own validating
// codec, like every other codec.
//
// Public only for core's own tests, which check avro4k's validating codec against Apache's through them.

/** The codec ABI over an Apache [org.apache.avro.io.Encoder]. */
@InternalAvro4kApi
public class ApacheEncoderAdapter(
    private val encoder: org.apache.avro.io.Encoder,
) : AvroBinaryEncoder() {
    override fun writeNull() {
        encoder.writeNull()
    }

    override fun writeBoolean(value: Boolean) {
        encoder.writeBoolean(value)
    }

    override fun writeInt(value: Int) {
        encoder.writeInt(value)
    }

    override fun writeLong(value: Long) {
        encoder.writeLong(value)
    }

    override fun writeFloat(value: Float) {
        encoder.writeFloat(value)
    }

    override fun writeDouble(value: Double) {
        encoder.writeDouble(value)
    }

    override fun writeString(value: String) {
        encoder.writeString(value)
    }

    override fun writeString(utf8: ByteArray) {
        encoder.writeString(Utf8(utf8))
    }

    override fun writeBytes(value: ByteArray) {
        encoder.writeBytes(value)
    }

    override fun writeFixed(value: ByteArray) {
        encoder.writeFixed(value)
    }

    override fun writeEnum(index: Int) {
        encoder.writeEnum(index)
    }

    override fun writeIndex(index: Int) {
        encoder.writeIndex(index)
    }

    override fun writeArrayStart(count: Long) {
        encoder.writeArrayStart()
        encoder.setItemCount(count)
    }

    override fun writeMapStart(count: Long) {
        encoder.writeMapStart()
        encoder.setItemCount(count)
    }

    override fun startItem() {
        encoder.startItem()
    }

    override fun writeArrayEnd() {
        encoder.writeArrayEnd()
    }

    override fun writeMapEnd() {
        encoder.writeMapEnd()
    }
}

/** The codec ABI over an Apache [org.apache.avro.io.Decoder]. */
@InternalAvro4kApi
public class ApacheDecoderAdapter(
    private val decoder: org.apache.avro.io.Decoder,
) : AvroBinaryDecoder() {
    override fun readNull() {
        reading { decoder.readNull() }
    }

    override fun readBoolean(): Boolean = reading { decoder.readBoolean() }

    override fun readInt(): Int = reading { decoder.readInt() }

    override fun readLong(): Long = reading { decoder.readLong() }

    override fun readFloat(): Float = reading { decoder.readFloat() }

    override fun readDouble(): Double = reading { decoder.readDouble() }

    override fun readString(): String = reading { decoder.readString() }

    override fun readStringBytes(): ByteArray {
        val utf8 = reading { decoder.readString(null) }
        val bytes = utf8.bytes
        // Only the first getByteLength() bytes are the value, and a decoder given no Utf8 to reuse allocates a fresh one
        return if (bytes.size == utf8.byteLength) bytes else bytes.copyOf(utf8.byteLength)
    }

    override fun skipString() {
        reading { decoder.skipString() }
    }

    /**
     * The returned buffer of [org.apache.avro.io.Decoder.readBytes] is only guaranteed to expose its content between
     * `position()` and `limit()`, so [ByteBuffer.array] may be larger than — or offset from — the value. It may also
     * be a view onto memory owned by someone else (e.g. `DirectBinaryDecoder` over a `ByteBufferInputStream` hands
     * back the caller's own buffer), so the bytes are always copied out.
     */
    override fun readBytes(): ByteArray {
        val buffer = reading { decoder.readBytes(null) }
        return ByteArray(buffer.remaining())
            .apply { buffer.get(this) }
    }

    override fun skipBytes() {
        reading { decoder.skipBytes() }
    }

    override fun readFixed(size: Int): ByteArray = ByteArray(size).also { reading { decoder.readFixed(it) } }

    override fun skipFixed(size: Int) {
        reading { decoder.skipFixed(size) }
    }

    override fun readEnum(): Int = reading { decoder.readEnum() }

    override fun readIndex(): Int = reading { decoder.readIndex() }

    override fun readArrayStart(): Long = reading { decoder.readArrayStart() }

    override fun arrayNext(): Long = reading { decoder.arrayNext() }

    override fun skipArray(): Long = reading { decoder.skipArray() }

    override fun readMapStart(): Long = reading { decoder.readMapStart() }

    override fun mapNext(): Long = reading { decoder.mapNext() }

    override fun skipMap(): Long = reading { decoder.skipMap() }

    /**
     * Runs one read of the Apache decoder, reporting the malformed input it detects as an [AvroMalformedDataException],
     * the original as the cause: an [AvroRuntimeException] (`Malformed data. Length is negative`, a
     * `SystemLimitException`, …) or an [InvalidNumberEncodingException]. Per read rather than around the whole decode,
     * so that an [AvroRuntimeException] thrown by a custom serializer is not mislabelled. The end of the input
     * (`EOFException`) is reported by the core decode entry point, as for any other input.
     */
    private inline fun <T> reading(read: () -> T): T {
        try {
            return read()
        } catch (e: AvroRuntimeException) {
            throw AvroMalformedDataException(e.message, e)
        } catch (e: InvalidNumberEncodingException) {
            throw AvroMalformedDataException(e.message, e)
        }
    }
}