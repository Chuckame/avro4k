package com.github.avrokotlin.avro4k.internal.decoder

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.DecodedNullError
import com.github.avrokotlin.avro4k.internal.DecodingStep
import com.github.avrokotlin.avro4k.internal.IllegalIndexedAccessError
import com.github.avrokotlin.avro4k.internal.SerializationWorkflow
import com.github.avrokotlin.avro4k.internal.SerializerLocatorMiddleware
import com.github.avrokotlin.avro4k.internal.defaultValueBytes
import com.github.avrokotlin.avro4k.internal.genericFixedValue
import com.github.avrokotlin.avro4k.internal.nonNullSerialName
import com.github.avrokotlin.avro4k.internal.resolveDefaultBranch
import com.github.avrokotlin.avro4k.internal.schema.CHAR_LOGICAL_TYPE_NAME
import com.github.avrokotlin.avro4k.internal.schema.elementType
import com.github.avrokotlin.avro4k.internal.schema.fields
import com.github.avrokotlin.avro4k.internal.schema.fixedSize
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import com.github.avrokotlin.avro4k.internal.schema.types
import com.github.avrokotlin.avro4k.internal.schema.valueType
import com.github.avrokotlin.avro4k.internal.toByteExact
import com.github.avrokotlin.avro4k.internal.toFloatExact
import com.github.avrokotlin.avro4k.internal.toIntExact
import com.github.avrokotlin.avro4k.internal.toShortExact
import com.github.avrokotlin.avro4k.unsupportedWriterTypeError
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.modules.SerializersModule

/**
 * Decodes the default value of a reader field that is missing from the writer schema, straight from the [JsonElement]
 * parsed from its `@AvroDefault` (or the implicit `null`, `[]` or `{}`), against the reader field's [schema].
 *
 * The json is never converted to an intermediate value tree: each node is decoded against the schema at its position,
 * a union being resolved to the first branch the json is valid for ([resolveDefaultBranch]), so [currentAvroSchema] is
 * never a union. A record goes through the [com.github.avrokotlin.avro4k.internal.RecordResolver] workflow of its reader
 * schema, its fields being read from the json object by name, and a field the json leaves out taking its own default.
 *
 * The accepted conversions are those of the former path, which converted the json to Apache `GenericData` values and
 * decoded them with the generic decoder (a number or a boolean can be read from a string), except where the
 * specification says otherwise: bytes and fixed defaults map each code point 0-255 to a byte.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class JsonDefaultDecoder(
    private val avro: Avro,
    private val value: JsonElement,
    schema: AvroSchema,
) : AbstractDecoder(), AvroDecoder {
    /**
     * The given schema, unless it is a union: then the branch matching the kind of [value].
     */
    val currentAvroSchema: AvroSchema = schema.resolveDefaultBranch(value)

    override val currentWriterSchema: AvroSchema
        get() = currentAvroSchema

    override val serializersModule: SerializersModule
        get() = avro.serializersModule

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        return SerializerLocatorMiddleware.apply(deserializer)
            .deserialize(this)
    }

    override fun decodeNotNullMark(): Boolean = value !is JsonNull

    override fun decodeNull(): Nothing? = null

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        throw IllegalIndexedAccessError()
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        return when (descriptor.kind) {
            StructureKind.LIST ->
                when {
                    value is JsonArray && currentAvroSchema.type == AvroSchema.Type.ARRAY ->
                        ArrayJsonDefaultDecoder(avro, value, currentAvroSchema.elementType)

                    else -> throw unsupportedWriterTypeError(AvroSchema.Type.ARRAY)
                }

            StructureKind.MAP ->
                when {
                    value is JsonObject && currentAvroSchema.type == AvroSchema.Type.MAP ->
                        MapJsonDefaultDecoder(avro, value, currentAvroSchema.valueType)

                    else -> throw unsupportedWriterTypeError(AvroSchema.Type.MAP)
                }

            StructureKind.CLASS, StructureKind.OBJECT ->
                when {
                    value is JsonObject && currentAvroSchema.type == AvroSchema.Type.RECORD ->
                        RecordJsonDefaultDecoder(avro, value, currentAvroSchema, descriptor)

                    else -> throw unsupportedWriterTypeError(AvroSchema.Type.RECORD)
                }

            is PolymorphicKind -> PolymorphicJsonDefaultDecoder(avro, descriptor, currentAvroSchema, value)

            else -> throw SerializationException("Unsupported descriptor for structure decoding: $descriptor")
        }
    }

    /**
     * The json primitive of a non-null default, or a failure naming what the json was expected to be.
     */
    private val primitive: JsonPrimitive
        get() =
            when (value) {
                is JsonNull -> throw DecodedNullError()
                is JsonPrimitive -> value
                else -> throw SerializationException("Not a valid primitive value for schema ${currentAvroSchema.toSchemaJsonString()}: $value")
            }

    /**
     * A char's default is its code (see [com.github.avrokotlin.avro4k.internal.toFieldDefault]), or, nested in a structure, still
     * its single-character string.
     */
    private fun intValue(): Int =
        if (currentAvroSchema.logicalTypeName == CHAR_LOGICAL_TYPE_NAME && primitive.isString) {
            primitive.content.single().code
        } else {
            primitive.int
        }

    /**
     * Bytes and fixed defaults map each code point 0-255 to a byte, as the specification says, and a fixed default is padded
     * with zeros or truncated to the fixed size, as Apache Java does. A string or an enum symbol is read as its UTF-8 bytes,
     * as a string is written.
     */
    private fun bytesValue(): ByteArray =
        when (currentAvroSchema.type) {
            AvroSchema.Type.BYTES -> primitive.content.defaultValueBytes()
            AvroSchema.Type.FIXED -> primitive.content.defaultValueBytes().copyOf(currentAvroSchema.fixedSize)
            else -> primitive.content.encodeToByteArray()
        }

    override fun decodeBoolean(): Boolean {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.BOOLEAN -> primitive.boolean
            AvroSchema.Type.STRING, AvroSchema.Type.ENUM -> primitive.content.toBoolean()
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.BOOLEAN, AvroSchema.Type.STRING)
        }
    }

    override fun decodeByte(): Byte {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.INT -> intValue().toByteExact()
            AvroSchema.Type.LONG -> primitive.long.toByteExact()
            AvroSchema.Type.STRING, AvroSchema.Type.ENUM -> primitive.content.toByte()
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.LONG, AvroSchema.Type.STRING)
        }
    }

    override fun decodeShort(): Short {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.INT -> intValue().toShortExact()
            AvroSchema.Type.LONG -> primitive.long.toShortExact()
            AvroSchema.Type.STRING, AvroSchema.Type.ENUM -> primitive.content.toShort()
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.LONG, AvroSchema.Type.STRING)
        }
    }

    override fun decodeInt(): Int {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.INT -> intValue()
            AvroSchema.Type.LONG -> primitive.long.toIntExact()
            AvroSchema.Type.STRING, AvroSchema.Type.ENUM -> primitive.content.toInt()
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.LONG, AvroSchema.Type.STRING)
        }
    }

    override fun decodeLong(): Long {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.LONG -> primitive.long
            AvroSchema.Type.INT -> intValue().toLong()
            AvroSchema.Type.STRING, AvroSchema.Type.ENUM -> primitive.content.toLong()
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.LONG, AvroSchema.Type.INT, AvroSchema.Type.STRING)
        }
    }

    override fun decodeFloat(): Float {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.FLOAT -> primitive.float
            AvroSchema.Type.DOUBLE -> primitive.double.toFloatExact()
            AvroSchema.Type.STRING, AvroSchema.Type.ENUM -> primitive.content.toFloat()
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.FLOAT, AvroSchema.Type.DOUBLE, AvroSchema.Type.STRING)
        }
    }

    override fun decodeDouble(): Double {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.DOUBLE -> primitive.double
            AvroSchema.Type.FLOAT -> primitive.float.toDouble()
            AvroSchema.Type.STRING, AvroSchema.Type.ENUM -> primitive.content.toDouble()
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.DOUBLE, AvroSchema.Type.FLOAT, AvroSchema.Type.STRING)
        }
    }

    override fun decodeChar(): Char {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.INT -> intValue().toChar()
            AvroSchema.Type.STRING, AvroSchema.Type.ENUM -> primitive.content.single()
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.STRING)
        }
    }

    override fun decodeString(): String {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.STRING, AvroSchema.Type.ENUM -> primitive.content
            AvroSchema.Type.BYTES, AvroSchema.Type.FIXED -> bytesValue().decodeToString()
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.STRING, AvroSchema.Type.BYTES, AvroSchema.Type.FIXED, AvroSchema.Type.ENUM)
        }
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.STRING, AvroSchema.Type.ENUM -> {
                val symbol = primitive.content
                enumDescriptor.getElementIndex(symbol).takeIf { it >= 0 }
                    ?: avro.enumResolver.getDefaultValueIndex(enumDescriptor)
                    ?: throw SerializationException("Unknown enum symbol '$symbol' for Enum '${enumDescriptor.serialName}'")
            }

            else -> throw unsupportedWriterTypeError(AvroSchema.Type.ENUM, AvroSchema.Type.STRING)
        }
    }

    override fun decodeBytes(): ByteArray {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.BYTES, AvroSchema.Type.FIXED, AvroSchema.Type.STRING, AvroSchema.Type.ENUM -> bytesValue()
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.BYTES, AvroSchema.Type.FIXED, AvroSchema.Type.STRING)
        }
    }

    override fun decodeFixed(): ByteArray {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.FIXED -> bytesValue()
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.FIXED)
        }
    }

    /**
     * The default as the generic value the former path produced, for a non-null primitive, enum or fixed only.
     */
    @Deprecated("Use currentWriterSchema to get the schema and then decode the value using the appropriate decode* method")
    override fun decodeValue(): Any {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.BOOLEAN -> primitive.boolean

            AvroSchema.Type.INT -> intValue()

            AvroSchema.Type.LONG -> primitive.long

            AvroSchema.Type.FLOAT -> primitive.float

            AvroSchema.Type.DOUBLE -> primitive.double

            AvroSchema.Type.STRING, AvroSchema.Type.ENUM -> primitive.content

            AvroSchema.Type.BYTES -> bytesValue()

            // the platform's generic value of a fixed, as the former path's (on the JVM, an Apache fixed carrying its schema)
            AvroSchema.Type.FIXED -> genericFixedValue(currentAvroSchema, decodeFixed())

            else -> throw UnsupportedOperationException("A default value of type ${currentAvroSchema.type} cannot be decoded as a generic value")
        }
    }
}

/**
 * A structure inside a default value: each element is decoded by its own [JsonDefaultDecoder].
 */
@OptIn(ExperimentalSerializationApi::class)
private abstract class JsonDefaultCompositeDecoder(
    protected val avro: Avro,
) : CompositeDecoder {
    final override val serializersModule: SerializersModule
        get() = avro.serializersModule

    protected abstract fun elementDecoder(index: Int): JsonDefaultDecoder

    override fun endStructure(descriptor: SerialDescriptor) {
    }

    override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int) = elementDecoder(index).decodeBoolean()

    override fun decodeByteElement(descriptor: SerialDescriptor, index: Int) = elementDecoder(index).decodeByte()

    override fun decodeCharElement(descriptor: SerialDescriptor, index: Int) = elementDecoder(index).decodeChar()

    override fun decodeShortElement(descriptor: SerialDescriptor, index: Int) = elementDecoder(index).decodeShort()

    override fun decodeIntElement(descriptor: SerialDescriptor, index: Int) = elementDecoder(index).decodeInt()

    override fun decodeLongElement(descriptor: SerialDescriptor, index: Int) = elementDecoder(index).decodeLong()

    override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int) = elementDecoder(index).decodeFloat()

    override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int) = elementDecoder(index).decodeDouble()

    override fun decodeStringElement(descriptor: SerialDescriptor, index: Int) = elementDecoder(index).decodeString()

    override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder = elementDecoder(index)

    override fun <T> decodeSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T>,
        previousValue: T?,
    ): T = elementDecoder(index).decodeSerializableValue(deserializer)

    override fun <T : Any> decodeNullableSerializableElement(
        descriptor: SerialDescriptor,
        index: Int,
        deserializer: DeserializationStrategy<T?>,
        previousValue: T?,
    ): T? = elementDecoder(index).decodeNullableSerializableValue(deserializer)
}

/**
 * A list or a map: its elements are decoded in order.
 *
 * @param size the number of items, as kotlinx expects it from [decodeCollectionSize]: a map has two elements per item
 */
@OptIn(ExperimentalSerializationApi::class)
private abstract class SequentialJsonDefaultDecoder(
    avro: Avro,
    private val size: Int,
    elementsPerItem: Int,
) : JsonDefaultCompositeDecoder(avro) {
    private val elementsCount = size * elementsPerItem
    private var nextIndex = 0

    override fun decodeSequentially() = true

    override fun decodeCollectionSize(descriptor: SerialDescriptor) = size

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int =
        if (nextIndex < elementsCount) nextIndex++ else CompositeDecoder.DECODE_DONE
}

private class ArrayJsonDefaultDecoder(
    avro: Avro,
    private val array: JsonArray,
    private val elementSchema: AvroSchema,
) : SequentialJsonDefaultDecoder(avro, array.size, elementsPerItem = 1) {
    override fun elementDecoder(index: Int) = JsonDefaultDecoder(avro, array[index], elementSchema)
}

/**
 * A map is decoded as a sequence of `key, value` elements: even indexes are keys (strings), odd indexes their values.
 */
private class MapJsonDefaultDecoder(
    avro: Avro,
    map: JsonObject,
    private val valueSchema: AvroSchema,
) : SequentialJsonDefaultDecoder(avro, map.size, elementsPerItem = 2) {
    private val entries = map.entries.iterator()
    private lateinit var currentEntry: Map.Entry<String, JsonElement>

    override fun elementDecoder(index: Int): JsonDefaultDecoder {
        if (index % 2 == 0) {
            currentEntry = entries.next()
            return JsonDefaultDecoder(avro, JsonPrimitive(currentEntry.key), STRING_SCHEMA)
        }
        return JsonDefaultDecoder(avro, currentEntry.value, valueSchema)
    }

    private companion object {
        val STRING_SCHEMA: AvroSchema = AvroSchema.StringSchema()
    }
}

/**
 * A record default, decoded through the workflow of its reader schema: each field is read from the json object by its
 * name in that schema. A field the json object leaves out takes its own default, exactly as a reader field missing from
 * the writer schema does ([SerializationWorkflow.missingElements]): its `@AvroDefault`, its kotlin default, the implicit
 * `null` or empty collection, or a failure. That is how the specification reads a record default.
 */
private class RecordJsonDefaultDecoder(
    avro: Avro,
    private val record: JsonObject,
    private val recordSchema: AvroSchema,
    descriptor: SerialDescriptor,
) : JsonDefaultCompositeDecoder(avro) {
    private val workflow = avro.recordResolver.resolveFields(recordSchema, descriptor)
    private var nextDecodingStepIndex = 0
    private lateinit var currentDecodingStep: DecodingStep.ValidatedDecodingStep

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (nextDecodingStepIndex < workflow.decoding.size) {
            var step = workflow.decoding[nextDecodingStepIndex++]
            if (step is DecodingStep.DeserializeWriterField && recordSchema.fields[step.writerFieldIndex].name !in record) {
                step = workflow.missingElements[step.elementIndex]
            }
            when (step) {
                is DecodingStep.IgnoreOptionalElement, is DecodingStep.SkipWriterField -> {}

                is DecodingStep.MissingElementValueFailure ->
                    throw SerializationException(
                        "Field '${descriptor.getElementName(step.elementIndex)}' is missing from the default value $record of " +
                            "'${descriptor.nonNullSerialName}', and has no default value of its own"
                    )

                is DecodingStep.ValidatedDecodingStep -> {
                    currentDecodingStep = step
                    return step.elementIndex
                }
            }
        }
        return CompositeDecoder.DECODE_DONE
    }

    override fun elementDecoder(index: Int): JsonDefaultDecoder =
        when (val step = currentDecodingStep) {
            is DecodingStep.DeserializeWriterField ->
                JsonDefaultDecoder(avro, record.getValue(recordSchema.fields[step.writerFieldIndex].name), step.schema)

            is DecodingStep.GetDefaultValue -> JsonDefaultDecoder(avro, step.defaultValue, step.schema)
        }
}

private class PolymorphicJsonDefaultDecoder(
    avro: Avro,
    descriptor: SerialDescriptor,
    schema: AvroSchema,
    private val value: JsonElement,
) : AbstractPolymorphicDecoder(avro, descriptor, schema) {
    override fun tryFindSerialNameForUnion(
        namesAndAliasesToSerialName: Map<String, String>,
        schema: AvroSchema,
    ): Pair<String, AvroSchema>? {
        return schema.types.firstNotNullOfOrNull { tryFindSerialName(namesAndAliasesToSerialName, it) }
    }

    override fun newDecoder(chosenSchema: AvroSchema): Decoder {
        return JsonDefaultDecoder(avro, value, chosenSchema)
    }
}