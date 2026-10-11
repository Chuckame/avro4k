package com.github.avrokotlin.avro4k.internal.encoder.direct

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryEncoder
import com.github.avrokotlin.avro4k.internal.encoder.AbstractAvroEncoder
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.SerializersModule

internal class AvroValueDirectEncoder(
    override var currentAvroSchema: AvroSchema,
    avro: Avro,
    binaryEncoder: AvroBinaryEncoder,
) : AbstractAvroDirectEncoder(avro, binaryEncoder)

internal sealed class AbstractAvroDirectEncoder(
    override val avro: Avro,
    protected val binaryEncoder: AvroBinaryEncoder,
) : AbstractAvroEncoder() {
    override val serializersModule: SerializersModule
        get() = avro.serializersModule

    override fun getRecordEncoder(descriptor: SerialDescriptor): CompositeEncoder {
        return RecordDirectEncoder(descriptor, currentAvroSchema, avro, binaryEncoder)
    }

    override fun getPolymorphicEncoder(descriptor: SerialDescriptor): CompositeEncoder {
        return PolymorphicDirectEncoder(avro, currentAvroSchema, binaryEncoder)
    }

    override fun getArrayEncoder(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder {
        return ArrayDirectEncoder(currentAvroSchema, collectionSize, avro, binaryEncoder)
    }

    override fun getMapEncoder(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder {
        return MapDirectEncoder(currentAvroSchema, collectionSize, avro, binaryEncoder)
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

@OptIn(ExperimentalSerializationApi::class)
internal class PolymorphicDirectEncoder(
    private val avro: Avro,
    private val schema: AvroSchema,
    private val binaryEncoder: AvroBinaryEncoder,
) : AbstractEncoder() {
    override val serializersModule: SerializersModule
        get() = avro.serializersModule

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
        AvroValueDirectEncoder(schema, avro, binaryEncoder)
            .encodeSerializableValue(serializer, value)
    }
}