package com.github.avrokotlin.avro4k.internal.encoder.direct

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.internal.EncodingWorkflow
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryEncoder
import com.github.avrokotlin.avro4k.internal.encoder.AbstractAvroEncoder
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.SerializersModule

/**
 * Encodes one value written with [currentAvroSchema]: the root of an encode call, or the value of a polymorphic
 * structure, whose encoder keeps it and hands it each next value through [reset].
 */
internal class AvroValueDirectEncoder(
    override var currentAvroSchema: AvroSchema,
    avro: Avro,
    binaryEncoder: AvroBinaryEncoder,
) : AbstractAvroDirectEncoder(avro, binaryEncoder) {
    /**
     * Makes this encoder encode a next value written with [schema], as a new one would: no union branch selected yet. Its
     * own child encoders are kept, each with its own guard.
     */
    fun reset(schema: AvroSchema): AvroValueDirectEncoder {
        currentAvroSchema = schema
        clearSelectedUnionIndex()
        return this
    }
}

internal sealed class AbstractAvroDirectEncoder(
    override val avro: Avro,
    protected val binaryEncoder: AvroBinaryEncoder,
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
    private var arrayEncoder: ArrayDirectEncoder? = null
    private var mapEncoder: MapDirectEncoder? = null
    private var polymorphicEncoder: PolymorphicDirectEncoder? = null

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
        val schema = currentAvroSchema as RecordSchema
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
            is EncodingWorkflow.NonContiguous -> reorderingRecordEncoder(schema, workflow, avro, binaryEncoder)
            is EncodingWorkflow.MissingWriterFields -> throw missingFieldsError(schema, workflow, descriptor)
        }

    /** The kept exact record encoder, reset for the record to begin, unless it is still in use. */
    private fun exactRecordEncoder(
        schema: RecordSchema,
        descriptor: SerialDescriptor,
    ): RecordContiguousExactEncoder {
        val kept = exactRecordEncoder
        if (kept != null && !kept.inUse) return kept.reset(schema, descriptor)
        val encoder = RecordContiguousExactEncoder(schema, descriptor, avro, binaryEncoder)
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
        val encoder = RecordContiguousSkippingEncoder(schema, descriptor, skippedElements, avro, binaryEncoder)
        if (kept == null) skippingRecordEncoder = encoder
        return encoder.reset(schema, descriptor, skippedElements)
    }

    override fun getPolymorphicEncoder(descriptor: SerialDescriptor): CompositeEncoder {
        val schema = currentAvroSchema
        val kept = polymorphicEncoder
        if (kept != null && !kept.inUse) return kept.reset(schema)
        val encoder = PolymorphicDirectEncoder(avro, schema, binaryEncoder)
        if (kept == null) polymorphicEncoder = encoder
        return encoder.reset(schema)
    }

    override fun getArrayEncoder(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder {
        return arrayEncoder().reset(currentAvroSchema, collectionSize)
    }

    /** The array encoder to begin an array on: the one this encoder keeps, unless it is still in use. */
    private fun arrayEncoder(): ArrayDirectEncoder {
        val kept = arrayEncoder
        if (kept != null && !kept.inUse) return kept
        val encoder = ArrayDirectEncoder(avro, binaryEncoder)
        if (kept == null) arrayEncoder = encoder
        return encoder
    }

    override fun getMapEncoder(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder {
        return mapEncoder().reset(currentAvroSchema, collectionSize)
    }

    /** The map encoder to begin a map on: the one this encoder keeps, unless it is still in use. */
    private fun mapEncoder(): MapDirectEncoder {
        val kept = mapEncoder
        if (kept != null && !kept.inUse) return kept
        val encoder = MapDirectEncoder(avro, binaryEncoder)
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
        binaryEncoder.writeIndex(index)
    }

    override fun encodeNullUnchecked() {
        binaryEncoder.writeNull()
    }

    override fun encodeBytesUnchecked(value: ByteArray) {
        binaryEncoder.writeBytes(value)
    }

    override fun encodeBooleanUnchecked(value: Boolean) {
        binaryEncoder.writeBoolean(value)
    }

    override fun encodeIntUnchecked(value: Int) {
        binaryEncoder.writeInt(value)
    }

    override fun encodeLongUnchecked(value: Long) {
        binaryEncoder.writeLong(value)
    }

    override fun encodeFloatUnchecked(value: Float) {
        binaryEncoder.writeFloat(value)
    }

    override fun encodeDoubleUnchecked(value: Double) {
        binaryEncoder.writeDouble(value)
    }

    override fun encodeStringUnchecked(utf8: ByteArray) {
        binaryEncoder.writeString(utf8)
    }

    override fun encodeStringUnchecked(value: String) {
        binaryEncoder.writeString(value)
    }

    /**
     * The symbol's index in the writer enum: one hash lookup ([AvroSchema.EnumSchema.getSymbolIndexOrNull]). A symbol the
     * writer enum does not have fails, as Apache's writer does: the enum's default symbol is for readers resolving an
     * unknown writer symbol, never written in place of a value.
     */
    override fun encodeEnumUnchecked(symbol: String) {
        val enumSchema = currentAvroSchema as AvroSchema.EnumSchema
        val index =
            enumSchema.getSymbolIndexOrNull(symbol)
                ?: throw AvroSchemaMismatchException("Enum symbol $symbol not found in schema ${enumSchema.toSchemaJsonString()}")
        binaryEncoder.writeEnum(index)
    }

    override fun encodeFixedUnchecked(value: ByteArray) {
        binaryEncoder.writeFixed(value)
    }
}

/**
 * What a reused encoder answers when asked for its writer schema before a field or item is begun, as reading the unset
 * schema of a new encoder failed.
 */
internal fun noElementBegunError(): IllegalStateException =
    IllegalStateException("No field or item is being encoded: encodeElement or an encode...Element call begins one")

/**
 * Encodes the value of a polymorphic structure, the discriminator being the union branch its value selects. Its parent
 * keeps it and hands it each next polymorphic structure through [reset]; it keeps the encoder of its values in turn.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class PolymorphicDirectEncoder(
    private val avro: Avro,
    schema: AvroSchema,
    private val binaryEncoder: AvroBinaryEncoder,
) : AbstractEncoder() {
    private var schema: AvroSchema = schema

    /** The encoder of this structure's values, created on first use. */
    private var valueEncoder: AvroValueDirectEncoder? = null

    /** From [reset] to [endStructure], as for the other encoders' `inUse`. */
    var inUse = false
        private set

    /**
     * Whether [valueEncoder] encodes a value not done yet: a value serializer that encodes on this encoder again from
     * inside its value, or a value that failed, leaves it set, and every next value gets a new encoder of its own, as
     * before values were reused.
     */
    private var encodingValue = false

    override val serializersModule: SerializersModule
        get() = avro.serializersModule

    /** Begins a polymorphic structure written with [schema]. Its value encoder is kept, with its own guard. */
    fun reset(schema: AvroSchema): PolymorphicDirectEncoder {
        this.schema = schema
        inUse = true
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        inUse = false
    }

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        // index 0 is the type discriminator, index 1 is the value itself
        // we don't need the type discriminator here
        return index == 1
    }

    override fun <T> encodeSerializableValue(
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        if (encodingValue) {
            AvroValueDirectEncoder(schema, avro, binaryEncoder).encodeSerializableValue(serializer, value)
            return
        }
        val encoder = valueEncoder?.reset(schema) ?: AvroValueDirectEncoder(schema, avro, binaryEncoder).also { valueEncoder = it }
        encodingValue = true
        encoder.encodeSerializableValue(serializer, value)
        encodingValue = false
    }
}