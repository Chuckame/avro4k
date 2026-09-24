package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecimal
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.SomeEnum
import com.github.avrokotlin.avro4k.apacheSchema
import com.github.avrokotlin.avro4k.decodeFromGenericDataWith
import com.github.avrokotlin.avro4k.decodeWith
import com.github.avrokotlin.avro4k.encodeWith
import com.github.avrokotlin.avro4k.internal.decoder.JsonDefaultDecoder
import com.github.avrokotlin.avro4k.internal.decoder.generic.AvroValueGenericDecoder
import com.github.avrokotlin.avro4k.internal.schema.CHAR_LOGICAL_TYPE_NAME
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json
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
import kotlinx.serialization.serializer
import org.apache.avro.Schema
import org.apache.avro.SchemaBuilder
import org.apache.avro.generic.GenericData
import java.math.BigDecimal

/**
 * M3-08: missing fields are decoded from their json default by [JsonDefaultDecoder], instead of converting the json to
 * Apache `GenericData` values and decoding those with the generic decoder.
 *
 * The differential tests pin the new decoder to that former path, kept here as the oracle: [formerConvert] is the
 * conversion `RecordResolver` used to do, and [AvroValueGenericDecoder] the decoder it fed. They must agree on every
 * value, and fail together.
 */
internal class JsonDefaultDecoderTest : StringSpec({
    "primitives, strings and bytes decode as the former GenericData path did" {
        assertSameAsFormerPath(String.serializer(), "hello", "\"hello\"", "12", "true", "null")
        assertSameAsFormerPath(String.serializer().nullable, "hello", "null")
        assertSameAsFormerPath(Int.serializer(), "1", "\"12\"", "-3", "1.5", "hello", "null")
        assertSameAsFormerPath(Int.serializer().nullable, "1", "null")
        assertSameAsFormerPath(Long.serializer(), "1", "12345678901", "\"7\"")
        assertSameAsFormerPath(Byte.serializer(), "1", "300")
        assertSameAsFormerPath(Short.serializer(), "1", "70000")
        assertSameAsFormerPath(Boolean.serializer(), "true", "false", "\"true\"", "1")
        assertSameAsFormerPath(Double.serializer(), "1.23", "1", "\"2.5\"")
        assertSameAsFormerPath(Float.serializer(), "1.5", "\"2.5\"")
        assertSameAsFormerPath(Char.serializer(), "a", "ab", "\"b\"")
        assertSameAsFormerPath(Char.serializer().nullable, "a", "null")
        assertSameAsFormerPath(ByteArraySerializer(), "abc", "\u0000", "é", "\"\"")
    }

    "enums decode as the former GenericData path did" {
        assertSameAsFormerPath(serializer<SomeEnum>(), "B", "\"C\"", "Z")
        assertSameAsFormerPath(serializer<SomeEnum?>(), "B", "null")
        assertSameAsFormerPath(serializer<EnumWithDefault>(), "Y", "unknown")
    }

    "collections decode as the former GenericData path did" {
        assertSameAsFormerPath(ListSerializer(Int.serializer()), "[]", "[1,2,3]", "{}")
        assertSameAsFormerPath(ListSerializer(String.serializer()).nullable, "[\"a\"]", "null")
        assertSameAsFormerPath(ListSerializer(serializer<Foo>()), "[{\"content\":\"bar\"}]")
        assertSameAsFormerPath(MapSerializer(String.serializer(), Int.serializer()), "{}", "{\"a\":1,\"b\":2}", "[]")
        assertSameAsFormerPath(MapSerializer(Int.serializer(), String.serializer()), "{\"1\":\"a\"}")
        assertSameAsFormerPath(MapSerializer(String.serializer(), serializer<Foo>().nullable).nullable, "{\"a\":{\"content\":\"x\"},\"b\":null}", "null")
    }

    "records, value classes and polymorphic types decode as the former GenericData path did" {
        assertSameAsFormerPath(serializer<Foo>(), "{\"content\":\"foo\"}", "{\"content\":1}", "\"foo\"")
        assertSameAsFormerPath(serializer<Foo?>(), "{\"content\":\"foo\"}", "null")
        assertSameAsFormerPath(serializer<Nested>(), "{\"id\":1,\"foo\":{\"content\":\"a\"},\"items\":[{\"content\":\"b\"}],\"label\":\"l\"}")
        // a field the json object does not have decodes as null: fine for a nullable field, a failure otherwise
        assertSameAsFormerPath(serializer<Nested>(), "{\"id\":1,\"foo\":{\"content\":\"a\"},\"items\":[]}", "{\"foo\":{\"content\":\"a\"},\"items\":[]}")
        assertSameAsFormerPath(serializer<WrappedString>(), "hello")
        assertSameAsFormerPath(serializer<Shape>(), "{\"radius\":2}")
    }

    "fixed, decimals and chars read their schema from the annotated field, as the former GenericData path did" {
        val holderSchema = Avro.apacheSchema<AnnotatedHolder>()

        fun fieldSchema(name: String) = holderSchema.getField(name).schema()

        assertSameAsFormerPath(ByteArraySerializer(), fieldSchema("fixed"), "abcd", "é")
        assertSameAsFormerPath(ByteArraySerializer().nullable, fieldSchema("fixedNullable"), "abcd", "null")
        assertSameAsFormerPath(Avro.serializersModule.serializer<BigDecimal>(), fieldSchema("decimalBytes"), "\u0000", "\u0001\u0002")
        assertSameAsFormerPath(Avro.serializersModule.serializer<BigDecimal>(), fieldSchema("decimalFixed"), "\u0000")
        assertSameAsFormerPath(Avro.serializersModule.serializer<BigDecimal?>(), fieldSchema("decimalNullable"), "\u0000", "null")
        assertSameAsFormerPath(Char.serializer().nullable, fieldSchema("charNullable"), "a", "null")
    }

    "every decode method accepts the same schema types as the former GenericData path" {
        val schemas =
            listOf(
                Schema.create(Schema.Type.STRING),
                Schema.create(Schema.Type.BYTES),
                Schema.createFixed("F", null, null, 3),
                Schema.createEnum("E", null, null, listOf("A", "B", "C")),
                Schema.create(Schema.Type.BOOLEAN),
                Schema.create(Schema.Type.INT),
                Avro.apacheSchema(Char.serializer()),
                Schema.create(Schema.Type.LONG),
                Schema.create(Schema.Type.FLOAT),
                Schema.create(Schema.Type.DOUBLE),
                Schema.create(Schema.Type.NULL)
            )
        val decodeMethods: Map<String, (AvroDecoder) -> Any?> =
            mapOf(
                "decodeBoolean" to { it.decodeBoolean() },
                "decodeByte" to { it.decodeByte() },
                "decodeShort" to { it.decodeShort() },
                "decodeInt" to { it.decodeInt() },
                "decodeLong" to { it.decodeLong() },
                "decodeFloat" to { it.decodeFloat() },
                "decodeDouble" to { it.decodeDouble() },
                "decodeChar" to { it.decodeChar() },
                "decodeString" to { it.decodeString() },
                "decodeEnum" to { it.decodeEnum(serializer<SomeEnum>().descriptor) },
                "decodeBytes" to { it.decodeBytes() },
                "decodeFixed" to { it.decodeFixed() },
                "decodeValue" to {
                    @Suppress("DEPRECATION")
                    it.decodeValue()
                }
            )
        for (schema in schemas) {
            for ((name, decode) in decodeMethods) {
                for (default in listOf("1", "true", "A", "3000000000", "1.5", "1.1", "é")) {
                    withClue("$name of '$default' against $schema") {
                        assertSameAsFormerPath(schema, default, decode)
                    }
                }
            }
        }
    }

    "a union default resolves to the branch matching the json kind, so currentWriterSchema is never a union" {
        val union =
            SchemaBuilder.unionOf().nullType().and().array().items().intType()
                .and().map().values().stringType().and().stringType().endUnion()

        JsonDefaultDecoder(Avro, JsonNull, union).currentWriterSchema.type shouldBe Schema.Type.NULL
        JsonDefaultDecoder(Avro, JsonArray(emptyList()), union).currentWriterSchema.type shouldBe Schema.Type.ARRAY
        JsonDefaultDecoder(Avro, JsonObject(emptyMap()), union).currentWriterSchema.type shouldBe Schema.Type.MAP
        JsonDefaultDecoder(Avro, JsonPrimitive(1), union).currentWriterSchema.type shouldBe Schema.Type.STRING
    }

    "an inline element reading its enum default with decodeEnum gets the symbol's index" {
        // RecordDirectDecoder.decodeEnum used to decode a default with Int.serializer(), so "B".toInt() failed
        val writerSchema = SchemaBuilder.record("EnumHolder").fields().requiredString("name").endRecord()
        val bytes = Avro.encodeWith(writerSchema, serializer<NameOnly>(), NameOnly("abc"))

        Avro.decodeWith(writerSchema, serializer<EnumHolder>(), bytes) shouldBe EnumHolder("abc", SomeEnum.B)
    }

    "missing fields decode their defaults through the generic data path too" {
        val writerSchema = SchemaBuilder.record("container").fields().requiredString("name").endRecord()
        val writerRecord = GenericData.Record(writerSchema).apply { put("name", "abc") }

        Avro.decodeFromGenericDataWith(writerSchema, serializer<ContainerWithDefaults>(), writerRecord) shouldBe
            ContainerWithDefaults(
                name = "abc",
                str = "hello",
                strNullable = null,
                int = 1,
                char = 'a',
                enum = SomeEnum.B,
                foo = Foo("foo"),
                foos = listOf(Foo("bar")),
                map = mapOf("k" to 1),
                decimal = BigDecimal.ZERO,
                implicitNull = null,
                implicitEmptyList = emptyList()
            )
    }
}) {
    @Serializable
    @SerialName("Foo")
    private data class Foo(val content: String)

    @Serializable
    @SerialName("Nested")
    private data class Nested(
        val id: Int,
        val foo: Foo,
        val items: List<Foo>,
        val label: String?,
    )

    @JvmInline
    @Serializable
    private value class WrappedString(val value: String)

    @Serializable
    private sealed interface Shape

    @Serializable
    @SerialName("Circle")
    private data class Circle(val radius: Int) : Shape

    @Serializable
    @SerialName("Square")
    private data class Square(val side: Int) : Shape

    @Serializable
    private enum class EnumWithDefault {
        X,
        Y,

        @com.github.avrokotlin.avro4k.AvroEnumDefault
        Z,
    }

    @Serializable
    @SerialName("EnumHolder")
    private data class NameOnly(val name: String)

    @Serializable(with = EnumHolderSerializer::class)
    private data class EnumHolder(
        val name: String,
        val enum: SomeEnum,
    )

    /**
     * Reads its enum field through [CompositeDecoder.decodeInlineElement], which hands back the record decoder itself.
     */
    private object EnumHolderSerializer : KSerializer<EnumHolder> {
        private val enumDescriptor = serializer<SomeEnum>().descriptor

        override val descriptor =
            buildClassSerialDescriptor("EnumHolder") {
                element<String>("name")
                element("enum", enumDescriptor, annotations = listOf(AvroDefault("B")))
            }

        override fun deserialize(decoder: Decoder) =
            decoder.decodeStructure(descriptor) {
                var name = ""
                var enum: SomeEnum? = null
                while (true) {
                    when (val index = decodeElementIndex(descriptor)) {
                        0 -> name = decodeStringElement(descriptor, 0)
                        1 -> enum = SomeEnum.entries[decodeInlineElement(descriptor, 1).decodeEnum(enumDescriptor)]
                        CompositeDecoder.DECODE_DONE -> break
                        else -> error("Unexpected index $index")
                    }
                }
                EnumHolder(name, enum!!)
            }

        override fun serialize(encoder: Encoder, value: EnumHolder) = throw UnsupportedOperationException()
    }

    @Serializable
    private data class AnnotatedHolder(
        @AvroFixed(4) val fixed: ByteArray,
        @AvroFixed(4) val fixedNullable: ByteArray?,
        @Contextual @AvroDecimal(scale = 0, precision = 8) val decimalBytes: BigDecimal,
        @Contextual @AvroFixed(16) @AvroDecimal(scale = 0, precision = 8) val decimalFixed: BigDecimal,
        @Contextual @AvroDecimal(scale = 0, precision = 8) val decimalNullable: BigDecimal?,
        val charNullable: Char?,
    )

    @Serializable
    @SerialName("container")
    private data class ContainerWithDefaults(
        val name: String,
        @AvroDefault("hello") val str: String,
        @AvroDefault("null") val strNullable: String?,
        @AvroDefault("1") val int: Int,
        @AvroDefault("a") val char: Char,
        @AvroDefault("B") val enum: SomeEnum,
        @AvroDefault("""{"content":"foo"}""") val foo: Foo,
        @AvroDefault("""[{"content":"bar"}]""") val foos: List<Foo>,
        @AvroDefault("""{"k":1}""") val map: Map<String, Int>,
        @Contextual @AvroDecimal(scale = 0, precision = 8) @AvroDefault("\u0000") val decimal: BigDecimal,
        val implicitNull: String?,
        val implicitEmptyList: List<Int>,
    )
}

private fun <T> assertSameAsFormerPath(
    serializer: KSerializer<T>,
    vararg defaults: String,
) = assertSameAsFormerPath(serializer, Avro.apacheSchema(serializer), *defaults)

private fun <T> assertSameAsFormerPath(
    serializer: KSerializer<T>,
    schema: Schema,
    vararg defaults: String,
) {
    for (default in defaults) {
        withClue("default '$default' decoded with ${serializer.descriptor.serialName} against $schema") {
            assertSameAsFormerPath(schema, default) { it.decodeSerializableValue(serializer) }
        }
    }
}

private fun assertSameAsFormerPath(
    schema: Schema,
    default: String,
    decode: (AvroDecoder) -> Any?,
) {
    val json = parseDefault(default)
    val former = runCatching { decode(AvroValueGenericDecoder(Avro, json.formerConvert(schema), schema)) }
    val current = runCatching { decode(JsonDefaultDecoder(Avro, json, schema)) }
    withClue("former: $former, current: $current") {
        current.isSuccess shouldBe former.isSuccess
        if (former.isSuccess) {
            current.getOrNull() shouldBe former.getOrNull()
        }
    }
}

/**
 * As `RecordResolver` reads an `@AvroDefault`.
 */
private fun parseDefault(value: String): JsonElement = if (value.isStartingAsJson()) Json.parseToJsonElement(value) else JsonPrimitive(value)

/**
 * The former `RecordResolver.convertDefaultToObject`, verbatim but for its name: the oracle of this test.
 */
private fun JsonElement.formerConvert(schema: Schema): Any? =
    when (this) {
        is JsonArray ->
            when (schema.type) {
                Schema.Type.ARRAY -> this.map { it.formerConvert(schema.elementType) }
                Schema.Type.UNION -> this.formerConvert(schema.formerResolveUnion(this, Schema.Type.ARRAY))
                else -> throw SerializationException("Not a valid array value for schema $schema: $this")
            }

        is JsonNull -> null

        is JsonObject ->
            when (schema.type) {
                Schema.Type.RECORD -> {
                    GenericData.Record(schema).apply {
                        entries.forEach { (fieldName, value) ->
                            val schemaField = schema.getField(fieldName)
                            put(schemaField.pos(), value.formerConvert(schemaField.schema()))
                        }
                    }
                }

                Schema.Type.MAP -> entries.associate { (key, value) -> key to value.formerConvert(schema.valueType) }

                Schema.Type.UNION -> this.formerConvert(schema.formerResolveUnion(this, Schema.Type.RECORD, Schema.Type.MAP))

                else -> throw SerializationException("Not a valid record value for schema $schema: $this")
            }

        is JsonPrimitive ->
            when (schema.type) {
                Schema.Type.BYTES -> this.content.toByteArray()

                Schema.Type.FIXED -> GenericData.Fixed(schema, this.content.toByteArray())

                Schema.Type.STRING -> this.content

                Schema.Type.ENUM -> this.content

                Schema.Type.BOOLEAN -> this.boolean

                Schema.Type.INT ->
                    when (schema.logicalType?.name) {
                        CHAR_LOGICAL_TYPE_NAME -> this.content.single().code
                        else -> this.int
                    }

                Schema.Type.LONG -> this.long

                Schema.Type.FLOAT -> this.float

                Schema.Type.DOUBLE -> this.double

                Schema.Type.UNION ->
                    this.formerConvert(
                        schema.formerResolveUnion(
                            this,
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
                    )

                else -> throw SerializationException("Not a valid primitive value for schema $schema: $this")
            }
    }

private fun Schema.formerResolveUnion(
    value: JsonElement?,
    vararg expectedTypes: Schema.Type,
): Schema {
    val index = types.indexOfFirst { it.type in expectedTypes }
    if (index < 0) {
        throw SerializationException("Union type does not contain one of ${expectedTypes.asList()}, unable to convert default value '$value' for schema $this")
    }
    return types[index]
}