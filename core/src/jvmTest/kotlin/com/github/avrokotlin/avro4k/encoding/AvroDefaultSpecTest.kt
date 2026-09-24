package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecimal
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.AvroEncoder
import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.SomeEnum
import com.github.avrokotlin.avro4k.apacheSchema
import com.github.avrokotlin.avro4k.decodeWith
import com.github.avrokotlin.avro4k.encodeWith
import com.github.avrokotlin.avro4k.serializer.AvroSerializer
import com.github.avrokotlin.avro4k.serializer.SchemaSupplierContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import org.apache.avro.Schema
import org.apache.avro.SchemaBuilder
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.EncoderFactory
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

/**
 * Default values of fields missing from the writer schema, checked against the Avro specification (1.12) with Apache
 * Java as the oracle: avro4k and Apache's resolving `GenericDatumReader` decode the same bytes with the reader schema
 * avro4k generates, and both results must encode back to the same bytes with that schema.
 *
 * See `docs/plans/notes/m3-08.md`, "Validation against the Avro specification", for the rules and how the other Avro
 * implementations behave.
 */
internal class AvroDefaultSpecTest : StringSpec({
    "bytes defaults map each code point 0-255 to a byte" {
        assertDecodedLikeApacheJava<BytesDefault>() shouldBe BytesDefault("x", byteArrayOf(0xFF.toByte()), byteArrayOf(0xE9.toByte()))
    }

    "fixed defaults map each code point 0-255 to a byte, and are padded or truncated to the size" {
        assertDecodedLikeApacheJava<FixedDefault>() shouldBe
            FixedDefault("x", byteArrayOf(0xE9.toByte(), 'b'.code.toByte(), 0, 0), "abcd".toByteArray(), BigDecimal(16777216))
    }

    "a bytes default with a code point above 255 is rejected when generating the schema" {
        shouldThrow<SerializationException> { Avro.apacheSchema<BytesAbove255>() }.message shouldContain "code points 0-255"
    }

    "a union default is read from the first branch it matches, which the schema generation puts first" {
        Avro.apacheSchema<UnionDefault>().getField("u").schema().types.map { it.type } shouldBe listOf(Schema.Type.STRING, Schema.Type.INT)
        assertDecodedLikeApacheJava<UnionDefault>() shouldBe UnionDefault("x", "foo")
    }

    "a sealed type's default can be any subclass, as the schema generation puts its branch first" {
        Avro.apacheSchema<SealedDefault>().getField("shape").schema().types.map { it.name } shouldBe listOf("Square", "Circle")
        Avro.apacheSchema<SealedDefault>().getField("shapeNullable").schema().types.map { it.name } shouldBe listOf("Square", "Circle", "null")
        Avro.apacheSchema<SealedDefault>().getField("firstShape").schema().types.map { it.name } shouldBe listOf("Circle", "Square")
        assertDecodedLikeApacheJava<SealedDefault>() shouldBe SealedDefault("x", Square(2), Square(3), Circle(1))
    }

    "null for a non-nullable string is the \"null\" string, as written in the schema" {
        Avro.apacheSchema<NullStringDefault>().getField("s").defaultVal() shouldBe "null"
        assertDecodedLikeApacheJava<NullStringDefault>() shouldBe NullStringDefault("x", "null")
    }

    "a field a record default leaves out takes its own default, at any depth" {
        val inner = Inner(a = 1, b = "dflt", c = 'z', d = null, e = emptyList(), deeper = Deeper(5, 7))
        assertDecodedLikeApacheJava<NestedDefaults>() shouldBe
            NestedDefaults(
                name = "x",
                inner = inner,
                inners = listOf(inner.copy(a = 2, b = "given")),
                innerMap = mapOf("k" to inner.copy(a = 3)),
                innerNullable = inner.copy(a = 4, deeper = Deeper(6, 7))
            )
    }

    "other defaults decode like Apache Java" {
        assertDecodedLikeApacheJava<OtherDefaults>() shouldBe
            OtherDefaults(
                name = "x",
                char = 'a',
                charNullable = 'b',
                int = 1,
                double = 1.0,
                enum = SomeEnum.B,
                list = listOf(1, 2),
                map = mapOf("k" to "v"),
                nested = Nested(1, "n"),
                nullable = null
            )
    }
}) {
    @Serializable
    @SerialName("R")
    private class BytesDefault(
        val name: String,
        @AvroDefault("ÿ") val b1: ByteArray,
        @AvroDefault("é") val b2: ByteArray,
    ) {
        override fun equals(other: Any?) = other is BytesDefault && name == other.name && b1.contentEquals(other.b1) && b2.contentEquals(other.b2)

        override fun hashCode() = name.hashCode()

        override fun toString() = "BytesDefault($name, ${b1.toList()}, ${b2.toList()})"
    }

    @Serializable
    @SerialName("R")
    private class FixedDefault(
        val name: String,
        @AvroFixed(4) @AvroDefault("éb") val padded: ByteArray,
        @AvroFixed(4) @AvroDefault("abcdef") val truncated: ByteArray,
        @Contextual @AvroFixed(4) @AvroDecimal(scale = 0, precision = 8) @AvroDefault("\u0001") val decimal: BigDecimal,
    ) {
        override fun equals(other: Any?) =
            other is FixedDefault && name == other.name && padded.contentEquals(other.padded) &&
                truncated.contentEquals(other.truncated) && decimal == other.decimal

        override fun hashCode() = name.hashCode()

        override fun toString() = "FixedDefault($name, ${padded.toList()}, ${truncated.toList()}, $decimal)"
    }

    @Serializable
    @SerialName("R")
    private class BytesAbove255(
        val name: String,
        @AvroDefault("€") val b: ByteArray,
    )

    @Serializable
    @SerialName("R")
    private data class UnionDefault(
        val name: String,
        @AvroDefault("foo") @Serializable(with = IntOrStringSerializer::class) val u: Any,
    )

    @Serializable
    private sealed interface Shape

    @Serializable
    @SerialName("Circle")
    private data class Circle(val radius: Int) : Shape

    @Serializable
    @SerialName("Square")
    private data class Square(val side: Int) : Shape

    @Serializable
    @SerialName("R")
    private data class SealedDefault(
        val name: String,
        @AvroDefault("""{"side":2}""") val shape: Shape,
        @AvroDefault("""{"side":3}""") val shapeNullable: Shape?,
        @AvroDefault("""{"radius":1}""") val firstShape: Shape,
    )

    @Serializable
    @SerialName("R")
    private data class NullStringDefault(
        val name: String,
        @AvroDefault("null") val s: String,
    )

    @Serializable
    @SerialName("Nested")
    private data class Nested(val a: Int, val b: String)

    @Serializable
    @SerialName("Inner")
    private data class Inner(
        val a: Int,
        @AvroDefault("dflt") val b: String,
        @AvroDefault("z") val c: Char,
        val d: String?,
        val e: List<Int>,
        @AvroDefault("""{"x": 5}""") val deeper: Deeper,
    )

    @Serializable
    @SerialName("Deeper")
    private data class Deeper(
        val x: Int,
        @AvroDefault("7") val y: Int,
    )

    @Serializable
    @SerialName("R")
    private data class NestedDefaults(
        val name: String,
        @AvroDefault("""{"a": 1}""") val inner: Inner,
        @AvroDefault("""[{"a": 2, "b": "given"}]""") val inners: List<Inner>,
        @AvroDefault("""{"k": {"a": 3}}""") val innerMap: Map<String, Inner>,
        @AvroDefault("""{"a": 4, "deeper": {"x": 6}}""") val innerNullable: Inner?,
    )

    @Serializable
    @SerialName("R")
    private data class OtherDefaults(
        val name: String,
        @AvroDefault("a") val char: Char,
        @AvroDefault("b") val charNullable: Char?,
        @AvroDefault("1.0") val int: Int,
        @AvroDefault("1") val double: Double,
        @AvroDefault("B") val enum: SomeEnum,
        @AvroDefault("[1, 2]") val list: List<Int>,
        @AvroDefault("""{"k": "v"}""") val map: Map<String, String>,
        @AvroDefault("""{"a": 1, "b": "n"}""") val nested: Nested,
        @AvroDefault("null") val nullable: String?,
    )
}

/**
 * The union `[int, string]`, decoding whichever branch was written.
 */
private object IntOrStringSerializer : AvroSerializer<Any>("IntOrString") {
    override fun getSchema(context: SchemaSupplierContext): Schema = SchemaBuilder.unionOf().intType().and().stringType().endUnion()

    override fun serializeAvro(encoder: AvroEncoder, value: Any) {
        when (value) {
            is Int -> encoder.encodeInt(value)
            else -> encoder.encodeString(value.toString())
        }
    }

    override fun deserializeAvro(decoder: AvroDecoder): Any =
        when (decoder.currentWriterSchema.type) {
            Schema.Type.INT -> decoder.decodeInt()
            else -> decoder.decodeString()
        }
}

/**
 * Decodes a record written with only its `name` field, with avro4k and with Apache Java, and checks that both results
 * encode to the same bytes with the reader schema.
 */
private inline fun <reified T> assertDecodedLikeApacheJava(): T = assertDecodedLikeApacheJava(Avro.serializersModule.serializer<T>())

private fun <T> assertDecodedLikeApacheJava(serializer: KSerializer<T>): T {
    val writerSchema = SchemaBuilder.record("R").fields().requiredString("name").endRecord()
    val readerSchema = Avro.apacheSchema(serializer)
    val bytes = Avro.encodeWith(writerSchema, serializer<NameOnlyWriter>(), NameOnlyWriter("x"))

    val apacheRecord = GenericDatumReader<Any>(writerSchema, readerSchema).read(null, DecoderFactory.get().binaryDecoder(bytes, null))
    val apacheBytes =
        ByteArrayOutputStream().also {
            val encoder = EncoderFactory.get().binaryEncoder(it, null)
            GenericDatumWriter<Any>(readerSchema).write(apacheRecord, encoder)
            encoder.flush()
        }.toByteArray()

    val decoded = Avro.decodeWith(writerSchema, serializer, bytes)
    withClue("avro4k decoded $decoded, Apache Java decoded $apacheRecord, with the reader schema $readerSchema") {
        Avro.encodeWith(readerSchema, serializer, decoded).toList() shouldBe apacheBytes.toList()
    }
    return decoded
}

@Serializable
@SerialName("R")
private data class NameOnlyWriter(val name: String)