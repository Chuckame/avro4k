package com.github.avrokotlin.avro4k.internal.decoder.generic

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.BadDecodedValueError
import com.github.avrokotlin.avro4k.internal.enumDefaultIndex
import com.github.avrokotlin.avro4k.internal.interceptedDeserializer
import com.github.avrokotlin.avro4k.internal.toByteExact
import com.github.avrokotlin.avro4k.internal.toDoubleExact
import com.github.avrokotlin.avro4k.internal.toFloatExact
import com.github.avrokotlin.avro4k.internal.toIntExact
import com.github.avrokotlin.avro4k.internal.toLongExact
import com.github.avrokotlin.avro4k.internal.toShortExact
import com.github.avrokotlin.avro4k.toAvro4k
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.SerializersModule
import org.apache.avro.generic.GenericArray
import org.apache.avro.generic.GenericContainer
import org.apache.avro.generic.GenericEnumSymbol
import org.apache.avro.generic.GenericFixed
import org.apache.avro.generic.IndexedRecord
import java.math.BigDecimal
import java.nio.ByteBuffer

/**
 * Decodes the values of Apache's generic data model. Their own schemas (a [GenericContainer]'s, an [IndexedRecord]'s) are
 * Apache schemas, converted to [AvroSchema] through the identity-cached converter: a lookup per record or array, accepted on
 * this deprecated path.
 */
@OptIn(ExperimentalSerializationApi::class)
internal abstract class AbstractAvroGenericDecoder : AbstractDecoder(), AvroDecoder {
    internal abstract val avro: Avro

    /** The writer schema of the value being decoded. */
    abstract val currentAvroSchema: AvroSchema

    final override val currentWriterSchema: AvroSchema
        get() = currentAvroSchema

    abstract override fun decodeNotNullMark(): Boolean

    /**
     * The generic value at the current position (an Apache generic container, a collection, a map, a [CharSequence], a
     * boxed primitive, a [ByteArray]…), never `null`: every `decode*` method reads it, then converts it to the requested type.
     */
    abstract fun decodeGenericValue(): Any

    override val serializersModule: SerializersModule
        get() = avro.serializersModule

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        return interceptedDeserializer(deserializer)
            .deserialize(this)
    }

    @Suppress("UNCHECKED_CAST")
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        return when (descriptor.kind) {
            StructureKind.LIST ->
                when (val value = decodeGenericValue()) {
                    is GenericArray<*> ->
                        ArrayGenericDecoder(
                            collection = value,
                            writerSchema = value.schema.toAvro4k(),
                            avro = avro
                        )

                    is Collection<*> ->
                        ArrayGenericDecoder(
                            collection = value,
                            writerSchema = currentAvroSchema,
                            avro = avro
                        )

                    else -> throw BadDecodedValueError(value, StructureKind.LIST, GenericArray::class, Collection::class, ByteBuffer::class)
                }

            StructureKind.MAP ->
                when (val value = decodeGenericValue()) {
                    is Map<*, *> ->
                        MapGenericDecoder(
                            value as Map<CharSequence, Any?>,
                            currentAvroSchema,
                            avro
                        )

                    else -> throw BadDecodedValueError(value, StructureKind.MAP, Map::class)
                }

            StructureKind.CLASS, StructureKind.OBJECT ->
                when (val value = decodeGenericValue()) {
                    is IndexedRecord -> RecordGenericDecoder(value, descriptor, avro)
                    else -> throw BadDecodedValueError(value, descriptor.kind, IndexedRecord::class)
                }

            is PolymorphicKind ->
                when (val value = decodeGenericValue()) {
                    is GenericContainer -> PolymorphicGenericDecoder(avro, descriptor, value.schema.toAvro4k(), value)
                    else -> PolymorphicGenericDecoder(avro, descriptor, currentAvroSchema, value)
                }

            else -> throw SerializationException("Unsupported descriptor for structure decoding: $descriptor")
        }
    }

    override fun decodeBoolean(): Boolean {
        return when (val value = decodeGenericValue()) {
            is Boolean -> value
            is CharSequence -> value.toString().toBoolean()
            else -> throw BadDecodedValueError(value, PrimitiveKind.BOOLEAN, Boolean::class, Int::class, CharSequence::class)
        }
    }

    override fun decodeByte(): Byte {
        return when (val value = decodeGenericValue()) {
            is Int -> value.toByteExact()
            is Long -> value.toByteExact()
            is BigDecimal -> value.toByteExact()
            is CharSequence -> value.toString().toByte()
            else -> throw BadDecodedValueError(value, PrimitiveKind.BYTE, Int::class, Long::class, BigDecimal::class, CharSequence::class)
        }
    }

    override fun decodeShort(): Short {
        return when (val value = decodeGenericValue()) {
            is Int -> value.toShortExact()
            is Long -> value.toShortExact()
            is BigDecimal -> value.toShortExact()
            is CharSequence -> value.toString().toShort()
            else -> throw BadDecodedValueError(value, PrimitiveKind.SHORT, Int::class, Long::class, BigDecimal::class, CharSequence::class)
        }
    }

    override fun decodeInt(): Int {
        return when (val value = decodeGenericValue()) {
            is Int -> value
            is Long -> value.toIntExact()
            is BigDecimal -> value.toIntExact()
            is CharSequence -> value.toString().toInt()
            else -> throw BadDecodedValueError(value, PrimitiveKind.INT, Int::class, Long::class, BigDecimal::class, CharSequence::class)
        }
    }

    override fun decodeLong(): Long {
        return when (val value = decodeGenericValue()) {
            is Long -> value
            is Int -> value.toLong()
            is BigDecimal -> value.toLongExact()
            is CharSequence -> value.toString().toLong()
            else -> throw BadDecodedValueError(value, PrimitiveKind.LONG, Int::class, Long::class, BigDecimal::class, CharSequence::class)
        }
    }

    override fun decodeFloat(): Float {
        return when (val value = decodeGenericValue()) {
            is Float -> value
            is Double -> value.toFloatExact()
            is BigDecimal -> value.toFloatExact()
            is CharSequence -> value.toString().toFloat()
            else -> throw BadDecodedValueError(value, PrimitiveKind.FLOAT, Float::class, Double::class, BigDecimal::class, CharSequence::class)
        }
    }

    override fun decodeDouble(): Double {
        return when (val value = decodeGenericValue()) {
            is Double -> value
            is Float -> value.toDouble()
            is BigDecimal -> value.toDoubleExact()
            is CharSequence -> value.toString().toDouble()
            else -> throw BadDecodedValueError(value, PrimitiveKind.DOUBLE, Float::class, Double::class, BigDecimal::class, CharSequence::class)
        }
    }

    override fun decodeChar(): Char {
        return when (val value = decodeGenericValue()) {
            is Int -> value.toChar()
            is CharSequence -> value.single()
            else -> throw BadDecodedValueError(value, PrimitiveKind.CHAR, Int::class, CharSequence::class)
        }
    }

    override fun decodeString(): String {
        return when (val value = decodeGenericValue()) {
            is CharSequence -> value.toString()
            is ByteArray -> value.decodeToString()
            is GenericFixed -> value.bytes().decodeToString()
            else -> throw BadDecodedValueError(value, PrimitiveKind.STRING, CharSequence::class, ByteArray::class, GenericFixed::class)
        }
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        return when (val value = decodeGenericValue()) {
            is GenericEnumSymbol<*>, is CharSequence -> {
                enumDescriptor.getElementIndex(value.toString()).takeIf { it >= 0 }
                    ?: avro.enumDefaultIndex(enumDescriptor)
                    ?: throw SerializationException("Unknown enum symbol '$value' for Enum '${enumDescriptor.serialName}'")
            }

            else -> throw BadDecodedValueError(value, SerialKind.ENUM, GenericEnumSymbol::class, CharSequence::class)
        }
    }

    override fun decodeBytes(): ByteArray {
        return when (val value = decodeGenericValue()) {
            is ByteArray -> value
            is ByteBuffer -> value.array()
            is GenericFixed -> value.bytes()
            is CharSequence -> value.toString().toByteArray()
            else -> throw BadDecodedValueError<ByteArray>(value, ByteArray::class, GenericFixed::class, CharSequence::class)
        }
    }

    override fun decodeFixed(): ByteArray {
        return when (val value = decodeGenericValue()) {
            is GenericFixed -> value.bytes()
            else -> throw BadDecodedValueError<GenericFixed>(value, GenericFixed::class)
        }
    }
}