package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.InternalAvro4kApi

/**
 * avro4k's own low-level Avro binary writer: the single seam between the kotlinx-serialization encoders and the bytes.
 *
 * The methods mirror the grammar of Apache Avro's `org.apache.avro.io.Encoder` one for one, except that a collection
 * start carries its item count (Apache's `writeArrayStart()` + `setItemCount(count)`). This lets an Apache `Encoder`
 * handed in from outside — Confluent's serializers, a `DataFileWriter`, a `ValidatingEncoder` — be adapted without
 * changing the sequence of calls it receives.
 *
 * An abstract class rather than an interface: a virtual call is cheaper than an interface call on Kotlin/Native, and
 * costs the same elsewhere. An instance belongs to a single encoding call and is never shared.
 */
@InternalAvro4kApi
public abstract class AvroBinaryEncoder {
    /** Writes nothing: Avro's null has no binary representation. */
    public abstract fun writeNull()

    public abstract fun writeBoolean(value: Boolean)

    public abstract fun writeInt(value: Int)

    public abstract fun writeLong(value: Long)

    public abstract fun writeFloat(value: Float)

    public abstract fun writeDouble(value: Double)

    /** Writes a STRING, encoding [value] as UTF-8. */
    public abstract fun writeString(value: String)

    /** Writes a STRING whose content is already UTF-8 encoded. */
    public abstract fun writeString(utf8: ByteArray)

    public abstract fun writeBytes(value: ByteArray)

    /** Writes the raw bytes of a FIXED. The caller has already checked the size against the schema. */
    public abstract fun writeFixed(value: ByteArray)

    public abstract fun writeEnum(index: Int)

    public abstract fun writeIndex(index: Int)

    /** Starts an array of [count] items, each of them introduced by [startItem], and closed by [writeArrayEnd]. */
    public abstract fun writeArrayStart(count: Long)

    /** Starts a map of [count] entries, each of them introduced by [startItem], and closed by [writeMapEnd]. */
    public abstract fun writeMapStart(count: Long)

    public abstract fun startItem()

    public abstract fun writeArrayEnd()

    public abstract fun writeMapEnd()
}