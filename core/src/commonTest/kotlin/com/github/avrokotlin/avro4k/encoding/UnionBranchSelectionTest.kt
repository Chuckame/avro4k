package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroEncoder
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.Name
import com.github.avrokotlin.avro4k.ResolvedSchema
import com.github.avrokotlin.avro4k.internal.codec.ByteArrayBinaryWriter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test

/**
 * Every Kotlin primitive written to every union shape that can take it: the branch of the value's own type when the
 * union has one, whatever its position, else the first compatible branch in the order each type prefers, with the value
 * converted to it. Of two branches of one type (two fixed of one size, two enums with the symbol), the first is chosen.
 * The value written to its own type outside a union, the conversions outside a union, and the errors (their messages
 * pinned) are covered too: the encoder writes the natural case and the rest on separate paths.
 *
 * The expected bytes are written with the codec: a union index, then the value as the selected branch's type.
 */
class UnionBranchSelectionTest {
    @Test
    fun `a boolean selects its own branch first then a string`() {
        Boolean.serializer().encodes(true, BOOLEAN) { writeBoolean(true) }
        Boolean.serializer().encodes(true, union(NULL, BOOLEAN), branch = 1) { writeBoolean(true) }
        Boolean.serializer().encodes(false, union(STRING, BOOLEAN), branch = 1) { writeBoolean(false) }
        Boolean.serializer().encodes(true, union(NULL, STRING), branch = 1) { writeString("true") }
        Boolean.serializer().encodes(false, STRING) { writeString("false") }
        Boolean.serializer().failsWith(true, union(NULL, INT))
        Boolean.serializer().failsWith(true, INT)
    }

    @Test
    fun `an int a short and a byte select int first then long then string`() {
        for ((serializer, value) in listOf(Int.serializer() to 42, Short.serializer() to 42.toShort(), Byte.serializer() to 42.toByte())) {
            @Suppress("UNCHECKED_CAST")
            serializer as KSerializer<Any>
            withClue(serializer.descriptor.serialName) {
                serializer.encodes(value, INT) { writeInt(42) }
                serializer.encodes(value, union(NULL, INT), branch = 1) { writeInt(42) }
                serializer.encodes(value, union(STRING, LONG, INT), branch = 2) { writeInt(42) }
                serializer.encodes(value, union(NULL, LONG), branch = 1) { writeLong(42L) }
                serializer.encodes(value, union(STRING, LONG), branch = 1) { writeLong(42L) }
                serializer.encodes(value, union(NULL, STRING), branch = 1) { writeString("42") }
                serializer.encodes(value, LONG) { writeLong(42L) }
                serializer.encodes(value, STRING) { writeString("42") }
                serializer.failsWith(value, union(NULL, DOUBLE))
                serializer.failsWith(value, DOUBLE)
            }
        }
    }

    @Test
    fun `a long selects long first then int then string`() {
        Long.serializer().encodes(7L, LONG) { writeLong(7L) }
        Long.serializer().encodes(7L, union(NULL, LONG), branch = 1) { writeLong(7L) }
        Long.serializer().encodes(7L, union(STRING, INT, LONG), branch = 2) { writeLong(7L) }
        Long.serializer().encodes(7L, union(NULL, INT), branch = 1) { writeInt(7) }
        Long.serializer().encodes(7L, union(STRING, INT), branch = 1) { writeInt(7) }
        Long.serializer().encodes(7L, union(NULL, STRING), branch = 1) { writeString("7") }
        Long.serializer().encodes(7L, INT) { writeInt(7) }
        Long.serializer().encodes(7L, STRING) { writeString("7") }
        Long.serializer().failsWith(Long.MAX_VALUE, union(NULL, INT))
        Long.serializer().failsWith(7L, union(NULL, FLOAT))
    }

    @Test
    fun `a float selects float first then double then string`() {
        Float.serializer().encodes(1.5f, FLOAT) { writeFloat(1.5f) }
        Float.serializer().encodes(1.5f, union(NULL, FLOAT), branch = 1) { writeFloat(1.5f) }
        Float.serializer().encodes(1.5f, union(STRING, DOUBLE, FLOAT), branch = 2) { writeFloat(1.5f) }
        Float.serializer().encodes(1.5f, union(NULL, DOUBLE), branch = 1) { writeDouble(1.5) }
        Float.serializer().encodes(1.5f, union(STRING, DOUBLE), branch = 1) { writeDouble(1.5) }
        Float.serializer().encodes(1.5f, union(NULL, STRING), branch = 1) { writeString("1.5") }
        Float.serializer().encodes(1.5f, DOUBLE) { writeDouble(1.5) }
        Float.serializer().encodes(1.5f, STRING) { writeString("1.5") }
        Float.serializer().failsWith(1.5f, union(NULL, INT))
        Float.serializer().failsWith(1.5f, LONG)
    }

    @Test
    fun `a double selects double first then float then string`() {
        Double.serializer().encodes(2.5, DOUBLE) { writeDouble(2.5) }
        Double.serializer().encodes(2.5, union(NULL, DOUBLE), branch = 1) { writeDouble(2.5) }
        Double.serializer().encodes(2.5, union(STRING, FLOAT, DOUBLE), branch = 2) { writeDouble(2.5) }
        Double.serializer().encodes(2.5, union(NULL, FLOAT), branch = 1) { writeFloat(2.5f) }
        Double.serializer().encodes(2.5, union(STRING, FLOAT), branch = 1) { writeFloat(2.5f) }
        Double.serializer().encodes(2.5, union(NULL, STRING), branch = 1) { writeString("2.5") }
        Double.serializer().encodes(2.5, FLOAT) { writeFloat(2.5f) }
        Double.serializer().encodes(2.5, STRING) { writeString("2.5") }
        Double.serializer().failsWith(2.5, union(NULL, LONG))
        Double.serializer().failsWith(0.1, union(NULL, FLOAT))
        Double.serializer().failsWith(0.1, FLOAT)
    }

    @Test
    fun `a char selects int first then string`() {
        Char.serializer().encodes('c', INT) { writeInt('c'.code) }
        Char.serializer().encodes('c', union(NULL, INT), branch = 1) { writeInt('c'.code) }
        Char.serializer().encodes('c', union(STRING, INT), branch = 1) { writeInt('c'.code) }
        Char.serializer().encodes('c', union(NULL, STRING), branch = 1) { writeString("c") }
        Char.serializer().encodes('c', STRING) { writeString("c") }
        Char.serializer().failsWith('c', union(NULL, LONG))
        Char.serializer().failsWith('c', LONG)
    }

    @Test
    fun `a string selects string then bytes then fixed then enum then boolean int long float and double`() {
        String.serializer().encodes("abc", STRING) { writeString("abc") }
        String.serializer().encodes("abc", union(NULL, STRING), branch = 1) { writeString("abc") }
        String.serializer().encodes("abc", union(DOUBLE, BYTES, FIXED_ABC_3, STRING), branch = 3) { writeString("abc") }
        String.serializer().encodes("abc", union(NULL, BYTES), branch = 1) { writeBytes("abc".encodeToByteArray()) }
        String.serializer().encodes("abc", union(DOUBLE, FIXED_ABC_3, BYTES), branch = 2) { writeBytes("abc".encodeToByteArray()) }
        String.serializer().encodes("abc", union(NULL, FIXED_2, FIXED_ABC_3), branch = 2) { writeFixed("abc".encodeToByteArray()) }
        String.serializer().encodes("abc", union(INT, SUIT_ABC, FIXED_ABC_3), branch = 2) { writeFixed("abc".encodeToByteArray()) }
        String.serializer().encodes("abc", union(NULL, SUIT_ABC), branch = 1) { writeEnum(0) }
        String.serializer().encodes("abc", union(BOOLEAN, SUIT_ABC), branch = 1) { writeEnum(0) }
        String.serializer().encodes("true", union(NULL, BOOLEAN), branch = 1) { writeBoolean(true) }
        String.serializer().encodes("42", union(DOUBLE, LONG, INT), branch = 2) { writeInt(42) }
        String.serializer().encodes("42", union(DOUBLE, FLOAT, LONG), branch = 2) { writeLong(42L) }
        String.serializer().encodes("1.5", union(NULL, DOUBLE, FLOAT), branch = 2) { writeFloat(1.5f) }
        String.serializer().encodes("2.5", union(NULL, DOUBLE), branch = 1) { writeDouble(2.5) }
        String.serializer().encodes("abc", BYTES) { writeBytes("abc".encodeToByteArray()) }
        String.serializer().encodes("abc", FIXED_ABC_3) { writeFixed("abc".encodeToByteArray()) }
        String.serializer().encodes("abc", SUIT_ABC) { writeEnum(0) }
        String.serializer().encodes("false", BOOLEAN) { writeBoolean(false) }
        String.serializer().encodes("42", INT) { writeInt(42) }
        String.serializer().encodes("42", LONG) { writeLong(42L) }
        String.serializer().encodes("1.5", FLOAT) { writeFloat(1.5f) }
        String.serializer().encodes("2.5", DOUBLE) { writeDouble(2.5) }
        String.serializer().failsWith("abc", union(NULL, RECORD))
        String.serializer().failsWith("abc", RECORD)
        String.serializer().failsWith("abc", union(NULL, INT))
        String.serializer().failsWith("abc", FIXED_2)
    }

    @Test
    fun `a string selects the first of two fixed of its size and the first of two enums with its symbol`() {
        String.serializer().encodes("abc", union(NULL, FIXED_ABC_3, FIXED_XYZ_3), branch = 1) { writeFixed("abc".encodeToByteArray()) }
        String.serializer().encodes("abc", union(NULL, FIXED_XYZ_3, FIXED_ABC_3), branch = 1) { writeFixed("abc".encodeToByteArray()) }
        String.serializer().encodes("abc", union(NULL, SUIT_ABC, LETTERS_ABC), branch = 1) { writeEnum(0) }
        String.serializer().encodes("abc", union(NULL, LETTERS_ABC, SUIT_ABC), branch = 1) { writeEnum(1) }
        String.serializer().encodes("xyz", union(NULL, SUIT_ABC, LETTERS_ABC), branch = 2) { writeEnum(2) }
    }

    @Test
    fun `bytes select bytes first then string then a fixed of their size`() {
        val abc = "abc".encodeToByteArray()
        BYTE_ARRAY.encodes(abc, BYTES) { writeBytes(abc) }
        BYTE_ARRAY.encodes(abc, union(NULL, BYTES), branch = 1) { writeBytes(abc) }
        BYTE_ARRAY.encodes(abc, union(FIXED_ABC_3, STRING, BYTES), branch = 2) { writeBytes(abc) }
        BYTE_ARRAY.encodes(abc, union(NULL, STRING), branch = 1) { writeString(abc) }
        BYTE_ARRAY.encodes(abc, union(FIXED_ABC_3, STRING), branch = 1) { writeString(abc) }
        BYTE_ARRAY.encodes(abc, union(NULL, FIXED_2, FIXED_ABC_3), branch = 2) { writeFixed(abc) }
        BYTE_ARRAY.encodes(abc, union(NULL, FIXED_ABC_3, FIXED_XYZ_3), branch = 1) { writeFixed(abc) }
        BYTE_ARRAY.encodes(abc, union(NULL, FIXED_XYZ_3, FIXED_ABC_3), branch = 1) { writeFixed(abc) }
        BYTE_ARRAY.encodes(abc, STRING) { writeString(abc) }
        BYTE_ARRAY.encodes(abc, FIXED_ABC_3) { writeFixed(abc) }
        BYTE_ARRAY.failsWith(abc, union(NULL, FIXED_2))
        BYTE_ARRAY.failsWith(abc, FIXED_2)
        BYTE_ARRAY.failsWith(abc, INT)
    }

    @Test
    fun `a fixed selects a fixed of its size first then bytes then string`() {
        val abc = "abc".encodeToByteArray()
        FIXED_VALUE.encodes(abc, FIXED_ABC_3) { writeFixed(abc) }
        FIXED_VALUE.encodes(abc, union(NULL, FIXED_ABC_3), branch = 1) { writeFixed(abc) }
        FIXED_VALUE.encodes(abc, union(STRING, BYTES, FIXED_ABC_3), branch = 2) { writeFixed(abc) }
        FIXED_VALUE.encodes(abc, union(NULL, FIXED_2, FIXED_ABC_3), branch = 2) { writeFixed(abc) }
        FIXED_VALUE.encodes(abc, union(NULL, FIXED_ABC_3, FIXED_XYZ_3), branch = 1) { writeFixed(abc) }
        FIXED_VALUE.encodes(abc, union(NULL, FIXED_XYZ_3, FIXED_ABC_3), branch = 1) { writeFixed(abc) }
        FIXED_VALUE.encodes(abc, union(STRING, FIXED_2, BYTES), branch = 2) { writeBytes(abc) }
        FIXED_VALUE.encodes(abc, union(NULL, STRING), branch = 1) { writeString(abc) }
        FIXED_VALUE.encodes(abc, BYTES) { writeBytes(abc) }
        FIXED_VALUE.encodes(abc, STRING) { writeString(abc) }
        FIXED_VALUE.failsWith(abc, union(NULL, FIXED_2))
        FIXED_VALUE.failsWith(abc, FIXED_2)
        FIXED_VALUE.failsWith(abc, LONG)
    }

    @Test
    fun `an enum selects its named branch first then a string`() {
        WSuit.serializer().encodes(WSuit.HEARTS, SUIT) { writeEnum(2) }
        WSuit.serializer().encodes(WSuit.HEARTS, union(NULL, SUIT), branch = 1) { writeEnum(2) }
        WSuit.serializer().encodes(WSuit.HEARTS, union(STRING, LETTERS_ABC, SUIT), branch = 2) { writeEnum(2) }
        WSuit.serializer().encodes(WSuit.HEARTS, union(NULL, STRING), branch = 1) { writeString("HEARTS") }
        WSuit.serializer().encodes(WSuit.HEARTS, union(LETTERS_ABC, STRING), branch = 1) { writeString("HEARTS") }
        WSuit.serializer().encodes(WSuit.HEARTS, STRING) { writeString("HEARTS") }
        WSuit.serializer().failsWith(WSuit.HEARTS, union(NULL, INT))
        WSuit.serializer().failsWith(WSuit.HEARTS, LETTERS_ABC)
        WSuit.serializer().failsWith(WSuit.HEARTS, INT)
    }

    @Test
    fun `no compatible branch in a union names the type and its fallbacks in the order tried`() {
        Int.serializer().errorMessage(42, union(NULL, BOOLEAN)) shouldBe
            "Unsupported schema 'union[null, boolean]' for encoded type of int, and also not matching to any compatible type " +
            "(one of LONG, STRING).. Actual schema: [\"null\",\"boolean\"]"
        Long.serializer().errorMessage(7L, union(NULL, BOOLEAN)) shouldBe
            "Unsupported schema 'union[null, boolean]' for encoded type of long, and also not matching to any compatible type " +
            "(one of INT, STRING).. Actual schema: [\"null\",\"boolean\"]"
        String.serializer().errorMessage("abc", union(NULL, RECORD)) shouldBe
            "Unsupported schema 'union[null, R]' for encoded type of boolean, and also not matching to any compatible type " +
            "(one of INT, LONG, FLOAT, DOUBLE, STRING, BYTES, FIXED, ENUM).. Actual schema: " +
            "[\"null\",{\"type\":\"record\",\"name\":\"R\",\"fields\":[{\"name\":\"x\",\"type\":\"int\"}]}]"
    }

    @Test
    fun `an incompatible writer type and a failed conversion keep their messages`() {
        Long.serializer().errorMessage(7L, BOOLEAN) shouldBe
            "Unsupported schema 'boolean' for encoded type of int, and also not matching to any compatible type " +
            "(one of LONG, STRING).. Actual schema: \"boolean\""
        String.serializer().errorMessage("abc", union(NULL, INT)) shouldStartWith
            "Cannot convert the String value 'abc' to the writer type int: "
        WSuit.serializer().errorMessage(WSuit.HEARTS, union(NULL, INT)) shouldBe
            "Named schema Suit not found in union. Also no compatible type found (one of STRING). Actual schema: [\"null\",\"int\"]"
    }

    @Serializable
    @SerialName("Suit")
    private enum class WSuit { CLUBS, DIAMONDS, HEARTS, SPADES }

    private companion object {
        val AVROS = listOf(Avro, Avro { validateSerialization = true })

        val NULL = AvroSchema.NullSchema()
        val BOOLEAN = AvroSchema.BooleanSchema()
        val INT = AvroSchema.IntSchema()
        val LONG = AvroSchema.LongSchema()
        val FLOAT = AvroSchema.FloatSchema()
        val DOUBLE = AvroSchema.DoubleSchema()
        val STRING = AvroSchema.StringSchema()
        val BYTES = AvroSchema.BytesSchema()
        val FIXED_2 = AvroSchema.FixedSchema(Name("Fixed2"), 2)
        val FIXED_ABC_3 = AvroSchema.FixedSchema(Name("FixedAbc"), 3)
        val FIXED_XYZ_3 = AvroSchema.FixedSchema(Name("FixedXyz"), 3)
        val SUIT = AvroSchema.EnumSchema(Name("Suit"), listOf("CLUBS", "DIAMONDS", "HEARTS", "SPADES"))
        val SUIT_ABC = AvroSchema.EnumSchema(Name("SuitAbc"), listOf("abc", "def"))
        val LETTERS_ABC = AvroSchema.EnumSchema(Name("Letters"), listOf("uvw", "abc", "xyz"))
        val RECORD = AvroSchema.RecordSchema(Name("R"), listOf(AvroSchema.RecordSchema.Field("x", AvroSchema.IntSchema())))

        val BYTE_ARRAY = ByteArraySerializer()

        /** Writes its bytes with [AvroEncoder.encodeFixed], as no built-in serializer of core does without an annotation. */
        val FIXED_VALUE =
            object : KSerializer<ByteArray> {
                override val descriptor = PrimitiveSerialDescriptor("FixedValue", PrimitiveKind.STRING)

                override fun serialize(
                    encoder: Encoder,
                    value: ByteArray,
                ) = (encoder as AvroEncoder).encodeFixed(value)

                override fun deserialize(decoder: Decoder): ByteArray = throw UnsupportedOperationException()
            }

        fun union(vararg types: ResolvedSchema) = AvroSchema.UnionSchema(*types)

        /** [branch]: the index of the union branch the value is expected to select, written before the value. */
        fun <T> SerializationStrategy<T>.encodes(
            value: T,
            writer: AvroSchema,
            branch: Int? = null,
            expected: ByteArrayBinaryWriter.() -> Unit,
        ) {
            val expectedBytes =
                ByteArrayBinaryWriter().apply {
                    if (branch != null) writeIndex(branch)
                    expected()
                }.toByteArray()
            for (avro in AVROS) {
                withClue("$value as $writer") {
                    avro.encodeToByteArray(writer, this, value) shouldBe expectedBytes
                }
            }
        }

        fun <T> SerializationStrategy<T>.failsWith(
            value: T,
            writer: AvroSchema,
        ) {
            errorMessage(value, writer)
        }

        fun <T> SerializationStrategy<T>.errorMessage(
            value: T,
            writer: AvroSchema,
        ): String {
            val messages =
                AVROS.map { avro ->
                    withClue("$value as $writer") {
                        shouldThrow<AvroSchemaMismatchException> { avro.encodeToByteArray(writer, this, value) }.message!!
                    }
                }
            messages.distinct().size shouldBe 1
            return messages.first()
        }
    }
}