package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.InternalAvro4kApi

/**
 * avro4k's own low-level Avro binary reader: the single seam between the bytes and the kotlinx-serialization decoders.
 *
 * The methods mirror the grammar of Apache Avro's `org.apache.avro.io.Decoder` one for one, so an Apache `Decoder`
 * handed in from outside — Confluent's deserializers, a `DataFileReader`, a `ValidatingDecoder` — can be adapted
 * without changing the sequence of calls it receives.
 *
 * Every `ByteArray` returned is exclusively owned by the caller: it is never a view onto a buffer the decoder, or
 * anyone else, may later reuse.
 *
 * An abstract class rather than an interface: a virtual call is cheaper than an interface call on Kotlin/Native, and
 * costs the same elsewhere. An instance belongs to a single decoding call and is never shared.
 */
@InternalAvro4kApi
public abstract class AvroBinaryDecoder {
    /** Reads nothing: Avro's null has no binary representation. */
    public abstract fun readNull()

    public abstract fun readBoolean(): Boolean

    public abstract fun readInt(): Int

    public abstract fun readLong(): Long

    public abstract fun readFloat(): Float

    public abstract fun readDouble(): Double

    public abstract fun readString(): String

    /** Reads a STRING without decoding it: the returned array holds exactly its UTF-8 bytes. */
    public abstract fun readStringBytes(): ByteArray

    public abstract fun skipString()

    public abstract fun readBytes(): ByteArray

    public abstract fun skipBytes()

    public abstract fun readFixed(size: Int): ByteArray

    public abstract fun skipFixed(size: Int)

    public abstract fun readEnum(): Int

    public abstract fun readIndex(): Int

    /** Reads the item count of the first block of an array: 0 means the array is empty (and already ended). */
    public abstract fun readArrayStart(): Long

    /** Reads the item count of the next block of an array: 0 means the array has ended. */
    public abstract fun arrayNext(): Long

    /**
     * Skips as many whole blocks of an array as the encoding allows without knowing the item schema, and returns the
     * item count of the next block to skip item by item, or 0 when the array has ended.
     */
    public abstract fun skipArray(): Long

    /** Reads the entry count of the first block of a map: 0 means the map is empty (and already ended). */
    public abstract fun readMapStart(): Long

    /** Reads the entry count of the next block of a map: 0 means the map has ended. */
    public abstract fun mapNext(): Long

    /** The map counterpart of [skipArray]. */
    public abstract fun skipMap(): Long
}