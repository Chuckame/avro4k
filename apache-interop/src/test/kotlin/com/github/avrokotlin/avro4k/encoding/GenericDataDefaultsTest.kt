package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.decodeFromGenericDataWith
import com.github.avrokotlin.avro4k.toAvro4k
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.decodeStructure
import org.apache.avro.SchemaBuilder
import org.apache.avro.generic.GenericData

/**
 * A reader field missing from the writer record takes its default. Read through any `decode*` method of the decoder a
 * composite deserializer gets from `decodeInlineElement`, that default is the same from generic data as from bytes, or
 * fails on both.
 */
internal class GenericDataDefaultsTest : StringSpec({
    "every decode method reads a missing field's default from generic data as it does from bytes" {
        val writerSchema = SchemaBuilder.record("Defaults").fields().requiredString("name").endRecord()
        val writerRecord = GenericData.Record(writerSchema).apply { put("name", "abc") }
        val bytes = Avro.encodeToByteArray(writerSchema.toAvro4k(), NameOnly.serializer(), NameOnly("abc"))

        val fromGenericData = Avro.decodeFromGenericDataWith(writerSchema, EveryDecodeMethod, writerRecord)
        val fromBytes = Avro.decodeFromByteArray(writerSchema.toAvro4k(), EveryDecodeMethod, bytes)

        fromGenericData.keys shouldBe fromBytes.keys
        for ((read, expected) in fromBytes) {
            val actual = fromGenericData.getValue(read)
            withClue("$read: from bytes $expected, from generic data $actual") {
                actual.isSuccess shouldBe expected.isSuccess
                actual.getOrNull() shouldBe expected.getOrNull()
            }
        }

        // the reads matching each field's own type succeed
        fromGenericData.getValue("str.decodeString").getOrNull() shouldBe "hello"
        fromGenericData.getValue("int.decodeInt").getOrNull() shouldBe 1
        fromGenericData.getValue("long.decodeLong").getOrNull() shouldBe 3_000_000_000
        fromGenericData.getValue("float.decodeFloat").getOrNull() shouldBe 1.5f
        fromGenericData.getValue("double.decodeDouble").getOrNull() shouldBe 1.5
        fromGenericData.getValue("bool.decodeBoolean").getOrNull() shouldBe true
        fromGenericData.getValue("char.decodeChar").getOrNull() shouldBe 'a'
        fromGenericData.getValue("enum.decodeEnum").getOrNull() shouldBe 1
        fromGenericData.getValue("bytes.decodeBytes").getOrNull() shouldBe listOf(0xE9.toByte())
        fromGenericData.getValue("fixed.decodeFixed").getOrNull() shouldBe listOf('a'.code.toByte(), 'b'.code.toByte(), 0)
        fromGenericData.getValue("fixed.decodeString").getOrNull() shouldBe "ab\u0000"
        fromGenericData.getValue("nullable.decodeString").isFailure shouldBe true
    }
}) {
    @Serializable
    @SerialName("Defaults")
    private data class NameOnly(val name: String)

    @Serializable
    private enum class Letter { A, B, C }

    @Serializable
    @SerialName("Defaults")
    private data class Defaults(
        val name: String,
        @AvroDefault("hello") val str: String,
        @AvroDefault("1") val int: Int,
        @AvroDefault("3000000000") val long: Long,
        @AvroDefault("1.5") val float: Float,
        @AvroDefault("1.5") val double: Double,
        @AvroDefault("true") val bool: Boolean,
        @AvroDefault("a") val char: Char,
        @AvroDefault("B") val enum: Letter,
        @AvroDefault("é") val bytes: ByteArray,
        @AvroFixed(3) @AvroDefault("ab") val fixed: ByteArray,
        @AvroDefault("null") val nullable: String?,
    )

    /**
     * Reads `name` from the writer record, then every other field, all missing from it, through each `decode*` method, keyed by
     * `field.method`. A byte array is kept as a list, to be compared by content.
     */
    private object EveryDecodeMethod : DeserializationStrategy<Map<String, Result<Any?>>> {
        override val descriptor = Defaults.serializer().descriptor

        private val decodeMethods: Map<String, (AvroDecoder) -> Any?> =
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
                "decodeEnum" to { it.decodeEnum(Letter.serializer().descriptor) },
                "decodeBytes" to { it.decodeBytes() },
                "decodeFixed" to { it.decodeFixed() }
            )

        override fun deserialize(decoder: Decoder): Map<String, Result<Any?>> =
            decoder.decodeStructure(descriptor) {
                val results = mutableMapOf<String, Result<Any?>>()
                while (true) {
                    when (val index = decodeElementIndex(descriptor)) {
                        CompositeDecoder.DECODE_DONE -> break

                        0 -> decodeStringElement(descriptor, 0)

                        else ->
                            for ((method, decode) in decodeMethods) {
                                results["${descriptor.getElementName(index)}.$method"] =
                                    runCatching { decode(decodeInlineElement(descriptor, index) as AvroDecoder) }
                                        .map { if (it is ByteArray) it.toList() else it }
                            }
                    }
                }
                results
            }
    }
}