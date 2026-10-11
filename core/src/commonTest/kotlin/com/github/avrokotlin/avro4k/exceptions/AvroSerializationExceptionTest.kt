package com.github.avrokotlin.avro4k.exceptions

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroMalformedDataException
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.EnumSchema
import com.github.avrokotlin.avro4k.AvroSchema.IntSchema
import com.github.avrokotlin.avro4k.AvroSchema.LongSchema
import com.github.avrokotlin.avro4k.AvroSchema.NullSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.AvroSchema.StringSchema
import com.github.avrokotlin.avro4k.AvroSchema.UnionSchema
import com.github.avrokotlin.avro4k.AvroSchemaGenerationException
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.AvroSchemaParseException
import com.github.avrokotlin.avro4k.AvroSerializationException
import com.github.avrokotlin.avro4k.AvroValidationException
import com.github.avrokotlin.avro4k.FieldNamingStrategy
import com.github.avrokotlin.avro4k.MissingFieldsEncodingException
import com.github.avrokotlin.avro4k.Name
import com.github.avrokotlin.avro4k.decodeFromSource
import com.github.avrokotlin.avro4k.fromJsonString
import com.github.avrokotlin.avro4k.internal.codec.KotlinxIoEncoder
import com.github.avrokotlin.avro4k.internal.codec.MAX_BINARY_LENGTH
import com.github.avrokotlin.avro4k.schema
import com.github.avrokotlin.avro4k.serializer.AvroDuration
import com.github.avrokotlin.avro4k.serializer.AvroDurationParseException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.io.Buffer
import kotlinx.io.EOFException
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlin.jvm.JvmInline
import kotlin.test.Test

/**
 * Every failure avro4k reports is an [AvroSerializationException], so a `catch (e: SerializationException)` keeps
 * catching it, and its subclass tells the failure apart: one test group per family.
 */
class AvroSerializationExceptionTest {
    // ---- schema generation: the path of the element that failed ----

    @Test
    fun `a polymorphic property without any subclass fails with its path`() {
        val error = shouldThrow<AvroSchemaGenerationException> { Avro.schema<Wrapper>() }

        error.path shouldBe "$PACKAGE_NAME.Wrapper.shape"
        error.message shouldBe "Polymorphic descriptor '${Wrapper.serializer().descriptor.getElementDescriptor(0)}' must have at least one possible schema " +
            "(at '$PACKAGE_NAME.Wrapper.shape')"
        error.cause shouldBe null
        // still a SerializationException, as before
        shouldThrow<SerializationException> { Avro.schema<Wrapper>() }
    }

    @Test
    fun `the path goes through list items and map values and keys`() {
        shouldThrow<AvroSchemaGenerationException> { Avro.schema<InList>() }.path shouldBe "$PACKAGE_NAME.InList.shapes[]"
        shouldThrow<AvroSchemaGenerationException> { Avro.schema<InMapValue>() }.path shouldBe "$PACKAGE_NAME.InMapValue.byName{value}"
        shouldThrow<AvroSchemaGenerationException> { Avro.schema<InMapKey>() }.path shouldBe "$PACKAGE_NAME.InMapKey.byKey{key}"
    }

    @Test
    fun `a value class adds nothing to the path`() {
        shouldThrow<AvroSchemaGenerationException> { Avro.schema<InValueClass>() }.path shouldBe "$PACKAGE_NAME.InValueClass.box"
    }

    @Test
    fun `the path goes through the subclasses of a sealed type`() {
        shouldThrow<AvroSchemaGenerationException> { Avro.schema<InSealed>() }.path shouldBe "$PACKAGE_NAME.InSealed.animal<$PACKAGE_NAME.Animal.Dog>.shape"
    }

    @Test
    fun `the path of a recursive record is not repeated`() {
        shouldThrow<AvroSchemaGenerationException> { Avro.schema<Node>() }.path shouldBe "$PACKAGE_NAME.Node.shape"
    }

    @Test
    fun `a check of the schema model is wrapped with its exception as the cause`() {
        val snakeCase = Avro { fieldNamingStrategy = FieldNamingStrategy.Builtins.SnakeCase }

        val error = shouldThrow<AvroSchemaGenerationException> { snakeCase.schema<SameSnakeCaseNames>() }

        error.path shouldBe "$PACKAGE_NAME.SameSnakeCaseNames"
        error.cause.shouldBeInstanceOf<IllegalArgumentException>()
        error.message shouldBe "Record fields must be unique: 'foo_bar' is used twice in record '$PACKAGE_NAME.SameSnakeCaseNames' (at '$PACKAGE_NAME.SameSnakeCaseNames')"
    }

    // ---- schema parsing ----

    @Test
    fun `every invalid schema json fails as a parse failure with its message`() {
        parseFailure("null") shouldBe "type cannot be null json literal"
        parseFailure("\"com.example.Unknown\"") shouldBe "Unknown named type: com.example.Unknown"
        parseFailure("[[\"null\", \"int\"]]") shouldBe "Unions cannot contain nested unions"
        parseFailure("{\"type\": \"unknown\"}") shouldBe "Unknown schema type: unknown"
        parseFailure("{\"type\": \"record\", \"name\": \"R\"}") shouldBe "Missing 'fields' key"
        parseFailure("{\"type\": 1}") shouldBe "Not a string: 1"
    }

    @Test
    fun `invalid json and the checks of the schema model are wrapped with their exception as the cause`() {
        shouldThrow<AvroSchemaParseException> { AvroSchema.fromJsonString("{") }.cause.shouldBeInstanceOf<SerializationException>()
        val error = shouldThrow<AvroSchemaParseException> { AvroSchema.fromJsonString("{\"type\": \"enum\", \"name\": \"E\", \"symbols\": [\"A\", \"A\"]}") }
        error.cause.shouldBeInstanceOf<IllegalArgumentException>()
        error.message shouldBe "Enum symbols must be unique"
        // still an IllegalArgumentException, as before
        shouldThrow<IllegalArgumentException> { AvroSchema.fromJsonString("{") }
    }

    private fun parseFailure(json: String): String? = shouldThrow<AvroSchemaParseException> { AvroSchema.fromJsonString(json) }.message

    // ---- writer schema against the Kotlin type or value ----

    @Test
    fun `encoding with a writer schema that does not fit the type or the value is a mismatch`() {
        shouldThrow<AvroSchemaMismatchException> { Avro.encodeToByteArray(IntSchema(), Point.serializer(), Point(1, 2)) }
        shouldThrow<AvroSchemaMismatchException> { Avro.encodeToByteArray(IntSchema(), Long.serializer(), Long.MAX_VALUE) }
            .message shouldBe "Value ${Long.MAX_VALUE} is not a valid Int"
        shouldThrow<AvroSchemaMismatchException> { Avro.encodeToByteArray(IntSchema(), String.serializer(), "abc") }
            .cause.shouldBeInstanceOf<NumberFormatException>()
    }

    @Test
    fun `decoding a value that does not fit the type is a mismatch`() {
        val error = shouldThrow<AvroSchemaMismatchException> { Avro.decodeFromByteArray(StringSchema(), Int.serializer(), encodedString("abc")) }
        error.cause.shouldBeInstanceOf<NumberFormatException>()
        error.message shouldStartWith "Cannot convert the decoded value 'abc' of writer type string to Int"

        shouldThrow<AvroSchemaMismatchException> { Avro.decodeFromByteArray(StringSchema(), Char.serializer(), encodedString("ab")) }
        shouldThrow<AvroSchemaMismatchException> { Avro.decodeFromByteArray(StringSchema(), Boolean.serializer(), encodedString("tue")) }
    }

    @Test
    fun `a reader field missing from the writer schema without a default is a mismatch`() {
        val writerSchema = RecordSchema(Name("$PACKAGE_NAME.Point"), listOf(RecordSchema.Field("x", IntSchema())))

        shouldThrow<AvroSchemaMismatchException> { Avro.decodeFromByteArray(writerSchema, Point.serializer(), byteArrayOf(2)) }
    }

    @Test
    fun `an unknown enum symbol without a default is a mismatch`() {
        shouldThrow<AvroSchemaMismatchException> { Avro.decodeFromByteArray(StringSchema(), Suit.serializer(), encodedString("CLUBS")) }
            .message shouldStartWith "Unknown enum symbol name 'CLUBS'"
    }

    @Test
    fun `the existing exceptions are in the hierarchy`() {
        MissingFieldsEncodingException("missing").shouldBeInstanceOf<AvroSchemaMismatchException>()
        shouldThrow<AvroDurationParseException> { AvroDuration.parse("not a duration") }.shouldBeInstanceOf<AvroSerializationException>()
    }

    // ---- malformed input ----

    @Test
    fun `a truncated input is malformed with the end of input as the cause`() {
        val bytes = Avro.encodeToByteArray(Avro.schema<Point>(), Point.serializer(), Point(1000, 2000))
        val truncated = bytes.copyOf(bytes.size - 1)

        shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteArray(Avro.schema<Point>(), Point.serializer(), truncated) }
            .cause.shouldBeInstanceOf<EOFException>()
        val source = Buffer().apply { write(truncated) }
        shouldThrow<AvroMalformedDataException> { Avro.decodeFromSource(Avro.schema<Point>(), Point.serializer(), source) }
            .cause.shouldBeInstanceOf<EOFException>()
        // a long read at the very end of the input, from a source with no data left at all
        shouldThrow<AvroMalformedDataException> { Avro.decodeFromSource(LongSchema(), Long.serializer(), Buffer()) }
            .cause.shouldBeInstanceOf<EOFException>()
        shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteArray(LongSchema(), Long.serializer(), byteArrayOf()) }
            .cause.shouldBeInstanceOf<EOFException>()
    }

    @Test
    fun `an over-long varint is malformed`() {
        val tooLong = ByteArray(11) { 0xFF.toByte() }
        shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteArray(LongSchema(), Long.serializer(), tooLong) }
            .message shouldBe "Invalid varlong encoding: more bytes than expected"
        shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteArray(IntSchema(), Int.serializer(), tooLong) }
            .message shouldBe "Invalid varint encoding: more bytes than expected"
    }

    @Test
    fun `a negative or too large length is malformed`() {
        shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteArray(StringSchema(), String.serializer(), encodedLong(-3)) }
            .message shouldBe "Malformed data: negative length -3"
        shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteArray(StringSchema(), String.serializer(), encodedLong(MAX_BINARY_LENGTH + 1L)) }
    }

    @Test
    fun `a union or enum index out of range is malformed with or without validation`() {
        val nullableInt = UnionSchema(NullSchema(), IntSchema())
        val suit = EnumSchema(Name("$PACKAGE_NAME.Suit"), listOf("SPADES", "HEARTS"))
        for (avro in listOf(Avro, VALIDATING)) {
            shouldThrow<AvroMalformedDataException> { avro.decodeFromByteArray(nullableInt, Int.serializer().nullable, encodedLong(2)) }
            shouldThrow<AvroMalformedDataException> { avro.decodeFromByteArray(nullableInt, Int.serializer().nullable, encodedLong(-1)) }
            shouldThrow<AvroMalformedDataException> { avro.decodeFromByteArray(suit, Suit.serializer(), encodedLong(2)) }
            shouldThrow<AvroMalformedDataException> { avro.decodeFromByteArray(suit, String.serializer(), encodedLong(5)) }
        }
        shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteArray(nullableInt, Int.serializer().nullable, encodedLong(2)) }
            .message shouldBe "Malformed data: union index 2 out of range [0, 2) for union[null, int]"
        shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteArray(suit, Suit.serializer(), encodedLong(2)) }
            .message shouldBe "Malformed data: enum index 2 out of range [0, 2) for $PACKAGE_NAME.Suit"
    }

    @Test
    fun `trailing bytes are malformed`() {
        shouldThrow<AvroMalformedDataException> { Avro.decodeFromByteArray(IntSchema(), Int.serializer(), byteArrayOf(2, 2)) }
            .message shouldBe "Not all bytes were consumed during deserialization"
    }

    // ---- validation ----

    @Test
    fun `a serializer that does not follow the writer schema fails validation with the path`() {
        val schema = Avro.schema<OneSkippedInt>()
        val error = shouldThrow<AvroValidationException> { VALIDATING.encodeToByteArray(schema, OneSkippedInt.serializer(), OneSkippedInt(1, "last")) }

        error.path shouldBe "skipped"
        error.message shouldStartWith "Invalid call writeString() at 'skipped' for the writer schema"
    }

    private fun encodedString(value: String): ByteArray = encoded { writeString(value) }

    private fun encodedLong(value: Long): ByteArray = encoded { writeLong(value) }

    private fun encoded(write: KotlinxIoEncoder.() -> Unit): ByteArray {
        val buffer = Buffer()
        KotlinxIoEncoder(buffer).write()
        return buffer.readByteArray()
    }

    private companion object {
        val VALIDATING = Avro { validateSerialization = true }
    }
}

/** An open polymorphic type: no subclass is registered in the serializers module. */
private interface Shape

@Serializable
private data class Wrapper(val shape: Shape)

@Serializable
private data class InList(val shapes: List<Shape>)

@Serializable
private data class InMapValue(val byName: Map<String, Shape>)

@Serializable
private data class InMapKey(val byKey: Map<Point, Int>)

@Serializable
@JvmInline
private value class ShapeBox(val shape: Shape)

@Serializable
private data class InValueClass(val box: ShapeBox)

@Serializable
private sealed interface Animal {
    @Serializable
    data class Dog(val shape: Shape) : Animal
}

@Serializable
private data class InSealed(val animal: Animal)

@Serializable
private data class Node(val next: Node?, val shape: Shape)

@Serializable
private data class SameSnakeCaseNames(
    val fooBar: Int,
    @SerialName("foo_bar") val other: Int,
)

@Serializable
private data class Point(val x: Int, val y: Int)

@Serializable
private enum class Suit { SPADES, HEARTS }

/** Makes no call for its int field: what the validation catches, and nothing else does. */
@Serializable(with = OneSkippedIntSerializer::class)
private data class OneSkippedInt(val skipped: Int, val last: String)

private object OneSkippedIntSerializer : KSerializer<OneSkippedInt> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("$PACKAGE_NAME.OneSkippedInt") {
            element<Int>("skipped")
            element<String>("last")
        }

    override fun serialize(
        encoder: Encoder,
        value: OneSkippedInt,
    ) {
        encoder.encodeStructure(descriptor) { encodeStringElement(descriptor, 1, value.last) }
    }

    override fun deserialize(decoder: Decoder): OneSkippedInt = decoder.decodeStructure(descriptor) { OneSkippedInt(0, decodeStringElement(descriptor, 1)) }
}

internal const val PACKAGE_NAME = "com.github.avrokotlin.avro4k.exceptions"