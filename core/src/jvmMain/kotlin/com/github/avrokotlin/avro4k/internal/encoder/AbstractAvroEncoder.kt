package com.github.avrokotlin.avro4k.internal.encoder

import com.github.avrokotlin.avro4k.AvroEncoder
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.apacheFacadeOf
import com.github.avrokotlin.avro4k.ensureFixedSize
import com.github.avrokotlin.avro4k.fullNameOrAliasMismatchError
import com.github.avrokotlin.avro4k.internal.SerializerLocatorMiddleware
import com.github.avrokotlin.avro4k.internal.aliases
import com.github.avrokotlin.avro4k.internal.isFullNameOrAliasMatch
import com.github.avrokotlin.avro4k.internal.nonNullSerialName
import com.github.avrokotlin.avro4k.internal.schema.isUnion
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import com.github.avrokotlin.avro4k.internal.schema.types
import com.github.avrokotlin.avro4k.internal.toFloatExact
import com.github.avrokotlin.avro4k.internal.toIntExact
import com.github.avrokotlin.avro4k.namedSchemaNotFoundInUnionError
import com.github.avrokotlin.avro4k.trySelectEnumSchemaForSymbol
import com.github.avrokotlin.avro4k.trySelectFixedSchemaForSize
import com.github.avrokotlin.avro4k.trySelectNamedSchema
import com.github.avrokotlin.avro4k.trySelectTypeNameFromUnion
import com.github.avrokotlin.avro4k.unsupportedWriterTypeError
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import org.apache.avro.Schema

@OptIn(ExperimentalSerializationApi::class)
internal abstract class AbstractAvroEncoder : AbstractEncoder(), AvroEncoder {
    private var selectedUnionIndex: Int = -1

    /**
     * The writer schema of the value being encoded, as avro4k's own model: the schema core's runtime reads. Once a union
     * branch is selected ([encodeUnionIndex]), it is that branch.
     */
    abstract var currentAvroSchema: AvroSchema

    /** The Apache-typed facade over [currentAvroSchema], for the public API only: see [apacheFacadeOf]. */
    final override val currentWriterSchema: Schema
        get() = apacheFacadeOf(currentAvroSchema)

    abstract fun getRecordEncoder(descriptor: SerialDescriptor): CompositeEncoder

    abstract fun getPolymorphicEncoder(descriptor: SerialDescriptor): CompositeEncoder

    abstract fun getMapEncoder(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder

    abstract fun getArrayEncoder(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder

    abstract fun encodeUnionIndexUnchecked(index: Int)

    abstract fun encodeNullUnchecked()

    abstract fun encodeBooleanUnchecked(value: Boolean)

    abstract fun encodeIntUnchecked(value: Int)

    abstract fun encodeLongUnchecked(value: Long)

    abstract fun encodeFloatUnchecked(value: Float)

    abstract fun encodeDoubleUnchecked(value: Double)

    /** Encodes a STRING whose content is already UTF-8 encoded. */
    abstract fun encodeStringUnchecked(utf8: ByteArray)

    abstract fun encodeStringUnchecked(value: String)

    abstract fun encodeBytesUnchecked(value: ByteArray)

    abstract fun encodeFixedUnchecked(value: ByteArray)

    abstract fun encodeEnumUnchecked(symbol: String)

    override fun <T> encodeSerializableValue(
        serializer: SerializationStrategy<T>,
        value: T,
    ) {
        SerializerLocatorMiddleware.apply(serializer)
            .serialize(this, value)
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        return when (descriptor.kind) {
            StructureKind.CLASS,
            StructureKind.OBJECT,
            -> {
                val nameChecked: Boolean
                if (currentAvroSchema.isUnion) {
                    trySelectNamedSchema(descriptor) ||
                        throw namedSchemaNotFoundInUnionError(descriptor.nonNullSerialName, descriptor.aliases)
                    nameChecked = true
                } else {
                    nameChecked = false
                }
                when (currentAvroSchema.type) {
                    AvroSchema.Type.RECORD -> {
                        if (nameChecked || currentAvroSchema.isFullNameOrAliasMatch(descriptor)) {
                            getRecordEncoder(descriptor)
                        } else {
                            throw fullNameOrAliasMismatchError(descriptor.nonNullSerialName, descriptor.aliases)
                        }
                    }

                    else -> throw unsupportedWriterTypeError(AvroSchema.Type.RECORD)
                }
            }

            is PolymorphicKind -> getPolymorphicEncoder(descriptor)

            else -> throw SerializationException("Unsupported structure kind: $descriptor")
        }
    }

    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder {
        return when (descriptor.kind) {
            StructureKind.LIST -> {
                if (currentAvroSchema.isUnion) {
                    trySelectTypeNameFromUnion(AvroSchema.Type.ARRAY) || throw unsupportedWriterTypeError(AvroSchema.Type.ARRAY)
                }
                when (currentAvroSchema.type) {
                    AvroSchema.Type.ARRAY -> getArrayEncoder(descriptor, collectionSize)
                    else -> throw unsupportedWriterTypeError(AvroSchema.Type.ARRAY)
                }
            }

            StructureKind.MAP -> {
                if (currentAvroSchema.isUnion) {
                    trySelectTypeNameFromUnion(AvroSchema.Type.MAP) || throw unsupportedWriterTypeError(AvroSchema.Type.MAP)
                }
                when (currentAvroSchema.type) {
                    AvroSchema.Type.MAP -> getMapEncoder(descriptor, collectionSize)
                    else -> throw unsupportedWriterTypeError(AvroSchema.Type.MAP)
                }
            }

            else -> throw SerializationException("Unsupported collection kind: $descriptor")
        }
    }

    override fun encodeUnionIndex(index: Int) {
        if (selectedUnionIndex != -1) {
            throw SerializationException("Already selected union index: $selectedUnionIndex, got $index, for selected schema ${currentAvroSchema.toSchemaJsonString()}")
        }
        currentAvroSchema = currentAvroSchema.types[index]
        encodeUnionIndexUnchecked(index)
        selectedUnionIndex = index
    }

    override fun encodeElement(
        descriptor: SerialDescriptor,
        index: Int,
    ): Boolean {
        selectedUnionIndex = -1
        return true
    }

    override fun encodeNull() {
        if (currentAvroSchema.isUnion) {
            trySelectTypeNameFromUnion(AvroSchema.Type.NULL) ||
                throw SerializationException("Cannot encode null value for non-nullable schema: ${currentAvroSchema.toSchemaJsonString()}")
        } else if (currentAvroSchema.type != AvroSchema.Type.NULL) {
            throw SerializationException("Cannot encode null value for non-null schema: ${currentAvroSchema.toSchemaJsonString()}")
        }
        encodeNullUnchecked()
    }

    override fun encodeBytes(value: ByteArray) {
        if (currentAvroSchema.isUnion) {
            trySelectTypeNameFromUnion(AvroSchema.Type.BYTES) ||
                trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                trySelectFixedSchemaForSize(value.size) ||
                throw unsupportedWriterTypeError(AvroSchema.Type.FIXED, AvroSchema.Type.BYTES, AvroSchema.Type.STRING)
        }
        when (currentAvroSchema.type) {
            AvroSchema.Type.BYTES -> encodeBytesUnchecked(value)
            AvroSchema.Type.STRING -> encodeStringUnchecked(utf8 = value)
            AvroSchema.Type.FIXED -> encodeFixedUnchecked(ensureFixedSize(value))
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.BYTES, AvroSchema.Type.STRING, AvroSchema.Type.FIXED)
        }
    }

    override fun encodeFixed(value: ByteArray) {
        if (currentAvroSchema.isUnion) {
            trySelectFixedSchemaForSize(value.size) ||
                trySelectTypeNameFromUnion(AvroSchema.Type.BYTES) ||
                trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                throw unsupportedWriterTypeError(AvroSchema.Type.FIXED, AvroSchema.Type.BYTES, AvroSchema.Type.STRING)
        }
        when (currentAvroSchema.type) {
            AvroSchema.Type.FIXED -> encodeFixedUnchecked(ensureFixedSize(value))
            AvroSchema.Type.BYTES -> encodeBytesUnchecked(value)
            AvroSchema.Type.STRING -> encodeStringUnchecked(utf8 = value)
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.FIXED, AvroSchema.Type.BYTES, AvroSchema.Type.STRING)
        }
    }

    override fun encodeEnum(
        enumDescriptor: SerialDescriptor,
        index: Int,
    ) {
        val nameChecked: Boolean
        if (currentAvroSchema.isUnion) {
            trySelectNamedSchema(enumDescriptor).also { nameChecked = it } ||
                trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                throw namedSchemaNotFoundInUnionError(
                    enumDescriptor.nonNullSerialName,
                    enumDescriptor.aliases,
                    AvroSchema.Type.STRING
                )
        } else {
            nameChecked = false
        }
        val enumName = enumDescriptor.getElementName(index)
        when (currentAvroSchema.type) {
            AvroSchema.Type.ENUM ->
                if (nameChecked || currentAvroSchema.isFullNameOrAliasMatch(enumDescriptor)) {
                    encodeEnumUnchecked(enumName)
                } else {
                    throw fullNameOrAliasMismatchError(enumDescriptor.nonNullSerialName, enumDescriptor.aliases)
                }

            AvroSchema.Type.STRING -> encodeStringUnchecked(enumName)

            else -> throw unsupportedWriterTypeError(AvroSchema.Type.ENUM, AvroSchema.Type.STRING)
        }
    }

    override fun encodeBoolean(value: Boolean) {
        if (currentAvroSchema.isUnion) {
            trySelectTypeNameFromUnion(AvroSchema.Type.BOOLEAN) ||
                trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                throw unsupportedWriterTypeError(AvroSchema.Type.BOOLEAN, AvroSchema.Type.STRING)
        }
        when (currentAvroSchema.type) {
            AvroSchema.Type.BOOLEAN -> encodeBooleanUnchecked(value)
            AvroSchema.Type.STRING -> encodeStringUnchecked(value.toString())
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.BOOLEAN, AvroSchema.Type.STRING)
        }
    }

    override fun encodeByte(value: Byte) {
        encodeInt(value.toInt())
    }

    override fun encodeShort(value: Short) {
        encodeInt(value.toInt())
    }

    override fun encodeInt(value: Int) {
        if (currentAvroSchema.isUnion) {
            trySelectTypeNameFromUnion(AvroSchema.Type.INT) ||
                trySelectTypeNameFromUnion(AvroSchema.Type.LONG) ||
                trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.LONG, AvroSchema.Type.STRING)
        }
        when (currentAvroSchema.type) {
            AvroSchema.Type.INT -> encodeIntUnchecked(value)
            AvroSchema.Type.LONG -> encodeLongUnchecked(value.toLong())
            AvroSchema.Type.STRING -> encodeStringUnchecked(value.toString())
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.LONG, AvroSchema.Type.STRING)
        }
    }

    override fun encodeLong(value: Long) {
        if (currentAvroSchema.isUnion) {
            trySelectTypeNameFromUnion(AvroSchema.Type.LONG) ||
                trySelectTypeNameFromUnion(AvroSchema.Type.INT) ||
                trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                throw unsupportedWriterTypeError(AvroSchema.Type.LONG, AvroSchema.Type.INT, AvroSchema.Type.STRING)
        }
        when (currentAvroSchema.type) {
            AvroSchema.Type.INT -> encodeIntUnchecked(value.toIntExact())
            AvroSchema.Type.LONG -> encodeLongUnchecked(value)
            AvroSchema.Type.STRING -> encodeStringUnchecked(value.toString())
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.LONG, AvroSchema.Type.STRING)
        }
    }

    override fun encodeFloat(value: Float) {
        if (currentAvroSchema.isUnion) {
            trySelectTypeNameFromUnion(AvroSchema.Type.FLOAT) ||
                trySelectTypeNameFromUnion(AvroSchema.Type.DOUBLE) ||
                trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                throw unsupportedWriterTypeError(AvroSchema.Type.FLOAT, AvroSchema.Type.DOUBLE, AvroSchema.Type.STRING)
        }
        when (currentAvroSchema.type) {
            AvroSchema.Type.FLOAT -> encodeFloatUnchecked(value)
            AvroSchema.Type.DOUBLE -> encodeDoubleUnchecked(value.toDouble())
            AvroSchema.Type.STRING -> encodeStringUnchecked(value.toString())
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.FLOAT, AvroSchema.Type.DOUBLE, AvroSchema.Type.STRING)
        }
    }

    override fun encodeDouble(value: Double) {
        if (currentAvroSchema.isUnion) {
            trySelectTypeNameFromUnion(AvroSchema.Type.DOUBLE) ||
                trySelectTypeNameFromUnion(AvroSchema.Type.FLOAT) ||
                trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                throw unsupportedWriterTypeError(AvroSchema.Type.DOUBLE, AvroSchema.Type.FLOAT, AvroSchema.Type.STRING)
        }
        when (currentAvroSchema.type) {
            AvroSchema.Type.FLOAT -> encodeFloatUnchecked(value.toFloatExact())
            AvroSchema.Type.DOUBLE -> encodeDoubleUnchecked(value)
            AvroSchema.Type.STRING -> encodeStringUnchecked(value.toString())
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.DOUBLE, AvroSchema.Type.FLOAT, AvroSchema.Type.STRING)
        }
    }

    override fun encodeChar(value: Char) {
        if (currentAvroSchema.isUnion) {
            trySelectTypeNameFromUnion(AvroSchema.Type.INT) ||
                trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.STRING)
        }
        when (currentAvroSchema.type) {
            AvroSchema.Type.INT -> encodeIntUnchecked(value.code)
            AvroSchema.Type.STRING -> encodeStringUnchecked(value.toString())
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.STRING)
        }
    }

    override fun encodeString(value: String) {
        if (currentAvroSchema.isUnion) {
            trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                trySelectTypeNameFromUnion(AvroSchema.Type.BYTES) ||
                trySelectFixedSchemaForSize(value.length) ||
                trySelectEnumSchemaForSymbol(value) ||
                trySelectTypeNameFromUnion(AvroSchema.Type.BOOLEAN) ||
                trySelectTypeNameFromUnion(AvroSchema.Type.INT) ||
                trySelectTypeNameFromUnion(AvroSchema.Type.LONG) ||
                trySelectTypeNameFromUnion(AvroSchema.Type.FLOAT) ||
                trySelectTypeNameFromUnion(AvroSchema.Type.DOUBLE) ||
                throw unsupportedWriterTypeError(
                    AvroSchema.Type.BOOLEAN,
                    AvroSchema.Type.INT,
                    AvroSchema.Type.LONG,
                    AvroSchema.Type.FLOAT,
                    AvroSchema.Type.DOUBLE,
                    AvroSchema.Type.STRING,
                    AvroSchema.Type.BYTES,
                    AvroSchema.Type.FIXED,
                    AvroSchema.Type.ENUM
                )
        }
        when (currentAvroSchema.type) {
            AvroSchema.Type.BOOLEAN -> encodeBooleanUnchecked(value.toBooleanStrict())

            AvroSchema.Type.INT -> encodeIntUnchecked(value.toInt())

            AvroSchema.Type.LONG -> encodeLongUnchecked(value.toLong())

            AvroSchema.Type.FLOAT -> encodeFloatUnchecked(value.toFloat())

            AvroSchema.Type.DOUBLE -> encodeDoubleUnchecked(value.toDouble())

            AvroSchema.Type.STRING -> encodeStringUnchecked(value)

            AvroSchema.Type.BYTES -> encodeBytesUnchecked(value.encodeToByteArray())

            AvroSchema.Type.FIXED -> encodeFixedUnchecked(ensureFixedSize(value.encodeToByteArray()))

            AvroSchema.Type.ENUM -> encodeEnumUnchecked(value)

            else -> throw unsupportedWriterTypeError(
                AvroSchema.Type.BOOLEAN,
                AvroSchema.Type.INT,
                AvroSchema.Type.LONG,
                AvroSchema.Type.FLOAT,
                AvroSchema.Type.DOUBLE,
                AvroSchema.Type.STRING,
                AvroSchema.Type.BYTES,
                AvroSchema.Type.FIXED,
                AvroSchema.Type.ENUM
            )
        }
    }
}