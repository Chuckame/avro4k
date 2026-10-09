package com.github.avrokotlin.avro4k.internal.decoder.generic

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.ResolvedSchema
import com.github.avrokotlin.avro4k.internal.BadDecodedValueError
import com.github.avrokotlin.avro4k.internal.decoder.AvroFormatDecoder
import com.github.avrokotlin.avro4k.internal.enumDefaultIndex
import com.github.avrokotlin.avro4k.internal.interceptedDeserializer
import com.github.avrokotlin.avro4k.internal.toByteExact
import com.github.avrokotlin.avro4k.internal.toDoubleExact
import com.github.avrokotlin.avro4k.internal.toFloatExact
import com.github.avrokotlin.avro4k.internal.toIntExact
import com.github.avrokotlin.avro4k.internal.toLongExact
import com.github.avrokotlin.avro4k.internal.toShortExact
import com.github.avrokotlin.avro4k.toAvro4k
import com.github.avrokotlin.avro4k.toJsonElement
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
 * Apache schemas, converted to [AvroSchema] through the identity-cached converter: a lookup per record or array.
 *
 * A union is resolved from the generic value it holds (see [branchOfOrNull]), as the binary decoder resolves it from the
 * written index: [currentWriterSchema] is the value's own branch, never the union.
 */
@OptIn(ExperimentalSerializationApi::class)
internal abstract class AbstractAvroGenericDecoder : AbstractDecoder(), AvroFormatDecoder {
    abstract override val avro: Avro

    /** The writer schema of the value being decoded, as the parent structure gives it: possibly a union. */
    abstract val currentAvroSchema: AvroSchema

    final override val currentWriterSchema: ResolvedSchema
        get() =
            when (val schema = currentAvroSchema) {
                is ResolvedSchema -> schema
                is AvroSchema.UnionSchema -> resolveUnion(schema)
            }

    /**
     * The generic value at the current position, or `null`, **without moving to the next one**: a serializer may read
     * [currentWriterSchema] before decoding the value.
     */
    protected abstract fun currentGenericValueOrNull(): Any?

    /** The branch of [union] holding the current value. */
    protected open fun resolveUnion(union: AvroSchema.UnionSchema): ResolvedSchema {
        val value = currentGenericValueOrNull()
        return union.branchOfOrNull(value) ?: throw noUnionBranchError(value, union)
    }

    /**
     * The schema of a structure's [value] that was just read: the branch holding it when [currentAvroSchema] is a union.
     * When no branch fits, the union itself, so that only reading the structure's element schemas fails, as before.
     */
    private fun structureSchemaOf(value: Any): AvroSchema {
        val schema = currentAvroSchema
        return if (schema is AvroSchema.UnionSchema) schema.branchOfOrNull(value) ?: schema else schema
    }

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
                            writerSchema = structureSchemaOf(value),
                            avro = avro
                        )

                    else -> throw BadDecodedValueError(value, StructureKind.LIST, GenericArray::class, Collection::class, ByteBuffer::class)
                }

            StructureKind.MAP ->
                when (val value = decodeGenericValue()) {
                    is Map<*, *> ->
                        MapGenericDecoder(
                            value as Map<CharSequence, Any?>,
                            structureSchemaOf(value),
                            avro
                        )

                    // a record decoded as a map of its fields, as the binary decoder does (e.g. by AnySerializer)
                    is IndexedRecord -> RecordAsMapGenericDecoder(value, avro)

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

            // an enum symbol, as the binary decoder reads an enum as a string
            is GenericEnumSymbol<*> -> value.toString()

            else -> throw BadDecodedValueError(value, PrimitiveKind.STRING, CharSequence::class, ByteArray::class, GenericFixed::class, GenericEnumSymbol::class)
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

/**
 * The branch of this union that holds the generic [value], by the kind of value, as Apache's `GenericData.resolveUnion`
 * picks it: `null` → `null`; an Apache record, fixed or enum symbol → the named branch of its schema's full name; a [Map]
 * → map; a [GenericArray] or any other [Collection] → array; a [CharSequence] → string; a [ByteBuffer] or a [ByteArray] →
 * bytes; an [Int], [Long], [Float], [Double] or [Boolean] → that type. The specification forbids two branches of the same
 * type except named types, so the match is unambiguous.
 *
 * Nothing matching exactly, the only non-null branch if there is one, as the decoder took it before it resolved unions:
 * an `Int` held for a `["null", "long"]` field still decodes. Otherwise `null`.
 *
 * Branches are compared by type and by full name only, never with the structural `equals` of a schema.
 */
internal fun AvroSchema.UnionSchema.branchOfOrNull(value: Any?): ResolvedSchema? {
    val exact =
        when (value) {
            null -> branchOfType(AvroSchema.Type.NULL)

            // an Apache container too, but of an array: not a named type
            is GenericArray<*> -> branchOfType(AvroSchema.Type.ARRAY)

            is GenericContainer -> getIndexOrNull(value.schema.fullName)?.let { types[it] }

            is Map<*, *> -> branchOfType(AvroSchema.Type.MAP)

            is Collection<*> -> branchOfType(AvroSchema.Type.ARRAY)

            is CharSequence -> branchOfType(AvroSchema.Type.STRING)

            is ByteBuffer, is ByteArray -> branchOfType(AvroSchema.Type.BYTES)

            is Int -> branchOfType(AvroSchema.Type.INT)

            is Long -> branchOfType(AvroSchema.Type.LONG)

            is Float -> branchOfType(AvroSchema.Type.FLOAT)

            is Double -> branchOfType(AvroSchema.Type.DOUBLE)

            is Boolean -> branchOfType(AvroSchema.Type.BOOLEAN)

            else -> null
        }
    return exact ?: onlyNonNullBranch()
}

private fun AvroSchema.UnionSchema.branchOfType(type: AvroSchema.Type): ResolvedSchema? = types.firstOrNull { it.type === type }

private fun AvroSchema.UnionSchema.onlyNonNullBranch(): ResolvedSchema? {
    var found: ResolvedSchema? = null
    for (branch in types) {
        if (branch.type === AvroSchema.Type.NULL) continue
        if (found != null) return null
        found = branch
    }
    return found
}

private fun noUnionBranchError(
    value: Any?,
    union: AvroSchema.UnionSchema,
): SerializationException =
    SerializationException(
        "The generic value '$value' of type ${value?.let { it::class.qualifiedName }} matches no branch of the union ${union.toJsonElement()}"
    )