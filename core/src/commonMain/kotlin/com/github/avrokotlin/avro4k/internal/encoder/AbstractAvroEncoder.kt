package com.github.avrokotlin.avro4k.internal.encoder

import com.github.avrokotlin.avro4k.AvroEncoder
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.AvroSerializationException
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.ensureFixedSize
import com.github.avrokotlin.avro4k.fullNameOrAliasMismatchError
import com.github.avrokotlin.avro4k.internal.SerializerLocatorMiddleware
import com.github.avrokotlin.avro4k.internal.aliases
import com.github.avrokotlin.avro4k.internal.convertForEncoding
import com.github.avrokotlin.avro4k.internal.encodeToUtf8
import com.github.avrokotlin.avro4k.internal.isFullNameOrAliasMatch
import com.github.avrokotlin.avro4k.internal.nonNullSerialName
import com.github.avrokotlin.avro4k.internal.schema.asType
import com.github.avrokotlin.avro4k.internal.schema.isUnion
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import com.github.avrokotlin.avro4k.internal.toFloatExact
import com.github.avrokotlin.avro4k.internal.toIntExact
import com.github.avrokotlin.avro4k.namedSchemaNotFoundInUnionError
import com.github.avrokotlin.avro4k.trySelectEnumSchemaForSymbol
import com.github.avrokotlin.avro4k.trySelectFixedSchemaForSize
import com.github.avrokotlin.avro4k.trySelectNamedSchema
import com.github.avrokotlin.avro4k.trySelectTypeNameFromUnion
import com.github.avrokotlin.avro4k.unsupportedWriterTypeError
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder

/**
 * The base of every avro4k encoder: it resolves unions and checks each value against the writer schema, then hands the
 * value to the `*Unchecked` method of the right Avro type.
 *
 * Each `encodeXxx` only handles a value written to its own Avro type; the union selection (`selectUnionBranchForXxx`),
 * the conversions and the errors are out of line, so that `encodeXxx` stays small enough for the JIT to inline it.
 *
 * Public only for the generic data encoder of the `apache-interop` module.
 */
@InternalAvro4kApi
@OptIn(ExperimentalSerializationApi::class)
public abstract class AbstractAvroEncoder : AbstractEncoder(), AvroEncoder {
    private var selectedUnionIndex: Int = -1

    /** The writer schema of the value being encoded. Once a union branch is selected ([encodeUnionIndex]), it is that branch. */
    abstract override var currentWriterSchema: AvroSchema

    public abstract fun getRecordEncoder(descriptor: SerialDescriptor): CompositeEncoder

    public abstract fun getPolymorphicEncoder(descriptor: SerialDescriptor): CompositeEncoder

    public abstract fun getMapEncoder(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder

    public abstract fun getArrayEncoder(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder

    public abstract fun encodeUnionIndexUnchecked(index: Int)

    public abstract fun encodeNullUnchecked()

    public abstract fun encodeBooleanUnchecked(value: Boolean)

    public abstract fun encodeIntUnchecked(value: Int)

    public abstract fun encodeLongUnchecked(value: Long)

    public abstract fun encodeFloatUnchecked(value: Float)

    public abstract fun encodeDoubleUnchecked(value: Double)

    /** Encodes a STRING whose content is already UTF-8 encoded. */
    public abstract fun encodeStringUnchecked(utf8: ByteArray)

    public abstract fun encodeStringUnchecked(value: String)

    public abstract fun encodeBytesUnchecked(value: ByteArray)

    public abstract fun encodeFixedUnchecked(value: ByteArray)

    public abstract fun encodeEnumUnchecked(symbol: String)

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
                if (currentWriterSchema.isUnion) {
                    trySelectNamedSchema(descriptor) ||
                        throw namedSchemaNotFoundInUnionError(descriptor.nonNullSerialName, descriptor.aliases)
                    nameChecked = true
                } else {
                    nameChecked = false
                }
                when (currentWriterSchema.type) {
                    AvroSchema.Type.RECORD -> {
                        if (nameChecked || currentWriterSchema.isFullNameOrAliasMatch(descriptor)) {
                            getRecordEncoder(descriptor)
                        } else {
                            throw fullNameOrAliasMismatchError(descriptor.nonNullSerialName, descriptor.aliases)
                        }
                    }

                    else -> throw unsupportedWriterTypeError(AvroSchema.Type.RECORD)
                }
            }

            is PolymorphicKind -> getPolymorphicEncoder(descriptor)

            else -> throw AvroSerializationException("Unsupported structure kind: $descriptor")
        }
    }

    override fun beginCollection(
        descriptor: SerialDescriptor,
        collectionSize: Int,
    ): CompositeEncoder {
        return when (descriptor.kind) {
            StructureKind.LIST -> {
                if (currentWriterSchema.isUnion) {
                    trySelectTypeNameFromUnion(AvroSchema.Type.ARRAY) || throw unsupportedWriterTypeError(AvroSchema.Type.ARRAY)
                }
                when (currentWriterSchema.type) {
                    AvroSchema.Type.ARRAY -> getArrayEncoder(descriptor, collectionSize)
                    else -> throw unsupportedWriterTypeError(AvroSchema.Type.ARRAY)
                }
            }

            StructureKind.MAP -> {
                if (currentWriterSchema.isUnion) {
                    trySelectTypeNameFromUnion(AvroSchema.Type.MAP) || throw unsupportedWriterTypeError(AvroSchema.Type.MAP)
                }
                when (currentWriterSchema.type) {
                    AvroSchema.Type.MAP -> getMapEncoder(descriptor, collectionSize)
                    else -> throw unsupportedWriterTypeError(AvroSchema.Type.MAP)
                }
            }

            else -> throw AvroSerializationException("Unsupported collection kind: $descriptor")
        }
    }

    override fun encodeUnionIndex(index: Int) {
        if (selectedUnionIndex != -1) {
            throw AvroSerializationException("Already selected union index: $selectedUnionIndex, got $index, for selected schema ${currentWriterSchema.toSchemaJsonString()}")
        }
        currentWriterSchema = currentWriterSchema.asType<AvroSchema.UnionSchema>().types[index]
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

    /**
     * Forgets the union branch selected for the previous value, as a new encoder has none: for an encoder that core reuses
     * for another structure or value. [encodeElement] does the same for each element, but a polymorphic value is encoded
     * without one.
     */
    internal fun clearSelectedUnionIndex() {
        selectedUnionIndex = -1
    }

    override fun encodeNull() {
        if (currentWriterSchema.isUnion) {
            trySelectTypeNameFromUnion(AvroSchema.Type.NULL) ||
                throw AvroSchemaMismatchException("Cannot encode null value for non-nullable schema: ${currentWriterSchema.toSchemaJsonString()}")
        } else if (currentWriterSchema.type != AvroSchema.Type.NULL) {
            throw AvroSchemaMismatchException("Cannot encode null value for non-null schema: ${currentWriterSchema.toSchemaJsonString()}")
        }
        encodeNullUnchecked()
    }

    override fun encodeBytes(value: ByteArray) {
        if (currentWriterSchema.isUnion) selectUnionBranchForBytes(value)
        if (currentWriterSchema.type === AvroSchema.Type.BYTES) encodeBytesUnchecked(value) else encodeBytesConverted(value)
    }

    private fun selectUnionBranchForBytes(value: ByteArray) {
        trySelectTypeNameFromUnion(AvroSchema.Type.BYTES) ||
            trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
            trySelectFixedSchemaForSize(value.size) ||
            throw unsupportedWriterTypeError(AvroSchema.Type.FIXED, AvroSchema.Type.BYTES, AvroSchema.Type.STRING)
    }

    private fun encodeBytesConverted(value: ByteArray) {
        when (currentWriterSchema.type) {
            AvroSchema.Type.STRING -> encodeStringUnchecked(utf8 = value)
            AvroSchema.Type.FIXED -> encodeFixedUnchecked(ensureFixedSize(value))
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.BYTES, AvroSchema.Type.STRING, AvroSchema.Type.FIXED)
        }
    }

    override fun encodeFixed(value: ByteArray) {
        if (currentWriterSchema.isUnion) selectUnionBranchForFixed(value)
        if (currentWriterSchema.type === AvroSchema.Type.FIXED) encodeFixedUnchecked(ensureFixedSize(value)) else encodeFixedConverted(value)
    }

    private fun selectUnionBranchForFixed(value: ByteArray) {
        trySelectFixedSchemaForSize(value.size) ||
            trySelectTypeNameFromUnion(AvroSchema.Type.BYTES) ||
            trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
            throw unsupportedWriterTypeError(AvroSchema.Type.FIXED, AvroSchema.Type.BYTES, AvroSchema.Type.STRING)
    }

    private fun encodeFixedConverted(value: ByteArray) {
        when (currentWriterSchema.type) {
            AvroSchema.Type.BYTES -> encodeBytesUnchecked(value)
            AvroSchema.Type.STRING -> encodeStringUnchecked(utf8 = value)
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.FIXED, AvroSchema.Type.BYTES, AvroSchema.Type.STRING)
        }
    }

    override fun encodeEnum(
        enumDescriptor: SerialDescriptor,
        index: Int,
    ) {
        val nameChecked = currentWriterSchema.isUnion && selectUnionBranchForEnum(enumDescriptor)
        val enumName = enumDescriptor.getElementName(index)
        if (currentWriterSchema.type === AvroSchema.Type.ENUM && (nameChecked || currentWriterSchema.isFullNameOrAliasMatch(enumDescriptor))) {
            encodeEnumUnchecked(enumName)
        } else {
            encodeEnumConverted(enumDescriptor, enumName)
        }
    }

    /** Returns true when the selected branch was found by the enum's name, so its name needs no other check. */
    private fun selectUnionBranchForEnum(enumDescriptor: SerialDescriptor): Boolean {
        if (trySelectNamedSchema(enumDescriptor)) return true
        trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
            throw namedSchemaNotFoundInUnionError(
                enumDescriptor.nonNullSerialName,
                enumDescriptor.aliases,
                AvroSchema.Type.STRING
            )
        return false
    }

    /** An enum written to a string, or to an enum whose name does not match the descriptor's. */
    private fun encodeEnumConverted(
        enumDescriptor: SerialDescriptor,
        enumName: String,
    ) {
        when (currentWriterSchema.type) {
            AvroSchema.Type.ENUM -> throw fullNameOrAliasMismatchError(enumDescriptor.nonNullSerialName, enumDescriptor.aliases)
            AvroSchema.Type.STRING -> encodeStringUnchecked(enumName)
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.ENUM, AvroSchema.Type.STRING)
        }
    }

    override fun encodeBoolean(value: Boolean) {
        if (currentWriterSchema.isUnion) selectUnionBranchForBoolean()
        if (currentWriterSchema.type === AvroSchema.Type.BOOLEAN) encodeBooleanUnchecked(value) else encodeBooleanConverted(value)
    }

    private fun selectUnionBranchForBoolean() {
        trySelectTypeNameFromUnion(AvroSchema.Type.BOOLEAN) ||
            trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
            throw unsupportedWriterTypeError(AvroSchema.Type.BOOLEAN, AvroSchema.Type.STRING)
    }

    private fun encodeBooleanConverted(value: Boolean) {
        when (currentWriterSchema.type) {
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
        if (currentWriterSchema.isUnion) selectUnionBranchForInt()
        if (currentWriterSchema.type === AvroSchema.Type.INT) encodeIntUnchecked(value) else encodeIntConverted(value)
    }

    private fun selectUnionBranchForInt() {
        trySelectTypeNameFromUnion(AvroSchema.Type.INT) ||
            trySelectTypeNameFromUnion(AvroSchema.Type.LONG) ||
            trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
            throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.LONG, AvroSchema.Type.STRING)
    }

    private fun encodeIntConverted(value: Int) {
        when (currentWriterSchema.type) {
            AvroSchema.Type.LONG -> encodeLongUnchecked(value.toLong())
            AvroSchema.Type.STRING -> encodeStringUnchecked(value.toString())
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.LONG, AvroSchema.Type.STRING)
        }
    }

    override fun encodeLong(value: Long) {
        if (currentWriterSchema.isUnion) selectUnionBranchForLong()
        if (currentWriterSchema.type === AvroSchema.Type.LONG) encodeLongUnchecked(value) else encodeLongConverted(value)
    }

    private fun selectUnionBranchForLong() {
        trySelectTypeNameFromUnion(AvroSchema.Type.LONG) ||
            trySelectTypeNameFromUnion(AvroSchema.Type.INT) ||
            trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
            throw unsupportedWriterTypeError(AvroSchema.Type.LONG, AvroSchema.Type.INT, AvroSchema.Type.STRING)
    }

    private fun encodeLongConverted(value: Long) {
        when (currentWriterSchema.type) {
            AvroSchema.Type.INT -> encodeIntUnchecked(value.toIntExact())
            AvroSchema.Type.STRING -> encodeStringUnchecked(value.toString())
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.LONG, AvroSchema.Type.STRING)
        }
    }

    override fun encodeFloat(value: Float) {
        if (currentWriterSchema.isUnion) selectUnionBranchForFloat()
        if (currentWriterSchema.type === AvroSchema.Type.FLOAT) encodeFloatUnchecked(value) else encodeFloatConverted(value)
    }

    private fun selectUnionBranchForFloat() {
        trySelectTypeNameFromUnion(AvroSchema.Type.FLOAT) ||
            trySelectTypeNameFromUnion(AvroSchema.Type.DOUBLE) ||
            trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
            throw unsupportedWriterTypeError(AvroSchema.Type.FLOAT, AvroSchema.Type.DOUBLE, AvroSchema.Type.STRING)
    }

    private fun encodeFloatConverted(value: Float) {
        when (currentWriterSchema.type) {
            AvroSchema.Type.DOUBLE -> encodeDoubleUnchecked(value.toDouble())
            AvroSchema.Type.STRING -> encodeStringUnchecked(value.toString())
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.FLOAT, AvroSchema.Type.DOUBLE, AvroSchema.Type.STRING)
        }
    }

    override fun encodeDouble(value: Double) {
        if (currentWriterSchema.isUnion) selectUnionBranchForDouble()
        if (currentWriterSchema.type === AvroSchema.Type.DOUBLE) encodeDoubleUnchecked(value) else encodeDoubleConverted(value)
    }

    private fun selectUnionBranchForDouble() {
        trySelectTypeNameFromUnion(AvroSchema.Type.DOUBLE) ||
            trySelectTypeNameFromUnion(AvroSchema.Type.FLOAT) ||
            trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
            throw unsupportedWriterTypeError(AvroSchema.Type.DOUBLE, AvroSchema.Type.FLOAT, AvroSchema.Type.STRING)
    }

    private fun encodeDoubleConverted(value: Double) {
        when (currentWriterSchema.type) {
            AvroSchema.Type.FLOAT -> encodeFloatUnchecked(value.toFloatExact())
            AvroSchema.Type.STRING -> encodeStringUnchecked(value.toString())
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.DOUBLE, AvroSchema.Type.FLOAT, AvroSchema.Type.STRING)
        }
    }

    override fun encodeChar(value: Char) {
        if (currentWriterSchema.isUnion) selectUnionBranchForChar()
        if (currentWriterSchema.type === AvroSchema.Type.INT) encodeIntUnchecked(value.code) else encodeCharConverted(value)
    }

    private fun selectUnionBranchForChar() {
        trySelectTypeNameFromUnion(AvroSchema.Type.INT) ||
            trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
            throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.STRING)
    }

    private fun encodeCharConverted(value: Char) {
        when (currentWriterSchema.type) {
            AvroSchema.Type.STRING -> encodeStringUnchecked(value.toString())
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.STRING)
        }
    }

    override fun encodeString(value: String) {
        if (currentWriterSchema.isUnion) selectUnionBranchForString(value)
        if (currentWriterSchema.type === AvroSchema.Type.STRING) encodeStringUnchecked(value) else encodeStringConverted(value)
    }

    private fun selectUnionBranchForString(value: String) {
        trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
            trySelectTypeNameFromUnion(AvroSchema.Type.BYTES) ||
            trySelectFixedSchemaForSize(value.length) ||
            trySelectEnumSchemaForSymbol(value) ||
            trySelectTypeNameFromUnion(AvroSchema.Type.BOOLEAN) ||
            trySelectTypeNameFromUnion(AvroSchema.Type.INT) ||
            trySelectTypeNameFromUnion(AvroSchema.Type.LONG) ||
            trySelectTypeNameFromUnion(AvroSchema.Type.FLOAT) ||
            trySelectTypeNameFromUnion(AvroSchema.Type.DOUBLE) ||
            throw stringUnsupportedWriterTypeError()
    }

    private fun encodeStringConverted(value: String) {
        when (currentWriterSchema.type) {
            AvroSchema.Type.BOOLEAN -> encodeBooleanUnchecked(value.convertForEncoding("String", AvroSchema.Type.BOOLEAN) { it.toBooleanStrict() })
            AvroSchema.Type.INT -> encodeIntUnchecked(value.convertForEncoding("String", AvroSchema.Type.INT) { it.toInt() })
            AvroSchema.Type.LONG -> encodeLongUnchecked(value.convertForEncoding("String", AvroSchema.Type.LONG) { it.toLong() })
            AvroSchema.Type.FLOAT -> encodeFloatUnchecked(value.convertForEncoding("String", AvroSchema.Type.FLOAT) { it.toFloat() })
            AvroSchema.Type.DOUBLE -> encodeDoubleUnchecked(value.convertForEncoding("String", AvroSchema.Type.DOUBLE) { it.toDouble() })
            AvroSchema.Type.BYTES -> encodeBytesUnchecked(value.encodeToUtf8())
            AvroSchema.Type.FIXED -> encodeFixedUnchecked(ensureFixedSize(value.encodeToUtf8()))
            AvroSchema.Type.ENUM -> encodeEnumUnchecked(value)
            else -> throw stringUnsupportedWriterTypeError()
        }
    }

    /** The same list of types when no union branch fits a string and when the writer type does not take one. */
    private fun stringUnsupportedWriterTypeError(): Throwable =
        unsupportedWriterTypeError(
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