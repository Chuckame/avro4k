package com.github.avrokotlin.avro4k.internal.decoder

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.internal.DecodedNullError
import com.github.avrokotlin.avro4k.internal.DecodingStep
import com.github.avrokotlin.avro4k.internal.IllegalIndexedAccessError
import com.github.avrokotlin.avro4k.internal.SerializerLocatorMiddleware
import com.github.avrokotlin.avro4k.internal.nonNullSerialName
import com.github.avrokotlin.avro4k.internal.schema.CHAR_LOGICAL_TYPE_NAME
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
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericFixed

/**
 * Decodes the default value of a reader field that is missing from the writer schema, straight from the [JsonElement]
 * parsed from its `@AvroDefault` (or the implicit `null`, `[]` or `{}`), against the reader field's [schema].
 *
 * The json is never converted to an intermediate value tree: each node is decoded against the schema at its position,
 * a union being resolved by the json's kind ([resolveDefaultBranch]), so [currentWriterSchema] is never a union.
 * A record goes through the [com.github.avrokotlin.avro4k.internal.RecordResolver] workflow of its reader schema, its
 * fields being read from the json object by name.
 *
 * The accepted conversions are those of the former path, which converted the json to Apache `GenericData` values and
 * decoded them with the generic decoder: bytes and fixed defaults are the UTF-8 bytes of the json string, a char is the
 * single character of the string (its schema being an int with the `char` logical type), a number or a boolean can be
 * read from a string, and a missing field of a record default decodes as `null`.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class JsonDefaultDecoder(
    private val avro: Avro,
    private val value: JsonElement,
    schema: Schema,
) : AbstractDecoder(), AvroDecoder {
    /**
     * The given schema, unless it is a union: then the branch matching the kind of [value].
     */
    override val currentWriterSchema: Schema = schema.resolveDefaultBranch(value)

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
                    value is JsonArray && currentWriterSchema.type == Schema.Type.ARRAY ->
                        ArrayJsonDefaultDecoder(avro, value, currentWriterSchema.elementType)

                    else -> throw unsupportedWriterTypeError(Schema.Type.ARRAY)
                }

            StructureKind.MAP ->
                when {
                    value is JsonObject && currentWriterSchema.type == Schema.Type.MAP ->
                        MapJsonDefaultDecoder(avro, value, currentWriterSchema.valueType)

                    else -> throw unsupportedWriterTypeError(Schema.Type.MAP)
                }

            StructureKind.CLASS, StructureKind.OBJECT ->
                when {
                    value is JsonObject && currentWriterSchema.type == Schema.Type.RECORD ->
                        RecordJsonDefaultDecoder(avro, value, currentWriterSchema, descriptor)

                    else -> throw unsupportedWriterTypeError(Schema.Type.RECORD)
                }

            is PolymorphicKind -> PolymorphicJsonDefaultDecoder(avro, descriptor, currentWriterSchema, value)

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
                else -> throw SerializationException("Not a valid primitive value for schema $currentWriterSchema: $value")
            }

    private fun intValue(): Int =
        if (currentWriterSchema.logicalType?.name == CHAR_LOGICAL_TYPE_NAME) {
            primitive.content.single().code
        } else {
            primitive.int
        }

    private fun bytesValue(): ByteArray = primitive.content.toByteArray()

    override fun decodeBoolean(): Boolean {
        return when (currentWriterSchema.type) {
            Schema.Type.BOOLEAN -> primitive.boolean
            Schema.Type.STRING, Schema.Type.ENUM -> primitive.content.toBoolean()
            else -> throw unsupportedWriterTypeError(Schema.Type.BOOLEAN, Schema.Type.STRING)
        }
    }

    override fun decodeByte(): Byte {
        return when (currentWriterSchema.type) {
            Schema.Type.INT -> intValue().toByteExact()
            Schema.Type.LONG -> primitive.long.toByteExact()
            Schema.Type.STRING, Schema.Type.ENUM -> primitive.content.toByte()
            else -> throw unsupportedWriterTypeError(Schema.Type.INT, Schema.Type.LONG, Schema.Type.STRING)
        }
    }

    override fun decodeShort(): Short {
        return when (currentWriterSchema.type) {
            Schema.Type.INT -> intValue().toShortExact()
            Schema.Type.LONG -> primitive.long.toShortExact()
            Schema.Type.STRING, Schema.Type.ENUM -> primitive.content.toShort()
            else -> throw unsupportedWriterTypeError(Schema.Type.INT, Schema.Type.LONG, Schema.Type.STRING)
        }
    }

    override fun decodeInt(): Int {
        return when (currentWriterSchema.type) {
            Schema.Type.INT -> intValue()
            Schema.Type.LONG -> primitive.long.toIntExact()
            Schema.Type.STRING, Schema.Type.ENUM -> primitive.content.toInt()
            else -> throw unsupportedWriterTypeError(Schema.Type.INT, Schema.Type.LONG, Schema.Type.STRING)
        }
    }

    override fun decodeLong(): Long {
        return when (currentWriterSchema.type) {
            Schema.Type.LONG -> primitive.long
            Schema.Type.INT -> intValue().toLong()
            Schema.Type.STRING, Schema.Type.ENUM -> primitive.content.toLong()
            else -> throw unsupportedWriterTypeError(Schema.Type.LONG, Schema.Type.INT, Schema.Type.STRING)
        }
    }

    override fun decodeFloat(): Float {
        return when (currentWriterSchema.type) {
            Schema.Type.FLOAT -> primitive.float
            Schema.Type.DOUBLE -> primitive.double.toFloatExact()
            Schema.Type.STRING, Schema.Type.ENUM -> primitive.content.toFloat()
            else -> throw unsupportedWriterTypeError(Schema.Type.FLOAT, Schema.Type.DOUBLE, Schema.Type.STRING)
        }
    }

    override fun decodeDouble(): Double {
        return when (currentWriterSchema.type) {
            Schema.Type.DOUBLE -> primitive.double
            Schema.Type.FLOAT -> primitive.float.toDouble()
            Schema.Type.STRING, Schema.Type.ENUM -> primitive.content.toDouble()
            else -> throw unsupportedWriterTypeError(Schema.Type.DOUBLE, Schema.Type.FLOAT, Schema.Type.STRING)
        }
    }

    override fun decodeChar(): Char {
        return when (currentWriterSchema.type) {
            Schema.Type.INT -> intValue().toChar()
            Schema.Type.STRING, Schema.Type.ENUM -> primitive.content.single()
            else -> throw unsupportedWriterTypeError(Schema.Type.INT, Schema.Type.STRING)
        }
    }

    override fun decodeString(): String {
        return when (currentWriterSchema.type) {
            Schema.Type.STRING, Schema.Type.ENUM -> primitive.content
            Schema.Type.BYTES, Schema.Type.FIXED -> bytesValue().decodeToString()
            else -> throw unsupportedWriterTypeError(Schema.Type.STRING, Schema.Type.BYTES, Schema.Type.FIXED, Schema.Type.ENUM)
        }
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        return when (currentWriterSchema.type) {
            Schema.Type.STRING, Schema.Type.ENUM -> {
                val symbol = primitive.content
                enumDescriptor.getElementIndex(symbol).takeIf { it >= 0 }
                    ?: avro.enumResolver.getDefaultValueIndex(enumDescriptor)
                    ?: throw SerializationException("Unknown enum symbol '$symbol' for Enum '${enumDescriptor.serialName}'")
            }

            else -> throw unsupportedWriterTypeError(Schema.Type.ENUM, Schema.Type.STRING)
        }
    }

    override fun decodeBytes(): ByteArray {
        return when (currentWriterSchema.type) {
            Schema.Type.BYTES, Schema.Type.FIXED, Schema.Type.STRING, Schema.Type.ENUM -> bytesValue()
            else -> throw unsupportedWriterTypeError(Schema.Type.BYTES, Schema.Type.FIXED, Schema.Type.STRING)
        }
    }

    override fun decodeFixed(): GenericFixed {
        return when (currentWriterSchema.type) {
            Schema.Type.FIXED -> GenericData.Fixed(currentWriterSchema, bytesValue())
            else -> throw unsupportedWriterTypeError(Schema.Type.FIXED)
        }
    }

    /**
     * The default as the generic value the former path produced, for a non-null primitive, enum or fixed only.
     */
    @Deprecated("Use currentWriterSchema to get the schema and then decode the value using the appropriate decode* method")
    override fun decodeValue(): Any {
        return when (currentWriterSchema.type) {
            Schema.Type.BOOLEAN -> primitive.boolean
            Schema.Type.INT -> intValue()
            Schema.Type.LONG -> primitive.long
            Schema.Type.FLOAT -> primitive.float
            Schema.Type.DOUBLE -> primitive.double
            Schema.Type.STRING, Schema.Type.ENUM -> primitive.content
            Schema.Type.BYTES -> bytesValue()
            Schema.Type.FIXED -> decodeFixed()
            else -> throw UnsupportedOperationException("A default value of type ${currentWriterSchema.type} cannot be decoded as a generic value")
        }
    }
}

/**
 * Returns the schema a default value is decoded with: this schema, or, for a union, its first branch matching the kind
 * of [value] (Avro's rule is the first branch, but avro4k's generated unions put the default's branch first anyway).
 */
internal fun Schema.resolveDefaultBranch(value: JsonElement): Schema {
    if (type != Schema.Type.UNION) return this
    return when (value) {
        is JsonNull -> types.firstOrNull { it.type == Schema.Type.NULL } ?: this
        is JsonArray -> resolveDefaultBranch(value, Schema.Type.ARRAY)
        is JsonObject -> resolveDefaultBranch(value, Schema.Type.RECORD, Schema.Type.MAP)
        is JsonPrimitive -> resolveDefaultBranch(value, *PRIMITIVE_DEFAULT_TYPES)
    }
}

private val PRIMITIVE_DEFAULT_TYPES =
    arrayOf(
        Schema.Type.BYTES,
        Schema.Type.FIXED,
        Schema.Type.STRING,
        Schema.Type.ENUM,
        Schema.Type.BOOLEAN,
        Schema.Type.INT,
        Schema.Type.LONG,
        Schema.Type.FLOAT,
        Schema.Type.DOUBLE
    )

private fun Schema.resolveDefaultBranch(
    value: JsonElement,
    vararg expectedTypes: Schema.Type,
): Schema =
    types.firstOrNull { it.type in expectedTypes }
        ?: throw SerializationException(
            "Union type does not contain one of ${expectedTypes.asList()}, unable to convert default value '$value' for schema $this"
        )

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
    private val elementSchema: Schema,
) : SequentialJsonDefaultDecoder(avro, array.size, elementsPerItem = 1) {
    override fun elementDecoder(index: Int) = JsonDefaultDecoder(avro, array[index], elementSchema)
}

/**
 * A map is decoded as a sequence of `key, value` elements: even indexes are keys (strings), odd indexes their values.
 */
private class MapJsonDefaultDecoder(
    avro: Avro,
    map: JsonObject,
    private val valueSchema: Schema,
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
        val STRING_SCHEMA: Schema = Schema.create(Schema.Type.STRING)
    }
}

/**
 * A record default, decoded through the workflow of its reader schema: each field is read from the json object by its
 * name in that schema, and a field the json does not have decodes as `null`.
 */
private class RecordJsonDefaultDecoder(
    avro: Avro,
    private val record: JsonObject,
    private val recordSchema: Schema,
    descriptor: SerialDescriptor,
) : JsonDefaultCompositeDecoder(avro) {
    private val decodingSteps = avro.recordResolver.resolveFields(recordSchema, descriptor).decoding
    private var nextDecodingStepIndex = 0
    private lateinit var currentDecodingStep: DecodingStep.ValidatedDecodingStep

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        while (nextDecodingStepIndex < decodingSteps.size) {
            when (val step = decodingSteps[nextDecodingStepIndex++]) {
                is DecodingStep.IgnoreOptionalElement, is DecodingStep.SkipWriterField -> {}

                is DecodingStep.MissingElementValueFailure ->
                    throw SerializationException(
                        "Reader field '${descriptor.nonNullSerialName}.${descriptor.getElementName(step.elementIndex)}' " +
                            "has no corresponding field in the default value's schema $recordSchema"
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
                JsonDefaultDecoder(avro, record[recordSchema.fields[step.writerFieldIndex].name()] ?: JsonNull, step.schema)

            is DecodingStep.GetDefaultValue -> JsonDefaultDecoder(avro, step.defaultValue, step.schema)
        }
}

private class PolymorphicJsonDefaultDecoder(
    avro: Avro,
    descriptor: SerialDescriptor,
    schema: Schema,
    private val value: JsonElement,
) : AbstractPolymorphicDecoder(avro, descriptor, schema) {
    override fun tryFindSerialNameForUnion(
        namesAndAliasesToSerialName: Map<String, String>,
        schema: Schema,
    ): Pair<String, Schema>? {
        return schema.types.firstNotNullOfOrNull { tryFindSerialName(namesAndAliasesToSerialName, it) }
    }

    override fun newDecoder(chosenSchema: Schema): Decoder {
        return JsonDefaultDecoder(avro, value, chosenSchema)
    }
}