package com.github.avrokotlin.avro4k.internal.codec

import org.apache.avro.Schema
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.EncoderFactory
import org.apache.avro.util.Utf8
import java.nio.ByteBuffer

// Bridges between avro4k's codec ABI and Apache Avro's `Encoder`/`Decoder`, in both directions.
//
// - ABI over Apache ([ApacheEncoderAdapter], [ApacheDecoderAdapter]): an Apache codec handed in from outside
//   (Confluent, `DataFileWriter`/`DataFileReader`) receives exactly the calls avro4k made on it before it had its own
//   codec: one ABI call is one Apache call, except a collection start, which is `writeArrayStart()` (resp.
//   `writeMapStart()`) followed by `setItemCount(count)`, as before.
// - Apache over ABI ([ApacheEncoderOverAbi], [ApacheDecoderOverAbi]): only for `validateSerialization` on avro4k's
//   own codecs, which wraps Apache's `ValidatingEncoder`/`ValidatingDecoder` around them.
//
// JVM-only by nature. `validateSerialization` gets a common implementation in M3-18, and these adapters move to
// `apache-interop` in M3-14.

/** Wraps this encoder in Apache's `ValidatingEncoder`, which checks every call against [writerSchema]. */
internal fun AvroBinaryEncoder.validating(writerSchema: Schema): AvroBinaryEncoder =
    ApacheEncoderAdapter(EncoderFactory.get().validatingEncoder(writerSchema, ApacheEncoderOverAbi(this)))

/** Wraps this decoder in Apache's `ValidatingDecoder`, which checks every call against [writerSchema]. */
internal fun AvroBinaryDecoder.validating(writerSchema: Schema): AvroBinaryDecoder =
    ApacheDecoderAdapter(DecoderFactory.get().validatingDecoder(writerSchema, ApacheDecoderOverAbi(this)))

/** The codec ABI over an Apache [org.apache.avro.io.Encoder]. */
internal class ApacheEncoderAdapter(
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
internal class ApacheDecoderAdapter(
    private val decoder: org.apache.avro.io.Decoder,
) : AvroBinaryDecoder() {
    override fun readNull() {
        decoder.readNull()
    }

    override fun readBoolean(): Boolean = decoder.readBoolean()

    override fun readInt(): Int = decoder.readInt()

    override fun readLong(): Long = decoder.readLong()

    override fun readFloat(): Float = decoder.readFloat()

    override fun readDouble(): Double = decoder.readDouble()

    override fun readString(): String = decoder.readString()

    override fun readStringBytes(): ByteArray {
        val utf8 = decoder.readString(null)
        val bytes = utf8.bytes
        // Only the first getByteLength() bytes are the value, and a decoder given no Utf8 to reuse allocates a fresh one
        return if (bytes.size == utf8.byteLength) bytes else bytes.copyOf(utf8.byteLength)
    }

    override fun skipString() {
        decoder.skipString()
    }

    /**
     * The returned buffer of [org.apache.avro.io.Decoder.readBytes] is only guaranteed to expose its content between
     * `position()` and `limit()`, so [ByteBuffer.array] may be larger than — or offset from — the value. It may also
     * be a view onto memory owned by someone else (e.g. `DirectBinaryDecoder` over a `ByteBufferInputStream` hands
     * back the caller's own buffer), so the bytes are always copied out.
     */
    override fun readBytes(): ByteArray {
        val buffer = decoder.readBytes(null)
        return ByteArray(buffer.remaining())
            .apply { buffer.get(this) }
    }

    override fun skipBytes() {
        decoder.skipBytes()
    }

    override fun readFixed(size: Int): ByteArray = ByteArray(size).also { decoder.readFixed(it) }

    override fun skipFixed(size: Int) {
        decoder.skipFixed(size)
    }

    override fun readEnum(): Int = decoder.readEnum()

    override fun readIndex(): Int = decoder.readIndex()

    override fun readArrayStart(): Long = decoder.readArrayStart()

    override fun arrayNext(): Long = decoder.arrayNext()

    override fun skipArray(): Long = decoder.skipArray()

    override fun readMapStart(): Long = decoder.readMapStart()

    override fun mapNext(): Long = decoder.mapNext()

    override fun skipMap(): Long = decoder.skipMap()
}

/**
 * An Apache [org.apache.avro.io.Encoder] over the codec ABI, only fed by Apache's `ValidatingEncoder` (itself fed by
 * [ApacheEncoderAdapter]), so it only receives the calls [ApacheEncoderAdapter] makes.
 */
private class ApacheEncoderOverAbi(
    private val encoder: AvroBinaryEncoder,
) : org.apache.avro.io.Encoder() {
    private var pendingCollectionStart = NO_COLLECTION_START

    override fun flush() {
    }

    override fun writeNull() {
        encoder.writeNull()
    }

    override fun writeBoolean(b: Boolean) {
        encoder.writeBoolean(b)
    }

    override fun writeInt(n: Int) {
        encoder.writeInt(n)
    }

    override fun writeLong(n: Long) {
        encoder.writeLong(n)
    }

    override fun writeFloat(f: Float) {
        encoder.writeFloat(f)
    }

    override fun writeDouble(d: Double) {
        encoder.writeDouble(d)
    }

    override fun writeString(utf8: Utf8) {
        encoder.writeString(utf8.bytes.exactly(0, utf8.byteLength))
    }

    override fun writeString(str: String) {
        encoder.writeString(str)
    }

    override fun writeBytes(bytes: ByteBuffer) {
        encoder.writeBytes(ByteArray(bytes.remaining()).also { bytes.duplicate().get(it) })
    }

    override fun writeBytes(
        bytes: ByteArray,
        start: Int,
        len: Int,
    ) {
        encoder.writeBytes(bytes.exactly(start, len))
    }

    override fun writeFixed(
        bytes: ByteArray,
        start: Int,
        len: Int,
    ) {
        encoder.writeFixed(bytes.exactly(start, len))
    }

    override fun writeEnum(e: Int) {
        encoder.writeEnum(e)
    }

    override fun writeIndex(unionIndex: Int) {
        encoder.writeIndex(unionIndex)
    }

    // The ABI starts a collection with its item count, while Apache sends it separately, right after the start.
    override fun writeArrayStart() {
        pendingCollectionStart = ARRAY_START
    }

    override fun writeMapStart() {
        pendingCollectionStart = MAP_START
    }

    override fun setItemCount(itemCount: Long) {
        when (pendingCollectionStart) {
            ARRAY_START -> encoder.writeArrayStart(itemCount)
            MAP_START -> encoder.writeMapStart(itemCount)
            else -> throw IllegalStateException("setItemCount($itemCount) without a preceding writeArrayStart() or writeMapStart()")
        }
        pendingCollectionStart = NO_COLLECTION_START
    }

    override fun startItem() {
        check(pendingCollectionStart == NO_COLLECTION_START) { "startItem() before setItemCount()" }
        encoder.startItem()
    }

    override fun writeArrayEnd() {
        if (pendingCollectionStart == ARRAY_START) setItemCount(0)
        encoder.writeArrayEnd()
    }

    override fun writeMapEnd() {
        if (pendingCollectionStart == MAP_START) setItemCount(0)
        encoder.writeMapEnd()
    }

    private companion object {
        const val NO_COLLECTION_START = 0
        const val ARRAY_START = 1
        const val MAP_START = 2
    }
}

private fun ByteArray.exactly(
    start: Int,
    length: Int,
): ByteArray = if (start == 0 && length == size) this else copyOfRange(start, start + length)

/**
 * An Apache [org.apache.avro.io.Decoder] over the codec ABI, only fed by Apache's `ValidatingDecoder` (itself fed by
 * [ApacheDecoderAdapter]).
 */
private class ApacheDecoderOverAbi(
    private val decoder: AvroBinaryDecoder,
) : org.apache.avro.io.Decoder() {
    override fun readNull() {
        decoder.readNull()
    }

    override fun readBoolean(): Boolean = decoder.readBoolean()

    override fun readInt(): Int = decoder.readInt()

    override fun readLong(): Long = decoder.readLong()

    override fun readFloat(): Float = decoder.readFloat()

    override fun readDouble(): Double = decoder.readDouble()

    override fun readString(old: Utf8?): Utf8 = Utf8(decoder.readStringBytes())

    override fun readString(): String = decoder.readString()

    override fun skipString() {
        decoder.skipString()
    }

    override fun readBytes(old: ByteBuffer?): ByteBuffer = ByteBuffer.wrap(decoder.readBytes())

    override fun skipBytes() {
        decoder.skipBytes()
    }

    override fun readFixed(
        bytes: ByteArray,
        start: Int,
        length: Int,
    ) {
        decoder.readFixed(length).copyInto(bytes, start)
    }

    override fun skipFixed(length: Int) {
        decoder.skipFixed(length)
    }

    override fun readEnum(): Int = decoder.readEnum()

    override fun readArrayStart(): Long = decoder.readArrayStart()

    override fun arrayNext(): Long = decoder.arrayNext()

    override fun skipArray(): Long = decoder.skipArray()

    override fun readMapStart(): Long = decoder.readMapStart()

    override fun mapNext(): Long = decoder.mapNext()

    override fun skipMap(): Long = decoder.skipMap()

    override fun readIndex(): Int = decoder.readIndex()
}