package com.github.avrokotlin.avro4k.internal.encoder

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.internal.EncodingWorkflow
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryWriter
import com.github.avrokotlin.avro4k.internal.schema.asType
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.SerializersModule

internal sealed class AbstractAvroBinaryEncoder(
    override val avro: Avro,
    protected val binaryWriter: AvroBinaryWriter,
) : AbstractAvroEncoder() {
    /**
     * The encoders of the records, arrays, maps and polymorphic values this encoder begins, each created on first use and
     * then handed every next structure of its kind: a list of records, or a record's successive fields of one kind, reuse
     * one encoder instead of allocating one per value. Kotlinx encodes depth-first, so an encoder only ever has one
     * structure begun at a time; one it begins inside another is begun by that other's own encoder. They belong to the
     * encode call that created the root encoder, as this one does: nothing is shared between calls.
     *
     * Records keep two: one for those whose fields match the writer schema's in order, one for those that skip some of
     * their elements. A record whose fields are written in another order still gets a new encoder each time.
     */
    private var exactRecordEncoder: RecordContiguousExactEncoder? = null
    private var skippingRecordEncoder: RecordContiguousSkippingEncoder? = null
    private var arrayEncoder: ArrayEncoder? = null
    private var mapEncoder: MapEncoder? = null
    private var polymorphicEncoder: PolymorphicEncoder? = null

    /**
     * Whether this encoder encodes a structure its parent began and that is not ended yet, from [beginReuse] to
     * [endStructure]. Its parent then begins the next structure on a new encoder, kept out of its slot: so a serializer
     * that begins a second structure before ending the first, never ends one, or catches an error inside one and goes on,
     * still encodes correctly, only allocating as it did before encoders were reused.
     */
    protected var inUse = false
        private set

    override val serializersModule: SerializersModule
        get() = avro.serializersModule

    /**
     * The encoder of a record written with the current writer schema, chosen by how [descriptor]'s elements map to that
     * schema's fields. The kept record encoders remember the schema and descriptor they were resolved for, compared by
     * identity: the records of a list, or a field's successive values, are most often of one type, and so never reach the
     * resolver. Two record types alternating at one level are resolved again at each switch, through the resolver's own
     * identity cache.
     */
    override fun getRecordEncoder(descriptor: SerialDescriptor): CompositeEncoder {
        // the caller checked the type: a record
        val schema = currentWriterSchema as RecordSchema
        val exact = exactRecordEncoder
        if (exact != null && exact.schema === schema && exact.resolvedDescriptor === descriptor) {
            return exactRecordEncoder(schema, descriptor)
        }
        val skipping = skippingRecordEncoder
        if (skipping != null && skipping.schema === schema && skipping.resolvedDescriptor === descriptor) {
            return skippingRecordEncoder(schema, descriptor, skipping.skippedElements)
        }
        return resolveRecordEncoder(schema, descriptor)
    }

    /** Out of the hot path: the first record of a type at this level, a switch of type, or a record written in another order. */
    private fun resolveRecordEncoder(
        schema: RecordSchema,
        descriptor: SerialDescriptor,
    ): CompositeEncoder =
        when (val workflow = avro.recordResolver.resolveFields(schema, descriptor).encoding) {
            is EncodingWorkflow.ExactMatch -> exactRecordEncoder(schema, descriptor)
            is EncodingWorkflow.ContiguousWithSkips -> skippingRecordEncoder(schema, descriptor, workflow.fieldsToSkip)
            is EncodingWorkflow.NonContiguous -> reorderingRecordEncoder(schema, workflow, avro, binaryWriter)
            is EncodingWorkflow.MissingWriterFields -> throw missingFieldsError(schema, workflow, descriptor)
        }

    /** The kept exact record encoder, reset for the record to begin, unless it is still in use. */
    private fun exactRecordEncoder(
        schema: RecordSchema,
        descriptor: SerialDescriptor,
    ): RecordContiguousExactEncoder {
        val kept = exactRecordEncoder
        if (kept != null && !kept.inUse) return kept.reset(schema, descriptor)
        val encoder = RecordContiguousExactEncoder(schema, descriptor, avro, binaryWriter)
        if (kept == null) exactRecordEncoder = encoder
        return encoder.reset(schema, descriptor)
    }

    /** The kept skipping record encoder, reset for the record to begin, unless it is still in use. */
    private fun skippingRecordEncoder(
        schema: RecordSchema,
        descriptor: SerialDescriptor,
        skippedElements: BooleanArray,
    ): RecordContiguousSkippingEncoder {
        val kept = skippingRecordEncoder
        if (kept != null && !kept.inUse) return kept.reset(schema, descriptor, skippedElements)
        val encoder = RecordContiguousSkippingEncoder(schema, descriptor, skippedElements, avro, binaryWriter)
        if (kept == null) skippingRecordEncoder = encoder
        return encoder.reset(schema, descriptor, skippedElements)
    }

    override fun getPolymorphicEncoder(descriptor: SerialDescriptor): CompositeEncoder {
        val schema = currentWriterSchema
        val kept = polymorphicEncoder
        if (kept != null && !kept.inUse) return kept.reset(schema)
        val encoder = PolymorphicEncoder(avro, schema, binaryWriter)
        if (kept == null) polymorphicEncoder = encoder
        return encoder.reset(schema)
    }

    override fun getArrayEncoder(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder {
        return arrayEncoder().reset(currentWriterSchema.asType<AvroSchema.ArraySchema>(), collectionSize)
    }

    /** The array encoder to begin an array on: the one this encoder keeps, unless it is still in use. */
    private fun arrayEncoder(): ArrayEncoder {
        val kept = arrayEncoder
        if (kept != null && !kept.inUse) return kept
        val encoder = ArrayEncoder(avro, binaryWriter)
        if (kept == null) arrayEncoder = encoder
        return encoder
    }

    override fun getMapEncoder(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder {
        return mapEncoder().reset(currentWriterSchema.asType<AvroSchema.MapSchema>(), collectionSize)
    }

    /** The map encoder to begin a map on: the one this encoder keeps, unless it is still in use. */
    private fun mapEncoder(): MapEncoder {
        val kept = mapEncoder
        if (kept != null && !kept.inUse) return kept
        val encoder = MapEncoder(avro, binaryWriter)
        if (kept == null) mapEncoder = encoder
        return encoder
    }

    /**
     * The part of a reused encoder's reset that this class owns: marks it in use until [endStructure], and forgets the
     * union branch its previous structure selected, as a new encoder has none. Called last by each reset.
     */
    protected fun beginReuse() {
        clearSelectedUnionIndex()
        inUse = true
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        inUse = false
    }

    override fun encodeUnionIndexUnchecked(index: Int) {
        binaryWriter.writeIndex(index)
    }

    override fun encodeNullUnchecked() {
        binaryWriter.writeNull()
    }

    override fun encodeBytesUnchecked(value: ByteArray) {
        binaryWriter.writeBytes(value)
    }

    override fun encodeBooleanUnchecked(value: Boolean) {
        binaryWriter.writeBoolean(value)
    }

    override fun encodeIntUnchecked(value: Int) {
        binaryWriter.writeInt(value)
    }

    override fun encodeLongUnchecked(value: Long) {
        binaryWriter.writeLong(value)
    }

    override fun encodeFloatUnchecked(value: Float) {
        binaryWriter.writeFloat(value)
    }

    override fun encodeDoubleUnchecked(value: Double) {
        binaryWriter.writeDouble(value)
    }

    override fun encodeStringUnchecked(utf8: ByteArray) {
        binaryWriter.writeString(utf8)
    }

    override fun encodeStringUnchecked(value: String) {
        binaryWriter.writeString(value)
    }

    /**
     * The symbol's index in the writer enum: one hash lookup ([AvroSchema.EnumSchema.getSymbolIndexOrNull]). A symbol the
     * writer enum does not have fails, as Apache's writer does: the enum's default symbol is for readers resolving an
     * unknown writer symbol, never written in place of a value.
     */
    override fun encodeEnumUnchecked(symbol: String) {
        val enumSchema = currentWriterSchema as AvroSchema.EnumSchema
        val index =
            enumSchema.getSymbolIndexOrNull(symbol)
                ?: throw AvroSchemaMismatchException("Enum symbol $symbol not found in schema ${enumSchema.toSchemaJsonString()}")
        binaryWriter.writeEnum(index)
    }

    override fun encodeFixedUnchecked(value: ByteArray) {
        binaryWriter.writeFixed(value)
    }
}

/**
 * What a reused encoder answers when asked for its writer schema before a field or item is begun, as reading the unset
 * schema of a new encoder failed.
 */
internal fun noElementBegunError(): IllegalStateException =
    IllegalStateException("No field or item is being encoded: encodeElement or an encode...Element call begins one")