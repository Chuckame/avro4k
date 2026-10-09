package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.ArraySchema
import com.github.avrokotlin.avro4k.AvroSchema.BooleanSchema
import com.github.avrokotlin.avro4k.AvroSchema.BytesSchema
import com.github.avrokotlin.avro4k.AvroSchema.DoubleSchema
import com.github.avrokotlin.avro4k.AvroSchema.EnumSchema
import com.github.avrokotlin.avro4k.AvroSchema.FixedSchema
import com.github.avrokotlin.avro4k.AvroSchema.FloatSchema
import com.github.avrokotlin.avro4k.AvroSchema.IntSchema
import com.github.avrokotlin.avro4k.AvroSchema.LongSchema
import com.github.avrokotlin.avro4k.AvroSchema.MapSchema
import com.github.avrokotlin.avro4k.AvroSchema.NullSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.AvroSchema.StringSchema
import com.github.avrokotlin.avro4k.AvroSchema.UnionSchema
import com.github.avrokotlin.avro4k.LockableList
import com.github.avrokotlin.avro4k.Name
import com.github.avrokotlin.avro4k.decodeFromByteArray
import com.github.avrokotlin.avro4k.encodeToByteArray
import com.github.avrokotlin.avro4k.internal.schema.skip
import com.github.avrokotlin.avro4k.schema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test

/**
 * The validating encoder and decoder accept exactly the calls the writer schema allows: every valid sequence goes
 * through unchanged, and each kind of invalid call is rejected with a [SerializationException] naming the call, what
 * was expected, and where. Runs on every platform; the JVM also compares them with Apache Avro's validating codecs.
 */
class ValidatingAvroBinaryCodecTest {
    private val suit = EnumSchema(Name("Suit"), listOf("SPADES", "HEARTS", "CLUBS"))
    private val md5 = FixedSchema(Name("Md5"), 4)
    private val inner = RecordSchema(Name("Inner"), listOf(RecordSchema.Field("s", StringSchema()), RecordSchema.Field("l", LongSchema())))
    private val empty = RecordSchema(Name("Empty"), emptyList())
    private val everyType =
        RecordSchema(
            Name("EveryType"),
            listOf(
                RecordSchema.Field("n", NullSchema()),
                RecordSchema.Field("b", BooleanSchema()),
                RecordSchema.Field("i", IntSchema()),
                RecordSchema.Field("l", LongSchema()),
                RecordSchema.Field("f", FloatSchema()),
                RecordSchema.Field("d", DoubleSchema()),
                RecordSchema.Field("s", StringSchema()),
                RecordSchema.Field("bytes", BytesSchema()),
                RecordSchema.Field("fixed", md5),
                RecordSchema.Field("suit", suit),
                RecordSchema.Field("maybe", UnionSchema(NullSchema(), inner)),
                RecordSchema.Field("list", ArraySchema(IntSchema())),
                RecordSchema.Field("map", MapSchema(inner)),
                RecordSchema.Field("empty", empty),
                RecordSchema.Field("last", IntSchema())
            )
        )

    /** A valid datum of [everyType], with two array items and one map entry. */
    private fun AvroBinaryEncoder.writeEveryType() {
        writeNull()
        writeBoolean(true)
        writeInt(-3)
        writeLong(1L shl 40)
        writeFloat(1.5f)
        writeDouble(-2.25)
        writeString("héllo")
        writeBytes(byteArrayOf(1, 2, 3))
        writeFixed(byteArrayOf(9, 8, 7, 6))
        writeEnum(2)
        writeIndex(1)
        writeString("inner")
        writeLong(7)
        writeArrayStart(2)
        startItem()
        writeInt(1)
        startItem()
        writeInt(2)
        writeArrayEnd()
        writeMapStart(1)
        startItem()
        writeString("k")
        writeString("v")
        writeLong(5)
        writeMapEnd()
        writeInt(99)
    }

    private fun bytesOf(write: AvroBinaryEncoder.() -> Unit): ByteArray {
        val buffer = Buffer()
        KotlinxIoEncoder(buffer).write()
        return buffer.readByteArray()
    }

    private fun validatingEncoder(schema: AvroSchema) = ValidatingAvroBinaryEncoder(KotlinxIoEncoder(Buffer()), schema)

    private fun validatingDecoder(
        schema: AvroSchema,
        bytes: ByteArray,
    ) = ValidatingAvroBinaryDecoder(KotlinxIoDecoder(Buffer().apply { write(bytes) }), schema)

    /** Writes with the validating encoder, which must reject the sequence with a message containing every [parts]. */
    private fun encoderRejects(
        schema: AvroSchema,
        vararg parts: String,
        write: ValidatingAvroBinaryEncoder.() -> Unit,
    ) {
        val error =
            shouldThrow<SerializationException> {
                validatingEncoder(schema).apply {
                    write()
                    checkDatumComplete()
                }
            }
        for (part in parts) error.message shouldContain part
    }

    private fun decoderRejects(
        schema: AvroSchema,
        bytes: ByteArray,
        vararg parts: String,
        read: ValidatingAvroBinaryDecoder.() -> Unit,
    ) {
        val error =
            shouldThrow<SerializationException> {
                validatingDecoder(schema, bytes).apply {
                    read()
                    checkDatumComplete()
                }
            }
        for (part in parts) error.message shouldContain part
    }

    // ---- encoder: accepted ----

    @Test
    fun `a valid datum is accepted and forwarded unchanged`() {
        val buffer = Buffer()
        ValidatingAvroBinaryEncoder(KotlinxIoEncoder(buffer), everyType).apply {
            writeEveryType()
            checkDatumComplete()
        }
        buffer.readByteArray().toHexString() shouldBe bytesOf { writeEveryType() }.toHexString()
    }

    @Test
    fun `empty arrays and maps are accepted`() {
        val schema = RecordSchema(Name("R"), listOf(RecordSchema.Field("a", ArraySchema(IntSchema())), RecordSchema.Field("m", MapSchema(IntSchema()))))
        validatingEncoder(schema).apply {
            writeArrayStart(0)
            writeArrayEnd()
            writeMapStart(0)
            writeMapEnd()
            checkDatumComplete()
        }
    }

    @Test
    fun `nested unions and collections of unions are accepted`() {
        val schema = UnionSchema(NullSchema(), RecordSchema(Name("R"), listOf(RecordSchema.Field("u", ArraySchema(UnionSchema(IntSchema(), StringSchema()))))))
        validatingEncoder(schema).apply {
            writeIndex(1)
            writeArrayStart(2)
            startItem()
            writeIndex(0)
            writeInt(1)
            startItem()
            writeIndex(1)
            writeString("x")
            writeArrayEnd()
            checkDatumComplete()
        }
    }

    @Test
    fun `a record without fields and an array of them take no call but startItem`() {
        validatingEncoder(empty).checkDatumComplete()
        validatingEncoder(ArraySchema(empty)).apply {
            writeArrayStart(2)
            startItem()
            startItem()
            writeArrayEnd()
            checkDatumComplete()
        }
    }

    @Test
    fun `a recursive record is followed through its unions`() {
        val fields = LockableList<RecordSchema.Field>()
        val node = RecordSchema(Name("Node"), fields)
        fields += RecordSchema.Field("v", IntSchema())
        fields += RecordSchema.Field("next", UnionSchema(NullSchema(), node))
        fields.lock()
        validatingEncoder(node).apply {
            writeInt(1)
            writeIndex(1)
            writeInt(2)
            writeIndex(1)
            writeInt(3)
            writeIndex(0)
            writeNull()
            checkDatumComplete()
        }
        encoderRejects(node, "writeLong()", "at 'next.next.v'", "a value of type int") {
            writeInt(1)
            writeIndex(1)
            writeInt(2)
            writeIndex(1)
            writeLong(3)
        }
    }

    // ---- encoder: rejected ----

    @Test
    fun `a wrong primitive kind is rejected without promotion`() {
        encoderRejects(IntSchema(), "writeLong()", "a value of type int", "the root") { writeLong(1) }
        encoderRejects(LongSchema(), "writeInt()", "a value of type long") { writeInt(1) }
        encoderRejects(FloatSchema(), "writeDouble()", "a value of type float") { writeDouble(1.0) }
        encoderRejects(StringSchema(), "writeBytes()", "a value of type string") { writeBytes(byteArrayOf()) }
        encoderRejects(BytesSchema(), "writeString()", "a value of type bytes") { writeString("x") }
        encoderRejects(NullSchema(), "writeBoolean()", "a value of type null") { writeBoolean(true) }
        encoderRejects(BytesSchema(), "writeFixed()", "a value of type bytes") { writeFixed(byteArrayOf()) }
        encoderRejects(md5, "writeBytes()", "a fixed 'Md5' of 4 bytes") { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        encoderRejects(suit, "writeInt()", "an index of enum 'Suit'") { writeInt(0) }
    }

    @Test
    fun `the path of the mismatch is in the message`() {
        encoderRejects(everyType, "writeInt()", "at 'maybe.l'", "a value of type long") {
            writeNull()
            writeBoolean(true)
            writeInt(-3)
            writeLong(1L shl 40)
            writeFloat(1.5f)
            writeDouble(-2.25)
            writeString("héllo")
            writeBytes(byteArrayOf(1, 2, 3))
            writeFixed(byteArrayOf(9, 8, 7, 6))
            writeEnum(2)
            writeIndex(1)
            writeString("inner")
            writeInt(7)
        }
        val schema = RecordSchema(Name("R"), listOf(RecordSchema.Field("list", ArraySchema(inner))))
        encoderRejects(schema, "writeInt()", "at 'list[1].l'") {
            writeArrayStart(2)
            startItem()
            writeString("a")
            writeLong(1)
            startItem()
            writeString("b")
            writeInt(2)
        }
    }

    @Test
    fun `a missing field is rejected`() {
        // in the middle: the next field's call does not match
        encoderRejects(inner, "writeLong()", "at 's'", "a value of type string") { writeLong(1) }
        // at the end: the datum is incomplete
        encoderRejects(inner, "Incomplete datum", "at 'l'", "a value of type long") { writeString("s") }
    }

    @Test
    fun `an extra call after the datum is rejected`() {
        encoderRejects(inner, "writeInt()", "after the end of the datum") {
            writeString("s")
            writeLong(1)
            writeInt(2)
        }
        encoderRejects(IntSchema(), "writeInt()", "after the end of the datum") {
            writeInt(1)
            writeInt(2)
        }
    }

    @Test
    fun `a union index out of range is rejected`() {
        val union = UnionSchema(NullSchema(), StringSchema())
        encoderRejects(union, "writeIndex()", "a branch index of union[null, string] in [0, 2), but got 2") { writeIndex(2) }
        encoderRejects(union, "writeIndex()", "but got -1") { writeIndex(-1) }
        encoderRejects(IntSchema(), "writeIndex()", "a value of type int") { writeIndex(0) }
    }

    @Test
    fun `a value of the wrong branch is rejected`() {
        val union = UnionSchema(NullSchema(), StringSchema())
        encoderRejects(union, "writeString()", "a value of type null") {
            writeIndex(0)
            writeString("x")
        }
        // without its index
        encoderRejects(union, "writeString()", "a branch index of union[null, string]") { writeString("x") }
    }

    @Test
    fun `nested unions need every index`() {
        val schema = UnionSchema(NullSchema(), RecordSchema(Name("R"), listOf(RecordSchema.Field("u", UnionSchema(IntSchema(), StringSchema())))))
        encoderRejects(schema, "writeString()", "at 'u'", "a branch index of union[int, string]") {
            writeIndex(1)
            writeString("x")
        }
        encoderRejects(schema, "writeInt()", "at 'u'", "a value of type string") {
            writeIndex(1)
            writeIndex(1)
            writeInt(1)
        }
    }

    @Test
    fun `an enum index out of range is rejected`() {
        encoderRejects(suit, "writeEnum()", "an index of enum 'Suit' in [0, 3), but got 3") { writeEnum(3) }
        encoderRejects(suit, "writeEnum()", "but got -1") { writeEnum(-1) }
    }

    @Test
    fun `a fixed of the wrong length is rejected`() {
        encoderRejects(md5, "writeFixed()", "a fixed 'Md5' of 4 bytes, but got 3 bytes") { writeFixed(byteArrayOf(1, 2, 3)) }
        encoderRejects(md5, "writeFixed()", "but got 5 bytes") { writeFixed(byteArrayOf(1, 2, 3, 4, 5)) }
    }

    @Test
    fun `an item count that does not match the items is rejected`() {
        val array = ArraySchema(IntSchema())
        // fewer items than announced
        encoderRejects(array, "writeArrayEnd()", "startItem() (1 more items announced)") {
            writeArrayStart(2)
            startItem()
            writeInt(1)
            writeArrayEnd()
        }
        // more items than announced
        encoderRejects(array, "startItem()", "writeArrayEnd()") {
            writeArrayStart(1)
            startItem()
            writeInt(1)
            startItem()
        }
        encoderRejects(array, "writeArrayStart()", "a non-negative item count, but got -1") { writeArrayStart(-1) }
    }

    @Test
    fun `an item without its startItem is rejected`() {
        encoderRejects(ArraySchema(IntSchema()), "writeInt()", "startItem()") {
            writeArrayStart(1)
            writeInt(1)
        }
        // two values for one startItem
        encoderRejects(ArraySchema(IntSchema()), "writeInt()", "writeArrayEnd()") {
            writeArrayStart(1)
            startItem()
            writeInt(1)
            writeInt(2)
        }
        encoderRejects(MapSchema(IntSchema()), "writeString()", "startItem()") {
            writeMapStart(1)
            writeString("k")
        }
        encoderRejects(IntSchema(), "startItem()", "a value of type int") { startItem() }
    }

    @Test
    fun `an unterminated array or map is rejected`() {
        encoderRejects(ArraySchema(IntSchema()), "Incomplete datum", "writeArrayEnd()") {
            writeArrayStart(1)
            startItem()
            writeInt(1)
        }
        encoderRejects(MapSchema(IntSchema()), "Incomplete datum", "writeMapEnd()") {
            writeMapStart(0)
        }
        // ended as the other kind
        encoderRejects(ArraySchema(IntSchema()), "writeMapEnd()", "writeArrayEnd()") {
            writeArrayStart(0)
            writeMapEnd()
        }
        encoderRejects(MapSchema(IntSchema()), "writeArrayStart()", "the start of a map") { writeArrayStart(0) }
    }

    @Test
    fun `map keys must be strings`() {
        encoderRejects(MapSchema(LongSchema()), "writeLong()", "a map key (string)") {
            writeMapStart(1)
            startItem()
            writeLong(1)
        }
        encoderRejects(MapSchema(LongSchema()), "writeString()", "a value of type long") {
            writeMapStart(1)
            startItem()
            writeString("k")
            writeString("v")
        }
    }

    // ---- decoder ----

    @Test
    fun `a valid datum is read through and the values are unchanged`() {
        val bytes = bytesOf { writeEveryType() }
        validatingDecoder(everyType, bytes).apply {
            readNull()
            readBoolean() shouldBe true
            readInt() shouldBe -3
            readLong() shouldBe (1L shl 40)
            readFloat() shouldBe 1.5f
            readDouble() shouldBe -2.25
            readString() shouldBe "héllo"
            readBytes().toList() shouldBe listOf<Byte>(1, 2, 3)
            readFixed(4).toList() shouldBe listOf<Byte>(9, 8, 7, 6)
            readEnum() shouldBe 2
            readIndex() shouldBe 1
            readStringBytes().decodeToString() shouldBe "inner"
            readLong() shouldBe 7
            readArrayStart() shouldBe 2
            readInt() shouldBe 1
            readInt() shouldBe 2
            arrayNext() shouldBe 0
            readMapStart() shouldBe 1
            readString() shouldBe "k"
            readString() shouldBe "v"
            readLong() shouldBe 5
            mapNext() shouldBe 0
            readInt() shouldBe 99
            checkDatumComplete()
        }
    }

    @Test
    fun `a whole datum is skipped under validation`() {
        val bytes = bytesOf { writeEveryType() }
        validatingDecoder(everyType, bytes).apply {
            skip(everyType)
            checkDatumComplete()
        }
    }

    /** An array of ints in three blocks: 2 items, then 1 item with its byte size (negative count), then the end. */
    private val multiBlockArray =
        bytesOf {
            writeLong(2)
            writeInt(1)
            writeInt(2)
            writeLong(-1)
            writeLong(1)
            writeInt(3)
            writeLong(0)
        }

    /** A map of longs in two blocks of one entry each, the second one with its byte size. */
    private val multiBlockMap =
        bytesOf {
            writeLong(1)
            writeString("a")
            writeLong(1)
            writeLong(-1)
            writeLong(3)
            writeString("b")
            writeLong(2)
            writeLong(0)
        }

    @Test
    fun `multi-block collections are read block by block`() {
        validatingDecoder(ArraySchema(IntSchema()), multiBlockArray).apply {
            readArrayStart() shouldBe 2
            readInt() shouldBe 1
            readInt() shouldBe 2
            arrayNext() shouldBe 1
            readInt() shouldBe 3
            arrayNext() shouldBe 0
            checkDatumComplete()
        }
        validatingDecoder(MapSchema(LongSchema()), multiBlockMap).apply {
            readMapStart() shouldBe 1
            readString() shouldBe "a"
            readLong() shouldBe 1
            mapNext() shouldBe 1
            readString() shouldBe "b"
            readLong() shouldBe 2
            mapNext() shouldBe 0
            checkDatumComplete()
        }
    }

    @Test
    fun `multi-block collections are skipped block by block`() {
        // the block with a byte size is jumped over by the wrapped decoder, so only the first block's items are skipped
        validatingDecoder(ArraySchema(IntSchema()), multiBlockArray).apply {
            skipArray() shouldBe 2
            readInt()
            readInt()
            skipArray() shouldBe 0
            checkDatumComplete()
        }
        validatingDecoder(MapSchema(LongSchema()), multiBlockMap).apply {
            skipMap() shouldBe 1
            skipString()
            readLong()
            skipMap() shouldBe 0
            checkDatumComplete()
        }
        val nested = RecordSchema(Name("R"), listOf(RecordSchema.Field("a", ArraySchema(ArraySchema(IntSchema()))), RecordSchema.Field("z", IntSchema())))
        val bytes =
            bytesOf {
                writeArrayStart(2)
                startItem()
                writeArrayStart(1)
                startItem()
                writeInt(1)
                writeArrayEnd()
                startItem()
                writeArrayStart(0)
                writeArrayEnd()
                writeArrayEnd()
                writeInt(9)
            }
        validatingDecoder(nested, bytes).apply {
            skip(nested)
            checkDatumComplete()
        }
    }

    @Test
    fun `a block not read to its end is rejected`() {
        decoderRejects(ArraySchema(IntSchema()), multiBlockArray, "arrayNext()", "the next item (1 more in this block)") {
            readArrayStart()
            readInt()
            arrayNext()
        }
        decoderRejects(ArraySchema(IntSchema()), multiBlockArray, "readInt()", "arrayNext()") {
            readArrayStart()
            readInt()
            readInt()
            readInt()
        }
        decoderRejects(MapSchema(LongSchema()), multiBlockMap, "Incomplete datum", "mapNext()") {
            readMapStart()
            readString()
            readLong()
        }
        decoderRejects(ArraySchema(IntSchema()), multiBlockArray, "skipArray()", "at '[0]'", "a value of type int") {
            skipArray()
            skipArray()
        }
    }

    @Test
    fun `the block calls must match the collection and how it is read`() {
        decoderRejects(ArraySchema(IntSchema()), multiBlockArray, "mapNext()", "expected arrayNext()") {
            readArrayStart()
            readInt()
            readInt()
            mapNext()
        }
        decoderRejects(ArraySchema(IntSchema()), multiBlockArray, "skipArray()", "arrayNext()") {
            readArrayStart()
            readInt()
            readInt()
            skipArray()
        }
        decoderRejects(ArraySchema(IntSchema()), multiBlockArray, "arrayNext()", "skipArray()") {
            skipArray()
            readInt()
            readInt()
            arrayNext()
        }
        decoderRejects(ArraySchema(IntSchema()), multiBlockArray, "readMapStart()", "the start of an array") { readMapStart() }
        decoderRejects(IntSchema(), multiBlockArray, "arrayNext()", "a value of type int") { arrayNext() }
    }

    @Test
    fun `map keys are read as strings`() {
        decoderRejects(MapSchema(LongSchema()), multiBlockMap, "readLong()", "a map key (string)") {
            readMapStart()
            readLong()
        }
        decoderRejects(MapSchema(LongSchema()), multiBlockMap, "skipBytes()", "a map key (string)") {
            skipMap()
            skipBytes()
        }
    }

    @Test
    fun `empty collections and arrays of records without fields are read`() {
        val emptyCollections = bytesOf {
            writeArrayStart(0)
            writeArrayEnd()
            writeMapStart(0)
            writeMapEnd()
        }
        val schema = RecordSchema(Name("R"), listOf(RecordSchema.Field("a", ArraySchema(IntSchema())), RecordSchema.Field("m", MapSchema(IntSchema()))))
        validatingDecoder(schema, emptyCollections).apply {
            readArrayStart() shouldBe 0
            readMapStart() shouldBe 0
            checkDatumComplete()
        }
        validatingDecoder(schema, emptyCollections).apply {
            skip(schema)
            checkDatumComplete()
        }
        val emptyRecords = bytesOf {
            writeArrayStart(3)
            writeArrayEnd()
        }
        validatingDecoder(ArraySchema(empty), emptyRecords).apply {
            readArrayStart() shouldBe 3
            arrayNext() shouldBe 0
            checkDatumComplete()
        }
        validatingDecoder(ArraySchema(empty), emptyRecords).apply {
            skip(ArraySchema(empty))
            checkDatumComplete()
        }
    }

    @Test
    fun `a read of the wrong kind is rejected`() {
        val bytes = bytesOf { writeInt(1) }
        decoderRejects(IntSchema(), bytes, "readLong()", "a value of type int") { readLong() }
        decoderRejects(StringSchema(), bytes, "skipBytes()", "a value of type string") { skipBytes() }
        decoderRejects(md5, bytes, "readFixed()", "a fixed 'Md5' of 4 bytes, but got 3 bytes") { readFixed(3) }
        decoderRejects(md5, bytes, "skipFixed()", "but got 5 bytes") { skipFixed(5) }
    }

    @Test
    fun `a read enum or union index out of range is rejected`() {
        decoderRejects(suit, bytesOf { writeEnum(3) }, "readEnum()", "an index of enum 'Suit' in [0, 3), but got 3") { readEnum() }
        val union = UnionSchema(NullSchema(), StringSchema())
        decoderRejects(union, bytesOf { writeIndex(2) }, "readIndex()", "in [0, 2), but got 2") { readIndex() }
        decoderRejects(union, bytesOf { writeIndex(0) }, "readString()", "a value of type null") {
            readIndex()
            readString()
        }
    }

    @Test
    fun `skipping with the wrong schema is rejected`() {
        val bytes =
            bytesOf {
                writeIndex(1)
                writeString("s")
                writeLong(7)
            }
        val otherInner = RecordSchema(Name("Inner"), listOf(RecordSchema.Field("s", StringSchema()), RecordSchema.Field("l", IntSchema())))
        decoderRejects(UnionSchema(NullSchema(), inner), bytes, "readInt()", "at 'l'", "a value of type long") {
            readIndex()
            skip(otherInner)
        }
    }

    @Test
    fun `the trailing state at the end of the datum is checked`() {
        val bytes =
            bytesOf {
                writeString("s")
                writeLong(1)
            }
        decoderRejects(inner, bytes, "Incomplete datum", "at 'l'") { readString() }
        decoderRejects(IntSchema(), bytesOf { writeInt(1) }, "readInt()", "after the end of the datum") {
            readInt()
            readInt()
        }
        decoderRejects(ArraySchema(IntSchema()), multiBlockArray, "Incomplete datum", "arrayNext()") {
            readArrayStart()
            readInt()
            readInt()
        }
    }

    // ---- through the entry points ----

    @Serializable
    private data class Validated(
        val name: String,
        val tags: List<String>,
        val scores: Map<String, Int>,
        val maybe: Long?,
    )

    @Serializable
    private data class OneSkippedInt(
        @Serializable(with = WritesNothingIntSerializer::class) val skipped: Int,
        val last: String,
    )

    /** Writes and reads no codec call at all: invalid, as an int needs one. */
    private object WritesNothingIntSerializer : KSerializer<Int> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("WritesNothingInt", PrimitiveKind.INT)

        override fun serialize(
            encoder: Encoder,
            value: Int,
        ) {
        }

        override fun deserialize(decoder: Decoder): Int = 0
    }

    private val validating = Avro { validateSerialization = true }

    @Test
    fun `values round trip with validation enabled`() {
        val value = Validated("n", listOf("a", "b"), mapOf("x" to 1), null)
        val schema = validating.schema<Validated>()
        validating.decodeFromByteArray<Validated>(schema, validating.encodeToByteArray(schema, value)) shouldBe value
        val other = value.copy(tags = emptyList(), scores = emptyMap(), maybe = 3)
        validating.decodeFromByteArray<Validated>(schema, validating.encodeToByteArray(schema, other)) shouldBe other
        validating.encodeToByteArray(schema, value).toHexString() shouldBe Avro.encodeToByteArray(schema, value).toHexString()
    }

    @Test
    fun `a serializer making the wrong calls fails only with validation enabled`() {
        val value = OneSkippedInt(1, "last")
        val schema = Avro.schema<OneSkippedInt>()
        // without validation, the missing int goes unnoticed: the bytes are just the string
        Avro.encodeToByteArray(schema, value).toHexString() shouldBe bytesOf { writeString("last") }.toHexString()
        shouldThrow<SerializationException> { validating.encodeToByteArray(schema, value) }.message shouldContain "writeString() at 'skipped'"

        val bytes = bytesOf {
            writeInt(1)
            writeString("last")
        }
        shouldThrow<SerializationException> { validating.decodeFromByteArray<OneSkippedInt>(schema, bytes) }
            .message shouldContain "at 'skipped'"
    }
}